package com.example.todoapp;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.todoapp.exception.BusinessException;
import com.example.todoapp.exception.TodoNotFoundException;

import jakarta.validation.Valid;

/**
 * トップ画面と、ToDoの一覧・入力・確認・編集・削除画面を提供します。
 *
 * <p>教材の{@code TodoController}に相当する既存コントローラーです。
 * フォームには{@link Todo}を使い、保存・所有者の検査は{@link TodoService}へ委譲します。
 * 戻り値はThymeleafのテンプレート名、または{@code redirect:}付きの遷移先です。</p>
 *
 * @author fw-todo contributors
 * @version 0.0.1
 * @since 0.0.1
 * @see TodoService
 * @see Todo
 * @see com.example.todoapp.api.TodoApiController
 */
@Controller
public class HomeController {
    private final MessageSource messages;

    /**
     * 現在の表示言語でメッセージを解決します。
     *
     * @param key メッセージキー（{@code null}不可）
     * @param args メッセージに埋め込む値
     * @return 翻訳・書式適用後のメッセージ
     * @throws org.springframework.context.NoSuchMessageException キーが見つからない場合
     */
    private String message(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }
    private final TodoService todoService;

    /**
     * ToDo操作と多言語メッセージの依存先を受け取ります。
     *
     * @param todoService ToDoサービス（{@code null}不可）
     * @param messages メッセージの取得元（{@code null}不可）
     */
    public HomeController(TodoService todoService, MessageSource messages) {
        this.messages = messages;
        this.todoService = todoService;
    }

    /**
     * 各画面のモデルへログインユーザー名を追加します。
     *
     * @param userDetails 認証ユーザー。未認証の場合は{@code null}
     * @return ユーザー名。未認証の場合は{@code null}
     */
    @ModelAttribute("username")
    public String username(@AuthenticationPrincipal UserDetails userDetails) {
        return userDetails == null ? null : userDetails.getUsername();
    }

    /** 自分のタスクを複製し、新しい詳細画面へ遷移します。 */
    @PostMapping({"/todos/{id}/duplicate", "/todo/{id}/duplicate"})
    public String duplicate(@PathVariable Long id,
            @RequestParam(required = false) Boolean includeSubtasks,
            @RequestParam(defaultValue = "false") boolean subtaskOption,
            RedirectAttributes ra) {
        Todo copy = todoService.duplicate(id, includeSubtasks != null ? includeSubtasks : !subtaskOption);
        ra.addFlashAttribute("message", message("todo.duplicated", copy.getTitle()));
        return "redirect:/todos/" + copy.getId();
    }

    /**
     * {@code GET /}でトップ画面を表示します。
     * @param model 画面タイトルの格納先
     * @return {@code "index"}
     */
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("title", message("app.title"));
        return "index";
    }

    /**
     * {@code GET /todos}で検索・ページ送り付きの一覧またはゴミ箱を表示します。
     *
     * @param keyword タイトルの部分一致条件（既定は空文字）
     * @param category ジャンルの完全一致条件（既定は空文字）
     * @param order {@code "desc"}なら期限の降順、それ以外は昇順
     * @param includeCompleted 完了済みも含めるか。ゴミ箱では常に含める
     * @param page 1始まりの希望ページ。存在する範囲へ補正する
     * @param trash {@code "1"}ならゴミ箱、それ以外は通常の一覧
     * @param model 一覧・検索条件・ページ情報の格納先（{@code null}不可）
     * @return {@code "todos"}
     * @throws org.springframework.security.access.AccessDeniedException 認証ユーザーが無効な場合
     */
    @GetMapping("/todos")
    public String todos(@RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "category", defaultValue = "") String category,
            @RequestParam(name = "order", defaultValue = "asc") String order,
            @RequestParam(name = "includeCompleted", defaultValue = "false") boolean includeCompleted,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "trash", defaultValue = "0") String trash, Model model) {
        boolean trashMode = "1".equals(trash);
        String sortOrder = "manual".equals(order) ? "manual" : ("desc".equals(order) ? "desc" : "asc");
        long totalCount = trashMode ? todoService.countTrash(keyword, category)
                : todoService.count(keyword, category, includeCompleted);
        int totalPages = (int) Math.max(1, (totalCount + TodoService.PAGE_SIZE - 1) / TodoService.PAGE_SIZE);
        int currentPage = Math.max(1, Math.min(page, totalPages));
        model.addAttribute("todos", trashMode ? todoService.searchTrashPage(keyword, category, sortOrder, currentPage)
                : todoService.searchPage(keyword, category, sortOrder, includeCompleted, currentPage));
        model.addAttribute("keyword", keyword);
        model.addAttribute("category", category);
        model.addAttribute("order", sortOrder);
        model.addAttribute("includeCompleted", trashMode || includeCompleted);
        model.addAttribute("trashMode", trashMode);
        model.addAttribute("page", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("statusUserId", todoService.currentUserId());
        if (!trashMode) {
            Todo quickTodo = new Todo();
            quickTodo.setId(0L);
            quickTodo.setUserId(todoService.currentUserId());
            quickTodo.setTitle("");
            quickTodo.setCategory("プログラミング");
            quickTodo.setPriority(2);
            quickTodo.setCompleted(false);
            quickTodo.setPinned(false);
            model.addAttribute("quickTodo", quickTodo);
        }
        return "todos";
    }

    /**
     * {@code GET /todos/new}で空の新規入力画面を表示します。
     *
     * @param model 新しいToDoの格納先（{@code null}不可）
     * @return {@code "create"}
     */
    @GetMapping("/todos/new")
    public String create(Model model) {
        model.addAttribute("todo", new Todo());
        return "create";
    }

    /**
     * {@code POST /todos/confirm}で新規入力を検証し、確認画面へ進みます。
     *
     * @param todo 検証対象のフォーム値（{@code null}不可）
     * @param bindingResult 入力検証の結果（{@code null}不可）
     * @return 正常時は{@code "create-confirm"}、入力エラー時は{@code "create"}
     */
    @PostMapping("/todos/confirm")
    public String confirm(@Valid @ModelAttribute("todo") Todo todo, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "create";
        }
        return "create-confirm";
    }

    /**
     * {@code POST /todos/new}で確認画面から入力値を保持して新規入力へ戻ります。
     *
     * @param todo 再表示するフォーム値（{@code null}不可）
     * @return {@code "create"}
     */
    @PostMapping("/todos/new")
    public String rewrite(@ModelAttribute("todo") Todo todo) {
        return "create";
    }

    /**
     * {@code POST /todos}でToDoを登録し、一覧へリダイレクトします。
     *
     * @param todo 登録するフォーム値（{@code null}不可）。このメソッドでは再検証しない
     * @param redirectAttributes 登録結果のフラッシュメッセージ格納先（{@code null}不可）
     * @return {@code "redirect:/todos"}
     * @throws org.springframework.security.access.AccessDeniedException 認証ユーザーが無効な場合
     */
    @PostMapping("/todos")
    public String store(@ModelAttribute("todo") Todo todo, RedirectAttributes redirectAttributes) {
        todoService.create(todo);
        redirectAttributes.addFlashAttribute("message", message("flash.created"));
        return "redirect:/todos";
    }

    /**
     * {@code GET /todos/{id}/edit}で既存のToDoを編集画面へ読み込みます。
     *
     * @param id 編集するToDoのID（{@code null}不可）
     * @param model ToDoとIDの格納先（{@code null}不可）
     * @param redirectAttributes リダイレクト用の属性（この処理では使用しない）
     * @return {@code "edit"}
     * @throws TodoNotFoundException 対象が存在しない、またはゴミ箱内の場合
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @GetMapping("/todos/{id}/edit")
    public String edit(@PathVariable("id") Long id, Model model, RedirectAttributes redirectAttributes) {
        Todo todo = todoService.findById(id);
        if (todo == null) {
            throw new TodoNotFoundException(id);
        }
        model.addAttribute("todo", todo);
        model.addAttribute("id", id);
        return "edit";
    }

    /**
     * {@code POST /todos/{id}/confirm}で権限と編集内容を検証し、確認画面へ進みます。
     *
     * @param id 編集するToDoのID（{@code null}不可）
     * @param todo 検証対象のフォーム値（{@code null}不可）
     * @param bindingResult 入力検証の結果（{@code null}不可）
     * @param model IDの格納先（{@code null}不可）
     * @return 正常時は{@code "edit-confirm"}、入力エラー時は{@code "edit"}
     * @throws TodoNotFoundException 対象が存在しない場合
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @PostMapping("/todos/{id}/confirm")
    public String editConfirm(@PathVariable("id") Long id, @Valid @ModelAttribute("todo") Todo todo,
            BindingResult bindingResult, Model model) {
        todoService.checkAccess(id);
        model.addAttribute("id", id);
        if (bindingResult.hasErrors()) {
            return "edit";
        }
        return "edit-confirm";
    }

    /**
     * {@code POST /todos/{id}/edit}で入力値を保持して編集画面へ戻ります。
     *
     * @param id 編集するToDoのID（{@code null}不可）
     * @param todo 再表示するフォーム値（{@code null}不可）
     * @param model IDの格納先（{@code null}不可）
     * @return {@code "edit"}
     * @throws TodoNotFoundException 対象が存在しない場合
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @PostMapping("/todos/{id}/edit")
    public String editRewrite(@PathVariable("id") Long id, @ModelAttribute("todo") Todo todo, Model model) {
        todoService.checkAccess(id);
        model.addAttribute("id", id);
        return "edit";
    }

    /**
     * {@code POST /todos/{id}}でToDoを更新し、一覧へリダイレクトします。
     *
     * @param id 更新するToDoのID。フォーム側のIDより優先する（{@code null}不可）
     * @param todo 更新するフォーム値（{@code null}不可）。このメソッドでは再検証しない
     * @param redirectAttributes 保存結果のフラッシュメッセージ格納先（{@code null}不可）
     * @return {@code "redirect:/todos"}
     * @throws TodoNotFoundException 対象が存在しない場合
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @PostMapping("/todos/{id}")
    public String update(@PathVariable("id") Long id, @ModelAttribute("todo") Todo todo,
            RedirectAttributes redirectAttributes) {
        todo.setId(id);
        todoService.update(todo);
        redirectAttributes.addFlashAttribute("message", message("flash.saved"));
        return "redirect:/todos";
    }

    /**
     * {@code GET /todos/{id}/delete}で削除確認画面を表示します。
     *
     * @param id 削除対象のToDoのID（{@code null}不可）
     * @param model ToDoとIDの格納先（{@code null}不可）
     * @param redirectAttributes リダイレクト用の属性（この処理では使用しない）
     * @return {@code "delete"}
     * @throws TodoNotFoundException 対象が存在しない、またはゴミ箱内の場合
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @GetMapping("/todos/{id}/delete")
    public String deleteConfirm(@PathVariable("id") Long id, Model model, RedirectAttributes redirectAttributes) {
        Todo todo = todoService.findById(id);
        if (todo == null) {
            throw new TodoNotFoundException(id);
        }
        model.addAttribute("todo", todo);
        model.addAttribute("id", id);
        return "delete";
    }

    /**
     * {@code POST /todos/{id}/delete}でToDoをゴミ箱へ移します。
     *
     * @param id 削除するToDoのID（{@code null}不可）
     * @param redirectAttributes 削除結果のフラッシュメッセージ格納先（{@code null}不可）
     * @return {@code "redirect:/todos"}
     * @throws TodoNotFoundException 対象が存在しない場合
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @PostMapping("/todos/{id}/delete")
    public String delete(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        todoService.delete(id);
        redirectAttributes.addFlashAttribute("message", message("flash.deleted"));
        return "redirect:/todos";
    }

    /** 選択した自分のタスクの状態を一括変更し、検索条件を保って一覧へ戻します。 */
    @PostMapping({"/todos/bulk-status", "/todo/bulk-status"})
    public String bulkUpdateStatus(@RequestParam(name = "selectedIds", required = false) List<Long> ids,
            @RequestParam boolean newStatus,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "asc") String order,
            @RequestParam(defaultValue = "false") boolean includeCompleted,
            @RequestParam(defaultValue = "1") int page, RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("error", message("bulk.status.empty"));
        } else {
            int count = todoService.bulkUpdateStatus(ids, newStatus);
            ra.addFlashAttribute("message", message("bulk.status.updated", count,
                    message(newStatus ? "todo.completed" : "todo.incomplete")));
        }
        ra.addAttribute("keyword", keyword);
        ra.addAttribute("category", category);
        ra.addAttribute("order", "manual".equals(order) ? "manual" : ("desc".equals(order) ? "desc" : "asc"));
        ra.addAttribute("includeCompleted", includeCompleted);
        ra.addAttribute("page", Math.max(1, page));
        return "redirect:/todos";
    }

    /**
     * {@code POST /todos/bulk-delete}で管理者が選択したToDoをまとめてゴミ箱へ移します。
     *
     * @param ids 選択したID。{@code null}・空の場合は選択を促すメッセージを表示する
     * @param keyword 一覧へ引き継ぐキーワード
     * @param category 一覧へ引き継ぐジャンル
     * @param order 一覧へ引き継ぐ並び順（{@code "desc"}以外は{@code "asc"}へ補正）
     * @param includeCompleted 一覧へ引き継ぐ完了済み表示の設定
     * @param page 一覧へ引き継ぐページ（1以上へ補正）
     * @param redirectAttributes 結果と検索条件の格納先（{@code null}不可）
     * @return {@code "redirect:/todos"}
     * @throws BusinessException 選択したIDに{@code null}または0以下の値が含まれる場合
     * @throws org.springframework.security.access.AccessDeniedException 管理者権限がない、または認証ユーザーが無効な場合
     */
    @PostMapping("/todos/bulk-delete")
    @PreAuthorize("hasRole('ADMIN')")
    public String bulkDelete(@RequestParam(name = "ids", required = false) List<Long> ids,
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "category", defaultValue = "") String category,
            @RequestParam(name = "order", defaultValue = "asc") String order,
            @RequestParam(name = "includeCompleted", defaultValue = "false") boolean includeCompleted,
            @RequestParam(name = "page", defaultValue = "1") int page, RedirectAttributes redirectAttributes) {
        int deleted;
        try {
            deleted = todoService.bulkDelete(ids);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("INVALID_TODO_ID", "削除する項目の番号が正しくありません");
        }
        redirectAttributes.addFlashAttribute("message", ids == null || ids.isEmpty()
                ? message("flash.selectItems") : message("flash.bulkDeleted", deleted));
        redirectAttributes.addAttribute("keyword", keyword);
        redirectAttributes.addAttribute("category", category);
        redirectAttributes.addAttribute("order", "manual".equals(order) ? "manual" : ("desc".equals(order) ? "desc" : "asc"));
        redirectAttributes.addAttribute("includeCompleted", includeCompleted);
        redirectAttributes.addAttribute("page", Math.max(1, page));
        return "redirect:/todos";
    }

    /**
     * {@code POST /todos/{id}/restore}でToDoを復元し、ゴミ箱の一覧へ戻ります。
     *
     * @param id 復元するToDoのID（{@code null}不可）
     * @param redirectAttributes 復元結果のフラッシュメッセージ格納先（{@code null}不可）
     * @return {@code "redirect:/todos?trash=1"}。対象なしでも結果を表示して遷移する
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @PostMapping("/todos/{id}/restore")
    public String restore(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        boolean restored = todoService.restore(id);
        redirectAttributes.addFlashAttribute("message", restored ? message("flash.restored") : message("flash.notFound"));
        return "redirect:/todos?trash=1";
    }

    /**
     * {@code POST /todos/{id}/pin}で印を切り替え、検索条件を保持して一覧へ戻ります。
     *
     * @param id 印を切り替えるToDoのID（{@code null}不可）
     * @param keyword 一覧へ引き継ぐキーワード
     * @param category 一覧へ引き継ぐジャンル
     * @param order 一覧へ引き継ぐ並び順（{@code "desc"}以外は{@code "asc"}へ補正）
     * @param includeCompleted 一覧へ引き継ぐ完了済み表示の設定
     * @param page 一覧へ引き継ぐページ（1以上へ補正）
     * @param redirectAttributes 結果と検索条件の格納先（{@code null}不可）
     * @return {@code "redirect:/todos"}。対象なしでも結果を表示して遷移する
     * @throws org.springframework.security.access.AccessDeniedException 認証・所有者の検査に失敗した場合
     */
    @PostMapping("/todos/{id}/pin")
    public String togglePinned(@PathVariable("id") Long id,
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "category", defaultValue = "") String category,
            @RequestParam(name = "order", defaultValue = "asc") String order,
            @RequestParam(name = "includeCompleted", defaultValue = "false") boolean includeCompleted,
            @RequestParam(name = "page", defaultValue = "1") int page, RedirectAttributes redirectAttributes) {
        boolean toggled = todoService.togglePinned(id);
        redirectAttributes.addFlashAttribute("message", toggled ? message("flash.pinned") : message("flash.notFound"));
        redirectAttributes.addAttribute("keyword", keyword);
        redirectAttributes.addAttribute("category", category);
        redirectAttributes.addAttribute("order", "manual".equals(order) ? "manual" : ("desc".equals(order) ? "desc" : "asc"));
        redirectAttributes.addAttribute("includeCompleted", includeCompleted);
        redirectAttributes.addAttribute("page", Math.max(1, page));
        return "redirect:/todos";
    }
}
