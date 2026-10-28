package com.example.todoapp;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.mail.MailException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final TodoMapper todos;
    private final UserMapper users;
    private final MailService mail;

    @Async("emailExecutor")
    public void notifyTodoCreatedAsync(TodoCreatedEvent event) {
        log.info("ToDo登録完了通知: todoId={}, userId={}, thread={}",
                event.todoId(), event.userId(), Thread.currentThread().getName());
        Todo todo = todos.findById(event.todoId());
        if (todo == null || !event.userId().equals(todo.getUserId())) return;
        User owner = users.findById(todo.getUserId());
        if (!canReceive(owner)) return;
        try {
            mail.sendTodoCreatedNotification(owner.getEmail(), todo);
        } catch (MailException exception) {
            // SMTP例外の詳細には宛先が含まれることがあるため、種別と対象IDを記録する。
            log.error("ToDo作成メール送信失敗: todoId={}, error={}", todo.getId(), exception.getClass().getSimpleName());
        }
    }

    private boolean canReceive(User user) {
        return user != null && user.isEnabled() && user.getEmail() != null && !user.getEmail().isBlank();
    }
}
