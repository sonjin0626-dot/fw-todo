package com.example.todoapp;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:roles-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleAuthorizationIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminUserService users;
    @Autowired TodoService todos;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    Long ownerId;
    Long todoId;
    Long adminId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users WHERE username IN ('roleadmin', 'roleowner')");
        jdbc.update("INSERT INTO users (username, password, role) VALUES ('roleadmin', ?, 'ADMIN'), ('roleowner', ?, 'USER')",
                encoder.encode("secret"), encoder.encode("secret"));
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'roleowner'", Long.class);
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'roleadmin'", Long.class);
        jdbc.update("INSERT INTO todos (user_id, title, category, priority) VALUES (?, 'owner-task', '資格', 2)", ownerId);
        todoId = jdbc.queryForObject("SELECT id FROM todos WHERE title = 'owner-task'", Long.class);
    }

    @Test
    void usersSeeOnlyOwnTasksAndCannotUseAdminPagesOrBulkDelete() throws Exception {
        mvc.perform(get("/todos").with(user("roleowner"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("owner-task")))
                .andExpect(content().string(not(containsString("id=\"bulk-delete-form\""))))
                .andExpect(content().string(not(containsString("/admin/users"))));
        mvc.perform(get("/admin/users").with(user("roleowner"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users/{id}/edit", adminId).with(user("roleowner"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("roleowner")).with(csrf())
                .param("role", "ADMIN").param("enabled", "true")).andExpect(status().isForbidden());
        mvc.perform(post("/todos/bulk-delete").with(user("roleowner")).with(csrf())
                .param("ids", todoId.toString())).andExpect(status().isForbidden());
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("roleowner", "",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))));
        try {
        assertThrows(AccessDeniedException.class, () -> users.findAll());
        assertThrows(AccessDeniedException.class, () -> todos.bulkDelete(List.of(todoId)));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void adminSeesAndEditsForeignTasksWithoutChangingOwnerAndCanDeleteRestoreAndBulkDelete() throws Exception {
        mvc.perform(get("/todos").with(user("roleadmin"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("owner-task")))
                .andExpect(content().string(containsString("/admin/users")))
                .andExpect(content().string(containsString("id=\"bulk-delete-form\"")));
        mvc.perform(get("/api/todos/{id}", todoId).with(user("roleadmin"))).andExpect(status().isOk());
        mvc.perform(get("/api/todos.csv").with(user("roleadmin"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("owner-task")));
        mvc.perform(get("/todos/{id}/edit", todoId).with(user("roleadmin"))).andExpect(status().isOk());
        mvc.perform(post("/todos/{id}", todoId).with(user("roleadmin")).with(csrf())
                .param("title", "admin-edited").param("category", "資格").param("priority", "1")
                .param("userId", adminId.toString())).andExpect(status().is3xxRedirection());
        assertEquals(ownerId, jdbc.queryForObject("SELECT user_id FROM todos WHERE id = ?", Long.class, todoId));
        assertEquals("admin-edited", jdbc.queryForObject("SELECT title FROM todos WHERE id = ?", String.class, todoId));
        mvc.perform(post("/todos/{id}/delete", todoId).with(user("roleadmin")).with(csrf())).andExpect(status().is3xxRedirection());
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM todos WHERE id = ?", Object.class, todoId));
        mvc.perform(post("/todos/{id}/restore", todoId).with(user("roleadmin")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/todos/bulk-delete").with(user("roleadmin")).with(csrf())
                .param("ids", todoId.toString())).andExpect(status().is3xxRedirection());
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM todos WHERE id = ?", Object.class, todoId));
    }

    @Test
    void managesRolesAndRejectsInvalidRolesSelfLockoutAndMissingUsers() throws Exception {
        mvc.perform(get("/admin/users").with(user("roleadmin"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("roleowner")))
                .andExpect(content().string(not(containsString("$2a$"))));
        mvc.perform(get("/admin/users/{id}/edit", ownerId).with(user("roleadmin"))).andExpect(status().isOk());
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("roleadmin")).with(csrf())
                .param("role", "ADMIN").param("enabled", "true")).andExpect(redirectedUrl("/admin/users"));
        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String.class, ownerId));
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("roleadmin")).with(csrf())
                .param("role", "SUPERUSER")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/users/{id}", adminId).with(user("roleadmin")).with(csrf())
                .param("role", "USER").param("enabled", "true")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/users/{id}", adminId).with(user("roleadmin")).with(csrf())
                .param("role", "ADMIN")).andExpect(status().isBadRequest());
        mvc.perform(get("/admin/users/9223372036854775807/edit").with(user("roleadmin"))).andExpect(status().isNotFound());
    }

    @Test
    void updatesExistingSessionsAndPreventsDisabledLoginAndCsrfForgery() throws Exception {
        var result = mvc.perform(post("/login").with(csrf()).param("username", "roleowner").param("password", "secret"))
                .andExpect(redirectedUrl("/todos")).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/admin/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("roleadmin")).with(csrf())
                .param("role", "ADMIN").param("enabled", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/users").session(session)).andExpect(status().isOk());
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("roleadmin")).with(csrf())
                .param("role", "USER").param("enabled", "true")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/users").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("roleadmin"))
                .param("role", "ADMIN")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("roleadmin")).with(csrf())
                .param("role", "USER")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/todos").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/login").with(csrf()).param("username", "roleowner").param("password", "secret"))
                .andExpect(redirectedUrl("/login?error=true"));
    }
}
