package com.example.todoapp.api;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WeatherApiController {

    private final WeatherClient weatherClient;

    public WeatherApiController(WeatherClient weatherClient) {
        this.weatherClient = weatherClient;
    }

    @GetMapping(value = "/api/weather", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<WeatherClient.WeatherDay>> list() {
        WeatherClient.FetchResult result = weatherClient.fetchWeather();
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.unavailable()) {
            response.header("X-Weather-Unavailable", "true");
        }
        return response.body(result.forecast());
    }
}
