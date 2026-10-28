package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TodoService {

    public static final int PAGE_SIZE = 10;

    private final TodoMapper todoMapper;
    private final UserMapper userMapper;

    public TodoService(TodoMapper todoMapper, UserMapper userMapper) {
        this.todoMapper = todoMapper;
        this.userMapper = userMapper;
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
        checkOwner(todoMapper.findIncludingDeletedById(id), userId);
    }

    public long countTrash(String keyword, String category) {
        return todoMapper.countTrash(scopeUserId(), keyword, category);
    }

    public List<Todo> searchTrashPage(String keyword, String category, String order, int page) {
        long offset = (page - 1L) * PAGE_SIZE;
        return todoMapper.searchTrashPage(scopeUserId(), keyword, category, order, PAGE_SIZE, offset);
    }

    public void create(Todo todo) {
        // 所有者はリクエストの値を信用せず、認証済みユーザーから設定する。
        todo.setUserId(currentUserId());
        todoMapper.insert(todo);
        log.info("登録 id={}", todo.getId());
    }

    public void update(Todo todo) {
        Long userId = scopeUserId();
        Todo existing = todoMapper.findIncludingDeletedById(todo.getId());
        checkOwner(existing, userId);
        if (existing == null) {
            return;
        }
        // 管理者による編集でも、リクエスト値ではなく元の所有者を使用する。
        todo.setUserId(existing.getUserId());
        todoMapper.update(todo);
        log.info("編集 id={}", todo.getId());
    }

    public void delete(Long id) {
        Long userId = scopeUserId();
        checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        todoMapper.deleteById(userId, id);
        log.info("削除 id={}", id);
    }

    @Transactional
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
        int deleted = todoMapper.bulkDeleteByIds(userId, distinctIds);
        log.info("一括削除 件数={}", deleted);
        return deleted;
    }

    public boolean restore(Long id) {
        Long userId = scopeUserId();
        checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        boolean restored = todoMapper.restoreById(userId, id) > 0;
        if (restored) {
            log.info("復元 id={}", id);
        }
        return restored;
    }

    public boolean togglePinned(Long id) {
        Long userId = scopeUserId();
        checkOwner(todoMapper.findIncludingDeletedById(id), userId);
        boolean toggled = todoMapper.togglePinned(userId, id) > 0;
        if (toggled) {
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
