package com.example.todoapp;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class TodoAttachment {
    private Long id;
    private Long todoId;
    private String originalFilename;
    private String storedFilename;
    private String contentType;
    private Long fileSize;
    private LocalDateTime uploadedAt;
}
