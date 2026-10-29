package com.example.todoapp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class TodoTemplate {
    private Long id;
    private String name;
    private String description;
    private Long userId;
    private LocalDateTime createdAt;
    private List<TodoTemplateItem> items = new ArrayList<>();
}
