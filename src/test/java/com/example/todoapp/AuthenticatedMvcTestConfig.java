package com.example.todoapp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedMvcTestConfig {
    @Bean
    UserMapper testUserMapper() {
        return TodoTestUsers.mapper();
    }

    @Bean
    MockMvcBuilderCustomizer csrfForExistingFeatureTests() {
        // 既存機能のテストも認証フィルターと文字コードフィルターを通して検証する。
        return builder -> builder.defaultRequest(get("/").with(csrf()));
    }
}
