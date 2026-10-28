package com.example.todoapp;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class NotificationService {
    @Async("emailExecutor")
    public void notifyTodoCreatedAsync(TodoCreatedEvent event) {
        // このアプリにはメールアドレス・SMTP設定がないため、通知先はサーバーログ。
        log.info("ToDo登録完了通知: todoId={}, userId={}, thread={}",
                event.todoId(), event.userId(), Thread.currentThread().getName());
    }
}
