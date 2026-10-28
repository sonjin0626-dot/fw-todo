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

    @NotBlank(message = "やることを入力してください")
    @Size(max = 255, message = "やることは255文字以内で入力してください")
    private String title;

    @Size(max = 255, message = "メモは255文字以内で入力してください")
    private String detail;

    @NotBlank(message = "ジャンルを選んでください")
    private String category;

    @NotNull(message = "優先度を選んでください")
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
