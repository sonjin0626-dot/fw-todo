package com.example.todoapp;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * ToDoの永続化データと、HTML画面の入力値を保持するモデル。
 *
 * <p>このプロジェクトでは教材のフォームクラスも兼ねます。
 * タイトルは必須・255文字以内、メモは255文字以内、ジャンルと優先度は必須です。
 * 検証はコントローラーの{@code @Valid}が付いた引数で実行されます。
 * 所有者IDは入力値を信用せず、{@link TodoService}が決定します。</p>
 *
 * @author fw-todo contributors
 * @version 0.0.1
 * @since 0.0.1
 * @see HomeController
 * @see TodoMapper
 * @see com.example.todoapp.api.TodoRequest
 */
@Data
public class Todo {

    /** ToDoのID。新規入力時は{@code null}で、登録時にDBが採番する。 */
    private Long id;
    /** 所有者のユーザーID。登録・更新時にサービスが設定する。 */
    private Long userId;
    private Long parentId;
    private Integer displayOrder;
    private Integer totalSubtaskCount;
    private Integer doneSubtaskCount;
    public long getSubtaskCount() { return totalSubtaskCount == null ? subtasks.size() : totalSubtaskCount; }
    private java.util.List<Todo> subtasks = new java.util.ArrayList<>();
    public boolean isSubtask() { return parentId != null; }
    public long getCompletedSubtaskCount() {
        return doneSubtaskCount == null ? subtasks.stream().filter(t -> Boolean.TRUE.equals(t.getCompleted())).count() : doneSubtaskCount;
    }
    public int getProgress() {
        return getSubtaskCount() == 0 ? 0 : (int) (getCompletedSubtaskCount() * 100 / getSubtaskCount());
    }

    /** 必須のタイトル（255文字以内）。 */
    @NotBlank(message = "{validation.titleRequired}")
    @Size(max = 255, message = "{validation.titleSize}")
    private String title;

    /** 任意のメモ（255文字以内、{@code null}可）。 */
    @Size(max = 255, message = "{validation.detailSize}")
    private String detail;

    /** 必須のジャンル。DBには翻訳前の値を保存する。 */
    @NotBlank(message = "{validation.categoryRequired}")
    private String category;

    /** 必須の優先度。画面では1が高、2が中、3が低を表す。 */
    @NotNull(message = "{typeMismatch.todo.priority}")
    private Integer priority;

    /** 任意の期限日。画面入力は{@code yyyy-MM-dd}形式、未設定は{@code null}。 */
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    /** 完了状態。{@code null}で登録すると未完了、更新すると既存の状態を保持する。 */
    private Boolean completed;
    /** 完了へ変更した日時。未完了では{@code null}。 */
    private LocalDateTime completedAt;
    /** 論理削除日時。ゴミ箱外では{@code null}。 */
    private LocalDateTime deletedAt;
    /** 一覧で先に表示するための印。{@code true}で印あり。 */
    private Boolean pinned;
    /** DBが設定する作成日時。新規入力時は{@code null}。 */
    private LocalDateTime createdAt;
    /** DBが管理する最終更新日時。新規入力時は{@code null}。 */
    private LocalDateTime updatedAt;

    /** 入力・DBマッピングに使う、全項目が未設定のToDoを作成します。 */
    public Todo() {
    }

    /**
     * システム既定タイムゾーンの今日を基準に、未完了で期限切れか判定します。
     *
     * @return 期限が今日より前かつ完了でなければ{@code true}。期限未設定は{@code false}
     */
    public boolean isOverdue() {
        return !Boolean.TRUE.equals(completed) && dueDate != null && dueDate.isBefore(LocalDate.now());
    }

    /**
     * システム既定タイムゾーンの今日から3日後までに期限が来るか判定します。
     *
     * @return 今日・3日後を含む期間が期限で、完了でなければ{@code true}。
     *         期限未設定や期限切れは{@code false}
     */
    public boolean isNearDeadline() {
        LocalDate today = LocalDate.now();
        return !Boolean.TRUE.equals(completed) && dueDate != null
                && !dueDate.isBefore(today) && !dueDate.isAfter(today.plusDays(3));
    }
}
