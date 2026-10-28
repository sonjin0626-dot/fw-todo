package com.example.todoapp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

import com.example.todoapp.api.TodoApiController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Opt-in MySQL checks. All writes target connection-local temporary tables.
@EnabledIfEnvironmentVariable(named = "COMPLETION_TEST_DB_PASSWORD", matches = ".+")
class TodoPinTests {

    private SqlSession session;
    private TodoMapper mapper;
    private TodoService service;

    @BeforeEach
    void setUp() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://127.0.0.1:3306/todoapp", "root", System.getenv("COMPLETION_TEST_DB_PASSWORD"));
        Configuration configuration = new Configuration(
                new Environment("pin-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TodoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE pin_template LIKE todoapp.todos");
            statement.execute("CREATE TEMPORARY TABLE todos LIKE pin_template");
        }
        mapper = session.getMapper(TodoMapper.class);
        service = new TodoService(mapper, TodoTestUsers.authenticatedMapper(),
                org.mockito.Mockito.mock(HistoryMapper.class), org.mockito.Mockito.mock(AuditService.class),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
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
    @ValueSource(strings = { "asc", "desc" })
    void putsPinsFirstThenUsesSelectedDeadlineOrder(String order) {
        insert("plain-early", 1);
        insert("plain-late", 30);
        Todo pinnedEarly = insert("pinned-early", 10);
        Todo pinnedLate = insert("pinned-late", 20);
        service.togglePinned(pinnedEarly.getId());
        service.togglePinned(pinnedLate.getId());

        List<String> titles = service.searchPage("", "", order, true, 1).stream().map(Todo::getTitle).toList();
        assertEquals("asc".equals(order)
                ? List.of("pinned-early", "pinned-late", "plain-early", "plain-late")
                : List.of("pinned-late", "pinned-early", "plain-late", "plain-early"), titles);
    }

    @Test
    void pinMovesFromLaterPageToFrontBeforeLimitIsApplied() {
        for (int number = 1; number <= 11; number++) {
            insert("plain-" + number, 1);
        }
        Todo pinned = insert("pin-from-second-page", 30);
        assertFalse(service.searchPage("", "", "asc", true, 1).stream()
                .anyMatch(todo -> todo.getId().equals(pinned.getId())));
        service.togglePinned(pinned.getId());

        List<Todo> first = service.searchPage("", "", "asc", true, 1);
        List<Todo> second = service.searchPage("", "", "asc", true, 2);
        assertEquals(10, first.size());
        assertEquals(2, second.size());
        assertEquals(pinned.getId(), first.getFirst().getId());
        assertTrue(second.stream().noneMatch(todo -> todo.getId().equals(pinned.getId())));
    }

    @Test
    void defaultsToUnpinnedAndTogglesWithoutChangingCompletionDate() {
        Todo todo = insert("toggle-me", 10);
        assertFalse(service.findById(todo.getId()).getPinned());
        todo.setCompleted(true);
        service.update(todo);
        LocalDateTime completedAt = service.findById(todo.getId()).getCompletedAt();
        assertNotNull(completedAt);

        assertTrue(service.togglePinned(todo.getId()));
        Todo pinned = service.findById(todo.getId());
        assertTrue(pinned.getPinned());
        assertEquals(completedAt, pinned.getCompletedAt());
        assertEquals("toggle-me", pinned.getTitle());
        assertTrue(service.togglePinned(todo.getId()));
        Todo unpinned = service.findById(todo.getId());
        assertFalse(unpinned.getPinned());
        assertTrue(unpinned.getCompleted());
        assertEquals(completedAt, unpinned.getCompletedAt());
    }

    @Test
    void ordinaryEditDoesNotOverwritePin() {
        Todo todo = insert("edit-me", 10);
        service.togglePinned(todo.getId());
        todo.setTitle("renamed");
        todo.setPinned(false);
        service.update(todo);

        assertEquals("renamed", service.findById(todo.getId()).getTitle());
        assertTrue(service.findById(todo.getId()).getPinned());
    }

    @Test
    void filtersStillApplyToPinnedRowsAndCompletion() {
        Todo pinned = insert("match-pinned", 20);
        insert("match-plain", 1);
        Todo completed = insert("match-completed", 10);
        completed.setCompleted(true);
        service.update(completed);
        Todo other = insert("other-pinned", 5);
        other.setCategory("デザイン");
        service.update(other);
        service.togglePinned(pinned.getId());
        service.togglePinned(completed.getId());
        service.togglePinned(other.getId());

        assertEquals(2, service.count("match", "プログラミング", false));
        assertEquals(List.of("match-pinned", "match-plain"),
                service.searchPage("match", "プログラミング", "asc", false, 1).stream().map(Todo::getTitle).toList());
        assertEquals(List.of("match-completed", "match-pinned", "match-plain"),
                service.searchPage("match", "プログラミング", "asc", true, 1).stream().map(Todo::getTitle).toList());
    }

    @Test
    void deletedRowsCannotBeToggledAndRestorationKeepsPin() {
        Todo todo = insert("delete-me", 10);
        service.togglePinned(todo.getId());
        service.delete(todo.getId());

        assertFalse(service.togglePinned(todo.getId()));
        assertTrue(service.searchPage("", "", "asc", true, 1).isEmpty());
        assertTrue(service.searchTrashPage("", "", "asc", 1).getFirst().getPinned());
        assertTrue(service.restore(todo.getId()));
        assertTrue(service.findById(todo.getId()).getPinned());
    }

    @ParameterizedTest
    @ValueSource(strings = { "asc", "desc" })
    void exportsAllMatchingPagesWithTheSamePinAndDeadlineOrder(String order) {
        Todo pinned = null;
        for (int number = 1; number <= 12; number++) {
            pinned = insert(String.format(java.util.Locale.ROOT, "match-%02d", number), number);
        }
        service.togglePinned(pinned.getId());
        Todo completed = insert("match-completed", 20);
        completed.setCompleted(true);
        service.update(completed);
        Todo deleted = insert("match-deleted", 21);
        service.delete(deleted.getId());
        Todo otherCategory = insert("match-other-category", 22);
        otherCategory.setCategory("デザイン");
        service.update(otherCategory);
        insert("different-keyword", 23);

        List<Todo> exported = service.searchForExport("match", "プログラミング", order, false);
        java.util.ArrayList<Long> screenIds = new java.util.ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            service.searchPage("match", "プログラミング", order, false, page).forEach(todo -> screenIds.add(todo.getId()));
        }
        assertEquals(12, exported.size());
        assertEquals(screenIds, exported.stream().map(Todo::getId).toList());
        assertEquals(pinned.getId(), exported.getFirst().getId());
        assertEquals(13, service.searchForExport("match", "プログラミング", order, true).size());
        String csv = new String(new TodoApiController(service)
                .exportCsv("match", "プログラミング", order, false).getBody(), StandardCharsets.UTF_8);
        assertEquals(13L, csv.lines().count());
        assertFalse(csv.contains("match-completed"));
        assertFalse(csv.contains("match-deleted"));
        assertFalse(csv.contains("match-other-category"));
        assertFalse(csv.contains("different-keyword"));
    }

    private Todo insert(String title, int day) {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setTitle(title);
        todo.setCategory("プログラミング");
        todo.setPriority(2);
        todo.setCompleted(false);
        todo.setDueDate(LocalDate.of(2026, 10, day));
        service.create(todo);
        return todo;
    }
}
