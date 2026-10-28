package com.example.todoapp.api;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class WeatherClient {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final int FORECAST_DAYS = 7;

    private final RestClient restClient;
    private final String forecastUrl;
    private final Clock clock;

    @Autowired
    public WeatherClient(@Value("${weather.api.url}") String forecastUrl,
            @Value("${weather.api.connect-timeout-ms:3000}") int connectTimeoutMillis,
            @Value("${weather.api.read-timeout-ms:5000}") int readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMillis));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.restClient = RestClient.builder().requestFactory(factory).build();
        this.forecastUrl = forecastUrl;
        this.clock = Clock.system(TOKYO);
    }

    WeatherClient(RestClient restClient, String forecastUrl, Clock clock) {
        this.restClient = restClient;
        this.forecastUrl = forecastUrl;
        this.clock = clock;
    }

    public FetchResult fetchWeather() {
        try {
            Map<String, Object> body = restClient.get().uri(URI.create(forecastUrl)).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            if (body == null || !(body.get("daily") instanceof Map<?, ?> daily)
                    || !(daily.get("time") instanceof List<?> dates)
                    || !(daily.get("weather_code") instanceof List<?> codes)
                    || dates.size() != FORECAST_DAYS || codes.size() != FORECAST_DAYS) {
                throw new IllegalArgumentException("daily.time / daily.weather_code に7日分のデータがありません");
            }

            LocalDate today = LocalDate.now(clock.withZone(TOKYO));
            List<WeatherDay> forecast = new ArrayList<>();
            for (int index = 0; index < FORECAST_DAYS; index++) {
                if (!(dates.get(index) instanceof String dateText)
                        || !(codes.get(index) instanceof Number code)
                        || !Double.isFinite(code.doubleValue()) || code.doubleValue() != code.intValue()) {
                    throw new IllegalArgumentException("天気の日付またはコードが不正です");
                }
                LocalDate date = LocalDate.parse(dateText);
                if (!date.equals(today.plusDays(index))) {
                    throw new IllegalArgumentException("天気の日付が東京の今日から7日間と一致しません");
                }
                forecast.add(new WeatherDay(date, code.intValue(), weatherName(code.intValue())));
            }
            return new FetchResult(List.copyOf(forecast), false);
        } catch (RestClientException | IllegalArgumentException | DateTimeException exception) {
            log.warn("天気APIの取得に失敗しました: {}", exception.toString());
            return new FetchResult(List.of(), true);
        }
    }

    // Open-Meteo's WMO table: https://open-meteo.com/en/docs#weathervariables
    static String weatherName(int code) {
        return switch (code) {
            case 0, 1 -> "晴れ";
            case 2, 3 -> "曇り";
            case 45, 48 -> "霧";
            case 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "雨";
            case 71, 73, 75, 77, 85, 86 -> "雪";
            case 95, 96, 97, 99 -> "雷";
            default -> "不明";
        };
    }

    public record WeatherDay(LocalDate date, int weatherCode, String weatherName) { }

    public record FetchResult(List<WeatherDay> forecast, boolean unavailable) { }
}
