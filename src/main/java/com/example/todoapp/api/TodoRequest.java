package com.example.todoapp.api;

import java.time.LocalDate;

import com.example.todoapp.Todo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * REST APIのToDo作成・更新に使う入力モデル。
 *
 * <p>タイトルは必須・255文字以内、メモは255文字以内、ジャンルと優先度は必須です。
 * {@link TodoApiController}で入力を検証してから{@link #toTodo()}で変換します。
 * ID・所有者ID・監査日時は入力項目に含めません。</p>
 *
 * @author fw-todo contributors
 * @version 0.0.1
 * @since 0.0.1
 * @see Todo
 * @see TodoApiController
 */
@Data
public class TodoRequest {

    /** 必須のタイトル（255文字以内）。 */
    @NotBlank(message = "{validation.titleRequired}")
    @Size(max = 255, message = "{validation.titleSize}")
    private String title;

    /** 任意のメモ（255文字以内、{@code null}可）。 */
    @Size(max = 255, message = "{validation.detailSize}")
    private String detail;

    /** 必須のジャンル。DBに保存する翻訳前の値。 */
    @NotBlank(message = "{validation.categoryRequired}")
    private String category;

    /** 必須の優先度。表示上は1が高、2が中、3が低。 */
    @NotNull(message = "{typeMismatch.todo.priority}")
    private Integer priority;

    /** 期限日。JSONでは{@code yyyy-MM-dd}形式、{@code null}で期限なし。 */
    private LocalDate dueDate;
    /** 完了状態。{@code null}なら作成時は未完了、更新時は既存の状態を保持する。 */
    private Boolean completed;

    /**
     * 入力項目を新しいToDoへコピーします。このメソッド自体は検証・保存を行いません。
     *
     * @return ID・所有者ID・各日時が未設定で、入力値が設定されたToDo
     */
    public Todo toTodo() {
        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setDetail(detail);
        todo.setCategory(category);
        todo.setPriority(priority);
        todo.setDueDate(dueDate);
        todo.setCompleted(completed);
        return todo;
    }
}
