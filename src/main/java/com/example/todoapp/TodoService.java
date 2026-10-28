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

@Slf4j
@Service
@Transactional(readOnly = true)
public class TodoService {

    public static final int PAGE_SIZE = 10;

    private final TodoMapper todoMapper;
    private final UserMapper userMapper;
    private final HistoryMapper historyMapper;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;

    public TodoService(TodoMapper todoMapper, UserMapper userMapper,
            HistoryMapper historyMapper, AuditService auditService, ApplicationEventPublisher events) {
        this.todoMapper = todoMapper;
        this.userMapper = userMapper;
        this.historyMapper = historyMapper;
        this.auditService = auditService;
        this.events = events;
    }

    public List<Todo> search(String keyword, String category, String order) {
        return search(keyword, category, order, null, null);
    }

    public List<Todo> search(String keyword, String category, String order, boolean includeCompleted) {
        return todoMapper.search(scopeUserId(), keyword, category, order, null, null, includeCompleted);
    }

    public List<Todo> search(String keyword, String category, String order, LocalDate from, LocalDate to) {
        return todoMapper.search(scopeUserId(), keyword, category, order, from, to, true);
    }

    public long count(String keyword, String category, boolean includeCompleted) {
        return todoMapper.count(scopeUserId(), keyword, category, includeCompleted);
    }

    public List<Todo> searchPage(String keyword, String category, String order, boolean includeCompleted, int page) {
        long offset = (page - 1L) * PAGE_SIZE;
        return todoMapper.searchPage(scopeUserId(), keyword, category, order, includeCompleted, PAGE_SIZE, offset);
    }

    public List<Todo> searchForExport(String keyword, String category, String order, boolean includeCompleted) {
        return todoMapper.searchForExport(scopeUserId(), keyword, category, order, includeCompleted);
    }

    public Todo findById(Long id) {
        Long userId = scopeUserId();
        Todo todo = todoMapper.findById(id);
        checkOwner(todo, userId);
        if (todo == null) {
            checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        }
        return todo;
    }

    public void checkAccess(Long id) {
        Long userId = scopeUserId();
        Todo todo = todoMapper.findIncludingDeletedById(id);
        checkOwner(todo, userId);
        if (todo == null) {
            throw new TodoNotFoundException(id);
        }
    }

    public long countTrash(String keyword, String category) {
        return todoMapper.countTrash(scopeUserId(), keyword, category);
    }

    public List<Todo> searchTrashPage(String keyword, String category, String order, int page) {
        long offset = (page - 1L) * PAGE_SIZE;
        return todoMapper.searchTrashPage(scopeUserId(), keyword, category, order, PAGE_SIZE, offset);
    }

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
    @PreAuthorize("hasRole('ADMIN')")
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

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
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

    private Long scopeUserId() {
        // 認証と有効状態を確認してから、管理者だけ所有者条件を外す。
        Long userId = currentUserId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")) ? null : userId;
    }

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

    private void checkOwner(Todo todo, Long userId) {
        if (todo != null && userId != null && !userId.equals(todo.getUserId())) {
            throw new AccessDeniedException("このToDoにはアクセスできません");
        }
    }
}
