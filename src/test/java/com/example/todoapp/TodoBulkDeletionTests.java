package com.example.todoapp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HomeController.class)
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
@Import({TodoService.class, AuthenticatedMvcTestConfig.class})
class TodoBulkDeletionTests {
    @Autowired MockMvc mvc;
    @Autowired TodoService service;
    @MockitoBean TodoMapper mapper;

    @Test
    void rendersSeparateBulkFormAndRowCheckboxesWithoutNestingPinForms() throws Exception {
        Todo first = todo(1L);
        Todo second = todo(2147483648L);
        when(mapper.count(null, "", "", false)).thenReturn(2L);
        when(mapper.searchPage(null, "", "", "asc", false, 10, 0L)).thenReturn(List.of(first, second));
        String html = mvc.perform(get("/todos")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("action=\"/todos/bulk-delete\" method=\"post\""));
        assertTrue(html.contains("id=\"bulk-select-all\""));
        assertTrue(Pattern.compile("<button[^>]*id=\"bulk-delete-button\"[^>]*disabled").matcher(html).find());
        Matcher boxes = Pattern.compile("<input[^>]*name=\"ids\"[^>]*>").matcher(html);
        for (Long id : List.of(first.getId(), second.getId())) {
            assertTrue(boxes.find());
            assertTrue(boxes.group().contains("value=\"" + id + "\""));
            assertTrue(boxes.group().contains("form=\"bulk-delete-form\""));
        }
        assertFalse(boxes.find());
        Matcher forms = Pattern.compile("<form\\b[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(html);
        while (forms.find()) assertFalse(forms.group(1).contains("<form"));
        assertTrue(html.contains("/todos/1/pin"));
    }

    @Test
    void hidesBulkControlsWhenEmptyOrViewingTrash() throws Exception {
        String empty = mvc.perform(get("/todos")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(empty.contains("id=\"bulk-delete-form\""));
        assertFalse(empty.contains("id=\"bulk-select-all\""));
        when(mapper.countTrash(null, "", "")).thenReturn(1L);
        when(mapper.searchTrashPage(null, "", "", "asc", 10, 0L)).thenReturn(List.of(todo(1L)));
        String trash = mvc.perform(get("/todos").param("trash", "1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(trash.contains("id=\"bulk-delete-form\""));
        assertFalse(trash.contains("name=\"ids\""));
        assertTrue(trash.contains("/todos/1/restore"));
    }

    @Test
    void deletesOnlySubmittedIdsOnceAndPreservesFiltersAndPage() throws Exception {
        when(mapper.bulkDeleteByIds(null, List.of(7L, 2147483648L))).thenReturn(2);
        String redirect = mvc.perform(post("/todos/bulk-delete").param("ids", "7", "2147483648", "7")
                        .param("keyword", "Java & 勉強").param("category", "資格").param("order", "desc")
                        .param("includeCompleted", "true").param("page", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "2件を削除しました"))
                .andReturn().getResponse().getRedirectedUrl();
        var uri = UriComponentsBuilder.fromUriString(HtmlUtils.htmlUnescape(redirect)).build();
        assertEquals("/todos", uri.getPath());
        assertEquals("desc", uri.getQueryParams().getFirst("order"));
        assertEquals("true", uri.getQueryParams().getFirst("includeCompleted"));
        assertEquals("2", uri.getQueryParams().getFirst("page"));
        assertTrue(java.net.URLDecoder.decode(redirect, StandardCharsets.UTF_8).contains("keyword=Java & 勉強"));
        verify(mapper, times(2)).findIncludingDeletedById(7L);
        verify(mapper, times(2)).findIncludingDeletedById(2147483648L);
        verify(mapper).bulkDeleteByIds(null, List.of(7L, 2147483648L));
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void missingSelectionDoesNotWriteAnything() throws Exception {
        mvc.perform(post("/todos/bulk-delete"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "削除する項目を選んでください"));
        assertEquals(0, service.bulkDelete(List.of()));
        assertEquals(0, service.bulkDelete(null));
        assertThrows(IllegalArgumentException.class, () -> service.bulkDelete(Arrays.asList(1L, null)));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidIdsBeforeAnyWrite(String invalid) throws Exception {
        mvc.perform(post("/todos/bulk-delete").param("ids", "7", invalid))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(mapper);
    }

    @Test
    void bulkSqlUsesBoundIdsAndCannotDeleteAllWithAnEmptyList() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TodoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement("com.example.todoapp.TodoMapper.bulkDeleteByIds");
        var selected = statement.getBoundSql(Map.of("ids", List.of(7L, 8L)));
        assertEquals("UPDATE todos SET deleted_at = CURRENT_TIMESTAMP, deleted_with_parent = false WHERE deleted_at IS NULL AND id IN ( ? , ? )",
                selected.getSql().replaceAll("\\s+", " ").strip());
        assertEquals(2, selected.getParameterMappings().size());
        for (Map<String, Object> parameters : List.<Map<String, Object>>of(Map.of("ids", List.of()), Map.of())) {
            var empty = statement.getBoundSql(parameters);
            assertEquals("UPDATE todos SET deleted_at = CURRENT_TIMESTAMP, deleted_with_parent = false WHERE deleted_at IS NULL AND 1 = 0",
                    empty.getSql().replaceAll("\\s+", " ").strip());
            assertEquals(0, empty.getParameterMappings().size());
        }
    }

    private Todo todo(Long id) {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setId(id);
        todo.setTitle("todo-" + id);
        todo.setPriority(2);
        todo.setCompleted(false);
        return todo;
    }
}
