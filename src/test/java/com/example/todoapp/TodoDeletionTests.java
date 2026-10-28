package com.example.todoapp;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.example.todoapp.api.HolidayClient;
import com.example.todoapp.api.TodoApiController;
import com.example.todoapp.api.TodoRequest;
import com.example.todoapp.mcp.TodoTools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Opt-in MySQL checks. All writes target connection-local temporary tables.
@EnabledIfEnvironmentVariable(named = "COMPLETION_TEST_DB_PASSWORD", matches = ".+")
class TodoDeletionTests {

    private SqlSession session;
    private TodoMapper mapper;
    private TodoService service;
    private TodoApiController api;
    private TodoTools tools;

    @BeforeEach
    void setUp() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://127.0.0.1:3306/todoapp", "root", System.getenv("COMPLETION_TEST_DB_PASSWORD"));
        Configuration configuration = new Configuration(
                new Environment("deletion-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TodoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE deletion_template LIKE todoapp.todos");
            statement.execute("CREATE TEMPORARY TABLE todos LIKE deletion_template");
        }
        mapper = session.getMapper(TodoMapper.class);
        service = new TodoService(mapper, TodoTestUsers.authenticatedMapper(),
                mock(HistoryMapper.class), mock(AuditService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        api = new TodoApiController(service);
        HolidayClient holidays = mock(HolidayClient.class);
        when(holidays.fetchHolidays()).thenReturn(new HolidayClient.FetchResult(Map.of(), false));
        tools = new TodoTools(service, holidays);
    }

    @AfterEach
    void closeSession() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        if (session != null) {
            session.rollback();
            session.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "screen", "api", "mcp" })
    void deletionKeepsRowButHidesItFromEveryNormalReadAndCanBeRestored(String entry) throws Exception {
        Todo todo = sample("delete-me", false);
        mapper.insert(todo);
        Long id = todo.getId();
        assertEquals(1, tools.listTodos(null, null, null, null).size());
        assertEquals(id, tools.getTodo(id).getId());
        assertTrue(tools.findFreeDays("2026-10-28", "2026-10-28").isEmpty());

        switch (entry) {
            case "screen" -> {
                var messages = new org.springframework.context.support.ResourceBundleMessageSource();
                messages.setBasename("messages");
                messages.setDefaultEncoding("UTF-8");
                new HomeController(service, messages).delete(id, new RedirectAttributesModelMap());
            }
            case "api" -> assertEquals(204, api.delete(id).getStatusCode().value());
            case "mcp" -> tools.deleteTodo(id);
            default -> throw new IllegalArgumentException(entry);
        }

        assertEquals(1, rawCount());
        assertEquals(0, service.count("", "", true));
        assertTrue(service.searchPage("", "", "asc", true, 1).isEmpty());
        assertTrue(service.search("", "", "asc").isEmpty());
        assertNull(service.findById(id));
        assertTrue(api.list("", "", "asc", null, null).isEmpty());
        assertThrows(com.example.todoapp.exception.TodoNotFoundException.class, () -> api.getById(id));
        assertThrows(com.example.todoapp.exception.TodoNotFoundException.class, () -> api.delete(id));
        TodoRequest request = new TodoRequest();
        request.setTitle("blocked-update");
        request.setCategory("プログラミング");
        request.setPriority(2);
        assertThrows(com.example.todoapp.exception.TodoNotFoundException.class, () -> api.update(id, request));
        assertTrue(tools.listTodos(null, null, null, null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> tools.getTodo(id));
        assertThrows(IllegalArgumentException.class,
                () -> tools.updateTodo(id, "blocked-update", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tools.completeTodo(id));
        assertThrows(IllegalArgumentException.class, () -> tools.deleteTodo(id));
        assertEquals(List.of(LocalDate.of(2026, 10, 28)), tools.findFreeDays("2026-10-28", "2026-10-28"));

        assertEquals(1, service.countTrash("", ""));
        Todo deleted = service.searchTrashPage("", "", "asc", 1).getFirst();
        assertNotNull(deleted.getDeletedAt());
        service.delete(id);
        assertEquals(deleted.getDeletedAt(), service.searchTrashPage("", "", "asc", 1).getFirst().getDeletedAt());
        assertTrue(service.restore(id));
        assertFalse(service.restore(id));
        assertEquals(0, service.countTrash("", ""));
        assertEquals(1, service.count("", "", true));
        assertEquals(id, api.list("", "", "asc", null, null).getFirst().getId());
        assertEquals(id, tools.getTodo(id).getId());
        assertNull(service.findById(id).getDeletedAt());
        assertEquals(1, rawCount());
    }

    @Test
    void deletedRowsCannotBeEditedEvenThroughDirectMapperCall() {
        Todo todo = sample("keep-original", false);
        mapper.insert(todo);
        service.delete(todo.getId());
        todo.setTitle("blocked-update");
        todo.setCompleted(true);
        mapper.update(todo);

        Todo deleted = service.searchTrashPage("", "", "asc", 1).getFirst();
        assertEquals("keep-original", deleted.getTitle());
        assertFalse(deleted.getCompleted());
        assertNull(deleted.getCompletedAt());
        assertNotNull(deleted.getDeletedAt());
    }

    @Test
    void restoringCompletedTodoPreservesItsCompletionDate() {
        Todo todo = sample("completed", false);
        mapper.insert(todo);
        LocalDateTime completedAt = tools.completeTodo(todo.getId()).getCompletedAt();
        service.delete(todo.getId());
        assertTrue(service.restore(todo.getId()));

        Todo restored = service.findById(todo.getId());
        assertTrue(restored.getCompleted());
        assertEquals(completedAt, restored.getCompletedAt());
        assertNull(restored.getDeletedAt());
    }

    @Test
    void trashFiltersAndPaginationIncludeBothCompletionStates() {
        for (int number = 1; number <= 12; number++) {
            Todo todo = sample("todo-" + number, number % 2 == 0);
            if (number == 12) {
                todo.setCategory("デザイン");
            }
            mapper.insert(todo);
            service.delete(todo.getId());
        }

        assertEquals(0, service.count("", "", true));
        assertEquals(11, service.countTrash("todo-", "プログラミング"));
        List<Todo> first = service.searchTrashPage("todo-", "プログラミング", "asc", 1);
        List<Todo> second = service.searchTrashPage("todo-", "プログラミング", "asc", 2);
        assertEquals(10, first.size());
        assertEquals(1, second.size());
        assertEquals("todo-11", second.getFirst().getTitle());
        assertTrue(first.stream().anyMatch(todo -> Boolean.TRUE.equals(todo.getCompleted())));
        assertTrue(first.stream().anyMatch(todo -> Boolean.FALSE.equals(todo.getCompleted())));
        assertTrue(first.stream().allMatch(todo -> todo.getDeletedAt() != null));
    }

    @Test
    void mcpCreationProducesAnOrdinaryVisibleTodo() {
        Long id = tools.addTodo("new-active", "プログラミング", 2, null, null).getId();
        assertNotNull(service.findById(id));
        assertNull(service.findById(id).getDeletedAt());
        assertEquals(0, service.countTrash("", ""));
    }

    @Test
    void bulkDeletionMovesOnlySelectedActiveRowsToTrashAndAllowsRestoringThem() throws Exception {
        Todo first = sample("first", false);
        Todo second = sample("second", true);
        Todo keep = sample("keep", false);
        Todo oldTrash = sample("old-trash", false);
        for (Todo todo : List.of(first, second, keep, oldTrash)) mapper.insert(todo);
        service.delete(oldTrash.getId());
        LocalDateTime oldDeletion = service.searchTrashPage("", "", "asc", 1).getFirst().getDeletedAt();

        assertEquals(2, service.bulkDelete(List.of(first.getId(), second.getId(), first.getId(),
                oldTrash.getId(), Long.MAX_VALUE)));
        assertEquals(4, rawCount());
        assertNull(service.findById(first.getId()));
        assertNull(service.findById(second.getId()));
        assertNotNull(service.findById(keep.getId()));
        assertEquals(1, api.list("", "", "asc", null, null).size());
        assertEquals(1, tools.listTodos(null, null, null, null).size());
        assertEquals(3, service.countTrash("", ""));
        assertEquals(oldDeletion, service.searchTrashPage("old-trash", "", "asc", 1).getFirst().getDeletedAt());
        assertEquals(0, service.bulkDelete(List.of(first.getId(), second.getId())));
        assertTrue(service.restore(second.getId()));
        assertTrue(service.findById(second.getId()).getCompleted());
    }

    @Test
    void directBulkMapperWithEmptyOrNullIdsLeavesEveryRowUntouched() throws Exception {
        Todo keep = sample("keep", false);
        mapper.insert(keep);
        assertEquals(0, mapper.bulkDeleteByIds(1L, List.of()));
        assertEquals(0, mapper.bulkDeleteByIds(1L, null));
        assertNotNull(service.findById(keep.getId()));
        assertEquals(1, rawCount());
        assertEquals(0, service.countTrash("", ""));
    }

    private Todo sample(String title, boolean completed) {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setTitle(title);
        todo.setCategory("プログラミング");
        todo.setPriority(2);
        todo.setCompleted(completed);
        todo.setDueDate(LocalDate.of(2026, 10, 28));
        return todo;
    }

    private long rawCount() throws Exception {
        try (Statement statement = session.getConnection().createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM todos")) {
            result.next();
            return result.getLong(1);
        }
    }
}
