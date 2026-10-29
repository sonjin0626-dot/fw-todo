package com.example.todoapp;

import lombok.Data;

@Data
public class TodoTemplateItem {
    private Long id;
    private Long templateId;
    private String title;
    private String description;
    private Integer displayOrder;
}
