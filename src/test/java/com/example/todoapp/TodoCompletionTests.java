package com.example.todoapp;

import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;

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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Opt-in MySQL checks. Each connection shadows todos with a temporary table.
@EnabledIfEnvironmentVariable(named = "COMPLETION_TEST_DB_PASSWORD", matches = ".+")
class TodoCompletionTests {

    private static final LocalDateTime PREVIOUS_COMPLETION = LocalDateTime.of(2026, 10, 26, 12, 34, 56);

    private SqlSession session;
    private TodoMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                "jdbc:mysql://127.0.0.1:3306/todoapp", "root", System.getenv("COMPLETION_TEST_DB_PASSWORD"));
        Configuration configuration = new Configuration(
                new Environment("completion-test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream("/mapper/TodoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TodoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        try (Statement statement = session.getConnection().createStatement()) {
            statement.execute("CREATE TEMPORARY TABLE completion_template LIKE todoapp.todos");
            statement.execute("CREATE TEMPORARY TABLE todos LIKE completion_template");
        }
        mapper = session.getMapper(TodoMapper.class);
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.rollback();
            session.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void doesNotRecordCompletionDuringCreation(boolean completed) {
        Todo todo = sample(completed);
        todo.setCompletedAt(PREVIOUS_COMPLETION);
        mapper.insert(todo);

        Todo stored = mapper.findById(todo.getId());
        assertEquals(completed, stored.getCompleted());
        assertNull(stored.getCompletedAt());
    }

    @Test
    void recordsDatabaseTimeOnlyWhenBecomingCompletedAndMapsItInAllSearches() throws Exception {
        Todo todo = sample(false);
        mapper.insert(todo);
        todo.setTitle("renamed-incomplete");
        mapper.update(todo);
        assertNull(mapper.findById(todo.getId()).getCompletedAt());

        LocalDateTime before = databaseTime();
        todo.setCompleted(true);
        mapper.update(todo);
        Todo stored = mapper.findById(todo.getId());
        assertNotNull(stored.getCompletedAt());
        assertFalse(stored.getCompletedAt().isBefore(before));
        assertFalse(stored.getCompletedAt().isAfter(databaseTime()));
        assertTrue(stored.getCompleted());
        assertEquals(stored.getCompletedAt(), mapper.search(1L, "", "", "asc", null, null, true)
                .getFirst().getCompletedAt());
        assertEquals(stored.getCompletedAt(), mapper.searchPage(1L, "", "", "asc", true, 10, 0L)
                .getFirst().getCompletedAt());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = { true })
    void preservesCompletionDateWhenOnlyTitleChangesOrCompletionIsRepeated(Boolean completed) throws Exception {
        Todo todo = sample(true);
        mapper.insert(todo);
        try (Statement statement = session.getConnection().createStatement()) {
            statement.executeUpdate("UPDATE todos SET completed_at = '2026-10-26 12:34:56'");
        }

        todo.setTitle("renamed-completed");
        todo.setCompleted(completed);
        todo.setCompletedAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        mapper.update(todo);

        Todo stored = mapper.findById(todo.getId());
        assertEquals("renamed-completed", stored.getTitle());
        assertTrue(stored.getCompleted());
        assertEquals(PREVIOUS_COMPLETION, stored.getCompletedAt());
    }

    @Test
    void clearsDateWhenReopenedAndRecordsNewTimeWhenCompletedAgain() throws Exception {
        Todo todo = sample(true);
        mapper.insert(todo);
        try (Statement statement = session.getConnection().createStatement()) {
            statement.executeUpdate("UPDATE todos SET completed_at = '2026-10-26 12:34:56'");
        }

        todo.setCompleted(false);
        mapper.update(todo);
        Todo reopened = mapper.findById(todo.getId());
        assertFalse(reopened.getCompleted());
        assertNull(reopened.getCompletedAt());

        LocalDateTime before = databaseTime();
        todo.setCompleted(true);
        mapper.update(todo);
        Todo recompleted = mapper.findById(todo.getId());
        assertTrue(recompleted.getCompleted());
        assertNotNull(recompleted.getCompletedAt());
        assertNotEquals(PREVIOUS_COMPLETION, recompleted.getCompletedAt());
        assertFalse(recompleted.getCompletedAt().isBefore(before));
        assertFalse(recompleted.getCompletedAt().isAfter(databaseTime()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = { true })
    void keepsUnknownHistoricalCompletionDateEmpty(Boolean completed) {
        Todo todo = sample(true);
        mapper.insert(todo);
        todo.setTitle("renamed-historical");
        todo.setCompleted(completed);
        mapper.update(todo);

        Todo stored = mapper.findById(todo.getId());
        assertTrue(stored.getCompleted());
        assertNull(stored.getCompletedAt());
    }

    private Todo sample(boolean completed) {
        Todo todo = new Todo();
        todo.setUserId(1L);
        todo.setTitle("completion-test");
        todo.setCategory("プログラミング");
        todo.setPriority(2);
        todo.setCompleted(completed);
        return todo;
    }

    private LocalDateTime databaseTime() throws Exception {
        try (Statement statement = session.getConnection().createStatement();
                ResultSet result = statement.executeQuery("SELECT CURRENT_TIMESTAMP")) {
            result.next();
            return result.getObject(1, LocalDateTime.class);
        }
    }
}
