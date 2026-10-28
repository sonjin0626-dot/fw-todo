package com.example.todoapp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTests {
    private static final String PASSWORD = "test-password-123";
    @Autowired MockMvc mvc;
    @Autowired UserMapper userMapper;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TodoService todoService;

    @BeforeEach
    void clearUsers() {
        jdbc.update("DELETE FROM users");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/todos", "/todos/new", "/calendar", "/api/todos", "/api/todos.csv", "/api/weather", "/mcp"})
    void redirectsAnonymousUsersToLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        verifyNoInteractions(todoService);
    }

    @Test
    void servesPublicPagesAndStylesAndIncludesCsrfToken() throws Exception {
        for (String path : List.of("/login", "/register")) {
            String html = mvc.perform(get(path)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(html.contains("name=\"_csrf\""));
        }
        mvc.perform(get("/css/style.css")).andExpect(status().isOk());
    }

    @Test
    void registersBcryptUserAndPreventsRoleEscalation() throws Exception {
        register("alice", PASSWORD, PASSWORD).andExpect(redirectedUrl("/login?registered=true"));
        User user = userMapper.findByUsername("alice");
        assertNotNull(user.getId());
        assertNotEquals(PASSWORD, user.getPassword());
        assertTrue(user.getPassword().startsWith("$2"));
        assertTrue(passwordEncoder.matches(PASSWORD, user.getPassword()));
        assertEquals("USER", user.getRole());
    }

    @Test
    void rejectsDuplicateUsernameWithoutChangingExistingPassword() throws Exception {
        register("alice", PASSWORD, PASSWORD);
        String hash = userMapper.findByUsername("alice").getPassword();
        register("alice", "another-password", "another-password")
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("registrationForm", "username"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("このユーザー名はすでに使われています")));
        assertEquals(hash, userMapper.findByUsername("alice").getPassword());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    @Test
    void rejectsInvalidRegistrationAndDoesNotRedisplayPasswords() throws Exception {
        String html = register("a", "short", "different").andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("registrationForm", "username", "password", "passwordMatching"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(html.contains("value=\"short\""));
        assertFalse(html.contains("value=\"different\""));
        String multibytePassword = "あ".repeat(25);
        register("alice", multibytePassword, multibytePassword)
                .andExpect(model().attributeHasFieldErrors("registrationForm", "passwordWithinByteLimit"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
    }

    @Test
    void logsInWithDatabaseCredentialsRotatesSessionAndLogsOut() throws Exception {
        register("alice", PASSWORD, PASSWORD);
        MvcResult loginPage = mvc.perform(get("/login")).andReturn();
        MockHttpSession session = (MockHttpSession) loginPage.getRequest().getSession();
        String previousSessionId = session.getId();
        MvcResult login = mvc.perform(post("/login").session(session)
                .param("_csrf", token(loginPage)).param("username", "alice").param("password", PASSWORD))
                .andExpect(redirectedUrl("/todos")).andExpect(authenticated().withUsername("alice").withRoles("USER"))
                .andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotEquals(previousSessionId, session.getId());
        when(todoService.searchPage(anyString(), anyString(), anyString(), anyBoolean(), anyInt())).thenReturn(List.of());
        MvcResult todos = mvc.perform(get("/todos").session(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("alice"))).andReturn();
        mvc.perform(post("/logout").session(session).param("_csrf", token(todos)))
                .andExpect(redirectedUrl("/login?logout=true")).andExpect(unauthenticated());
        assertTrue(session.isInvalid());
        mvc.perform(get("/todos")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login?logout=true"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ログアウトしました")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", ""})
    void rejectsIncorrectCredentials(String password) throws Exception {
        register("alice", PASSWORD, PASSWORD);
        mvc.perform(post("/login").with(csrf()).param("username", "alice").param("password", password))
                .andExpect(redirectedUrl("/login?error=true")).andExpect(unauthenticated());
        mvc.perform(get("/login?error=true"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ユーザー名またはパスワードが正しくありません")));
        mvc.perform(post("/login").with(csrf()).param("username", "unknown").param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error=true")).andExpect(unauthenticated());
    }

    @Test
    void requiresCsrfForRegistrationLoginUpdatesAndLogout() throws Exception {
        mvc.perform(post("/register").param("username", "alice").param("password", PASSWORD)
                .param("passwordConfirmation", PASSWORD)).andExpect(status().isForbidden());
        mvc.perform(post("/login").param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(post("/todos/1/delete").with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/todos/1").with(user("alice")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(user("alice"))).andExpect(status().isForbidden());
        verifyNoInteractions(todoService);
    }

    @Test
    void existingTodoUpdateWorksWithTheTokenRenderedByThymeleaf() throws Exception {
        register("alice", PASSWORD, PASSWORD);
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setId(1L);
        todo.setTitle("テスト");
        when(todoService.findById(1L)).thenReturn(todo);
        MvcResult page = mvc.perform(get("/todos/1/delete").with(user("alice"))).andExpect(status().isOk()).andReturn();
        mvc.perform(post("/todos/1/delete").with(user("alice"))
                .session((MockHttpSession) page.getRequest().getSession()).param("_csrf", token(page)))
                .andExpect(redirectedUrl("/todos"));
        verify(todoService).delete(1L);
    }

    private org.springframework.test.web.servlet.ResultActions register(String username, String password, String confirmation) throws Exception {
        return mvc.perform(post("/register").with(csrf()).param("username", username)
                .param("password", password).param("passwordConfirmation", confirmation).param("role", "ADMIN"));
    }

    private String token(MvcResult result) throws Exception {
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(html);
        assertTrue(matcher.find(), "フォームにCSRFトークンが必要です");
        return matcher.group(1);
    }
}
