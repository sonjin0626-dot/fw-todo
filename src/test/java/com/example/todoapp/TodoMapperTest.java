package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:todo-mapper-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.schema-locations=classpath:db/users.sql,classpath:db/todos.sql"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional
@DisplayName("TodoMapperのデータレイヤーテスト（H2・テスト後ロールバック）")
class TodoMapperTest {
    @Autowired TodoMapper mapper;
    @Autowired UserMapper users;
    private Long ownerId;
    private Long otherOwnerId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("mapper-owner");
        otherOwnerId = insertUser("mapper-other");
    }

    @Test
    @DisplayName("挿入時にIDを採番し、全フィールドと初期値を取得できる")
    void insertsAndMapsGeneratedIdAndDefaults() {
        Todo todo = sample(ownerId, "テストタスク", "2026-11-01");
        mapper.insert(todo);

        assertThat(todo.getId()).isNotNull();
        Todo found = mapper.findById(todo.getId());
        assertThat(found).isNotNull();
        assertThat(found.getUserId()).isEqualTo(ownerId);
        assertThat(found.getTitle()).isEqualTo("テストタスク");
        assertThat(found.getDetail()).isEqualTo("テスト説明");
        assertThat(found.getCategory()).isEqualTo("資格");
        assertThat(found.getPriority()).isEqualTo(2);
        assertThat(found.getDueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(found.getCompleted()).isFalse();
        assertThat(found.getPinned()).isFalse();
        assertThat(found.getCompletedAt()).isNull();
        assertThat(found.getDeletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("更新内容と完了日時を保存し、未完了へ戻すと完了日時を消す")
    void updatesFieldsAndCompletionTimestamp() {
        Todo todo = insert(ownerId, "変更前", "2026-11-01");
        todo.setTitle("変更後");
        todo.setDetail("変更した説明");
        todo.setCategory("仕事");
        todo.setPriority(1);
        todo.setDueDate(LocalDate.of(2026, 11, 2));
        todo.setCompleted(true);

        assertThat(mapper.update(todo)).isEqualTo(1);
        Todo found = mapper.findById(todo.getId());
        assertThat(found).usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt", "completedAt", "pinned").isEqualTo(todo);
        assertThat(found.getCompletedAt()).isNotNull();

        todo.setCompleted(false);
        assertThat(mapper.update(todo)).isEqualTo(1);
        assertThat(mapper.findById(todo.getId()).getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("論理削除後は通常検索から除き、ゴミ箱から復元できる")
    void softDeletesAndRestores() {
        Todo todo = insert(ownerId, "削除対象", "2026-11-01");

        assertThat(mapper.deleteById(ownerId, todo.getId())).isEqualTo(1);
        assertThat(mapper.deleteById(ownerId, todo.getId())).isZero();
        assertThat(mapper.findById(todo.getId())).isNull();
        assertThat(mapper.findIncludingDeletedById(todo.getId()).getUserId()).isEqualTo(ownerId);
        assertThat(mapper.count(ownerId, "", "", true)).isZero();
        assertThat(mapper.search(ownerId, "", "", "asc", null, null, true)).isEmpty();
        assertThat(mapper.searchForExport(ownerId, "", "", "asc", true)).isEmpty();
        assertThat(mapper.countTrash(ownerId, "削除", "資格")).isEqualTo(1);
        assertThat(mapper.searchTrashPage(ownerId, "削除", "資格", "asc", 10, 0L))
                .extracting(Todo::getId).containsExactly(todo.getId());
        assertThat(mapper.searchTrashPage(ownerId, "削除", "資格", "asc", 10, 0L).getFirst().getDeletedAt())
                .isNotNull();

        assertThat(mapper.restoreById(ownerId, todo.getId())).isEqualTo(1);
        assertThat(mapper.restoreById(ownerId, todo.getId())).isZero();
        assertThat(mapper.findById(todo.getId()).getDeletedAt()).isNull();
        assertThat(mapper.countTrash(ownerId, "", "")).isZero();
    }

    @Test
    @DisplayName("他ユーザーの更新・削除・復元・印切替では行を変更しない")
    void protectsForeignRowsInAllScopedWrites() {
        Todo todo = insert(otherOwnerId, "他ユーザーのタスク", "2026-11-01");
        Todo forged = sample(ownerId, "不正な更新", "2026-11-02");
        forged.setId(todo.getId());

        assertThat(mapper.update(forged)).isZero();
        assertThat(mapper.deleteById(ownerId, todo.getId())).isZero();
        assertThat(mapper.bulkDeleteByIds(ownerId, List.of(todo.getId()))).isZero();
        assertThat(mapper.togglePinned(ownerId, todo.getId())).isZero();
        assertThat(mapper.findById(todo.getId()).getTitle()).isEqualTo(todo.getTitle());
        assertThat(mapper.findById(todo.getId()).getPinned()).isFalse();
        assertThat(mapper.deleteById(otherOwnerId, todo.getId())).isEqualTo(1);
        assertThat(mapper.restoreById(ownerId, todo.getId())).isZero();
        assertThat(mapper.findById(todo.getId())).isNull();
    }

    @Test
    @DisplayName("所有者・キーワード・カテゴリ・完了状態・期間で絞り込める")
    void filtersSearchAndCount() {
        Todo expected = insert(ownerId, "試験の勉強", "2026-11-01");
        Todo completed = sample(ownerId, "試験の復習", "2026-11-02");
        completed.setCompleted(true);
        mapper.insert(completed);
        insert(otherOwnerId, "試験の勉強", "2026-11-01");
        insert(ownerId, "別のタスク", "2026-12-01");

        assertThat(mapper.search(ownerId, "試験", "資格", "asc", null, null, false))
                .extracting(Todo::getId).containsExactly(expected.getId());
        assertThat(mapper.count(ownerId, "試験", "資格", false)).isEqualTo(1);
        assertThat(mapper.count(ownerId, "試験", "資格", true)).isEqualTo(2);
        assertThat(mapper.count(null, "試験", "資格", true)).isEqualTo(3);
        assertThat(mapper.search(ownerId, "試験", "資格", "asc",
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), true))
                .extracting(Todo::getId).containsExactly(expected.getId());
        assertThat(mapper.search(ownerId, "試験", "仕事", "asc", null, null, true)).isEmpty();
    }

    @Test
    @DisplayName("一覧とCSVは印を優先し、期限順・ページ指定・IDの同順を守る")
    void ordersAndPaginatesDeterministically() {
        Todo early = insert(ownerId, "先の期限", "2026-11-01");
        Todo sameDate = insert(ownerId, "同じ期限", "2026-11-01");
        Todo late = insert(ownerId, "後の期限", "2026-11-02");
        Todo pinned = insert(ownerId, "印付き", "2026-11-03");
        assertThat(mapper.togglePinned(ownerId, pinned.getId())).isEqualTo(1);

        assertThat(mapper.searchPage(ownerId, "", "", "asc", true, 2, 0L))
                .extracting(Todo::getId).containsExactly(pinned.getId(), early.getId());
        assertThat(mapper.searchPage(ownerId, "", "", "asc", true, 2, 2L))
                .extracting(Todo::getId).containsExactly(sameDate.getId(), late.getId());
        assertThat(mapper.searchForExport(ownerId, "", "", "desc", true))
                .extracting(Todo::getId).containsExactly(pinned.getId(), late.getId(), early.getId(), sameDate.getId());
        assertThat(mapper.togglePinned(ownerId, pinned.getId())).isEqualTo(1);
        assertThat(mapper.findById(pinned.getId()).getPinned()).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @DisplayName("一括削除のIDリストが空でも全件削除しない")
    void emptyBulkSelectionDoesNotDeleteAnything(List<Long> ids) {
        Todo todo = insert(ownerId, "保持するタスク", "2026-11-01");

        assertThat(mapper.bulkDeleteByIds(null, ids)).isZero();
        assertThat(mapper.findById(todo.getId())).isNotNull();
    }

    @Test
    @DisplayName("一括削除は指定された有効なIDだけを削除する")
    void bulkDeletesOnlySelectedActiveRows() {
        Todo first = insert(ownerId, "対象1", "2026-11-01");
        Todo second = insert(otherOwnerId, "対象2", "2026-11-02");
        Todo retained = insert(ownerId, "選択外", "2026-11-03");

        assertThat(mapper.bulkDeleteByIds(null, List.of(first.getId(), second.getId(), first.getId())))
                .isEqualTo(2);
        assertThat(mapper.bulkDeleteByIds(null, List.of(first.getId(), second.getId()))).isZero();
        assertThat(mapper.findById(first.getId())).isNull();
        assertThat(mapper.findById(second.getId())).isNull();
        assertThat(mapper.findById(retained.getId())).isNotNull();
        assertThat(mapper.countTrash(ownerId, "", "")).isEqualTo(1);
        assertThat(mapper.countTrash(otherOwnerId, "", "")).isEqualTo(1);
    }

    @Test
    @DisplayName("削除済みToDoは更新・印切替の対象にしない")
    void doesNotUpdateOrPinDeletedRows() {
        Todo todo = insert(ownerId, "削除対象", "2026-11-01");
        mapper.deleteById(ownerId, todo.getId());

        assertThat(mapper.update(todo)).isZero();
        assertThat(mapper.togglePinned(ownerId, todo.getId())).isZero();
    }

    @Test
    @DisplayName("ユーザーの物理削除用処理は完了済み・ゴミ箱を含み他ユーザーを保持する")
    void physicallyDeletesAllRowsForOneOwner() {
        Todo active = insert(ownerId, "有効", "2026-11-01");
        Todo deleted = insert(ownerId, "ゴミ箱", "2026-11-02");
        mapper.deleteById(ownerId, deleted.getId());
        Todo completed = sample(ownerId, "完了済み", "2026-11-03");
        completed.setCompleted(true);
        mapper.insert(completed);
        Todo retained = insert(otherOwnerId, "別ユーザー", "2026-11-04");

        assertThat(mapper.deleteByUserId(ownerId)).isEqualTo(3);

        for (Todo removed : List.of(active, deleted, completed)) {
            assertThat(mapper.findIncludingDeletedById(removed.getId())).isNull();
        }
        assertThat(mapper.findById(retained.getId())).isNotNull();
    }

    private Long insertUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("unused");
        user.setRole("USER");
        users.insert(user);
        return users.findByUsername(username).getId();
    }

    private Todo insert(Long owner, String title, String dueDate) {
        Todo todo = sample(owner, title, dueDate);
        mapper.insert(todo);
        return todo;
    }

    private Todo sample(Long owner, String title, String dueDate) {
        Todo todo = new Todo();
        todo.setUserId(owner);
        todo.setTitle(title);
        todo.setDetail("テスト説明");
        todo.setCategory("資格");
        todo.setPriority(2);
        todo.setDueDate(LocalDate.parse(dueDate));
        return todo;
    }
}
