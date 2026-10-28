package com.example.todoapp;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.reminders-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {
    private final TodoMapper todos;
    private final UserMapper users;
    private final MailService mail;
    private final Clock reminderClock;

    @Scheduled(cron = "${app.mail.reminder-cron:0 0 9 * * *}", zone = "${app.mail.zone:Asia/Tokyo}")
    public synchronized void sendDailyReminders() {
        LocalDate tomorrow = LocalDate.now(reminderClock).plusDays(1);
        for (Todo todo : todos.findByDeadline(tomorrow)) send(todo, false);
    }

    @Scheduled(fixedDelayString = "${app.mail.overdue-interval-ms:3600000}",
            initialDelayString = "${app.mail.overdue-initial-delay-ms:60000}")
    public synchronized void checkOverdueTodos() {
        for (Todo todo : todos.findOverdue(LocalDate.now(reminderClock))) send(todo, true);
    }

    private void send(Todo candidate, boolean overdue) {
        try {
            // 抽出後に完了・削除・期限変更されたToDoは送信しない。
            Todo todo = todos.findById(candidate.getId());
            if (todo == null || Boolean.TRUE.equals(todo.getCompleted())
                    || !Objects.equals(todo.getDueDate(), candidate.getDueDate())) return;
            User owner = todo.getUserId() == null ? null : users.findById(todo.getUserId());
            if (owner == null || !owner.isEnabled() || owner.getEmail() == null || owner.getEmail().isBlank()) {
                log.info("期限通知スキップ（送信先未設定・無効）: todoId={}", todo.getId());
                return;
            }
            if (mail.sendDeadlineReminder(owner.getEmail(), todo, overdue)) {
                if (overdue) todos.markOverdueNotified(todo.getId(), todo.getDueDate());
                else todos.markReminderNotified(todo.getId(), todo.getDueDate());
                log.info("期限通知送信完了: todoId={}, overdue={}", todo.getId(), overdue);
            } else {
                log.info("期限通知プレビュー: todoId={}, overdue={}", todo.getId(), overdue);
            }
        } catch (RuntimeException exception) {
            // 1件の失敗で残りを止めない。未送信のまま次回の期限切れチェックで再試行できる。
            log.error("期限通知失敗: todoId={}, overdue={}, error={}",
                    candidate.getId(), overdue, exception.getClass().getSimpleName());
        }
    }
}
