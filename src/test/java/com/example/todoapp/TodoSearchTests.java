package com.example.todoapp;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.todoapp.api.TodoApiController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest({ HomeController.class, TodoApiController.class })
@org.springframework.security.test.context.support.WithMockUser
@Import({TodoService.class, AuthenticatedMvcTestConfig.class})
class TodoSearchTests {

    @MockitoBean
    private TodoMapper todoMapper;

    @Autowired
    private TodoService todoService;

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @CsvSource(value = {
            "NULL, NULL", "2026-04-01, NULL", "NULL, 2026-04-30", "2026-04-01, 2026-04-30"
    }, nullValues = "NULL")
    void passesOptionalDatesThroughApiAndService(String from, String to) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/todos")
                .param("keyword", "勉強").param("category", "資格").param("order", "desc");
        if (from != null) {
            request.param("from", from);
        }
        if (to != null) {
            request.param("to", to);
        }

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(todoMapper).search(1L, "勉強", "資格", "desc", date(from), date(to), true);
    }

    @Test
    void keepsApiDefaults() throws Exception {
        mockMvc.perform(get("/api/todos"))
                .andExpect(status().isOk());
        verify(todoMapper).search(1L, "", "", "asc", null, null, true);
    }

    @Test
    void keepsExistingScreenSearchWithoutDateConditions() {
        todoService.search("勉強", "資格", "asc");
        verify(todoMapper).search(1L, "勉強", "資格", "asc", null, null, true);
    }

    @Test
    void defaultsListToIncompleteTodos() throws Exception {
        mockMvc.perform(get("/todos"))
                .andExpect(status().isOk())
                .andExpect(view().name("todos"))
                .andExpect(model().attribute("includeCompleted", false))
                .andExpect(model().attribute("page", 1))
                .andExpect(model().attribute("totalPages", 1));

        verify(todoMapper).count(1L, "", "", false);
        verify(todoMapper).searchPage(1L, "", "", "asc", false, 10, 0L);
        verifyNoMoreInteractions(todoMapper);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL, asc, false", "false, asc, false", "true, asc, true",
            "NULL, desc, false", "false, desc, false", "true, desc, true"
    }, nullValues = "NULL")
    void combinesCompletionWithFiltersAndRestoresCheckboxAndSortLinks(
            String parameter, String order, boolean includeCompleted) throws Exception {
        Todo incomplete = new Todo();
        incomplete.setUserId(1L);
        incomplete.setTitle("未完了の勉強");
        incomplete.setPriority(1);
        incomplete.setCompleted(false);
        Todo completed = new Todo();
        completed.setUserId(1L);
        completed.setTitle("完了済みの勉強");
        completed.setPriority(2);
        completed.setCompleted(true);
        List<Todo> todos = includeCompleted ? List.of(incomplete, completed) : List.of(incomplete);
        when(todoMapper.count(1L, "勉強", "資格", includeCompleted)).thenReturn((long) todos.size());
        when(todoMapper.searchPage(1L, "勉強", "資格", order, includeCompleted, 10, 0L)).thenReturn(todos);

        MockHttpServletRequestBuilder request = get("/todos")
                .param("keyword", "勉強").param("category", "資格").param("order", order);
        if (parameter != null) {
            request.param("includeCompleted", parameter);
        }

        String html = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(model().attribute("keyword", "勉強"))
                .andExpect(model().attribute("category", "資格"))
                .andExpect(model().attribute("order", order))
                .andExpect(model().attribute("includeCompleted", includeCompleted))
                .andExpect(model().attribute("todos", todos))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        verify(todoMapper).count(1L, "勉強", "資格", includeCompleted);
        verify(todoMapper).searchPage(1L, "勉強", "資格", order, includeCompleted, 10, 0L);
        assertTrue(html.contains("未完了の勉強"));
        assertEquals(includeCompleted, html.contains("完了済みの勉強"));
        Matcher checkbox = Pattern.compile("<input\\b[^>]*\\bid=\"includeCompleted\"[^>]*>").matcher(html);
        assertTrue(checkbox.find());
        assertTrue(checkbox.group().contains("name=\"includeCompleted\""));
        assertTrue(checkbox.group().contains("value=\"true\""));
        assertEquals(includeCompleted, Pattern.compile("\\bchecked(?:\\s|=|>)")
                .matcher(checkbox.group()).find());
        for (String sort : List.of("asc", "desc")) {
            String label = "asc".equals(sort) ? "期限が近い順" : "期限が遠い順";
            Matcher link = Pattern.compile("href=\"([^\"]+)\">" + label + "</a>").matcher(html);
            assertTrue(link.find());
            String url = URLDecoder.decode(HtmlUtils.htmlUnescape(link.group(1)), StandardCharsets.UTF_8);
            assertEquals("/todos?keyword=勉強&category=資格&order=" + sort
                    + "&includeCompleted=" + includeCompleted + "&page=1&trash=0", url);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void rendersTenRowsThenFiveWithPageLinksKeepingAllFilters(int page) throws Exception {
        List<Todo> todos = IntStream.rangeClosed(page == 1 ? 1 : 11, page == 1 ? 10 : 15)
                .mapToObj(number -> {
                    Todo todo = new Todo();
        todo.setUserId(1L);
                    todo.setId((long) number);
                    todo.setTitle("todo-" + number);
                    todo.setPriority(2);
                    todo.setCompleted(false);
                    return todo;
                }).toList();
        when(todoMapper.count(1L, "todo-", "資格", true)).thenReturn(15L);
        when(todoMapper.searchPage(1L, "todo-", "資格", "desc", true, 10, page == 1 ? 0L : 10L))
                .thenReturn(todos);

        String html = mockMvc.perform(get("/todos").param("keyword", "todo-").param("category", "資格")
                .param("order", "desc").param("includeCompleted", "true").param("page", String.valueOf(page)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("page", page))
                .andExpect(model().attribute("totalPages", 2))
                .andExpect(model().attribute("totalCount", 15L))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        verify(todoMapper).count(1L, "todo-", "資格", true);
        verify(todoMapper).searchPage(1L, "todo-", "資格", "desc", true, 10, page == 1 ? 0L : 10L);
        verifyNoMoreInteractions(todoMapper);
        Matcher body = Pattern.compile("<tbody>(.*?)</tbody>", Pattern.DOTALL).matcher(html);
        assertTrue(body.find());
        assertEquals(page == 1 ? 10L : 5L, Pattern.compile("<tr\\b").matcher(body.group(1)).results().count());
        assertTrue(html.contains("aria-current=\"page\">" + page + "</strong>"));
        Matcher navigation = Pattern.compile("<nav[^>]*aria-label=\"一覧のページ\"[^>]*>(.*?)</nav>", Pattern.DOTALL)
                .matcher(html);
        assertTrue(navigation.find());
        Matcher link = Pattern.compile("href=\"([^\"]+)\"").matcher(navigation.group(1));
        assertTrue(link.find());
        assertEquals("/todos?keyword=todo-&category=資格&order=desc&includeCompleted=true&page=" + (page == 1 ? 2 : 1) + "&trash=0",
                URLDecoder.decode(HtmlUtils.htmlUnescape(link.group(1)), StandardCharsets.UTF_8));
        assertTrue(html.contains("name=\"page\" value=\"1\""));
    }

    @ParameterizedTest
    @CsvSource({ "0, 1", "-2, 1", "99, 2" })
    void clampsPageToExistingRange(int requestedPage, int expectedPage) throws Exception {
        when(todoMapper.count(1L, "", "", false)).thenReturn(15L);
        mockMvc.perform(get("/todos").param("page", String.valueOf(requestedPage)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("page", expectedPage))
                .andExpect(model().attribute("totalPages", 2));
        verify(todoMapper).searchPage(1L, "", "", "asc", false, 10, expectedPage == 1 ? 0L : 10L);
    }

    @ParameterizedTest
    @CsvSource({ "0, 1", "10, 1", "11, 2", "20, 2", "21, 3" })
    void countsPagesFromFilteredResults(long count, int totalPages) throws Exception {
        when(todoMapper.count(1L, "", "", false)).thenReturn(count);
        String html = mockMvc.perform(get("/todos"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalPages", totalPages))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(count > 0, html.contains("aria-label=\"一覧のページ\""));
    }

    @ParameterizedTest
    @ValueSource(strings = { "from", "to" })
    void rejectsInvalidDateBeforeSearching(String parameter) throws Exception {
        mockMvc.perform(get("/api/todos").param(parameter, "2026-13-01"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(todoMapper);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL, NULL, ''",
            "2026-04-01, NULL, ' AND due_date >= ?'",
            "NULL, 2026-04-30, ' AND due_date <= ?'",
            "2026-04-01, 2026-04-30, ' AND due_date >= ? AND due_date <= ?'"
    }, nullValues = "NULL")
    void addsInclusiveSqlConditionsOnlyForProvidedDates(String from, String to, String conditions)
            throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TodoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        ParamNameResolver resolver = new ParamNameResolver(configuration,
                TodoMapper.class.getMethod("search", Long.class, String.class, String.class, String.class,
                        LocalDate.class, LocalDate.class, boolean.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) resolver.getNamedParams(
                new Object[] { 1L, "勉強", "資格", "desc", date(from), date(to), true });
        BoundSql sql = configuration.getMappedStatement("com.example.todoapp.TodoMapper.search")
                .getBoundSql(parameters);

        assertEquals("SELECT id, user_id, title, detail, category, priority, due_date, completed, completed_at, deleted_at, pinned, created_at, updated_at"
                + " FROM todos WHERE user_id = ? AND deleted_at IS NULL AND title LIKE CONCAT('%', ?, '%') AND category = ?"
                + conditions + " ORDER BY due_date DESC", sql.getSql().replaceAll("\\s+", " ").strip());
        List<String> names = sql.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList();
        assertEquals(from != null, names.contains("from"));
        assertEquals(to != null, names.contains("to"));
        assertEquals(date(from), parameters.get("from"));
        assertEquals(date(to), parameters.get("to"));

        Map<String, Object> defaults = new HashMap<>(parameters);
        defaults.put("keyword", "");
        defaults.put("category", "");
        defaults.put("order", "asc");
        BoundSql defaultSql = configuration.getMappedStatement("com.example.todoapp.TodoMapper.search")
                .getBoundSql(defaults);
        String where = " WHERE user_id = ? AND deleted_at IS NULL" + conditions;
        assertEquals("SELECT id, user_id, title, detail, category, priority, due_date, completed, completed_at, deleted_at, pinned, created_at, updated_at"
                + " FROM todos" + where + " ORDER BY due_date ASC",
                defaultSql.getSql().replaceAll("\\s+", " ").strip());
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, asc", "false, false, desc", "false, true, asc", "false, true, desc",
            "true, false, asc", "true, false, desc", "true, true, asc", "true, true, desc"
    })
    void combinesCompletionSqlWithExistingFiltersAndBothSortOrders(
            boolean includeCompleted, boolean filtered, String order) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TodoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        ParamNameResolver resolver = new ParamNameResolver(configuration,
                TodoMapper.class.getMethod("search", Long.class, String.class, String.class, String.class,
                        LocalDate.class, LocalDate.class, boolean.class));
        Object parameters = resolver.getNamedParams(new Object[] { 1L,
                filtered ? "勉強" : "", filtered ? "資格" : "", order, null, null, includeCompleted
        });
        BoundSql sql = configuration.getMappedStatement("com.example.todoapp.TodoMapper.search")
                .getBoundSql(parameters);
        String conditions = "user_id = ? AND deleted_at IS NULL" + (includeCompleted ? "" : " AND completed = false");
        if (filtered) {
            conditions += (conditions.isEmpty() ? "" : " AND ")
                    + "title LIKE CONCAT('%', ?, '%') AND category = ?";
        }
        assertEquals("SELECT id, user_id, title, detail, category, priority, due_date, completed, completed_at, deleted_at, pinned, created_at, updated_at"
                + " FROM todos" + (conditions.isEmpty() ? "" : " WHERE " + conditions)
                + " ORDER BY due_date " + order.toUpperCase(java.util.Locale.ROOT),
                sql.getSql().replaceAll("\\s+", " ").strip());
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, asc", "false, false, desc", "false, true, asc", "false, true, desc",
            "true, false, asc", "true, false, desc", "true, true, asc", "true, true, desc"
    })
    void limitsSqlToTenRowsAndCountsUsingTheSameFilters(
            boolean includeCompleted, boolean filtered, String order) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TodoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        String keyword = filtered ? "todo-" : "";
        String category = filtered ? "資格" : "";
        String conditions = "user_id = ? AND deleted_at IS NULL" + (includeCompleted ? "" : " AND completed = false");
        if (filtered) {
            conditions += (conditions.isEmpty() ? "" : " AND ")
                    + "title LIKE CONCAT('%', ?, '%') AND category = ?";
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + conditions;
        ParamNameResolver countResolver = new ParamNameResolver(configuration,
                TodoMapper.class.getMethod("count", Long.class, String.class, String.class, boolean.class));
        Object countParameters = countResolver.getNamedParams(new Object[] { 1L, keyword, category, includeCompleted });
        BoundSql countSql = configuration.getMappedStatement("com.example.todoapp.TodoMapper.count")
                .getBoundSql(countParameters);
        assertEquals("SELECT COUNT(*) FROM todos" + where,
                countSql.getSql().replaceAll("\\s+", " ").strip());

        ParamNameResolver pageResolver = new ParamNameResolver(configuration,
                TodoMapper.class.getMethod("searchPage", Long.class, String.class, String.class, String.class,
                        boolean.class, int.class, long.class));
        for (long offset : new long[] { 0L, 10L }) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) pageResolver.getNamedParams(
                    new Object[] { 1L, keyword, category, order, includeCompleted, 10, offset });
            BoundSql sql = configuration.getMappedStatement("com.example.todoapp.TodoMapper.searchPage")
                    .getBoundSql(parameters);
            assertEquals("SELECT id, user_id, title, detail, category, priority, due_date, completed, completed_at, deleted_at, pinned, created_at, updated_at"
                    + " FROM todos" + where + " ORDER BY pinned DESC, due_date " + order.toUpperCase(java.util.Locale.ROOT)
                    + ", id ASC LIMIT ? OFFSET ?",
                    sql.getSql().replaceAll("\\s+", " ").replace(" ,", ",").strip());
            List<String> mappings = sql.getParameterMappings().stream()
                    .map(mapping -> mapping.getProperty()).toList();
            assertEquals(List.of("limit", "offset"), mappings.subList(mappings.size() - 2, mappings.size()));
            assertEquals(10, parameters.get("limit"));
            assertEquals(offset, parameters.get("offset"));
        }
    }

    @Test
    void returnsCompletedAtInListAndSingleTodoApi() throws Exception {
        Todo completed = new Todo();
        completed.setUserId(1L);
        completed.setId(1L);
        completed.setCompleted(true);
        completed.setCompletedAt(LocalDateTime.of(2026, 10, 27, 12, 34, 56));
        Todo incomplete = new Todo();
        incomplete.setUserId(1L);
        incomplete.setId(2L);
        incomplete.setCompleted(false);
        when(todoMapper.search(1L, "", "", "asc", null, null, true)).thenReturn(List.of(completed, incomplete));
        when(todoMapper.findById(1L)).thenReturn(completed);

        mockMvc.perform(get("/api/todos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].completedAt").value("2026-10-27T12:34:56"))
                .andExpect(jsonPath("$[1].completedAt").hasJsonPath())
                .andExpect(jsonPath("$[1].completedAt").value(nullValue()));
        mockMvc.perform(get("/api/todos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").value("2026-10-27T12:34:56"));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false, NULL, 未完了",
            "true, NULL, 完了",
            "true, 2026-10-27T12:34:56, 完了（2026-10-27）"
    }, nullValues = "NULL")
    void showsCompletionDateOnlyWhenKnownAndCompleted(boolean completed, String timestamp, String expected)
            throws Exception {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setId(1L);
        todo.setPriority(2);
        todo.setCompleted(completed);
        todo.setCompletedAt(timestamp == null ? null : LocalDateTime.parse(timestamp));
        when(todoMapper.count(1L, "", "", true)).thenReturn(1L);
        when(todoMapper.searchPage(1L, "", "", "asc", true, 10, 0L)).thenReturn(List.of(todo));

        String html = mockMvc.perform(get("/todos").param("includeCompleted", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher statusCell = Pattern.compile("<td>\\s*<span>(未完了|完了)</span>(.*?)</td>", Pattern.DOTALL).matcher(html);
        assertTrue(statusCell.find());
        assertEquals(expected, (statusCell.group(1) + statusCell.group(2))
                .replaceAll("<[^>]+>", "").replaceAll("\\s+", ""));
    }

    @Test
    void showsTrashIncludingCompletedTodosAndKeepsFiltersWhenPaging() throws Exception {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setId(7L);
        todo.setTitle("deleted-completed");
        todo.setPriority(2);
        todo.setCompleted(true);
        todo.setDeletedAt(LocalDateTime.of(2026, 10, 27, 12, 0));
        when(todoMapper.countTrash(1L, "deleted", "資格")).thenReturn(15L);
        when(todoMapper.searchTrashPage(1L, "deleted", "資格", "desc", 10, 10L)).thenReturn(List.of(todo));

        String html = mockMvc.perform(get("/todos").param("trash", "1").param("keyword", "deleted")
                .param("category", "資格").param("order", "desc").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("trashMode", true))
                .andExpect(model().attribute("includeCompleted", true))
                .andExpect(model().attribute("totalPages", 2))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(html.contains("deleted-completed"));
        assertTrue(html.contains("action=\"/todos/7/restore\" method=\"post\""));
        assertTrue(html.contains(">戻す</button>"));
        assertTrue(!html.contains("/todos/7/edit") && !html.contains("/todos/7/delete"));
        assertTrue(!html.contains("/todos/7/pin"));
        String decoded = URLDecoder.decode(HtmlUtils.htmlUnescape(html), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("/todos?keyword=deleted&category=資格&order=desc&includeCompleted=true&page=1&trash=1"));
        verify(todoMapper).countTrash(1L, "deleted", "資格");
        verify(todoMapper).searchTrashPage(1L, "deleted", "資格", "desc", 10, 10L);
        verifyNoMoreInteractions(todoMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "2", "true" })
    void opensTrashOnlyForLiteralOne(String trash) throws Exception {
        mockMvc.perform(get("/todos").param("trash", trash))
                .andExpect(status().isOk())
                .andExpect(model().attribute("trashMode", false));
        verify(todoMapper).count(1L, "", "", false);
        verify(todoMapper).searchPage(1L, "", "", "asc", false, 10, 0L);
        verifyNoMoreInteractions(todoMapper);
    }

    @ParameterizedTest
    @CsvSource({ "1, 戻しました", "0, 見つかりませんでした" })
    void restoresViaPostAndReturnsToTrash(int updated, String message) throws Exception {
        when(todoMapper.restoreById(1L, 7L)).thenReturn(updated);
        mockMvc.perform(post("/todos/7/restore"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/todos?trash=1"))
                .andExpect(flash().attribute("message", message));
        verify(todoMapper).restoreById(1L, 7L);
        verify(todoMapper).findIncludingDeletedById(7L);
        verifyNoMoreInteractions(todoMapper);
    }

    @Test
    void ignoresTrashParameterOnApi() throws Exception {
        mockMvc.perform(get("/api/todos").param("trash", "1"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        verify(todoMapper).search(1L, "", "", "asc", null, null, true);
        verifyNoMoreInteractions(todoMapper);
    }

    @Test
    void usesExistingDeleteConfirmationAndDeleteAction() throws Exception {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setId(7L);
        todo.setTitle("delete-me");
        when(todoMapper.findById(7L)).thenReturn(todo);
        mockMvc.perform(get("/todos/7/delete"))
                .andExpect(status().isOk())
                .andExpect(view().name("delete"));
        mockMvc.perform(post("/todos/7/delete"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/todos"));
        verify(todoMapper).deleteById(1L, 7L);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void rendersPinButtonWithCurrentFiltersAndPage(boolean pinned) throws Exception {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setId(7L);
        todo.setPriority(2);
        todo.setPinned(pinned);
        when(todoMapper.count(1L, "勉強 & Java", "資格", true)).thenReturn(15L);
        when(todoMapper.searchPage(1L, "勉強 & Java", "資格", "desc", true, 10, 10L)).thenReturn(List.of(todo));

        String html = mockMvc.perform(get("/todos").param("keyword", "勉強 & Java").param("category", "資格")
                .param("order", "desc").param("includeCompleted", "true").param("page", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher pinForm = Pattern.compile("<form[^>]*action=\"/todos/7/pin\"[^>]*>(.*?)</form>", Pattern.DOTALL)
                .matcher(html);
        assertTrue(pinForm.find());
        String form = HtmlUtils.htmlUnescape(pinForm.group());
        assertTrue(form.contains("method=\"post\""));
        assertTrue(form.contains("name=\"keyword\" value=\"勉強 & Java\""));
        assertTrue(form.contains("name=\"category\" value=\"資格\""));
        assertTrue(form.contains("name=\"order\" value=\"desc\""));
        assertTrue(form.contains("name=\"includeCompleted\" value=\"true\""));
        assertTrue(form.contains("name=\"page\" value=\"2\""));
        assertTrue(form.contains("aria-pressed=\"" + pinned + "\""));
        assertTrue(form.contains(pinned ? "印を外す" : "印を付ける"));
    }

    @ParameterizedTest
    @CsvSource({ "true, 1, 印を切り替えました", "false, 1, 印を切り替えました", "true, 0, 見つかりませんでした" })
    void pinToggleReturnsToTheSameFiltersSortAndPage(boolean includeCompleted, int updated, String message)
            throws Exception {
        when(todoMapper.togglePinned(1L, 7L)).thenReturn(updated);
        String redirect = mockMvc.perform(post("/todos/7/pin").param("keyword", "勉強 & Java")
                .param("category", "資格").param("order", "desc")
                .param("includeCompleted", String.valueOf(includeCompleted)).param("page", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", message))
                .andReturn().getResponse().getRedirectedUrl();
        var uri = UriComponentsBuilder.fromUriString(redirect).build();
        assertEquals("/todos", uri.getPath());
        var parameters = uri.getQueryParams();
        assertEquals("勉強 & Java", URLDecoder.decode(parameters.getFirst("keyword"), StandardCharsets.UTF_8));
        assertEquals("資格", URLDecoder.decode(parameters.getFirst("category"), StandardCharsets.UTF_8));
        assertEquals("desc", parameters.getFirst("order"));
        assertEquals(String.valueOf(includeCompleted), parameters.getFirst("includeCompleted"));
        assertEquals("2", parameters.getFirst("page"));
        verify(todoMapper).togglePinned(1L, 7L);
        verify(todoMapper).findIncludingDeletedById(7L);
        verifyNoMoreInteractions(todoMapper);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "2026-10-27, NULL, false, false, false",
            "2026-10-27, 2026-10-26, false, true, false",
            "2026-10-27, 2026-10-27, false, false, true",
            "2026-10-27, 2026-10-28, false, false, true",
            "2026-10-27, 2026-10-30, false, false, true",
            "2026-10-27, 2026-10-31, false, false, false",
            "2026-10-27, 2026-10-26, true, false, false",
            "2026-10-27, 2026-10-27, true, false, false",
            "2026-10-27, 2026-10-30, true, false, false",
            "2026-10-27, 2026-10-26, NULL, true, false",
            "2026-10-27, 2026-10-30, NULL, false, true",
            "2026-12-30, 2027-01-02, false, false, true",
            "2026-12-30, 2027-01-03, false, false, false",
            "2024-02-27, 2024-03-01, false, false, true"
    }, nullValues = "NULL")
    void highlightsDeadlinesUsingEntityRulesIncludingBoundaryDatesAndCompletion(
            String todayValue, String dueValue, Boolean completed, boolean overdue, boolean near) throws Exception {
        LocalDate today = LocalDate.parse(todayValue);
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setId(1L);
        todo.setTitle("期限の確認");
        todo.setPriority(2);
        todo.setDueDate(date(dueValue));
        todo.setCompleted(completed);
        when(todoMapper.count(1L, "", "", true)).thenReturn(1L);
        when(todoMapper.searchPage(1L, "", "", "asc", true, 10, 0L)).thenReturn(List.of(todo));

        try (MockedStatic<LocalDate> dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            dates.when(LocalDate::now).thenReturn(today);
            assertEquals(overdue, todo.isOverdue());
            assertEquals(near, todo.isNearDeadline());
            String html = mockMvc.perform(get("/todos").param("includeCompleted", "true"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String body = html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
            assertEquals(overdue, body.contains("overdue-row"));
            assertEquals(near, body.contains("warning-row"));
            if (dueValue != null) {
                assertTrue(body.contains("<td>" + dueValue + "</td>"));
            }
        }
    }

    private LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
