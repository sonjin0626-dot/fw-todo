package com.example.todoapp;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@Controller
@RequestMapping("/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController {
    private final AuditLogService service;

    public AuditLogController(AuditLogService service) { this.service = service; }

    @GetMapping
    public String list(@Valid @ModelAttribute("criteria") AuditSearchCriteria criteria,
            BindingResult errors, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("actions", AuditLog.Action.values());
        model.addAttribute("outcomes", List.of("COMMITTED", "ROLLED_BACK", "NO_CHANGE", "ATTEMPTED", "FAILED", "UNKNOWN", "LEGACY"));
        if (errors.hasErrors()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            model.addAttribute("logs", List.of());
            model.addAttribute("total", 0L);
            model.addAttribute("hasNext", false);
        } else {
            long total = service.count(criteria);
            model.addAttribute("logs", service.search(criteria));
            model.addAttribute("total", total);
            model.addAttribute("hasNext", criteria.getOffset() + criteria.getLimit() < total);
        }
        return "admin/audit-logs";
    }

    @GetMapping("/entity/{type}/{id}")
    public String entityHistory(@PathVariable String type, @PathVariable Long id,
            @Valid @ModelAttribute("criteria") AuditSearchCriteria criteria,
            BindingResult errors, Model model, HttpServletResponse response) {
        if (!"TODO".equals(type) || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        criteria.setEntityType(type);
        criteria.setEntityId(id);
        model.addAttribute("entityHistory", true);
        return list(criteria, errors, model, response);
    }
}
