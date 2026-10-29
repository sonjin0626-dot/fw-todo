package com.example.todoapp;

import com.example.todoapp.api.TodoApiController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@WebMvcTest({HomeController.class, TodoApiController.class})
@Import(AuthenticatedMvcTestConfig.class)
@WithMockUser(roles = "USER")
class QuickTodoTests {
    @Autowired MockMvc mvc;
    @MockitoBean TodoService service;

    @Test
    void createsTitleOnlyWithServerDefaults() throws Exception {
        Todo saved = new Todo();
        saved.setId(42L);
        saved.setTitle("<script>alert(1)</script>");
        doAnswer(invocation -> {
            Todo input = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(saved.getTitle(), input.getTitle());
            org.junit.jupiter.api.Assertions.assertEquals("プログラミング", input.getCategory());
            org.junit.jupiter.api.Assertions.assertEquals(2, input.getPriority());
            org.junit.jupiter.api.Assertions.assertEquals(false, input.getCompleted());
            input.setId(42L);
            return null;
        }).when(service).create(any());
        when(service.findById(42L)).thenReturn(saved);
        mvc.perform(post("/api/todo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"  <script>alert(1)</script>  \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/todos/42"))
                .andExpect(jsonPath("$.title").value(saved.getTitle()));
    }

    @Test
    void rejectsMissingBlankAndOversizedTitles() throws Exception {
        for (String body : java.util.List.of("{}", "{\"title\":\"   \"}",
                "{\"title\":\"" + "a".repeat(256) + "\"}")) {
            mvc.perform(post("/api/todo").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("title"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidCsrf() throws Exception {
        mvc.perform(post("/api/todo").with(csrf().useInvalidToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"task\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void rendersQuickInputAndReusableRowEvenForEmptyList() throws Exception {
        mvc.perform(get("/todos"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"quick-todo-form\"")))
                .andExpect(content().string(containsString("id=\"quick-todo-row\"")))
                .andExpect(content().string(containsString("data-quick-title")))
                .andExpect(content().string(containsString("id=\"todo-list\"")));
        mvc.perform(get("/todos").param("trash", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"quick-todo-form\""))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rendersExistingRowsAndAdminManualControlsWithEscapedTitles() throws Exception {
        Todo todo = new Todo();
        todo.setId(7L);
        todo.setUserId(1L);
        todo.setTitle("<script>alert(1)</script>");
        todo.setCategory("プログラミング");
        todo.setPriority(2);
        when(service.currentUserId()).thenReturn(1L);
        when(service.count("", "", false)).thenReturn(1L);
        when(service.searchPage("", "", "manual", false, 1)).thenReturn(java.util.List.of(todo));
        mvc.perform(get("/todos").param("order", "manual"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-id=\"7\"")))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(containsString("class=\"drag-handle\"")))
                .andExpect(content().string(containsString("id=\"bulk-select-all\"")))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
    }
}
