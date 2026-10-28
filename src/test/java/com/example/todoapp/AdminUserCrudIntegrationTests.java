package com.example.todoapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:user-crud-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserCrudIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean UserMapper mapper;
    Long adminId;
    Long ownerId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (username, password, role) VALUES ('crudadmin', ?, 'ADMIN'), ('crudowner', ?, 'USER')",
                encoder.encode("old-password"), encoder.encode("old-password"));
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'crudadmin'", Long.class);
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'crudowner'", Long.class);
    }

    @Test
    void rendersDetailsDatesEditAndDeleteControlsWithoutPasswords() throws Exception {
        for (String path : new String[]{"/admin/users", "/admin/users/" + ownerId, "/admin/users/" + ownerId + "/edit"}) {
            mvc.perform(get(path).with(user("crudadmin"))).andExpect(status().isOk())
                    .andExpect(content().string(containsString("crudowner")))
                    .andExpect(content().string(not(containsString("$2"))));
        }
        mvc.perform(get("/admin/users/{id}", ownerId).with(user("crudadmin")))
                .andExpect(content().string(containsString("登録日時")))
                .andExpect(content().string(containsString("更新日時")))
                .andExpect(content().string(containsString("confirm(")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        assertNull(mapper.findById(ownerId).getPassword());
        assertNotNull(mapper.findById(ownerId).getCreatedAt());
        assertNotNull(mapper.findById(ownerId).getUpdatedAt());
    }

    @Test
    void renamesResetsPasswordAndPreservesOwnershipAndBlankPassword() throws Exception {
        jdbc.update("INSERT INTO todos (user_id, title, category) VALUES (?, 'kept-task', '仕事')", ownerId);
        jdbc.update("UPDATE users SET updated_at = '2000-01-01 00:00:00' WHERE id = ?", ownerId);
        mvc.perform(post("/admin/users/{id}/edit", ownerId).with(user("crudadmin")).with(csrf())
                .param("username", "renamed").param("role", "USER").param("enabled", "true")
                .param("newPassword", "new-password-123")).andExpect(redirectedUrl("/admin/users"));
        String hash = jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, ownerId);
        assertTrue(encoder.matches("new-password-123", hash));
        assertEquals(ownerId, jdbc.queryForObject("SELECT user_id FROM todos WHERE title = 'kept-task'", Long.class));
        assertTrue(mapper.findById(ownerId).getUpdatedAt().getYear() > 2000);
        mvc.perform(post("/login").with(csrf()).param("username", "renamed").param("password", "old-password"))
                .andExpect(redirectedUrl("/login?error=true"));
        mvc.perform(post("/login").with(csrf()).param("username", "renamed").param("password", "new-password-123"))
                .andExpect(redirectedUrl("/todos"));
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("crudadmin")).with(csrf())
                .param("username", "renamed").param("role", "ADMIN").param("enabled", "true")
                .param("newPassword", "")).andExpect(redirectedUrl("/admin/users"));
        assertEquals(hash, jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, ownerId));
    }

    @Test
    void invalidAndDuplicateInputDoNotPartiallyUpdateOrEchoPasswords() throws Exception {
        String original = jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, ownerId);
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("crudadmin")).with(csrf())
                .param("username", "crudadmin").param("role", "ADMIN").param("enabled", "true")
                .param("newPassword", "sensitive-password")).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "username"))
                .andExpect(content().string(not(containsString("sensitive-password"))));
        for (String password : new String[]{"short", "あ".repeat(25)}) {
            mvc.perform(post("/admin/users/{id}", ownerId).with(user("crudadmin")).with(csrf())
                    .param("username", "validname").param("role", "ADMIN").param("newPassword", password))
                    .andExpect(status().isOk()).andExpect(model().attributeHasErrors("form"));
        }
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("crudadmin")).with(csrf())
                .param("username", "<script>").param("role", "USER"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("form", "username"));
        assertEquals("crudowner", mapper.findById(ownerId).getUsername());
        assertEquals("USER", mapper.findById(ownerId).getRole());
        assertTrue(mapper.findById(ownerId).isEnabled());
        assertEquals(original, jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, ownerId));
    }

    @Test
    void deletionRemovesActiveCompletedAndTrashedTasksAndRevokesSession() throws Exception {
        jdbc.update("INSERT INTO todos (user_id, title, category) VALUES (?, 'active', '仕事'), (?, 'complete', '仕事'), (?, 'trash', '仕事'), (?, 'admin-task', '仕事')",
                ownerId, ownerId, ownerId, adminId);
        jdbc.update("UPDATE todos SET completed = true WHERE title = 'complete'");
        jdbc.update("UPDATE todos SET deleted_at = CURRENT_TIMESTAMP WHERE title = 'trash'");
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                .param("username", "crudowner").param("password", "old-password")).andReturn().getRequest().getSession(false);
        mvc.perform(post("/admin/users/{id}/delete", ownerId).with(user("crudadmin")).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));
        assertNull(mapper.findById(ownerId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE user_id = ?", Integer.class, ownerId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE user_id = ?", Integer.class, adminId));
        mvc.perform(get("/todos").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void failedUserDeleteRollsBackRelatedTaskDeletion() {
        jdbc.update("INSERT INTO todos (user_id, title, category) VALUES (?, 'rollback-task', '仕事')", ownerId);
        doThrow(new IllegalStateException("simulated delete failure")).when(mapper).deleteById(ownerId);
        assertThrows(Exception.class, () -> mvc.perform(post("/admin/users/{id}/delete", ownerId)
                .with(user("crudadmin")).with(csrf())));
        assertNotNull(mapper.findById(ownerId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE user_id = ?", Integer.class, ownerId));
    }

    @Test
    void protectsDetailDeleteAndUpdateFromNonAdminsCsrfSelfDeleteAndMissingIds() throws Exception {
        mvc.perform(get("/admin/users/{id}", ownerId).with(user("crudowner"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}/delete", ownerId).with(user("crudowner")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}/edit", ownerId).with(user("crudowner")).with(csrf())
                .param("username", "hacked").param("role", "ADMIN")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}/delete", ownerId).with(user("crudadmin"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}/delete", adminId).with(user("crudadmin")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/users/9223372036854775807").with(user("crudadmin"))).andExpect(status().isNotFound());
        mvc.perform(post("/admin/users/9223372036854775807/delete").with(user("crudadmin")).with(csrf())).andExpect(status().isNotFound());
        assertNotNull(mapper.findById(adminId));
        assertNotNull(mapper.findById(ownerId));
    }

    @Test
    void selfRenameRequiresLoginWithNewCredentials() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                .param("username", "crudadmin").param("password", "old-password")).andReturn().getRequest().getSession(false);
        mvc.perform(post("/admin/users/{id}", adminId).session(session).with(csrf())
                .param("username", "newadmin").param("role", "ADMIN").param("enabled", "true"))
                .andExpect(redirectedUrl("/login?accountUpdated=true"));
        assertTrue(session.isInvalid());
        mvc.perform(post("/login").with(csrf()).param("username", "newadmin").param("password", "old-password"))
                .andExpect(redirectedUrl("/todos"));
    }

    @Test
    void oldSessionCannotBecomeAnotherAccountWhenUsernameIsReused() throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login").with(csrf())
                .param("username", "crudowner").param("password", "old-password")).andReturn().getRequest().getSession(false);
        mvc.perform(post("/admin/users/{id}/delete", ownerId).with(user("crudadmin")).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));
        mvc.perform(post("/register").with(csrf()).param("username", "crudowner")
                .param("password", "another-password").param("passwordConfirmation", "another-password"))
                .andExpect(redirectedUrl("/login?registered=true"));
        mvc.perform(get("/todos").session(session)).andExpect(status().isForbidden());
        assertTrue(session.isInvalid());
    }
}
