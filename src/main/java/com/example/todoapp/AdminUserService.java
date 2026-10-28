package com.example.todoapp;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import com.example.todoapp.exception.BusinessException;

@Service
@Validated
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserService {
    private final UserMapper users;
    private final TodoMapper todos;
    private final PasswordEncoder encoder;
    private final FileStorageService files;

    public AdminUserService(UserMapper users, TodoMapper todos, PasswordEncoder encoder, FileStorageService files) {
        this.users = users;
        this.todos = todos;
        this.encoder = encoder;
        this.files = files;
    }

    public List<User> findAll() { return users.findAll(); }

    public User findById(Long id) {
        User user = users.findById(id);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ユーザーが見つかりません");
        return user;
    }

    public void update(Long id, String role, boolean enabled) {
        if (!List.of("ADMIN", "USER").contains(role)) {
            throw new BusinessException("INVALID_ROLE", "ロールが正しくありません");
        }
        User user = findById(id);
        if (user.getUsername().equals(SecurityContextHolder.getContext().getAuthentication().getName())
                && (!enabled || !"ADMIN".equals(role))) {
            throw new BusinessException("SELF_ACCESS_CHANGE", "自分自身の管理者権限・有効状態は解除できません");
        }
        user.setRole(role);
        user.setEnabled(enabled);
        users.updateAccess(user);
    }

    @Transactional
    public void updateByAdmin(Long id, @Valid UserEditForm form) {
        User user = users.findByIdForUpdate(id);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ユーザーが見つかりません");
        if (!List.of("ADMIN", "USER").contains(form.getRole() == null ? "" : form.getRole())) {
            throw new BusinessException("INVALID_ROLE", "ロールが正しくありません");
        }
        if (isCurrentUser(user) && (!form.isEnabled() || !"ADMIN".equals(form.getRole()))) {
            throw new BusinessException("SELF_ACCESS_CHANGE", "自分自身の管理者権限・有効状態は解除できません");
        }
        user.setUsername(form.getUsername());
        user.setEmail(form.getEmail());
        user.setRole(form.getRole());
        user.setEnabled(form.isEnabled());
        // 管理画面では既存ハッシュを取得せず、空欄ならSQLでパスワードを保持する。
        if (form.getNewPassword() != null && !form.getNewPassword().isEmpty()) {
            user.setPassword(encoder.encode(form.getNewPassword()));
        }
        users.update(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        User target = users.findByIdForUpdate(id);
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ユーザーが見つかりません");
        if (isCurrentUser(target)) {
            throw new BusinessException("SELF_DELETE", "自分自身のアカウントは削除できません");
        }
        files.deleteFilesForUser(id);
        todos.deleteByUserId(id);
        users.deleteById(id);
    }

    private boolean isCurrentUser(User user) {
        return user.getUsername().equals(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
