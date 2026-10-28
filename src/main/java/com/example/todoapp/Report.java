package com.example.todoapp;

import java.time.LocalDateTime;
import lombok.Value;

@Value
public class Report {
    long totalCount;
    long completedCount;
    long pendingCount;
    LocalDateTime generatedAt;
}
