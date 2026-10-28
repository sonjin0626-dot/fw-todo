package com.example.todoapp;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final MailProperties properties;

    /** 戻り値はSMTP送信成功時だけtrue。開発時は本文・宛先を記録せずログで確認する。 */
    public boolean sendSimpleMail(String to, String subject, String text) {
        if (!properties.isEnabled()) {
            log.info("メール送信プレビュー: format=text (SMTP送信なし)");
            return false;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        return deliver(() -> mailSender.send(message));
    }

    public boolean sendHtmlMail(String to, String subject, String text, String html) {
        if (!properties.isEnabled()) {
            log.info("メール送信プレビュー: format=html (SMTP送信なし)");
            return false;
        }
        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            return deliver(() -> mailSender.send(message));
        } catch (MessagingException exception) {
            throw new MailPreparationException("メール本文の生成に失敗しました", exception);
        }
    }

    public boolean sendTemplatedMail(String to, String subject, String text,
            String template, Map<String, Object> variables) {
        Context context = new Context(Locale.JAPANESE);
        context.setVariables(variables);
        return sendHtmlMail(to, subject, text, templateEngine.process(template, context));
    }

    public boolean sendTodoCreatedNotification(String to, Todo todo) {
        String text = "ToDoが作成されました。\n\nタイトル: %s\nメモ: %s\n期限: %s\n\n%s"
                .formatted(todo.getTitle(), todo.getDetail() == null ? "" : todo.getDetail(),
                        todo.getDueDate() == null ? "未設定" : todo.getDueDate(), todoUrl(todo));
        return sendSimpleMail(to, "【ToDo作成】" + todo.getTitle(), text);
    }

    public boolean sendDeadlineReminder(String to, Todo todo, boolean overdue) {
        String heading = overdue ? "期限を過ぎています" : "明日が期限です";
        String subject = (overdue ? "【期限切れ】" : "【期限間近】") + todo.getTitle();
        String text = "%s\n\nタイトル: %s\n期限: %s\n%s"
                .formatted(heading, todo.getTitle(), todo.getDueDate(), todoUrl(todo));
        return sendTemplatedMail(to, subject, text, "mail/reminder",
                Map.of("todo", todo, "heading", heading, "todoUrl", todoUrl(todo)));
    }

    private String todoUrl(Todo todo) {
        return properties.getBaseUrl().replaceAll("/+$", "") + "/todos/" + todo.getId() + "/edit";
    }

    private boolean deliver(Runnable send) {
        for (int attempt = 1; ; attempt++) {
            try {
                send.run();
                log.info("SMTPメール送信完了: attempt={}", attempt);
                return true;
            } catch (MailSendException exception) {
                if (attempt >= properties.getMaxAttempts()) throw exception;
                log.warn("SMTPメール送信を再試行: attempt={}", attempt);
            }
        }
    }
}
