package com.example.todoapp;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MailConfiguration {
    @Bean
    public Clock reminderClock(@Value("${app.mail.zone:Asia/Tokyo}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
