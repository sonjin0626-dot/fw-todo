package com.example.todoapp.api;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HolidayApiControllerTests {

    private MockMvc mockMvc;
    private MockRestServiceServer holidayApi;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        holidayApi = MockRestServiceServer.bindTo(builder).build();
        HolidayClient holidayClient = new HolidayClient(builder.build());
        mockMvc = MockMvcBuilders.standaloneSetup(new HolidayApiController(holidayClient)).build();
        respondWith(withSuccess("""
                {"2026-04-29":"昭和の日","2026-05-03":"憲法記念日","2026-05-04":"みどりの日"}
                """, MediaType.APPLICATION_JSON));
    }

    @AfterEach
    void verifyHolidayApiCalled() {
        holidayApi.verify();
    }

    @Test
    void returnsAllHolidaysWithoutDates() throws Exception {
        mockMvc.perform(get("/api/holidays"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Holidays-Unavailable"))
                .andExpect(content().json("""
                        {"2026-04-29":"昭和の日","2026-05-03":"憲法記念日","2026-05-04":"みどりの日"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void includesBothRangeBoundaries() throws Exception {
        mockMvc.perform(get("/api/holidays").param("from", "2026-04-29").param("to", "2026-05-03"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"2026-04-29":"昭和の日","2026-05-03":"憲法記念日"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void acceptsFromWithoutTo() throws Exception {
        mockMvc.perform(get("/api/holidays").param("from", "2026-05-03"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"2026-05-03":"憲法記念日","2026-05-04":"みどりの日"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void acceptsToWithoutFrom() throws Exception {
        mockMvc.perform(get("/api/holidays").param("to", "2026-04-29"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"2026-04-29":"昭和の日"}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void returnsEmptyObjectWhenNoHolidaysMatch() throws Exception {
        mockMvc.perform(get("/api/holidays").param("from", "2026-04-01").param("to", "2026-04-28"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Holidays-Unavailable"))
                .andExpect(content().json("{}", JsonCompareMode.STRICT));
    }

    @Test
    void returnsEmptyObjectWhenHostCannotBeResolved() throws Exception {
        assertUnavailable(new UnknownHostException("host unavailable"));
    }

    @Test
    void returnsEmptyObjectWhenConnectionIsRefused() throws Exception {
        assertUnavailable(new ConnectException("connection refused"));
    }

    @Test
    void returnsEmptyObjectWhenConnectionTimesOut() throws Exception {
        assertUnavailable(new SocketTimeoutException("connect timed out"));
    }

    @Test
    void returnsEmptyObjectWhenResponseTimesOut() throws Exception {
        assertUnavailable(new SocketTimeoutException("read timed out"));
    }

    @Test
    void returnsEmptyObjectWhenUpstreamReturnsError() throws Exception {
        respondWith(withServerError());
        assertUnavailableResponse();
    }

    @Test
    void returnsEmptyObjectWhenUpstreamBodyIsMissing() throws Exception {
        respondWith(withSuccess());
        assertUnavailableResponse();
    }

    @Test
    void returnsEmptyObjectWhenUpstreamBodyIsInvalid() throws Exception {
        respondWith(withSuccess("invalid json", MediaType.APPLICATION_JSON));
        assertUnavailableResponse();
    }

    @Test
    void doesNotMarkSuccessfulEmptyObjectAsUnavailable() throws Exception {
        respondWith(withSuccess("{}", MediaType.APPLICATION_JSON));
        mockMvc.perform(get("/api/holidays"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Holidays-Unavailable"))
                .andExpect(content().json("{}", JsonCompareMode.STRICT));
    }

    private void respondWith(ResponseCreator response) {
        holidayApi.reset();
        holidayApi.expect(requestTo("https://holidays-jp.github.io/api/v1/date.json"))
                .andRespond(response);
    }

    private void assertUnavailable(IOException failure) throws Exception {
        respondWith(withException(failure));
        assertUnavailableResponse();
        respondWith(withException(failure));
        mockMvc.perform(get("/api/holidays").param("from", "2026-04-01").param("to", "2026-05-31"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Holidays-Unavailable", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}", JsonCompareMode.STRICT));
    }

    private void assertUnavailableResponse() throws Exception {
        mockMvc.perform(get("/api/holidays"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Holidays-Unavailable", "true"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}", JsonCompareMode.STRICT));
    }
}
