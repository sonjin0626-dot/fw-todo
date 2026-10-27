package com.example.todoapp.mcp;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.example.todoapp.Todo;
import com.example.todoapp.TodoService;
import com.example.todoapp.api.HolidayClient;
import com.example.todoapp.api.TodoDto;

@Component
public class TodoTools {

    private final TodoService todoService;
    private final HolidayClient holidayClient;

    public TodoTools(TodoService todoService, HolidayClient holidayClient) {
        this.todoService = todoService;
        this.holidayClient = holidayClient;
    }

    @McpTool(name = "list_todos", description = "やることの一覧を返す（期間・ジャンルで絞れる）")
    public List<TodoDto> listTodos(
            @McpToolParam(required = false, description = "検索キーワード") String keyword,
            @McpToolParam(required = false, description = "ジャンル") String category,
            @McpToolParam(required = false, description = "期間の開始日（yyyy-MM-dd）") String from,
            @McpToolParam(required = false, description = "期間の終了日（yyyy-MM-dd）") String to) {
        LocalDate fromDate = from == null ? null : LocalDate.parse(from);
        LocalDate toDate = to == null ? null : LocalDate.parse(to);
        return todoService.search(keyword, category, "asc", fromDate, toDate).stream()
                .map(TodoDto::from)
                .toList();
    }

    @McpTool(name = "get_todo", description = "やることを1件返す")
    public TodoDto getTodo(
            @McpToolParam(required = true, description = "やることのID") Long id) {
        Todo todo = todoService.findById(id);
        if (todo == null) {
            throw new IllegalArgumentException("指定されたやることが見つかりません: " + id);
        }
        return TodoDto.from(todo);
    }

    @McpTool(name = "add_todo", description = "やることを1件足す")
    public TodoDto addTodo(
            @McpToolParam(required = true, description = "やること（255文字以内）") String title,
            @McpToolParam(required = true, description = "ジャンル（デザイン・マーケティング・プログラミング・資格・就職活動）") String category,
            @McpToolParam(required = true, description = "優先度（1〜3）") Integer priority,
            @McpToolParam(required = false, description = "メモ（255文字以内）") String detail,
            @McpToolParam(required = false, description = "期限（yyyy-MM-dd）") String dueDate) {
        validateTitle(title);
        validateCategory(category);
        validatePriority(priority);
        validateDetail(detail);

        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setCategory(category);
        todo.setPriority(priority);
        todo.setDetail(detail);
        todo.setDueDate(dueDate == null ? null : LocalDate.parse(dueDate));
        todo.setCompleted(false);
        todoService.create(todo);
        return TodoDto.from(todoService.findById(todo.getId()));
    }

    @McpTool(name = "update_todo", description = "やることを1件直す（期限を変えるのもこれ）")
    public TodoDto updateTodo(
            @McpToolParam(required = true, description = "やることのID") Long id,
            @McpToolParam(required = false, description = "やること（255文字以内。省略時は変更しない）") String title,
            @McpToolParam(required = false, description = "ジャンル（デザイン・マーケティング・プログラミング・資格・就職活動。省略時は変更しない）") String category,
            @McpToolParam(required = false, description = "優先度（1〜3。省略時は変更しない）") Integer priority,
            @McpToolParam(required = false, description = "メモ（255文字以内。省略時は変更しない）") String detail,
            @McpToolParam(required = false, description = "期限（yyyy-MM-dd。省略時は変更しない）") String dueDate,
            @McpToolParam(required = false, description = "完了状態（true：完了、false：未完了。省略時は変更しない）") Boolean completed) {
        Todo todo = todoService.findById(id);
        if (todo == null) {
            throw new IllegalArgumentException("指定されたやることが見つかりません: " + id);
        }
        if (title != null) {
            validateTitle(title);
            todo.setTitle(title);
        }
        if (category != null) {
            validateCategory(category);
            todo.setCategory(category);
        }
        if (priority != null) {
            validatePriority(priority);
            todo.setPriority(priority);
        }
        if (detail != null) {
            validateDetail(detail);
            todo.setDetail(detail);
        }
        if (dueDate != null) {
            todo.setDueDate(LocalDate.parse(dueDate));
        }
        if (completed != null) {
            todo.setCompleted(completed);
        }
        todoService.update(todo);
        return TodoDto.from(todoService.findById(id));
    }

    @McpTool(name = "complete_todo", description = "やることを完了にする")
    public TodoDto completeTodo(
            @McpToolParam(required = true, description = "やることのID") Long id) {
        Todo todo = todoService.findById(id);
        if (todo == null) {
            throw new IllegalArgumentException("指定されたやることが見つかりません: " + id);
        }
        todo.setCompleted(true);
        todoService.update(todo);
        return TodoDto.from(todoService.findById(id));
    }

    @McpTool(name = "delete_todo", description = "やることを1件消す")
    public String deleteTodo(
            @McpToolParam(required = true, description = "やることのID") Long id) {
        if (todoService.findById(id) == null) {
            throw new IllegalArgumentException("指定されたやることが見つかりません: " + id);
        }
        todoService.delete(id);
        return "やることを削除しました: " + id;
    }

    @McpTool(name = "find_free_days", description = "期間の中で、期限のやることが無く、土日でも祝日でもない「空いている日」を返す。やることの期限を動かす先を決めるのに使う")
    public List<LocalDate> findFreeDays(
            @McpToolParam(required = true, description = "期間の開始日（yyyy-MM-dd）") String from,
            @McpToolParam(required = true, description = "期間の終了日（yyyy-MM-dd）") String to) {
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("開始日は終了日以前にしてください");
        }

        HolidayClient.FetchResult result = holidayClient.fetchHolidays();
        if (result.unavailable()) {
            throw new IllegalStateException("祝日を取得できないため、空いている日を確認できません。時間をおいて再実行してください");
        }
        Set<String> holidays = result.holidays().keySet();
        Set<LocalDate> busyDays = todoService.search(null, null, "asc", fromDate, toDate).stream()
                .map(Todo::getDueDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<LocalDate> freeDays = new ArrayList<>();
        for (LocalDate day = fromDate; !day.isAfter(toDate); day = day.plusDays(1)) {
            if (busyDays.contains(day)) {
                continue;
            }
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            if (holidays.contains(day.toString())) {
                continue;
            }
            freeDays.add(day);
        }
        return freeDays;
    }

    private void validateTitle(String title) {
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new IllegalArgumentException("やることは空白以外の255文字以内で入力してください");
        }
    }

    private void validateCategory(String category) {
        if (category == null || !Set.of("デザイン", "マーケティング", "プログラミング", "資格", "就職活動").contains(category)) {
            throw new IllegalArgumentException("ジャンルはデザイン・マーケティング・プログラミング・資格・就職活動から選んでください");
        }
    }

    private void validatePriority(Integer priority) {
        if (priority == null || priority < 1 || priority > 3) {
            throw new IllegalArgumentException("優先度は1〜3で入力してください");
        }
    }

    private void validateDetail(String detail) {
        if (detail != null && detail.length() > 255) {
            throw new IllegalArgumentException("メモは255文字以内で入力してください");
        }
    }
}
