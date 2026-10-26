package com.example.todoapp;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.example.todoapp.api.TodoApiController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TodoSearchTests {

    private TodoMapper todoMapper;
    private TodoService todoService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        todoMapper = mock(TodoMapper.class);
        todoService = new TodoService(todoMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new TodoApiController(todoService)).build();
    }

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

        verify(todoMapper).search("勉強", "資格", "desc", date(from), date(to));
    }

    @Test
    void keepsApiDefaults() throws Exception {
        mockMvc.perform(get("/api/todos"))
                .andExpect(status().isOk());
        verify(todoMapper).search("", "", "asc", null, null);
    }

    @Test
    void keepsExistingScreenSearchWithoutDateConditions() {
        todoService.search("勉強", "資格", "asc");
        verify(todoMapper).search("勉強", "資格", "asc", null, null);
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
                TodoMapper.class.getMethod("search", String.class, String.class, String.class,
                        LocalDate.class, LocalDate.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) resolver.getNamedParams(
                new Object[] { "勉強", "資格", "desc", date(from), date(to) });
        BoundSql sql = configuration.getMappedStatement("com.example.todoapp.TodoMapper.search")
                .getBoundSql(parameters);

        assertEquals("SELECT id, title, detail, category, priority, due_date, completed, created_at, updated_at"
                + " FROM todos WHERE title LIKE CONCAT('%', ?, '%') AND category = ?"
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
        String where = conditions.isEmpty() ? "" : " WHERE " + conditions.strip().substring(4);
        assertEquals("SELECT id, title, detail, category, priority, due_date, completed, created_at, updated_at"
                + " FROM todos" + where + " ORDER BY due_date ASC",
                defaultSql.getSql().replaceAll("\\s+", " ").strip());
    }

    private LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }
}
