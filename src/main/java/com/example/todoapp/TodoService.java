package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import com.example.todoapp.exception.TodoNotFoundException;

/**
 * ToDoの検索・登録・更新・論理削除と、認証・所有者の確認を行うサービス。
 *
 * <p>一般ユーザーは自分のToDo、管理者は全ユーザーのToDoを操作できます。
 * 更新系処理は履歴と同一トランザクションで実行し、操作の試行は
 * {@link AuditService} で独立して記録します。作成通知はコミット後に処理されます。</p>
 *
 * <h2>使用例</h2>
 * <p>有効なユーザーで認証済みの実行コンテキストから呼び出します。</p>
 * <pre>{@code
 * Todo todo = new Todo();
 * todo.setTitle("買い物");
 * todo.setCategory("プログラミング");
 * todo.setPriority(2);
 * todoService.create(todo);
 * Long createdId = todo.getId();
 * }</pre>
 *
 * @author fw-todo contributors
 * @version 0.0.1
 * @since 0.0.1
 * @see HomeController
 * @see com.example.todoapp.api.TodoApiController
 * @see TodoMapper
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class TodoService {

    /** 一覧とゴミ箱に表示する1ページあたりの件数。 */
    public static final int PAGE_SIZE = 10;

    private final TodoMapper todoMapper;
    private final UserMapper userMapper;
    private final HistoryMapper historyMapper;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    /**
     * ToDo操作に必要な永続化・監査・イベント発行機能を受け取ります。
     *
     * @param todoMapper ToDoのMapper（{@code null}不可）
     * @param userMapper 認証ユーザーを取得するMapper（{@code null}不可）
     * @param historyMapper 成功履歴を保存するMapper（{@code null}不可）
     * @param auditService 操作の試行を記録するサービス（{@code null}不可）
     * @param events 作成イベントの発行先（{@code null}不可）
     */
    public TodoService(TodoMapper todoMapper, UserMapper userMapper,
            HistoryMapper historyMapper, AuditService auditService, ApplicationEventPublisher events) {
        this.todoMapper = todoMapper;
        this.userMapper = userMapper;
        this.historyMapper = historyMapper;
        this.auditService = auditService;
        this.events = events;
    }

    /**
     * 完了済みを含め、ゴミ箱以外のToDoを期限順に検索します。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外（{@code null}を含む）は昇順
     * @return 閲覧可能なToDoの一覧。該当なしの場合は空リスト
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     * @see #search(String, String, String, LocalDate, LocalDate)
     */
    public List<Todo> search(String keyword, String category, String order) {
        return search(keyword, category, order, null, null);
    }

    /**
     * 完了済みを含めるか指定し、ゴミ箱以外のToDoを期限順に検索します。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外（{@code null}を含む）は昇順
     * @param includeCompleted 完了済みも含める場合は{@code true}
     * @return 閲覧可能なToDoの一覧。該当なしの場合は空リスト
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    public List<Todo> search(String keyword, String category, String order, boolean includeCompleted) {
        return todoMapper.search(scopeUserId(), keyword, category, order, null, null, includeCompleted);
    }

    /**
     * 完了済みを含め、期限の範囲でゴミ箱以外のToDoを検索します。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外（{@code null}を含む）は昇順
     * @param from 期限の下限（当日を含む）。{@code null}で下限なし
     * @param to 期限の上限（当日を含む）。{@code null}で上限なし
     * @return 閲覧可能なToDoの一覧。該当なしの場合は空リスト
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    public List<Todo> search(String keyword, String category, String order, LocalDate from, LocalDate to) {
        return todoMapper.search(scopeUserId(), keyword, category, order, from, to, true);
    }

    /**
     * 検索条件に一致する、閲覧可能なゴミ箱以外のToDo件数を返します。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @param includeCompleted 完了済みも数える場合は{@code true}
     * @return 条件に一致した件数（0以上）
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    public long count(String keyword, String category, boolean includeCompleted) {
        return todoMapper.count(scopeUserId(), keyword, category, includeCompleted);
    }

    /**
     * ゴミ箱以外のToDoを印の付いたものから先に、期限順で1ページ取得します。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外（{@code null}を含む）は昇順
     * @param includeCompleted 完了済みも含める場合は{@code true}
     * @param page 1始まりのページ番号。呼び出し元で1以上に補正すること
     * @return 最大{@link #PAGE_SIZE}件のToDo。該当なしの場合は空リスト
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    public List<Todo> searchPage(String keyword, String category, String order, boolean includeCompleted, int page) {
        long offset = (page - 1L) * PAGE_SIZE;
        return todoMapper.searchPage(scopeUserId(), keyword, category, order, includeCompleted, PAGE_SIZE, offset);
    }

    /**
     * CSV用に、ページで分割せず印・期限順でゴミ箱以外のToDoを取得します。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外（{@code null}を含む）は昇順
     * @param includeCompleted 完了済みも含める場合は{@code true}
     * @return 閲覧可能な全該当ToDo。該当なしの場合は空リスト
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    public List<Todo> searchForExport(String keyword, String category, String order, boolean includeCompleted) {
        return todoMapper.searchForExport(scopeUserId(), keyword, category, order, includeCompleted);
    }

    /**
     * 所有者を確認して、ゴミ箱以外のToDoをIDで取得します。
     *
     * <p>他人のToDoはゴミ箱内でもアクセスを拒否します。存在しない場合や、
     * 閲覧権限のあるToDoがゴミ箱内の場合は{@code null}を返します。
     * このメソッド自身は{@link TodoNotFoundException}を投げません。</p>
     *
     * @param id 取得するToDoのID（{@code null}不可）
     * @return 対象のToDo。存在しないか、ゴミ箱内の場合は{@code null}
     * @throws AccessDeniedException 認証ユーザーが無効、または他人のToDoを一般ユーザーが参照した場合
     * @see #checkAccess(Long)
     */
    public Todo findById(Long id) {
        Long userId = scopeUserId();
        Todo todo = todoMapper.findById(id);
        checkOwner(todo, userId);
        if (todo == null) {
            checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        }
        return todo;
    }

    /**
     * ゴミ箱内も含め、指定ToDoの存在とアクセス権限を確認します。
     *
     * @param id 確認するToDoのID（{@code null}不可）
     * @throws TodoNotFoundException 対象が存在しない場合
     * @throws AccessDeniedException 認証ユーザーが無効、または他人のToDoを一般ユーザーが参照した場合
     */
    public void checkAccess(Long id) {
        Long userId = scopeUserId();
        Todo todo = todoMapper.findIncludingDeletedById(id);
        checkOwner(todo, userId);
        if (todo == null) {
            throw new TodoNotFoundException(id);
        }
    }

    /**
     * 検索条件に一致する、閲覧可能なゴミ箱内のToDo件数を返します。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @return 条件に一致した件数（0以上）
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    public long countTrash(String keyword, String category) {
        return todoMapper.countTrash(scopeUserId(), keyword, category);
    }

    /**
     * ゴミ箱内のToDoを印・期限順で1ページ取得します。完了済みも含みます。
     *
     * @param keyword タイトルの部分一致条件（{@code null}・空文字で指定なし）
     * @param category ジャンルの完全一致条件（{@code null}・空文字で指定なし）
     * @param order {@code "desc"}で期限の降順、それ以外（{@code null}を含む）は昇順
     * @param page 1始まりのページ番号。呼び出し元で1以上に補正すること
     * @return 最大{@link #PAGE_SIZE}件のToDo。該当なしの場合は空リスト
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    public List<Todo> searchTrashPage(String keyword, String category, String order, int page) {
        long offset = (page - 1L) * PAGE_SIZE;
        return todoMapper.searchTrashPage(scopeUserId(), keyword, category, order, PAGE_SIZE, offset);
    }

    /**
     * 認証ユーザーを所有者としてToDoを登録し、履歴保存と作成イベントの発行を行います。
     *
     * <p>入力値は呼び出し元で検証してください。渡された所有者IDは上書きし、
     * 採番されたIDを同じオブジェクトに設定します。作成日時はDBが設定します。
     * {@link TodoCreatedListener}による通知はトランザクション確定後に開始します。</p>
     *
     * @param todo 登録内容（{@code null}不可）。登録後にID・所有者IDが設定される
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     * @throws NullPointerException {@code todo}が{@code null}の場合
     */
    @Transactional(rollbackFor = Exception.class)
    @Auditable(action = AuditLog.Action.CREATE)
    public void create(Todo todo) {
        // 所有者はリクエストの値を信用せず、認証済みユーザーから設定する。
        todo.setUserId(currentUserId());
        // 監査は操作の試行を記録する。作成前なので対象IDはまだない。
        auditService.log("CREATE_ATTEMPT", null, todo.getUserId());
        todoMapper.insert(todo);
        historyMapper.insert(todo.getId(), todo.getUserId(), "CREATE");
        events.publishEvent(new TodoCreatedEvent(todo.getId(), todo.getUserId()));
        log.info("登録 id={}", todo.getId());
    }

    /**
     * 既存の所有者を保持してToDoを更新し、変更があれば履歴を保存します。
     *
     * <p>入力値は呼び出し元で検証してください。ゴミ箱内のToDoは更新せず、
     * 操作の試行だけを記録します。完了日時・通知済み状態の更新はMapperが行います。</p>
     *
     * @param todo 更新するIDと入力内容（{@code null}不可）。所有者IDは既存値で上書きされる
     * @throws TodoNotFoundException 指定IDのToDoが存在しない場合
     * @throws AccessDeniedException 認証ユーザーが無効、または他人のToDoを一般ユーザーが更新した場合
     * @throws NullPointerException {@code todo}が{@code null}の場合
     */
    @Transactional(rollbackFor = Exception.class)
    @Auditable(action = AuditLog.Action.UPDATE)
    public void update(Todo todo) {
        Long userId = scopeUserId();
        Todo existing = todoMapper.findIncludingDeletedById(todo.getId());
        checkOwner(existing, userId);
        if (existing == null) {
            throw new TodoNotFoundException(todo.getId());
        }
        // 管理者による編集でも、リクエスト値ではなく元の所有者を使用する。
        todo.setUserId(existing.getUserId());
        Long actorId = currentUserId();
        auditService.log("UPDATE_ATTEMPT", todo.getId(), actorId);
        if (todoMapper.update(todo) > 0) {
            historyMapper.insert(todo.getId(), actorId, "UPDATE");
        }
        log.info("編集 id={}", todo.getId());
    }

    /**
     * ToDoを論理削除し、ゴミ箱へ移動します。既にゴミ箱内なら変更しません。
     *
     * @param id 削除するToDoのID（{@code null}不可）
     * @throws TodoNotFoundException 対象が存在しない場合
     * @throws AccessDeniedException 認証ユーザーが無効、または他人のToDoを一般ユーザーが削除した場合
     * @see #restore(Long)
     */
    @Transactional(rollbackFor = Exception.class)
    @Auditable(action = AuditLog.Action.DELETE)
    public void delete(Long id) {
        Long userId = scopeUserId();
        Todo existing = todoMapper.findIncludingDeletedById(id);
        checkOwner(existing, userId);
        if (existing == null) {
            throw new TodoNotFoundException(id);
        }
        Long actorId = currentUserId();
        auditService.log("DELETE_ATTEMPT", id, actorId);
        if (todoMapper.deleteById(userId, id) > 0) {
            historyMapper.insert(id, actorId, "DELETE");
        }
        log.info("削除 id={}", id);
    }

    /**
     * 管理者が指定した複数のToDoを、重複を除いてまとめて論理削除します。
     *
     * <p>存在しないIDと既にゴミ箱内のIDは削除件数に含めません。
     * 履歴保存と一括削除は同一トランザクションで実行します。</p>
     *
     * @param ids 削除するID一覧。{@code null}または空なら変更せず0を返す
     * @return 実際にゴミ箱へ移動した件数
     * @throws IllegalArgumentException 一覧に{@code null}または0以下のIDが含まれる場合
     * @throws AccessDeniedException 管理者権限がない、または認証ユーザーが無効な場合
     */
    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasRole('ADMIN')")
    @Auditable(action = AuditLog.Action.BULK_DELETE)
    public int bulkDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("削除する項目の番号が正しくありません");
        }
        Long userId = scopeUserId();
        List<Long> distinctIds = ids.stream().distinct().toList();
        // サービスを直接呼び出す場合も、管理者だけが一括削除できる。
        for (Long id : distinctIds) {
            checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        }
        Long actorId = currentUserId();
        for (Long id : distinctIds) {
            auditService.log("BULK_DELETE_ATTEMPT", id, actorId);
        }
        // 削除前の有効な対象だけを記録する。後続のUPDATE失敗時には履歴も戻る。
        historyMapper.insertBulkDelete(userId, actorId, distinctIds);
        int deleted = todoMapper.bulkDeleteByIds(userId, distinctIds);
        log.info("一括削除 件数={}", deleted);
        return deleted;
    }

    /**
     * ゴミ箱内のToDoを復元し、復元できた場合に履歴を保存します。
     *
     * @param id 復元するToDoのID（{@code null}不可）
     * @return 復元した場合は{@code true}、対象なし・ゴミ箱外の場合は{@code false}
     * @throws AccessDeniedException 認証ユーザーが無効、または他人のToDoを一般ユーザーが復元した場合
     * @see #delete(Long)
     */
    @Transactional(rollbackFor = Exception.class)
    @Auditable(action = AuditLog.Action.RESTORE)
    public boolean restore(Long id) {
        Long userId = scopeUserId();
        checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        Long actorId = currentUserId();
        auditService.log("RESTORE_ATTEMPT", id, actorId);
        boolean restored = todoMapper.restoreById(userId, id) > 0;
        if (restored) {
            historyMapper.insert(id, actorId, "RESTORE");
            log.info("復元 id={}", id);
        }
        return restored;
    }

    /**
     * ゴミ箱以外のToDoの印を切り替え、変更できた場合に履歴を保存します。
     *
     * @param id 印を切り替えるToDoのID（{@code null}不可）
     * @return 切り替えた場合は{@code true}、対象なし・ゴミ箱内の場合は{@code false}
     * @throws AccessDeniedException 認証ユーザーが無効、または他人のToDoを一般ユーザーが変更した場合
     */
    @Transactional(rollbackFor = Exception.class)
    @Auditable(action = AuditLog.Action.TOGGLE_PIN)
    public boolean togglePinned(Long id) {
        Long userId = scopeUserId();
        checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        Long actorId = currentUserId();
        auditService.log("TOGGLE_PIN_ATTEMPT", id, actorId);
        boolean toggled = todoMapper.togglePinned(userId, id) > 0;
        if (toggled) {
            historyMapper.insert(id, actorId, "TOGGLE_PIN");
            log.info("印を切り替え id={}", id);
        }
        return toggled;
    }

    /**
     * 認証ユーザーを検査し、Mapperに渡す所有者の検索条件を求めます。
     *
     * @return 一般ユーザーのID。管理者の場合は全件を対象にするため{@code null}
     * @throws AccessDeniedException 未認証、または認証ユーザーが存在しない・無効な場合
     */
    private Long scopeUserId() {
        // 認証と有効状態を確認してから、管理者だけ所有者条件を外す。
        Long userId = currentUserId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? null : userId;
    }

    /**
     * 現在の認証名から、有効なアカウントのIDを取得します。
     *
     * @return 認証ユーザーのID（{@code null}にならない）
     * @throws AccessDeniedException 未認証、匿名認証、またはアカウントが存在しない・無効な場合
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("ログインが必要です");
        }
        User user = userMapper.findByUsername(authentication.getName());
        if (user == null || user.getId() == null || !user.isEnabled()) {
            throw new AccessDeniedException("ログインユーザーが見つかりません");
        }
        return user.getId();
    }

    /**
     * 存在するToDoに対する所有者制限を検査します。
     *
     * @param todo 検査対象。{@code null}の場合は存在確認を呼び出し元に任せる
     * @param userId 所有者の条件。{@code null}の場合は管理者として制限しない
     * @throws AccessDeniedException ToDoが存在し、指定ユーザーの所有物ではない場合
     */
    private void checkOwner(Todo todo, Long userId) {
        if (todo != null && userId != null && !userId.equals(todo.getUserId())) {
            throw new AccessDeniedException("このToDoにはアクセスできません");
        }
    }
}
