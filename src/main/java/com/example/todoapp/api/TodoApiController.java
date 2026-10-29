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

/**
 * ToDoのJSON APIとCSVダウンロードを提供するコントローラー。
 *
 * <p>認証・所有者の制限を{@link TodoService}と共有します。
 * 入力には{@link TodoRequest}、JSON応答には{@link TodoDto}を使用します。</p>
 *
 * @author fw-todo contributors
 * @version 0.0.1
 * @since 0.0.1
 * @see TodoService
 * @see TodoRequest
 * @see RestExceptionHandler
 */
@RestController
public class TodoApiController {

    private final TodoService todoService;

    /**
     * APIで使用するToDoサービスを受け取ります。
     *
     * @param todoService ToDoサービス（{@code null}不可）
     */
    public TodoApiController(TodoService todoService) {
        this.todoService = todoService;
    }

    /**
     * {@code GET /api/todos}で完了済みを含むToDo一覧をJSONで返します。ゴミ箱は除外します。
     *
     * @param keyword タイトルの部分一致条件（空文字で指定なし）
     * @param category ジャンルの完全一致条件（空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外は昇順
     * @param from 期限の下限（当日を含む）。{@code null}で下限なし
     * @param to 期限の上限（当日を含む）。{@code null}で上限なし
     * @return 閲覧可能なToDoの配列（HTTP 200）。該当なしなら空配列
     * @throws org.springframework.security.access.AccessDeniedException 認証ユーザーが無効な場合
     */
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

    /**
     * {@code GET /api/todos/{id}}でゴミ箱以外のToDoを1件取得します。
     *
     * @param id 取得するToDoのID（{@code null}不可）
     * @return {@link TodoDto}を本文に持つHTTP 200の応答
     * @throws TodoNotFoundException 対象が存在しない、またはゴミ箱内の場合（HTTP 404）
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @GetMapping("/api/todos/{id}")
    public ResponseEntity<?> getById(@PathVariable("id") Long id) {
        Todo todo = todoService.findById(id);
        if (todo == null) {
            throw new TodoNotFoundException(id);
        }
        return ResponseEntity.ok(TodoDto.from(todo));
    }

    /**
     * {@code GET /api/todos.csv}で検索結果をUTF-8・BOM付きのCSVとしてダウンロードします。
     *
     * <p>ページで分割せず印・期限順で出力し、ゴミ箱は除外します。
     * ヘッダー・状態・優先度のラベルは日本語です。</p>
     *
     * @param keyword タイトルの部分一致条件（空文字で指定なし）
     * @param category ジャンルの完全一致条件（空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外は昇順
     * @param includeCompleted 完了済みも含める場合は{@code true}
     * @return ファイル名{@code todos.csv}、HTTP 200のダウンロード応答
     * @throws org.springframework.security.access.AccessDeniedException 認証ユーザーが無効な場合
     */
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

    /**
     * CSVの1行を追加します。引用符をエスケープし、数式としての解釈を抑制します。
     *
     * @param csv 出力先（{@code null}不可）
     * @param values 各列の値。要素の{@code null}は空文字として出力する
     */
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

    /**
     * {@code POST /api/todos}でToDoを作成します。入力は呼び出し前に検証されます。
     *
     * @param request 検証済みの作成内容（{@code null}不可）
     * @return 作成済みToDoと、そのURLを示す{@code Location}ヘッダー付きのHTTP 201応答
     * @throws org.springframework.security.access.AccessDeniedException 認証ユーザーが無効な場合
     */
    @PostMapping("/api/todos")
    public ResponseEntity<TodoDto> create(@Valid @RequestBody TodoRequest request) {
        Todo todo = request.toTodo();
        todoService.create(todo);
        Todo created = todoService.findById(todo.getId());
        return ResponseEntity.created(URI.create("/api/todos/" + todo.getId()))
                .body(TodoDto.from(created));
    }

    /**
     * {@code PUT /api/todos/{id}}でゴミ箱以外のToDoを更新します。
     *
     * @param id 更新するToDoのID（{@code null}不可）
     * @param request 検証済みの更新内容（{@code null}不可）
     * @return 更新後の{@link TodoDto}を本文に持つHTTP 200の応答
     * @throws TodoNotFoundException 対象が存在しない、またはゴミ箱内の場合（HTTP 404）
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
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

    /**
     * {@code DELETE /api/todos/{id}}でToDoを論理削除し、ゴミ箱へ移します。
     *
     * @param id 削除するToDoのID（{@code null}不可）
     * @return 本文のないHTTP 204の応答
     * @throws TodoNotFoundException 対象が存在しない、または既にゴミ箱内の場合（HTTP 404）
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
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
