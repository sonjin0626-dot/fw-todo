package com.example.todoapp;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.format.annotation.DateTimeFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class Todo {

    private Long id;
    private Long userId;

    @NotBlank(message = "{validation.titleRequired}")
    @Size(max = 255, message = "{validation.titleSize}")
    private String title;

    @Size(max = 255, message = "{validation.detailSize}")
    private String detail;

    @NotBlank(message = "{validation.categoryRequired}")
    private String category;

    @NotNull(message = "{typeMismatch.todo.priority}")
    private Integer priority;

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private LocalDate dueDate;

    private Boolean completed;
    private LocalDateTime completedAt;
    private LocalDateTime deletedAt;
    private Boolean pinned;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Todo() {
    }

    public boolean isOverdue() {
        return !Boolean.TRUE.equals(completed) && dueDate != null && dueDate.isBefore(LocalDate.now());
    }

    public boolean isNearDeadline() {
        LocalDate today = LocalDate.now();
        return !Boolean.TRUE.equals(completed) && dueDate != null
                && !dueDate.isBefore(today) && !dueDate.isAfter(today.plusDays(3));
    }
}
