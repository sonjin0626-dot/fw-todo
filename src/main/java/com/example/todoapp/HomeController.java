package com.example.todoapp;

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

@Controller
public class HomeController {

    private final TodoService todoService;

    public HomeController(TodoService todoService) {
        this.todoService = todoService;
    }

    @ModelAttribute("username")
    public String username(@AuthenticationPrincipal UserDetails userDetails) {
        return userDetails == null ? null : userDetails.getUsername();
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("title", "やること管理");
        return "index";
    }

    @GetMapping("/todos")
    public String todos(@RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "category", defaultValue = "") String category,
            @RequestParam(name = "order", defaultValue = "asc") String order,
            @RequestParam(name = "includeCompleted", defaultValue = "false") boolean includeCompleted,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "trash", defaultValue = "0") String trash, Model model) {
        boolean trashMode = "1".equals(trash);
        String sortOrder = "desc".equals(order) ? "desc" : "asc";
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
        return "todos";
    }

    @GetMapping("/todos/new")
    public String create(Model model) {
        model.addAttribute("todo", new Todo());
        return "create";
    }

    @PostMapping("/todos/confirm")
    public String confirm(@Valid @ModelAttribute("todo") Todo todo, BindingResult bindingResult) {
        if (bindingResult.hasErrors()) {
            return "create";
        }
        return "create-confirm";
    }

    @PostMapping("/todos/new")
    public String rewrite(@ModelAttribute("todo") Todo todo) {
        return "create";
    }

    @PostMapping("/todos")
    public String store(@ModelAttribute("todo") Todo todo, RedirectAttributes redirectAttributes) {
        todoService.create(todo);
        redirectAttributes.addFlashAttribute("message", "登録しました");
        return "redirect:/todos";
    }

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

    @PostMapping("/todos/{id}/edit")
    public String editRewrite(@PathVariable("id") Long id, @ModelAttribute("todo") Todo todo, Model model) {
        todoService.checkAccess(id);
        model.addAttribute("id", id);
        return "edit";
    }

    @PostMapping("/todos/{id}")
    public String update(@PathVariable("id") Long id, @ModelAttribute("todo") Todo todo,
            RedirectAttributes redirectAttributes) {
        todo.setId(id);
        todoService.update(todo);
        redirectAttributes.addFlashAttribute("message", "保存しました");
        return "redirect:/todos";
    }

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

    @PostMapping("/todos/{id}/delete")
    public String delete(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        todoService.delete(id);
        redirectAttributes.addFlashAttribute("message", "削除しました");
        return "redirect:/todos";
    }

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
                ? "削除する項目を選んでください" : deleted + "件を削除しました");
        redirectAttributes.addAttribute("keyword", keyword);
        redirectAttributes.addAttribute("category", category);
        redirectAttributes.addAttribute("order", "desc".equals(order) ? "desc" : "asc");
        redirectAttributes.addAttribute("includeCompleted", includeCompleted);
        redirectAttributes.addAttribute("page", Math.max(1, page));
        return "redirect:/todos";
    }

    @PostMapping("/todos/{id}/restore")
    public String restore(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        boolean restored = todoService.restore(id);
        redirectAttributes.addFlashAttribute("message", restored ? "戻しました" : "見つかりませんでした");
        return "redirect:/todos?trash=1";
    }

    @PostMapping("/todos/{id}/pin")
    public String togglePinned(@PathVariable("id") Long id,
            @RequestParam(name = "keyword", defaultValue = "") String keyword,
            @RequestParam(name = "category", defaultValue = "") String category,
            @RequestParam(name = "order", defaultValue = "asc") String order,
            @RequestParam(name = "includeCompleted", defaultValue = "false") boolean includeCompleted,
            @RequestParam(name = "page", defaultValue = "1") int page, RedirectAttributes redirectAttributes) {
        boolean toggled = todoService.togglePinned(id);
        redirectAttributes.addFlashAttribute("message", toggled ? "印を切り替えました" : "見つかりませんでした");
        redirectAttributes.addAttribute("keyword", keyword);
        redirectAttributes.addAttribute("category", category);
        redirectAttributes.addAttribute("order", "desc".equals(order) ? "desc" : "asc");
        redirectAttributes.addAttribute("includeCompleted", includeCompleted);
        redirectAttributes.addAttribute("page", Math.max(1, page));
        return "redirect:/todos";
    }
}
