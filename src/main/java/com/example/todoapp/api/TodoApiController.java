package com.example.todoapp.api;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.todoapp.Todo;
import com.example.todoapp.TodoService;
import com.example.todoapp.exception.TodoNotFoundException;

import jakarta.validation.Valid;

@RestController
public class TodoApiController {

    private final TodoService todoService;

    public TodoApiController(TodoService todoService) {
        this.todoService = todoService;
    }

    @GetMapping(value = "/api/todos", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<TodoDto> list(
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "category", defaultValue = "") String category,
            @RequestParam(name = "order", defaultValue = "asc") String order,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return todoService.search(keyword, category, order, from, to).stream()
                .map(TodoDto::from)
                .toList();
    }

    @GetMapping("/api/todos/{id}")
    public ResponseEntity<?> getById(@PathVariable("id") Long id) {
        Todo todo = todoService.findById(id);
        if (todo == null) {
            throw new TodoNotFoundException(id);
        }
        return ResponseEntity.ok(TodoDto.from(todo));
    }

    @GetMapping(value = "/api/todos.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "category", defaultValue = "") String category,
            @RequestParam(name = "order", defaultValue = "asc") String order,
            @RequestParam(name = "includeCompleted", defaultValue = "false") boolean includeCompleted) {
        String sortOrder = "desc".equals(order) ? "desc" : "asc";
        List<Todo> todos = todoService.searchForExport(keyword, category, sortOrder, includeCompleted);
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, "印", "やること", "ジャンル", "優先度", "期限", "状態", "メモ");
        for (Todo todo : todos) {
            String status = Boolean.TRUE.equals(todo.getCompleted()) ? "完了" : "未完了";
            if (Boolean.TRUE.equals(todo.getCompleted()) && todo.getCompletedAt() != null) {
                status += "（" + todo.getCompletedAt().format(DateTimeFormatter.ISO_LOCAL_DATE) + "）";
            }
            Integer priority = todo.getPriority();
            appendCsvRow(csv,
                    Boolean.TRUE.equals(todo.getPinned()) ? "あり" : "なし",
                    todo.getTitle(), todo.getCategory(),
                    Integer.valueOf(1).equals(priority) ? "高" : (Integer.valueOf(2).equals(priority) ? "中" : "低"),
                    todo.getDueDate() == null ? "" : todo.getDueDate().toString(), status, todo.getDetail());
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("todos.csv").build().toString())
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void appendCsvRow(StringBuilder csv, String... values) {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                csv.append(',');
            }
            String value = values[index] == null ? "" : values[index];
            int start = 0;
            while (start < value.length()) {
                int codePoint = value.codePointAt(start);
                if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                        && !Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT) {
                    break;
                }
                start += Character.charCount(codePoint);
            }
            boolean formula = start < value.length() && "=+-@＝＋－＠".indexOf(value.charAt(start)) >= 0;
            boolean leadingControl = !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0;
            if (formula || leadingControl) {
                // The apostrophe marks text; the tab adds protection for Excel CSV handling.
                value = "'\t" + value;
            }
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    @PostMapping("/api/todos")
    public ResponseEntity<TodoDto> create(@Valid @RequestBody TodoRequest request) {
        Todo todo = request.toTodo();
        todoService.create(todo);
        Todo created = todoService.findById(todo.getId());
        return ResponseEntity.created(URI.create("/api/todos/" + todo.getId()))
                .body(TodoDto.from(created));
    }

    @PutMapping("/api/todos/{id}")
    public ResponseEntity<?> update(@PathVariable("id") Long id,
            @Valid @RequestBody TodoRequest request) {
        Todo existing = todoService.findById(id);
        if (existing == null) {
            throw new TodoNotFoundException(id);
        }
        Todo todo = request.toTodo();
        todo.setId(id);
        todoService.update(todo);
        return ResponseEntity.ok(TodoDto.from(todoService.findById(id)));
    }

    @DeleteMapping("/api/todos/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") Long id) {
        Todo existing = todoService.findById(id);
        if (existing == null) {
            throw new TodoNotFoundException(id);
        }
        todoService.delete(id);
        return ResponseEntity.noContent().build();
    }

}
