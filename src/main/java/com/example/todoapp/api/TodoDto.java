package com.example.todoapp.api;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.example.todoapp.Todo;

import lombok.Getter;

@Getter
public class TodoDto {

    private Long id;
    private String title;
    private String detail;
    private String category;
    private Integer priority;
    private LocalDate dueDate;
    private Boolean completed;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TodoDto from(Todo todo) {
        TodoDto dto = new TodoDto();
        dto.id = todo.getId();
        dto.title = todo.getTitle();
        dto.detail = todo.getDetail();
        dto.category = todo.getCategory();
        dto.priority = todo.getPriority();
        dto.dueDate = todo.getDueDate();
        dto.completed = todo.getCompleted();
        dto.completedAt = todo.getCompletedAt();
        dto.createdAt = todo.getCreatedAt();
        dto.updatedAt = todo.getUpdatedAt();
        return dto;
    }
}
