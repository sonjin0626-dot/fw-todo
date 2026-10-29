package com.example.todoapp;

import java.time.LocalDate;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import jakarta.validation.Valid;

@Controller
@RequestMapping("/templates")
public class TemplateController {
    private final TemplateService service;
    private final MessageSource messages;
    public TemplateController(TemplateService service, MessageSource messages) {
        this.service = service;
        this.messages = messages;
    }
    @InitBinder("templateForm")
    void bindForm(WebDataBinder binder) { binder.setAllowedFields("name", "description", "itemTitles"); }
    @ModelAttribute("categories")
    public java.util.List<String> categories() { return TemplateService.CATEGORIES; }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("templates", service.list());
        return "template/list";
    }
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("templateForm", new TemplateForm());
        return "template/form";
    }
    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        TodoTemplate template = service.find(id);
        TemplateForm form = new TemplateForm();
        form.setName(template.getName());
        form.setDescription(template.getDescription());
        form.setItemTitles(template.getItems().stream().map(TodoTemplateItem::getTitle).toList());
        model.addAttribute("templateForm", form);
        model.addAttribute("templateId", id);
        return "template/form";
    }
    @PostMapping({"", "/{id}"})
    public String save(@PathVariable(required = false) Long id,
            @Valid @ModelAttribute TemplateForm templateForm, BindingResult result, Model model, RedirectAttributes ra) {
        if (id != null) service.find(id);
        if (result.hasErrors()) {
            model.addAttribute("templateId", id);
            return "template/form";
        }
        service.save(id, templateForm);
        flash(ra, "template.saved");
        return "redirect:/templates";
    }
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        service.delete(id);
        flash(ra, "template.deleted");
        return "redirect:/templates";
    }
    @PostMapping("/{id}/apply")
    public String apply(@PathVariable Long id, @RequestParam(defaultValue = "資格") String category,
            @RequestParam(defaultValue = "2") Integer priority,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
            RedirectAttributes ra) {
        int count = service.applyTemplate(id, category, priority, dueDate);
        flash(ra, "template.applied", count);
        return "redirect:/todos";
    }
    private void flash(RedirectAttributes ra, String key, Object... args) {
        ra.addFlashAttribute("message", messages.getMessage(key, args, LocaleContextHolder.getLocale()));
    }
}
