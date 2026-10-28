package com.example.todoapp.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class TodoNotFoundException extends RuntimeException {
    private final Long todoId;

    public TodoNotFoundException(Long todoId) {
        super("ID " + todoId + " のやることは見つかりませんでした。");
        this.todoId = todoId;
    }

    public Long getTodoId() {
        return todoId;
    }
}
