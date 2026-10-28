package com.example.todoapp;

/** 可変のTodo全体を渡さず、確定時点の通知に必要なIDだけを保持する。 */
public record TodoCreatedEvent(Long todoId, Long userId) {
}
