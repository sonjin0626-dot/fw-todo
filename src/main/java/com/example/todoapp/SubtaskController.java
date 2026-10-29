package com.example.todoapp;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SubtaskController {
    private final TodoService todos;
    public SubtaskController(TodoService todos) { this.todos = todos; }

    @GetMapping("/todos/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("todo", todos.findByIdWithSubtasks(id));
        return "todo-detail";
    }
    @PostMapping("/todos/{parentId}/subtasks")
    public String add(@PathVariable Long parentId, @RequestParam String title) {
        todos.addSubtask(parentId, title);
        return "redirect:/todos/" + parentId;
    }
    @PostMapping("/todos/{parentId}/subtasks/{subtaskId}/{action:toggle|delete}")
    public String change(@PathVariable Long parentId, @PathVariable Long subtaskId, @PathVariable String action) {
        todos.changeSubtask(parentId, subtaskId, action.equals("delete"));
        return "redirect:/todos/" + parentId;
    }
}