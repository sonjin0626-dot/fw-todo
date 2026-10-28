package com.example.todoapp;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.todoapp.exception.BusinessException;
import com.example.todoapp.exception.TodoNotFoundException;

@Controller
public class TodoAttachmentController {
    private final MessageSource messages;

    private String message(String key, Object... args) {
        return messages.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private final TodoService todos;
    private final FileStorageService files;

    public TodoAttachmentController(TodoService todos, FileStorageService files, MessageSource messages) {
        this.messages = messages;
        this.todos = todos;
        this.files = files;
    }

    @GetMapping("/todos/{id}/attach")
    public String show(@PathVariable("id") Long id, Model model) {
        Todo todo = todos.findById(id);
        if (todo == null) throw new TodoNotFoundException(id);
        model.addAttribute("todo", todo);
        model.addAttribute("attachments", files.findByTodoId(id));
        return "attachments";
    }

    @PostMapping("/todos/{id}/attach")
    public String upload(@PathVariable("id") Long id, @RequestParam("file") MultipartFile file,
            RedirectAttributes redirect) throws IOException {
        try {
            files.store(id, file);
            redirect.addFlashAttribute("message", message("flash.attached"));
        } catch (BusinessException exception) {
            redirect.addFlashAttribute("error", messages.getMessage("business." + exception.getErrorCode(), null,
                    exception.getMessage(), LocaleContextHolder.getLocale()));
        }
        return "redirect:/todos/" + id + "/attach";
    }

    @GetMapping("/todos/attachments/{attachmentId}/download")
    public ResponseEntity<Resource> download(@PathVariable("attachmentId") Long id) throws IOException {
        TodoAttachment attachment = files.findById(id);
        Resource resource = files.load(attachment);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .contentLength(attachment.getFileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.getOriginalFilename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(resource);
    }

    @PostMapping("/todos/attachments/{attachmentId}/delete")
    public String delete(@PathVariable("attachmentId") Long id, RedirectAttributes redirect) {
        Long todoId = files.delete(id);
        redirect.addFlashAttribute("message", message("flash.attachmentDeleted"));
        return "redirect:/todos/" + todoId + "/attach";
    }
}
