package com.example.todoapp;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

import com.example.todoapp.exception.TodoNotFoundException;
import java.util.Locale;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternationalizationTests {
    @Autowired MockMvc mvc;
    @Autowired UserMapper users;
    @MockitoBean TodoService todoService;

    private MockHttpSession englishSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME, Locale.ENGLISH);
        return session;
    }

    @BeforeEach
    void sampleData() {
        if (users.findByUsername("i18n-admin") == null) {
            User admin = new User();
            admin.setUsername("i18n-admin");
            admin.setPassword("unused-by-mock-authentication");
            admin.setRole("ADMIN");
            users.insert(admin);
        }
        Todo todo = new Todo();
        todo.setId(1L);
        todo.setUserId(users.findByUsername("i18n-admin").getId());
        todo.setTitle("利用者の入力は保持");
        todo.setCategory("デザイン");
        todo.setPriority(1);
        todo.setCompleted(false);
        todo.setDueDate(LocalDate.of(2026, 11, 1));
        when(todoService.findById(1L)).thenReturn(todo);
        when(todoService.count(anyString(), anyString(), anyBoolean())).thenReturn(1L);
        when(todoService.searchPage(anyString(), anyString(), anyString(), anyBoolean(), anyInt()))
                .thenReturn(List.of(todo));
        when(todoService.countTrash(anyString(), anyString())).thenReturn(1L);
        when(todoService.searchTrashPage(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(todo));
    }

    @Test
    void defaultsToJapaneseRegardlessOfBrowserLanguage() throws Exception {
        mvc.perform(get("/login").header("Accept-Language", "en-US"))
                .andExpect(content().string(containsString("lang=\"ja\"")))
                .andExpect(content().string(containsString(">ログイン</h1>")));
    }

    @Test
    void switchesAndPersistsTheLanguageAcrossNavigation() throws Exception {
        var result = mvc.perform(get("/login").param("lang", "en"))
                .andExpect(status().isOk()).andExpect(request().sessionAttribute(SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME, Locale.ENGLISH))
                .andExpect(content().string(containsString("Sign in")))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/register").session(session))
                .andExpect(content().string(containsString("lang=\"en\"")))
                .andExpect(content().string(containsString("Confirm password")));
        mvc.perform(get("/login").session(session).param("lang", "ja"))
                .andExpect(request().sessionAttribute(SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME, Locale.JAPANESE))
                .andExpect(content().string(containsString(">ログイン</h1>")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fr", "en-US", "invalid_!", ""})
    void ignoresUnsupportedLanguageParameters(String language) throws Exception {
        mvc.perform(get("/login").session(englishSession()).param("lang", language))
                .andExpect(status().isOk())
                .andExpect(request().sessionAttribute(SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME, Locale.ENGLISH))
                .andExpect(content().string(containsString("lang=\"en\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/todos", "/todos?trash=1", "/todos/new", "/todos/1/edit",
            "/todos/1/delete", "/todos/1/attach", "/calendar?year=2026&month=10",
            "/calendar?view=week&date=2026-10-28", "/admin/users"})
    void rendersExistingScreensInEnglishWithoutMissingMessages(String path) throws Exception {
        mvc.perform(get(path).session(englishSession()).with(user("i18n-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lang=\"en\"")))
                .andExpect(content().string(not(containsString("??"))));
    }

    @Test
    void translatesStoredCategoryLabelsWithoutChangingTheirValuesOrUserInput() throws Exception {
        mvc.perform(get("/todos").session(englishSession()).with(user("i18n-admin").roles("ADMIN")))
                .andExpect(content().string(containsString("value=\"デザイン\"")))
                .andExpect(content().string(containsString(">Design</td>")))
                .andExpect(content().string(containsString("利用者の入力は保持")))
                .andExpect(content().string(containsString("Delete COUNT_VALUE selected tasks?")));
    }

    @Test
    void translatesValidationAndRegistrationMessages() throws Exception {
        mvc.perform(post("/todos/confirm").session(englishSession()).with(csrf())
                .with(user("i18n-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter a task.")))
                .andExpect(content().string(containsString("Select a priority.")));
        mvc.perform(post("/register").session(englishSession()).with(csrf())
                .param("username", "i18n-admin").param("password", "password-123")
                .param("passwordConfirmation", "password-123"))
                .andExpect(content().string(containsString("This username is already in use.")));
        mvc.perform(get("/login?error=true").session(englishSession()))
                .andExpect(content().string(containsString("Incorrect username or password")));
    }

    @Test
    void preservesEnteredValuesWhenChangingLanguageFromAConfirmationScreen() throws Exception {
        mvc.perform(post("/todos/new").with(csrf()).with(user("i18n-admin").roles("ADMIN"))
                .param("lang", "en").param("title", "入力中の内容").param("detail", "メモ内容")
                .param("category", "デザイン").param("priority", "1").param("dueDate", "2026-11-01"))
                .andExpect(status().isOk()).andExpect(view().name("create"))
                .andExpect(content().string(containsString("入力中の内容")))
                .andExpect(content().string(containsString("メモ内容")))
                .andExpect(request().sessionAttribute(SessionLocaleResolver.LOCALE_SESSION_ATTRIBUTE_NAME, Locale.ENGLISH));
        verify(todoService, never()).create(any());
    }

    @Test
    void translatesConfirmationAndSuccessMessages() throws Exception {
        mvc.perform(post("/todos/confirm").session(englishSession()).with(csrf())
                .with(user("i18n-admin").roles("ADMIN"))
                .param("title", "入力のまま").param("category", "デザイン").param("priority", "1"))
                .andExpect(status().isOk()).andExpect(view().name("create-confirm"))
                .andExpect(content().string(containsString("Confirm new task")))
                .andExpect(content().string(containsString("Design")))
                .andExpect(content().string(containsString("value=\"デザイン\"")));
        mvc.perform(post("/todos").session(englishSession()).with(csrf())
                .with(user("i18n-admin").roles("ADMIN")))
                .andExpect(redirectedUrl("/todos")).andExpect(flash().attribute("message", "Task created."));
    }

    @Test
    void translatesErrorPages() throws Exception {
        when(todoService.findById(404L)).thenThrow(new TodoNotFoundException(404L));
        mvc.perform(get("/todos/404/edit").session(englishSession()).with(user("i18n-admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("The requested task was not found.")));
    }

    @Test
    void messageCatalogsHaveIdenticalKeys() throws Exception {
        Properties defaults = catalog("messages.properties");
        assertEquals(defaults, catalog("messages_ja.properties"));
        Properties english = catalog("messages_en.properties");
        assertEquals(defaults.keySet(), english.keySet());
        assertTrue(english.values().stream().noneMatch(value -> value.toString().isBlank()));
    }

    private Properties catalog(String filename) throws Exception {
        Properties result = new Properties();
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/" + filename), StandardCharsets.UTF_8)) {
            result.load(reader);
        }
        return result;
    }
}
