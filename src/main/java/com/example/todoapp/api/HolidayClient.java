package com.example.todoapp.api;

import java.time.Duration;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class HolidayClient {

    private final RestClient restClient;

    public HolidayClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    HolidayClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public FetchResult fetchHolidays() {
        try {
            Map<String, String> holidays = restClient.get()
                    .uri("https://holidays-jp.github.io/api/v1/date.json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, String>>() {
                    });
            if (holidays == null) {
                return new FetchResult(Map.of(), true);
            }
            return new FetchResult(holidays, false);
        } catch (RestClientException ex) {
            return new FetchResult(Map.of(), true);
        }
    }

    public record FetchResult(Map<String, String> holidays, boolean unavailable) {
    }
}
