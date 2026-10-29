package com.example.todoapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reorder-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodoReorderIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoMapper mapper;
    Long owner;
    Long a;
    Long middle;
    Long b;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM todos");
        owner = jdbc.queryForObject("SELECT id FROM users WHERE username = 'sonjin'", Long.class);
        a = insert("first");
        middle = insert("hidden");
        b = insert("last");
    }

    Long insert(String title) {
        Todo todo = new Todo();
        todo.setUserId(owner);
        todo.setTitle(title);
        todo.setCategory("資格");
        todo.setPriority(2);
        mapper.insert(todo);
        return todo.getId();
    }

    String payload(Long first, Long second) {
        return "[{\"id\":" + first + ",\"displayOrder\":1},{\"id\":" + second + ",\"displayOrder\":2}]";
    }

    @Test
    void persistsSubsetOrderAndAppendsNewTasks() throws Exception {
        mvc.perform(put("/api/todo/reorder").with(user("sonjin")).with(csrf())
                .contentType("application/json").content(payload(b, a))).andExpect(status().isOk());
        assertEquals(java.util.List.of(b, middle, a), mapper.searchForExport(owner, "", "", "manual", true)
                .stream().map(Todo::getId).toList());
        Long last = insert("new");
        assertEquals(last, mapper.searchForExport(owner, "", "", "manual", true).getLast().getId());
        mvc.perform(get("/todos").with(user("sonjin")).param("order", "manual"))
                .andExpect(status().isOk()).andExpect(model().attribute("order", "manual"));
    }

    @Test
    void rejectsSubtasksAndInvalidPositionsWithoutChangingOrder() throws Exception {
        jdbc.update("UPDATE todos SET parent_id=? WHERE id=?", a, b);
        mvc.perform(put("/api/todo/reorder").with(user("sonjin")).with(csrf())
                .contentType("application/json").content(payload(middle, b))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/todo/reorder").with(user("sonjin")).with(csrf())
                .contentType("application/json").content("[{\"id\":" + a + ",\"displayOrder\":0}]"))
                .andExpect(status().isBadRequest());
        assertEquals(1, mapper.findById(a).getDisplayOrder());
        assertEquals(2, mapper.findById(middle).getDisplayOrder());
    }

    @Test
    void rejectsDuplicatesForeignOwnersDeletedRowsAndMissingCsrf() throws Exception {
        mvc.perform(put("/api/todo/reorder").with(user("sonjin")).with(csrf())
                .contentType("application/json").content(payload(a, a))).andExpect(status().isBadRequest());
        jdbc.update("INSERT INTO users(username,password,role) VALUES ('other-reorder','unused','USER')");
        Long other = jdbc.queryForObject("SELECT id FROM users WHERE username='other-reorder'", Long.class);
        jdbc.update("UPDATE todos SET user_id=? WHERE id=?", other, b);
        mvc.perform(put("/api/todo/reorder").with(user("sonjin")).with(csrf())
                .contentType("application/json").content(payload(middle, b))).andExpect(status().isForbidden());
        assertEquals(1, mapper.findById(a).getDisplayOrder());
        jdbc.update("UPDATE todos SET deleted_at=CURRENT_TIMESTAMP WHERE id=?", middle);
        mvc.perform(put("/api/todo/reorder").with(user("sonjin")).with(csrf())
                .contentType("application/json").content(payload(a, middle))).andExpect(status().isNotFound());
        mvc.perform(put("/api/todo/reorder").with(user("sonjin"))
                .contentType("application/json").content(payload(a, b))).andExpect(status().isForbidden());
        jdbc.update("DELETE FROM todos WHERE user_id=?", other);
        jdbc.update("DELETE FROM users WHERE id=?", other);
    }
}
