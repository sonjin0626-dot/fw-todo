package com.example.todoapp;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.BindingResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    private final MessageSource messages;

    private String message(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private final AdminUserService users;

    public AdminUserController(AdminUserService users, MessageSource messages) {
        this.messages = messages;
        this.users = users;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", users.findAll());
        return "admin/users";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        User user = users.findById(id);
        UserEditForm form = new UserEditForm();
        form.setUsername(user.getUsername());
        form.setEmail(user.getEmail());
        form.setRole(user.getRole());
        form.setEnabled(user.isEnabled());
        model.addAttribute("managedUser", user);
        model.addAttribute("form", form);
        return "admin/user-edit";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("managedUser", users.findById(id));
        return "admin/user-detail";
    }

    @PostMapping({"/{id}", "/{id}/edit"})
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") UserEditForm form,
            BindingResult result, Model model, HttpServletRequest request, RedirectAttributes redirect) {
        User target = users.findById(id);
        if (request.getParameter("email") == null) {
            form.setEmail(target.getEmail());
        }
        // 既存のロール・有効状態だけを送るPOSTも引き続き受け付ける。
        if (request.getParameter("username") == null) {
            form.setUsername(target.getUsername());
            var errors = new org.springframework.validation.BeanPropertyBindingResult(form, "form");
            result.getAllErrors().stream().filter(error -> !(error instanceof org.springframework.validation.FieldError field)
                    || !"username".equals(field.getField())).forEach(errors::addError);
            result = errors;
        }
        model.addAttribute("managedUser", target);
        if (result.hasErrors()) {
            form.setNewPassword(null);
            model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "form", result);
            return "admin/user-edit";
        }
        try {
            users.updateByAdmin(id, form);
        } catch (DuplicateKeyException exception) {
            result.rejectValue("username", "validation.duplicate", message("validation.duplicate"));
            form.setNewPassword(null);
            return "admin/user-edit";
        }
        boolean credentialsChanged = !target.getUsername().equals(form.getUsername())
                || (form.getNewPassword() != null && !form.getNewPassword().isEmpty());
        if (target.getUsername().equals(request.getUserPrincipal().getName()) && credentialsChanged) {
            SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            return "redirect:/login?accountUpdated=true";
        }
        redirect.addFlashAttribute("message", message("flash.userUpdated"));
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        users.deleteUser(id);
        redirect.addFlashAttribute("message", message("flash.userDeleted"));
        return "redirect:/admin/users";
    }
}
