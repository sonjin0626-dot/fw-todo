package com.example.todoapp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:todo-controller-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "controller-user")
@Transactional
@DisplayName("HomeControllerのToDo画面統合テスト")
class TodoControllerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TodoMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private Long ownerId;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO users (username, password, role) VALUES ('controller-user', 'unused', 'USER')");
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'controller-user'", Long.class);
    }

    @AfterTransaction
    void cleanIndependentAuditRecords() {
        // REQUIRES_NEWの監査記録だけはテストのロールバック対象外。
        jdbc.update("DELETE FROM audit_logs WHERE actor_user_id = ?", ownerId);
    }

    @Test
    @DisplayName("一覧ページにログインユーザーのToDoとページ情報を表示する")
    void showsListPage() throws Exception {
        Todo todo = insertTodo();

        mvc.perform(get("/todos"))
                .andExpect(status().isOk())
                .andExpect(view().name("todos"))
                .andExpect(model().attribute("todos", hasItem(hasProperty("id", is(todo.getId())))))
                .andExpect(model().attribute("totalCount", 1L))
                .andExpect(model().attribute("page", 1))
                .andExpect(model().attribute("totalPages", 1));
    }

    @Test
    @DisplayName("新規作成フォームに空のToDoを用意する")
    void showsCreateForm() throws Exception {
        mvc.perform(get("/todos/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("create"))
                .andExpect(model().attribute("todo", hasProperty("id", nullValue())))
                .andExpect(model().hasNoErrors());
    }

    @Test
    @DisplayName("有効な入力を確認画面に表示し、確認段階では保存しない")
    void confirmsWithoutSaving() throws Exception {
        mvc.perform(post("/todos/confirm").with(csrf())
                .param("title", "テストタスク").param("detail", "テスト説明")
                .param("category", "資格").param("priority", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("create-confirm"))
                .andExpect(model().hasNoErrors())
                .andExpect(model().attribute("todo", hasProperty("title", is("テストタスク"))));

        assertThat(mapper.count(ownerId, "", "", true)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("空タイトルの入力検証エラーでは作成フォームを再表示する")
    void rejectsBlankTitleAtConfirmation(String title) throws Exception {
        mvc.perform(post("/todos/confirm").with(csrf())
                .param("title", title).param("category", "資格").param("priority", "2"))
                .andExpect(status().isOk())
                .andExpect(view().name("create"))
                .andExpect(model().attributeHasFieldErrors("todo", "title"));

        assertThat(mapper.count(ownerId, "", "", true)).isZero();
    }

    @Test
    @DisplayName("登録するとDBに保存され、一覧へリダイレクトする")
    void createsAndRedirects() throws Exception {
        mvc.perform(post("/todos").with(csrf())
                .param("title", "テストタスク").param("detail", "テスト説明")
                .param("category", "資格").param("priority", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/todos"))
                .andExpect(flash().attributeExists("message"));

        var saved = mapper.search(ownerId, "テストタスク", "資格", "asc", null, null, true);
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getId()).isNotNull();
        assertThat(saved.getFirst().getUserId()).isEqualTo(ownerId);
        assertThat(saved.getFirst().getDetail()).isEqualTo("テスト説明");
        assertThat(saved.getFirst().getCompleted()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM todo_history WHERE todo_id = ? AND action = 'CREATE'",
                Long.class, saved.getFirst().getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("既存ToDoを編集フォームに表示する")
    void showsExistingTodoOnEditForm() throws Exception {
        Todo todo = insertTodo();

        mvc.perform(get("/todos/{id}/edit", todo.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("edit"))
                .andExpect(model().attribute("todo", hasProperty("id", is(todo.getId()))))
                .andExpect(model().attribute("todo", hasProperty("title", is(todo.getTitle()))));
    }

    @Test
    @DisplayName("存在しないToDoの編集は404画面を表示する")
    void showsNotFoundPage() throws Exception {
        mvc.perform(get("/todos/{id}/edit", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @DisplayName("編集内容を保存して一覧へ戻る")
    void updatesAndRedirects() throws Exception {
        Todo todo = insertTodo();

        mvc.perform(post("/todos/{id}", todo.getId()).with(csrf())
                .param("title", "変更後のタスク").param("category", "資格").param("priority", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/todos"));

        assertThat(mapper.findById(todo.getId()).getTitle()).isEqualTo("変更後のタスク");
        assertThat(mapper.findById(todo.getId()).getPriority()).isEqualTo(1);
    }

    @Test
    @DisplayName("削除するとゴミ箱へ移し、一覧へ戻る")
    void deletesAndRedirects() throws Exception {
        Todo todo = insertTodo();

        mvc.perform(post("/todos/{id}/delete", todo.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/todos"));

        assertThat(mapper.findById(todo.getId())).isNull();
        assertThat(mapper.countTrash(ownerId, "", "")).isEqualTo(1);
    }

    @Test
    @DisplayName("CSRFトークンのない登録リクエストは保存せず拒否する")
    void rejectsCreateWithoutCsrf() throws Exception {
        mvc.perform(post("/todos").param("title", "不正な登録").param("category", "資格").param("priority", "2"))
                .andExpect(status().isForbidden());

        assertThat(mapper.count(ownerId, "", "", true)).isZero();
    }

    private Todo insertTodo() {
        Todo todo = new Todo();
        todo.setUserId(ownerId);
        todo.setTitle("既存タスク");
        todo.setCategory("資格");
        todo.setPriority(2);
        mapper.insert(todo);
        return todo;
    }
}
