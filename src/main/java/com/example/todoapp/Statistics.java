package com.example.todoapp;

import lombok.Value;

@Value
public class Statistics {
    long overdueCount;
    long nearDeadlineCount;
    long trashCount;
}
