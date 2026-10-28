package com.example.todoapp.api;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeatherApiControllerTests {

    private static final String URL = "https://weather.example.test/forecast?daily=weather_code";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);
    // UTC is still October 26: the forecast must use Tokyo's date.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T15:30:00Z"), ZoneId.of("UTC"));

    private MockRestServiceServer upstream;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        upstream = MockRestServiceServer.bindTo(builder).build();
        WeatherClient client = new WeatherClient(builder.build(), URL, CLOCK);
        mockMvc = MockMvcBuilders.standaloneSetup(new WeatherApiController(client)).build();
    }

    @AfterEach
    void verifyUpstream() {
        upstream.verify();
    }

    @Test
    void pairsDatesAndCodesAndReturnsOnlyThePublicFields() throws Exception {
        respond(withSuccess(payload(TODAY, "0,3,61,71,95,45,999"), MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Weather-Unavailable"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        [
                          {"date":"2026-10-27","weatherCode":0,"weatherName":"晴れ"},
                          {"date":"2026-10-28","weatherCode":3,"weatherName":"曇り"},
                          {"date":"2026-10-29","weatherCode":61,"weatherName":"雨"},
                          {"date":"2026-10-30","weatherCode":71,"weatherName":"雪"},
                          {"date":"2026-10-31","weatherCode":95,"weatherName":"雷"},
                          {"date":"2026-11-01","weatherCode":45,"weatherName":"霧"},
                          {"date":"2026-11-02","weatherCode":999,"weatherName":"不明"}
                        ]
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @CsvSource({
            "0,晴れ", "1,晴れ", "2,曇り", "3,曇り", "45,霧", "48,霧",
            "51,雨", "53,雨", "55,雨", "56,雨", "57,雨", "61,雨", "63,雨", "65,雨",
            "66,雨", "67,雨", "71,雪", "73,雪", "75,雪", "77,雪", "80,雨", "81,雨",
            "82,雨", "85,雪", "86,雪", "95,雷", "96,雷", "97,雷", "99,雷", "999,不明", "-1,不明"
    })
    void classifiesEveryDocumentedCodeAndUnknownCodes(int code, String name) {
        assertEquals(name, WeatherClient.weatherName(code));
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void rejectsMalformedMissingOrInsufficientForecasts(String body) throws Exception {
        respond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertUnavailable();
    }

    static Stream<String> invalidPayloads() {
        String valid = payload(TODAY, "0,1,2,3,45,61,71");
        return Stream.of("", "not json", "null", "[]", "{}", "{\"daily\":null}",
                "{\"daily\":{\"time\":[],\"weather_code\":[]}}",
                "{\"daily\":{\"time\":[],\"weather_code\":[0]}}",
                valid.replace("\"weather_code\"", "\"unknown\""),
                valid.replace("0,1,2,3,45,61,71", "0,1,2,3,45,61"),
                valid.replace("0,1,2,3,45,61,71", "0,1,2,3,45,61,71,95"),
                valid.replace("0,1,2,3,45,61,71", "null,1,2,3,45,61,71"),
                valid.replace("0,1,2,3,45,61,71", "\"0\",1,2,3,45,61,71"),
                valid.replace("0,1,2,3,45,61,71", "0.5,1,2,3,45,61,71"),
                valid.replace("0,1,2,3,45,61,71", "2147483648,1,2,3,45,61,71"),
                valid.replace("2026-10-28", "invalid-date"),
                valid.replace("2026-10-28", "2026-10-27"),
                valid.replace("\"2026-10-28\"", "null"),
                payload(TODAY.minusDays(1), "0,1,2,3,45,61,71"),
                payload(TODAY.plusDays(1), "0,1,2,3,45,61,71"));
    }

    @ParameterizedTest
    @MethodSource("connectionFailures")
    void handlesConnectionAndTimeoutFailures(java.io.IOException exception) throws Exception {
        respond(withException(exception));
        assertUnavailable();
    }

    static Stream<java.io.IOException> connectionFailures() {
        return Stream.of(new UnknownHostException("unknown host"), new ConnectException("connection refused"),
                new SocketTimeoutException("connect timed out"), new SocketTimeoutException("read timed out"));
    }

    @ParameterizedTest
    @CsvSource({ "400", "429", "500", "503" })
    void handlesUpstreamHttpErrors(int statusCode) throws Exception {
        respond(withStatus(HttpStatus.valueOf(statusCode)));
        assertUnavailable();
    }

    @Test
    void usesConfiguredUrl() throws Exception {
        respond(withSuccess(payload(TODAY, "0,1,2,3,45,61,71"), MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/weather")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7));
    }

    @Test
    void invalidConfiguredUrlIsReportedAsUnavailable() throws Exception {
        WeatherClient client = new WeatherClient(RestClient.create(), "invalid url", CLOCK);
        mockMvc = MockMvcBuilders.standaloneSetup(new WeatherApiController(client)).build();
        assertUnavailable();
    }

    private static String payload(LocalDate firstDate, String codes) {
        String dates = IntStream.range(0, 7).mapToObj(index -> "\"" + firstDate.plusDays(index) + "\"")
                .collect(Collectors.joining(","));
        return "{\"latitude\":35.68,\"daily\":{\"time\":[" + dates + "],\"weather_code\":[" + codes + "]}}";
    }

    private void respond(ResponseCreator response) {
        upstream.expect(requestTo(URL)).andRespond(response);
    }

    private void assertUnavailable() throws Exception {
        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Weather-Unavailable", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
    }
}
