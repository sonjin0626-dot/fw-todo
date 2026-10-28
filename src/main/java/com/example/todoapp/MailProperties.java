package com.example.todoapp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {
    private boolean enabled;
    @NotBlank
    private String from = "noreply@example.com";
    @Pattern(regexp = "https?://[^\\s]+")
    private String baseUrl = "http://localhost:8080";
    @Min(1) @Max(5)
    private int maxAttempts = 3;
}
