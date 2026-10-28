package com.example.todoapp;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:transaction-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@WithMockUser(username = "tx-admin", roles = "ADMIN")
class TransactionManagementIntegrationTests {
    @Autowired TodoService todos;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean HistoryMapper history;
    @MockitoSpyBean TodoMapper mapper;
    @MockitoSpyBean AuditMapper audit;
    Long actorId;
    Long ownerId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM todo_history");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (username, password, role) VALUES"
                + " ('tx-admin', 'unused', 'ADMIN'), ('tx-owner', 'unused', 'USER')");
        actorId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'tx-admin'", Long.class);
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'tx-owner'", Long.class);
    }

    @Test
    void createCommitsTodoAndHistoryAndRecordsAnIndependentAttempt() {
        Todo todo = sample();
        todos.create(todo);

        assertThat(todos.findById(todo.getId()).getUserId()).isEqualTo(actorId);
        assertThat(jdbc.queryForObject("SELECT action FROM todo_history WHERE todo_id = ?", String.class, todo.getId()))
                .isEqualTo("CREATE");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM todo_history", Long.class)).isEqualTo(actorId);
        assertThat(jdbc.queryForObject("SELECT action FROM audit_logs", String.class)).isEqualTo("CREATE_ATTEMPT");
        assertThat(jdbc.queryForObject("SELECT todo_id FROM audit_logs", Long.class)).isNull();
    }

    @Test
    void aFailureAfterBothInsertsRollsBackTodoAndHistoryButKeepsAudit() {
        doAnswer(call -> {
            jdbc.update("INSERT INTO todo_history (todo_id, actor_user_id, action) VALUES (?, ?, ?)",
                    call.getArgument(0), call.getArgument(1), call.getArgument(2));
            throw new IllegalStateException("history failure after insert");
        }).when(history).insert(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> todos.create(sample())).isInstanceOf(IllegalStateException.class);

        assertThat(count("todos")).isZero();
        assertThat(count("todo_history")).isZero();
        assertThat(count("audit_logs")).isEqualTo(1);
    }

    @Test
    void checkedFailuresAreIncludedInTheRollbackRules() throws Exception {
        var attributes = new AnnotationTransactionAttributeSource();
        for (var method : TodoService.class.getDeclaredMethods()) {
            if (List.of("create", "update", "delete", "bulkDelete", "restore", "togglePinned").contains(method.getName())) {
                var rule = attributes.getTransactionAttribute(method, TodoService.class);
                assertThat(rule).isNotNull();
                assertThat(rule.isReadOnly()).isFalse();
                assertThat(rule.rollbackOn(new IOException("checked failure"))).isTrue();
            }
        }
        doAnswer(call -> { throw new IOException("checked history failure"); })
                .when(history).insert(anyLong(), anyLong(), anyString());

        assertThatThrownBy(() -> todos.create(sample())).hasRootCauseInstanceOf(IOException.class);

        assertThat(count("todos")).isZero();
        assertThat(count("todo_history")).isZero();
        assertThat(count("audit_logs")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE", "RESTORE", "TOGGLE_PIN"})
    void everyMutationRollsBackWhenHistoryFails(String action) {
        Long id = seed("original");
        if (action.equals("RESTORE")) {
            jdbc.update("UPDATE todos SET deleted_at = '2026-01-01 00:00:00' WHERE id = ?", id);
        }
        var before = jdbc.queryForMap("SELECT * FROM todos WHERE id = ?", id);
        doThrow(new IllegalStateException("history unavailable")).when(history).insert(id, actorId, action);

        assertThatThrownBy(() -> {
            switch (action) {
                case "UPDATE" -> {
                    Todo changed = todos.findById(id);
                    changed.setTitle("changed");
                    changed.setCompleted(true);
                    todos.update(changed);
                }
                case "DELETE" -> todos.delete(id);
                case "RESTORE" -> todos.restore(id);
                case "TOGGLE_PIN" -> todos.togglePinned(id);
                default -> throw new AssertionError(action);
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM todos WHERE id = ?", id)).isEqualTo(before);
        assertThat(count("todo_history")).isZero();
        assertThat(jdbc.queryForObject("SELECT action FROM audit_logs", String.class)).isEqualTo(action + "_ATTEMPT");
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM audit_logs", Long.class)).isEqualTo(actorId);
    }

    @Test
    void bulkDeleteRecordsOnlyActiveExistingDistinctTargets() {
        Long first = seed("first");
        Long second = seed("second");
        Long trashed = seed("trash");
        jdbc.update("UPDATE todos SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", trashed);

        assertThat(todos.bulkDelete(List.of(first, second, first, trashed, Long.MAX_VALUE))).isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT todo_id FROM todo_history ORDER BY todo_id", Long.class))
                .containsExactly(first, second);
        assertThat(jdbc.queryForList("SELECT action FROM todo_history", String.class))
                .containsOnly("BULK_DELETE");
        assertThat(todos.bulkDelete(List.of(first, second))).isZero();
        assertThat(count("todo_history")).isEqualTo(2);
    }

    @Test
    void bulkFailureAfterUpdateRollsBackAllRowsAndAlreadyInsertedHistory() {
        Long first = seed("first");
        Long second = seed("second");
        doAnswer(call -> {
            jdbc.update("UPDATE todos SET deleted_at = CURRENT_TIMESTAMP WHERE id IN (?, ?)", first, second);
            throw new IllegalStateException("bulk failure after update");
        }).when(mapper).bulkDeleteByIds(isNull(), eq(List.of(first, second)));

        assertThatThrownBy(() -> todos.bulkDelete(List.of(first, second))).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE deleted_at IS NULL", Integer.class)).isEqualTo(2);
        assertThat(count("todo_history")).isZero();
        assertThat(count("audit_logs")).isEqualTo(2);
    }

    @Test
    void outerRollbackAlsoRollsBackTodoAndHistoryWithoutLosingAudit() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            todos.create(sample());
            throw new IllegalStateException("outer failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("todos")).isZero();
        assertThat(count("todo_history")).isZero();
        assertThat(count("audit_logs")).isEqualTo(1);
    }

    @Test
    void searchOverloadsRunInsideAReadOnlyTransaction() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return List.of();
        }).when(mapper).search(any(), any(), any(), any(), any(), any(), anyBoolean());

        assertThat(todos.search("", "", "asc")).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(count("audit_logs")).isZero();
    }

    @Test
    @WithMockUser(username = "tx-owner")
    void deniedOperationsDoNotWriteHistoryOrAudit() {
        Long id = seed("admin task");
        jdbc.update("UPDATE todos SET user_id = ? WHERE id = ?", actorId, id);

        assertThatThrownBy(() -> todos.delete(id)).isInstanceOf(AccessDeniedException.class);
        assertThat(count("todo_history")).isZero();
        assertThat(count("audit_logs")).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM todos WHERE id = ?", Object.class, id)).isNull();
    }

    @Test
    void auditFailurePreventsAnUnauditedWrite() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).insert("CREATE_ATTEMPT", null, actorId);

        assertThatThrownBy(() -> todos.create(sample())).isInstanceOf(IllegalStateException.class);

        assertThat(count("todos")).isZero();
        assertThat(count("todo_history")).isZero();
        assertThat(count("audit_logs")).isZero();
    }

    private Long seed(String title) {
        jdbc.update("INSERT INTO todos (user_id, title, category, priority) VALUES (?, ?, '仕事', 2)", ownerId, title);
        return jdbc.queryForObject("SELECT id FROM todos WHERE title = ?", Long.class, title);
    }

    private Todo sample() {
        Todo todo = new Todo();
        todo.setTitle("transaction task");
        todo.setCategory("仕事");
        todo.setPriority(2);
        return todo;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
