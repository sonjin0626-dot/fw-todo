package com.example.todoapp.api;

/** 並び替え後の位置（1始まり）。 */
public record ReorderRequest(Long id, Integer displayOrder) {}
