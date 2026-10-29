package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:templates;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateIntegrationTests {
    @Autowired TemplateService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoSpyBean TodoMapper todoMapper;
    @MockitoSpyBean TodoTemplateItemMapper itemMapper;

    @BeforeEach void setup() {
        jdbc.update("DELETE FROM todo_history");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM todo_template");
        jdbc.update("INSERT INTO users (username,password,role) SELECT 'template-other','unused','USER' WHERE NOT EXISTS (SELECT 1 FROM users WHERE username='template-other')");
        authenticate("sonjin");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            username, "unused", AuthorityUtils.createAuthorityList("ROLE_USER")));
    }
    TemplateForm form() {
        TemplateForm form = new TemplateForm();
        form.setName("週次レビュー"); form.setDescription("毎週の振り返り");
        form.setItemTitles(List.of(" 進捗確認 ", "来週の計画"));
        return form;
    }
    Long create() { service.save(null, form()); return service.list().getFirst().getId(); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test void createsEditsAndDeletesOrderedItemsWithoutDeletingAppliedTasks() {
        Long id = create();
        assertEquals(List.of("進捗確認", "来週の計画"), service.find(id).getItems().stream().map(TodoTemplateItem::getTitle).toList());
        TemplateForm changed = form(); changed.setItemTitles(List.of("変更後"));
        service.save(id, changed);
        assertEquals(1, service.find(id).getItems().size());
        assertEquals(1, service.applyTemplate(id, "資格", 2, null));
        service.delete(id);
        assertEquals(0, count("todo_template_item"));
        assertEquals(1, count("todos"));
    }
    @Test void appliesOptionsOwnershipAndHistory() {
        Long id = create();
        assertEquals(2, service.applyTemplate(id, "プログラミング", 1, LocalDate.of(2026, 12, 1)));
        var todos = jdbc.queryForList("SELECT * FROM todos ORDER BY id");
        assertEquals("進捗確認", todos.getFirst().get("TITLE"));
        assertEquals("プログラミング", todos.getFirst().get("CATEGORY"));
        assertEquals(1, todos.getFirst().get("PRIORITY"));
        assertEquals(java.sql.Date.valueOf("2026-12-01"), todos.getFirst().get("DUE_DATE"));
        assertEquals(false, todos.getFirst().get("COMPLETED"));
        assertEquals(service.find(id).getUserId(), todos.getFirst().get("USER_ID"));
        assertEquals(2, count("todo_history"));
    }
    @Test void rejectsOtherUsersForEveryOperation() {
        Long id = create();
        authenticate("template-other");
        assertTrue(service.list().isEmpty());
        assertThrows(AccessDeniedException.class, () -> service.find(id));
        assertThrows(AccessDeniedException.class, () -> service.save(id, form()));
        assertThrows(AccessDeniedException.class, () -> service.delete(id));
        assertThrows(AccessDeniedException.class, () -> service.applyTemplate(id, "資格", 2, null));
        assertEquals(0, count("todos"));
    }
    @Test void rollsBackAllTasksAndHistoryWhenSecondInsertFails() {
        Long id = create();
        doThrow(new IllegalStateException("simulated insert failure")).when(todoMapper)
            .insert(argThat(t -> t != null && t.getTitle().equals("来週の計画")));
        assertThrows(RuntimeException.class, () -> service.applyTemplate(id, "資格", 2, null));
        assertEquals(0, count("todos"));
        assertEquals(0, count("todo_history"));
        verify(todoMapper).insert(argThat(t -> t != null && t.getTitle().equals("進捗確認")));
        assertEquals(2, service.find(id).getItems().size());
    }
    @Test void rollsBackTemplateAndItemsOnItemFailure() {
        doThrow(new IllegalStateException("item failure")).when(itemMapper).insert(any(TodoTemplateItem.class));
        assertThrows(IllegalStateException.class, () -> service.save(null, form()));
        assertEquals(0, count("todo_template"));
        assertEquals(0, count("todo_template_item"));
    }
    @Test void validatesInputAndKeepsValuesForCorrection() throws Exception {
        mvc.perform(post("/templates").with(user("sonjin")).with(csrf())
            .param("name", "入力済み").param("description", "説明").param("itemTitles", " "))
            .andExpect(status().isOk()).andExpect(view().name("template/form"))
            .andExpect(model().attributeHasErrors("templateForm")).andExpect(content().string(containsString("入力済み")));
        assertEquals(0, count("todo_template"));
        TemplateForm invalid = form(); invalid.setItemTitles(List.of());
        assertThrows(com.example.todoapp.exception.BusinessException.class, () -> service.save(null, invalid));
        invalid.setItemTitles(List.of("x".repeat(201)));
        assertThrows(com.example.todoapp.exception.BusinessException.class, () -> service.save(null, invalid));
    }
    @Test void rendersFormsAndProtectsRoutes() throws Exception {
        Long id = create();
        mvc.perform(get("/templates").with(user("sonjin"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("週次レビュー"))).andExpect(content().string(containsString("_csrf")));
        mvc.perform(get("/templates/new").with(user("sonjin"))).andExpect(status().isOk());
        mvc.perform(get("/templates/" + id + "/edit").with(user("sonjin"))).andExpect(status().isOk());
        mvc.perform(get("/templates").with(user("sonjin")).param("lang", "en")).andExpect(status().isOk())
            .andExpect(content().string(containsString("Templates")));
        mvc.perform(post("/templates/" + id + "/delete").with(user("sonjin"))).andExpect(status().isForbidden());
        mvc.perform(post("/templates/" + id + "/apply").with(user("template-other")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/templates/999999/edit").with(user("sonjin"))).andExpect(status().isNotFound());
        mvc.perform(get("/templates").with(anonymous())).andExpect(status().is3xxRedirection());
    }
    @Test void postsCreateEditApplyAndDelete() throws Exception {
        mvc.perform(post("/templates").with(user("sonjin")).with(csrf())
            .param("name", "Web template").param("itemTitles", "one", "two").param("userId", "999999"))
            .andExpect(redirectedUrl("/templates"));
        authenticate("sonjin");
        Long id = service.list().getFirst().getId();
        mvc.perform(post("/templates/" + id).with(user("sonjin")).with(csrf())
            .param("name", "Edited").param("itemTitles", "three")).andExpect(redirectedUrl("/templates"));
        mvc.perform(post("/templates/" + id + "/apply").with(user("sonjin")).with(csrf()))
            .andExpect(redirectedUrl("/todos")).andExpect(flash().attribute("message", "1件のタスクを作成しました"));
        mvc.perform(post("/templates/" + id + "/delete").with(user("sonjin")).with(csrf()))
            .andExpect(redirectedUrl("/templates"));
        assertEquals(1, count("todos"));
    }
}
