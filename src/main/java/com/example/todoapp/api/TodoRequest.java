package com.example.todoapp.api;

import java.time.LocalDate;

import com.example.todoapp.Todo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TodoRequest {

    @NotBlank(message = "{validation.titleRequired}")
    @Size(max = 255, message = "{validation.titleSize}")
    private String title;

    @Size(max = 255, message = "{validation.detailSize}")
    private String detail;

    @NotBlank(message = "{validation.categoryRequired}")
    private String category;

    @NotNull(message = "{typeMismatch.todo.priority}")
    private Integer priority;

    private LocalDate dueDate;
    private Boolean completed;

    public Todo toTodo() {
        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setDetail(detail);
        todo.setCategory(category);
        todo.setPriority(priority);
        todo.setDueDate(dueDate);
        todo.setCompleted(completed);
        return todo;
    }
}
