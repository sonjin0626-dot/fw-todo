package com.example.todoapp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HomeController.class)
@WithMockUser(roles = "USER")
@Import({TodoService.class, AuthenticatedMvcTestConfig.class})
class TodoBulkStatusTests {
    @Autowired MockMvc mvc;
    @MockitoBean TodoMapper mapper;

    @Test
    void updatesDistinctOwnedIdsAndPreservesFilters() throws Exception {
        when(mapper.lockById(7L)).thenReturn(task(7L, 1L, false));
        when(mapper.lockById(2147483648L)).thenReturn(task(2147483648L, 1L, false));
        when(mapper.bulkUpdateStatus(1L, List.of(7L, 2147483648L), true)).thenReturn(2);
        mvc.perform(post("/todos/bulk-status").param("selectedIds", "7", "2147483648", "7")
                .param("newStatus", "true").param("order", "manual").param("page", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "2件のタスクの状態を変更しました（指定: 完了）"))
                .andExpect(redirectedUrl("/todos?keyword=&category=&order=manual&includeCompleted=false&page=2"));
        verify(mapper).bulkUpdateStatus(1L, List.of(7L, 2147483648L), true);
    }

    @Test
    void rejectsForeignIdBeforeAnyUpdates() throws Exception {
        when(mapper.lockById(1L)).thenReturn(task(1L, 1L, false));
        when(mapper.lockById(2L)).thenReturn(task(2L, 2L, false));
        mvc.perform(post("/todos/bulk-status").param("selectedIds", "1", "2").param("newStatus", "true"))
                .andExpect(status().isForbidden());
        verify(mapper, never()).bulkUpdateStatus(any(), anyList(), anyBoolean());
    }

    @Test
    void missingSelectionShowsErrorWithoutSql() throws Exception {
        mvc.perform(post("/todo/bulk-status").param("newStatus", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "タスクが選択されていません"));
        verifyNoInteractions(mapper);
    }

    @Test
    void skipsMissingDeletedAndAlreadyMatchingTasks() throws Exception {
        when(mapper.lockById(1L)).thenReturn(task(1L, 1L, true));
        mvc.perform(post("/todos/bulk-status").param("selectedIds", "1", "2").param("newStatus", "true"))
                .andExpect(status().is3xxRedirection());
        verify(mapper, never()).bulkUpdateStatus(any(), anyList(), anyBoolean());
    }

    @Test
    void rendersStatusSelectionForNormalUserWithoutNestedForms() throws Exception {
        when(mapper.count(1L, "", "", false)).thenReturn(1L);
        when(mapper.searchPage(1L, "", "", "asc", false, 10, 0L)).thenReturn(List.of(task(1L, 1L, false)));
        String html = mvc.perform(get("/todos")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("id=\"bulk-status-form\""));
        assertTrue(html.contains("name=\"selectedIds\""));
        assertTrue(html.contains("form=\"bulk-status-form\""));
        var forms = java.util.regex.Pattern.compile("<form\\b[^>]*>(.*?)</form>", java.util.regex.Pattern.DOTALL).matcher(html);
        while (forms.find()) assertFalse(forms.group(1).contains("<form"));
    }

    private Todo task(Long id, Long userId, boolean completed) {
        Todo todo = new Todo();
        todo.setId(id);
        todo.setUserId(userId);
        todo.setTitle("task");
        todo.setCompleted(completed);
        return todo;
    }

    @Test
    void reopensCompletedTasks() throws Exception {
        when(mapper.lockById(9L)).thenReturn(task(9L, 1L, true));
        when(mapper.bulkUpdateStatus(1L, List.of(9L), false)).thenReturn(1);
        mvc.perform(post("/todos/bulk-status").param("selectedIds", "9").param("newStatus", "false"))
                .andExpect(status().is3xxRedirection());
        verify(mapper).bulkUpdateStatus(1L, List.of(9L), false);
    }

    @Test
    void parentWithIncompleteChildrenKeepsDerivedStatus() throws Exception {
        Todo parent = task(1L, 1L, false);
        when(mapper.lockById(1L)).thenReturn(parent);
        when(mapper.findSubtasks(1L)).thenReturn(List.of(task(2L, 1L, false)));
        when(mapper.findById(1L)).thenReturn(parent);
        mvc.perform(post("/todos/bulk-status").param("selectedIds", "1").param("newStatus", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "0件のタスクの状態を変更しました（指定: 完了）"));
        verify(mapper, never()).bulkUpdateStatus(any(), anyList(), anyBoolean());
        verify(mapper, never()).update(any());
    }

    @Test
    void dynamicSqlAlwaysScopesOwnerAndGuardsEmptyIds() throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration,
                    "mapper/TodoMapper.xml", configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement("com.example.todoapp.TodoMapper.bulkUpdateStatus");
        String sql = statement.getBoundSql(java.util.Map.of("userId", 1L, "ids", List.of(1L, 2L),
                "completed", false)).getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("user_id = ? AND deleted_at IS NULL"));
        assertTrue(sql.contains("id IN"));
        assertTrue(sql.contains("completed_at = CASE"));
        assertTrue(sql.contains("reminder_notified_date = CASE"));
        String empty = statement.getBoundSql(java.util.Map.of("userId", 1L, "ids", List.of(),
                "completed", true)).getSql();
        assertTrue(empty.contains("AND 1 = 0"));
    }
}
