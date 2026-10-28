package com.example.todoapp;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.todoapp.mcp.TodoTools;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ownership-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodoOwnershipIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoService service;
    @Autowired TodoTools tools;
    private Long aliceId;
    private Long bobId;
    private Long ownId;
    private Long foreignId;
    private Long ownTrashId;
    private Long foreignTrashId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users WHERE username IN ('alice', 'bob')");
        jdbc.update("INSERT INTO users (username, password, role) VALUES ('alice', 'unused', 'USER'), ('bob', 'unused', 'USER')");
        aliceId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'alice'", Long.class);
        bobId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'bob'", Long.class);
        ownId = insert(aliceId, "alice-active", false);
        foreignId = insert(bobId, "bob-secret", false);
        ownTrashId = insert(aliceId, "alice-trash", true);
        foreignTrashId = insert(bobId, "bob-trash-secret", true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsInWithTheSpecifiedSonjinAccount() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "sonjin").param("password", "password"))
                .andExpect(redirectedUrl("/todos"))
                .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers
                        .authenticated().withUsername("sonjin").withRoles("USER"));
    }

    @Test
    void isolatesListsCountsTrashCalendarApiAndCsvForBothUsers() throws Exception {
        for (String username : List.of("alice", "bob")) {
            String other = username.equals("alice") ? "bob" : "alice";
            mvc.perform(get("/todos").with(user(username)))
                    .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 1L))
                    .andExpect(content().string(containsString(username + (username.equals("alice") ? "-active" : "-secret"))))
                    .andExpect(content().string(not(containsString(other + "-"))));
            mvc.perform(get("/todos").param("trash", "1").with(user(username)))
                    .andExpect(status().isOk()).andExpect(model().attribute("totalCount", 1L))
                    .andExpect(content().string(containsString(username + "-trash")))
                    .andExpect(content().string(not(containsString(other + "-trash"))));
            mvc.perform(get("/api/todos").param("from", "2026-10-01").param("to", "2026-10-31")
                    .with(user(username))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].title", startsWith(username + "-")));
            String csv = mvc.perform(get("/api/todos.csv").with(user(username))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(csv.contains(username + "-"));
            assertFalse(csv.contains(other + "-"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "delete"})
    void deniesForeignConfirmationPagesIncludingDeletedTodos(String action) throws Exception {
        for (Long id : List.of(foreignId, foreignTrashId)) {
            mvc.perform(get("/todos/{id}/" + action, id).with(user("alice")))
                    .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "edit", "delete", "restore", "pin", ""})
    void deniesAllForeignFormPostsAndKeepsDatabaseUnchanged(String action) throws Exception {
        for (Long id : List.of(foreignId, foreignTrashId)) {
            String path = "/todos/" + id + (action.isEmpty() ? "" : "/" + action);
            mvc.perform(post(path).with(user("alice")).with(csrf())
                    .param("title", "hacked").param("category", "資格").param("priority", "1")
                    .param("userId", aliceId.toString())).andExpect(status().isForbidden());
        }
        assertEquals("bob-secret", jdbc.queryForObject("SELECT title FROM todos WHERE id = ?", String.class, foreignId));
        assertEquals(bobId, jdbc.queryForObject("SELECT user_id FROM todos WHERE id = ?", Long.class, foreignId));
        assertNull(jdbc.queryForObject("SELECT deleted_at FROM todos WHERE id = ?", Object.class, foreignId));
        assertFalse(jdbc.queryForObject("SELECT pinned FROM todos WHERE id = ?", Boolean.class, foreignId));
        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM todos WHERE id = ?", Object.class, foreignTrashId));
    }

    @Test
    void deniesForeignApiReadsWritesDeletesAndMixedBulkDeletion() throws Exception {
        for (Long id : List.of(foreignId, foreignTrashId)) {
            mvc.perform(get("/api/todos/{id}", id).with(user("alice"))).andExpect(status().isForbidden());
            mvc.perform(put("/api/todos/{id}", id).with(user("alice")).with(csrf())
                    .contentType("application/json").content("{\"title\":\"hacked\",\"category\":\"資格\",\"priority\":1}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/todos/{id}", id).with(user("alice")).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/todos/bulk-delete").with(user("alice")).with(csrf())
                .param("ids", ownId.toString(), foreignId.toString())).andExpect(status().isForbidden());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE deleted_at IS NULL", Integer.class));
        mvc.perform(get("/api/todos/9223372036854775807").with(user("alice"))).andExpect(status().isNotFound());
    }

    @Test
    void assignsOwnerFromLoginAndIgnoresForgedOwnerOnCreateAndUpdate() throws Exception {
        mvc.perform(post("/todos").with(user("alice")).with(csrf()).param("title", "form-created")
                .param("category", "資格").param("priority", "2").param("userId", bobId.toString()))
                .andExpect(redirectedUrl("/todos"));
        assertEquals(aliceId, jdbc.queryForObject("SELECT user_id FROM todos WHERE title = 'form-created'", Long.class));
        mvc.perform(post("/api/todos").with(user("alice")).with(csrf()).contentType("application/json")
                .content("{\"title\":\"api-created\",\"category\":\"資格\",\"priority\":2,\"userId\":" + bobId + "}"))
                .andExpect(status().isCreated());
        assertEquals(aliceId, jdbc.queryForObject("SELECT user_id FROM todos WHERE title = 'api-created'", Long.class));
        mvc.perform(post("/todos/{id}", ownId).with(user("alice")).with(csrf()).param("title", "edited")
                .param("category", "資格").param("priority", "2").param("userId", bobId.toString()))
                .andExpect(redirectedUrl("/todos"));
        assertEquals(aliceId, jdbc.queryForObject("SELECT user_id FROM todos WHERE id = ?", Long.class, ownId));
        mvc.perform(post("/todos/{id}/pin", ownId).with(user("alice")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/todos/{id}/restore", ownTrashId).with(user("alice")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/todos/bulk-delete").with(user("alice")).with(csrf())
                .param("ids", ownId.toString(), ownTrashId.toString())).andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE user_id = ? AND deleted_at IS NOT NULL", Integer.class, aliceId));
    }

    @Test
    void mcpAndDirectServiceCallsEnforceTheSameOwnershipAndFailWithoutAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", "", List.of()));
        assertEquals(List.of(ownId), tools.listTodos(null, null, null, null).stream().map(todo -> todo.getId()).toList());
        assertThrows(AccessDeniedException.class, () -> tools.getTodo(foreignId));
        assertThrows(AccessDeniedException.class, () -> tools.completeTodo(foreignId));
        assertThrows(AccessDeniedException.class, () -> tools.deleteTodo(foreignId));
        assertEquals(1, tools.summarizeWeek(null, null).totalCount());
        Long createdId = tools.addTodo("mcp-created", "資格", 2, null, null).getId();
        assertEquals(aliceId, jdbc.queryForObject("SELECT user_id FROM todos WHERE id = ?", Long.class, createdId));
        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class, () -> service.search(null, null, "asc"));
        assertThrows(AccessDeniedException.class, () -> tools.getTodo(ownId));
    }

    private Long insert(Long userId, String title, boolean deleted) {
        jdbc.update("INSERT INTO todos (user_id, title, category, priority, due_date, deleted_at) "
                + "VALUES (?, ?, '資格', 2, '2026-10-28', " + (deleted ? "CURRENT_TIMESTAMP" : "NULL") + ")", userId, title);
        return jdbc.queryForObject("SELECT id FROM todos WHERE title = ?", Long.class, title);
    }
}
