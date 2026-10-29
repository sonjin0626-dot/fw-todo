package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Validator;
import com.example.todoapp.exception.BusinessException;

@Service
@Transactional
public class TemplateService {
    private final TodoTemplateMapper templates;
    private final TodoTemplateItemMapper items;
    private final UserMapper users;
    private final TodoService todos;
    private final Validator validator;
    public static final List<String> CATEGORIES = List.of("デザイン", "マーケティング", "プログラミング", "資格", "就職活動");

    public TemplateService(TodoTemplateMapper templates, TodoTemplateItemMapper items,
            UserMapper users, TodoService todos, Validator validator) {
        this.templates = templates;
        this.items = items;
        this.users = users;
        this.todos = todos;
        this.validator = validator;
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken)
            throw new AccessDeniedException("ログインが必要です");
        User user = users.findByUsername(auth.getName());
        if (user == null || !user.isEnabled() || user.getId() == null
                || (auth.getPrincipal() instanceof CustomUserDetailsService.AccountPrincipal principal
                    && !user.getId().equals(principal.getAccountId())))
            throw new AccessDeniedException("ログインユーザーが見つかりません");
        return user.getId();
    }

    public List<TodoTemplate> list() { return templates.findByUserId(currentUserId()); }

    public TodoTemplate find(Long id) {
        Long userId = currentUserId();
        TodoTemplate template = templates.findById(id);
        if (template == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!userId.equals(template.getUserId())) throw new AccessDeniedException("このテンプレートにはアクセスできません");
        return template;
    }

    public void save(Long id, TemplateForm form) {
        if (!validator.validate(form).isEmpty()) throw new BusinessException("INVALID_TEMPLATE", "テンプレートの入力内容を確認してください");
        TodoTemplate template = id == null ? new TodoTemplate() : find(id);
        template.setUserId(currentUserId());
        template.setName(form.getName().strip());
        template.setDescription(form.getDescription());
        if (id == null) templates.insert(template);
        else {
            templates.update(template);
            items.deleteByTemplateId(id);
        }
        for (int i = 0; i < form.getItemTitles().size(); i++) {
            TodoTemplateItem item = new TodoTemplateItem();
            item.setTemplateId(template.getId());
            item.setTitle(form.getItemTitles().get(i).strip());
            item.setDisplayOrder(i + 1);
            items.insert(item);
        }
    }

    public void delete(Long id) { templates.deleteById(find(id).getId()); }

    /** 既存の履歴・コミット後通知を維持し、全タスクを同一トランザクションで作成する。 */
    @Transactional(rollbackFor = Exception.class)
    public int applyTemplate(Long id, String category, Integer priority, LocalDate dueDate) {
        TodoTemplate template = find(id);
        if (!CATEGORIES.contains(category) || priority == null || priority < 1 || priority > 3)
            throw new BusinessException("INVALID_TEMPLATE_OPTIONS", "ジャンル・優先度を確認してください");
        for (TodoTemplateItem item : template.getItems()) {
            Todo todo = new Todo();
            todo.setTitle(item.getTitle());
            todo.setDetail(item.getDescription());
            todo.setCategory(category);
            todo.setPriority(priority);
            todo.setDueDate(dueDate);
            todo.setCompleted(false);
            todos.create(todo);
        }
        return template.getItems().size();
    }
}
