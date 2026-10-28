package com.example.todoapp;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.example.todoapp.exception.TodoNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TodoServiceの単体テスト（DB・Springの起動なし）")
class TodoServiceTest {
    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;
    private static final Long TODO_ID = 100L;

    @Mock TodoMapper todoMapper;
    @Mock UserMapper userMapper;
    @Mock HistoryMapper historyMapper;
    @Mock AuditService auditService;
    @InjectMocks TodoService service;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("作成時は認証ユーザーを所有者にし、採番後のIDで履歴を残す")
    void createsWithAuthenticatedOwnerAndGeneratedId() {
        // Arrange: リクエストの所有者は信用しない。
        authenticate("USER");
        Todo todo = todo(OTHER_USER_ID);
        todo.setId(null);
        doAnswer(call -> {
            call.<Todo>getArgument(0).setId(TODO_ID);
            return null;
        }).when(todoMapper).insert(todo);

        // Act
        service.create(todo);

        // Assert
        assertEquals(USER_ID, todo.getUserId());
        assertEquals(TODO_ID, todo.getId());
        var order = inOrder(auditService, todoMapper, historyMapper);
        order.verify(auditService).log("CREATE_ATTEMPT", null, USER_ID);
        order.verify(todoMapper, times(1)).insert(todo);
        order.verify(historyMapper).insert(TODO_ID, USER_ID, "CREATE");
    }

    @Test
    @DisplayName("作成に失敗した場合は例外を伝播し成功履歴を残さない")
    void propagatesInsertFailureWithoutSuccessHistory() {
        authenticate("USER");
        Todo todo = todo(USER_ID);
        var failure = new IllegalStateException("insert failed");
        doThrow(failure).when(todoMapper).insert(todo);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.create(todo)));

        verify(auditService).log("CREATE_ATTEMPT", null, USER_ID);
        verifyNoInteractions(historyMapper);
    }

    @Test
    @DisplayName("自分のToDoをIDで取得できる")
    void findsOwnedTodo() {
        authenticate("USER");
        Todo expected = todo(USER_ID);
        when(todoMapper.findById(TODO_ID)).thenReturn(expected);

        assertSame(expected, service.findById(TODO_ID));

        verify(todoMapper, times(1)).findById(TODO_ID);
        verifyNoMoreInteractions(todoMapper);
    }

    @Test
    @DisplayName("存在しないIDは削除済みデータも確認してnullを返す")
    void returnsNullForMissingTodo() {
        authenticate("USER");
        when(todoMapper.findById(TODO_ID)).thenReturn(null);

        assertNull(service.findById(TODO_ID));

        verify(todoMapper).findIncludingDeletedById(TODO_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("他ユーザーのToDoは削除済みでも取得を拒否する")
    void deniesForeignTodoEvenWhenDeleted(boolean deleted) {
        authenticate("USER");
        if (deleted) {
            when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(OTHER_USER_ID));
        } else {
            when(todoMapper.findById(TODO_ID)).thenReturn(todo(OTHER_USER_ID));
        }

        assertThrows(AccessDeniedException.class, () -> service.findById(TODO_ID));
    }

    @Test
    @DisplayName("アクセス確認は自分のToDoを許可し、存在しないIDは例外にする")
    void checksAccessAndRejectsMissingTodo() {
        authenticate("USER");
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(USER_ID));
        assertDoesNotThrow(() -> service.checkAccess(TODO_ID));

        assertThrows(TodoNotFoundException.class, () -> service.checkAccess(999L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @DisplayName("検索・件数・CSV・ゴミ箱に権限に応じた所有者条件を渡す")
    void scopesAllReadOperations(String role) {
        authenticate(role);
        Long scope = "ADMIN".equals(role) ? null : USER_ID;
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(7);
        List<Todo> expected = List.of(todo(USER_ID));
        when(todoMapper.search(scope, "task", "資格", "asc", null, null, true)).thenReturn(expected);
        when(todoMapper.search(scope, "task", "資格", "desc", null, null, false)).thenReturn(expected);
        when(todoMapper.search(scope, "task", "資格", "asc", from, to, true)).thenReturn(expected);
        when(todoMapper.count(scope, "task", "資格", false)).thenReturn(23L);
        when(todoMapper.searchPage(scope, "task", "資格", "desc", false, 10, 20L)).thenReturn(expected);
        when(todoMapper.searchForExport(scope, "task", "資格", "asc", true)).thenReturn(expected);
        when(todoMapper.countTrash(scope, "task", "資格")).thenReturn(12L);
        when(todoMapper.searchTrashPage(scope, "task", "資格", "asc", 10, 10L)).thenReturn(expected);

        assertSame(expected, service.search("task", "資格", "asc"));
        assertSame(expected, service.search("task", "資格", "desc", false));
        assertSame(expected, service.search("task", "資格", "asc", from, to));
        assertEquals(23L, service.count("task", "資格", false));
        assertSame(expected, service.searchPage("task", "資格", "desc", false, 3));
        assertSame(expected, service.searchForExport("task", "資格", "asc", true));
        assertEquals(12L, service.countTrash("task", "資格"));
        assertSame(expected, service.searchTrashPage("task", "資格", "asc", 2));

        verify(todoMapper).searchPage(scope, "task", "資格", "desc", false, 10, 20L);
        verify(todoMapper).searchTrashPage(scope, "task", "資格", "asc", 10, 10L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    @DisplayName("更新は元の所有者を保持し、操作したユーザーを履歴に残す")
    void updatesWithoutChangingOwner(String role) {
        authenticate(role);
        Long owner = "ADMIN".equals(role) ? OTHER_USER_ID : USER_ID;
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(owner));
        Todo changed = todo(999L);
        when(todoMapper.update(changed)).thenReturn(1);

        service.update(changed);

        assertEquals(owner, changed.getUserId());
        verify(auditService).log("UPDATE_ATTEMPT", TODO_ID, USER_ID);
        verify(todoMapper).update(changed);
        verify(historyMapper).insert(TODO_ID, USER_ID, "UPDATE");
    }

    @Test
    @DisplayName("更新対象がなければ成功履歴を残さない")
    void doesNotRecordHistoryWhenUpdateAffectsNoRows() {
        authenticate("USER");
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(USER_ID));

        service.update(todo(USER_ID));

        verify(todoMapper).update(any(Todo.class));
        verifyNoInteractions(historyMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "delete"})
    @DisplayName("更新・削除の対象が存在しない場合は書き込みを実行しない")
    void rejectsMissingWriteTarget(String operation) {
        authenticate("USER");

        assertThrows(TodoNotFoundException.class, () -> mutate(operation));

        verifyNoInteractions(auditService, historyMapper);
        verify(todoMapper, never()).update(any());
        verify(todoMapper, never()).deleteById(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "delete", "restore", "pin", "access"})
    @DisplayName("他ユーザーのデータへの操作は監査・更新・履歴記録前に拒否する")
    void rejectsForeignMutations(String operation) {
        authenticate("USER");
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(OTHER_USER_ID));

        assertThrows(AccessDeniedException.class, () -> mutate(operation));

        verify(todoMapper).findIncludingDeletedById(TODO_ID);
        verifyNoMoreInteractions(todoMapper);
        verifyNoInteractions(auditService, historyMapper);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("論理削除が成功した場合だけ成功履歴を記録する")
    void deletesAndRecordsOnlyActualChanges(int affectedRows) {
        authenticate("USER");
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(USER_ID));
        when(todoMapper.deleteById(USER_ID, TODO_ID)).thenReturn(affectedRows);

        service.delete(TODO_ID);

        verify(todoMapper).deleteById(USER_ID, TODO_ID);
        verify(auditService).log("DELETE_ATTEMPT", TODO_ID, USER_ID);
        verify(historyMapper, times(affectedRows)).insert(TODO_ID, USER_ID, "DELETE");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("復元の成否を返し、成功した場合だけ履歴を残す")
    void restoresAndReportsResult(int affectedRows) {
        authenticate("USER");
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(USER_ID));
        when(todoMapper.restoreById(USER_ID, TODO_ID)).thenReturn(affectedRows);

        assertEquals(affectedRows > 0, service.restore(TODO_ID));

        verify(auditService).log("RESTORE_ATTEMPT", TODO_ID, USER_ID);
        verify(historyMapper, times(affectedRows)).insert(TODO_ID, USER_ID, "RESTORE");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    @DisplayName("印の切替の成否を返し、成功した場合だけ履歴を残す")
    void togglesPinAndReportsResult(int affectedRows) {
        authenticate("USER");
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(USER_ID));
        when(todoMapper.togglePinned(USER_ID, TODO_ID)).thenReturn(affectedRows);

        assertEquals(affectedRows > 0, service.togglePinned(TODO_ID));

        verify(auditService).log("TOGGLE_PIN_ATTEMPT", TODO_ID, USER_ID);
        verify(historyMapper, times(affectedRows)).insert(TODO_ID, USER_ID, "TOGGLE_PIN");
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @DisplayName("一括削除の選択が空ならDBへアクセスしない")
    void ignoresEmptyBulkSelection(List<Long> ids) {
        assertEquals(0, service.bulkDelete(ids));
        verifyNoInteractions(todoMapper, userMapper, auditService, historyMapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    @DisplayName("一括削除に不正なIDが含まれる場合は全件処理を中止する")
    void rejectsInvalidBulkIds(Long invalidId) {
        assertThrows(IllegalArgumentException.class,
                () -> service.bulkDelete(Arrays.asList(TODO_ID, invalidId)));
        verifyNoInteractions(todoMapper, userMapper, auditService, historyMapper);
    }

    @Test
    @DisplayName("管理者の一括削除では重複IDを除き、削除件数を返す")
    void bulkDeletesDistinctIdsAsAdmin() {
        authenticate("ADMIN");
        List<Long> ids = List.of(TODO_ID, 101L);
        when(todoMapper.bulkDeleteByIds(null, ids)).thenReturn(2);

        assertEquals(2, service.bulkDelete(List.of(TODO_ID, 101L, TODO_ID)));

        verify(auditService, times(1)).log("BULK_DELETE_ATTEMPT", TODO_ID, USER_ID);
        verify(auditService, times(1)).log("BULK_DELETE_ATTEMPT", 101L, USER_ID);
        var order = inOrder(historyMapper, todoMapper);
        order.verify(historyMapper).insertBulkDelete(null, USER_ID, ids);
        order.verify(todoMapper).bulkDeleteByIds(null, ids);
    }

    @Test
    @DisplayName("直接呼び出した一括削除でも他ユーザーのIDを含めると拒否する")
    void rejectsMixedOwnerBulkDeletion() {
        authenticate("USER");
        when(todoMapper.findIncludingDeletedById(TODO_ID)).thenReturn(todo(OTHER_USER_ID));

        assertThrows(AccessDeniedException.class, () -> service.bulkDelete(List.of(TODO_ID)));

        verifyNoInteractions(auditService, historyMapper);
        verify(todoMapper, never()).bulkDeleteByIds(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unauthenticated", "anonymous"})
    @DisplayName("未認証・匿名ユーザーはDB検索前に拒否する")
    void rejectsInvalidAuthentication(String kind) {
        if ("unauthenticated".equals(kind)) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("test-user", "unused"));
        } else if ("anonymous".equals(kind)) {
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                    "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        }

        assertThrows(AccessDeniedException.class, () -> service.findById(TODO_ID));
        assertThrows(AccessDeniedException.class, () -> service.create(todo(USER_ID)));

        verifyNoInteractions(todoMapper, userMapper, auditService, historyMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "missingId", "disabled"})
    @DisplayName("認証済みでもDBのユーザーが無効なら操作を拒否する")
    void rejectsUnavailableDatabaseUser(String kind) {
        User user = authenticate("USER");
        switch (kind) {
            case "missing" -> when(userMapper.findByUsername("test-user")).thenReturn(null);
            case "missingId" -> user.setId(null);
            case "disabled" -> user.setEnabled(false);
            default -> fail("Unexpected case");
        }

        assertThrows(AccessDeniedException.class, () -> service.findById(TODO_ID));
        assertThrows(AccessDeniedException.class, () -> service.create(todo(USER_ID)));

        verifyNoInteractions(todoMapper, auditService, historyMapper);
    }

    private User authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "test-user", "unused", AuthorityUtils.createAuthorityList("ROLE_" + role)));
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("test-user");
        when(userMapper.findByUsername("test-user")).thenReturn(user);
        return user;
    }

    private Todo todo(Long ownerId) {
        Todo todo = new Todo();
        todo.setId(TODO_ID);
        todo.setUserId(ownerId);
        todo.setTitle("テストタスク");
        todo.setDetail("テスト説明");
        todo.setCategory("資格");
        todo.setPriority(2);
        return todo;
    }

    private void mutate(String operation) {
        switch (operation) {
            case "update" -> service.update(todo(USER_ID));
            case "delete" -> service.delete(TODO_ID);
            case "restore" -> service.restore(TODO_ID);
            case "pin" -> service.togglePinned(TODO_ID);
            case "access" -> service.checkAccess(TODO_ID);
            default -> fail("Unexpected operation: " + operation);
        }
    }
}
