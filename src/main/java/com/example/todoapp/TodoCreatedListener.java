package com.example.todoapp;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TodoCreatedListener {
    private final NotificationService notifications;

    @TransactionalEventListener
    public void afterCommit(TodoCreatedEvent event) {
        try {
            // 別Bean経由で呼び出すことで@Asyncのプロキシを通す。
            notifications.notifyTodoCreatedAsync(event);
        } catch (TaskRejectedException exception) {
            // 登録は既に確定している。通知キュー満杯を登録失敗として返さない。
            log.error("登録完了通知を受け付けできませんでした: todoId={}", event.todoId(), exception);
        }
    }
}
