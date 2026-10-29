package com.example.todoapp;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tag-autocomplete-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TagAutocompleteIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TagMapper mapper;

    @Test
    void searchesPartialNamesAndLimitsResults() throws Exception {
        for (int i = 0; i < 12; i++) jdbc.update("INSERT INTO tags(name) VALUES (?)", "autocomplete-" + i);
        mvc.perform(get("/api/tags/search").with(user("sonjin")).param("q", " autocomplete- "))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(10)))
                .andExpect(jsonPath("$[0].name", is("autocomplete-0")));
        mvc.perform(get("/api/tags/search").with(user("sonjin")))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/tags/search")).andExpect(status().is3xxRedirection());
    }

    @Test
    void preservesTagsThroughConfirmationAndRewriteThenSavesAndClears() throws Exception {
        Long tagId = mapper.searchByName("Java").getFirst().id();
        for (String path : List.of("/todos/confirm", "/todos/new")) {
            mvc.perform(post(path).with(user("sonjin")).with(csrf())
                    .param("title", "tagged-test").param("category", "資格").param("priority", "2")
                    .param("tagIds", tagId.toString()))
                    .andExpect(status().isOk()).andExpect(content().string(containsString("Java")))
                    .andExpect(content().string(containsString("name=\"tagIds\"")));
        }
        mvc.perform(post("/todos").with(user("sonjin")).with(csrf())
                .param("title", "tagged-test").param("category", "資格").param("priority", "2")
                .param("tagIds", tagId.toString(), tagId.toString()))
                .andExpect(status().is3xxRedirection());
        Long todoId = jdbc.queryForObject("SELECT id FROM todos WHERE title = 'tagged-test'", Long.class);
        assertEquals(List.of(tagId), mapper.findTagIds(todoId));
        mvc.perform(get("/todos/{id}/edit", todoId).with(user("sonjin")))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Java")));
        mvc.perform(post("/todos/{id}", todoId).with(user("sonjin")).with(csrf())
                .param("title", "tagged-test").param("category", "資格").param("priority", "2")
                .param("_tagIds", "on"))
                .andExpect(status().is3xxRedirection());
        assertTrue(mapper.findTagIds(todoId).isEmpty());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void invalidTagRollsBackTodoCreation() throws Exception {
        mvc.perform(post("/todos").with(user("sonjin")).with(csrf())
                .param("title", "invalid-tag-test").param("category", "資格").param("priority", "2")
                .param("tagIds", "9223372036854775807"))
                .andExpect(view().name("error/business"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE title = 'invalid-tag-test'", Integer.class));
    }
}
