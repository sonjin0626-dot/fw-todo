package com.example.todoapp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailServiceTests {
    JavaMailSender sender;
    MailProperties properties;
    MailService mail;

    @BeforeEach
    void setup() {
        sender = mock(JavaMailSender.class);
        properties = new MailProperties();
        properties.setEnabled(true);
        properties.setFrom("sender@example.com");
        properties.setBaseUrl("https://todo.example.com/");
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        mail = new MailService(sender, engine, properties);
    }

    @Test
    void createsJapaneseTextMailUsingExistingTodoFieldsAndEditRoute() {
        Todo todo = todo();
        todo.setDueDate(null);
        todo.setDetail(null);
        assertThat(mail.sendTodoCreatedNotification("owner@example.com", todo)).isTrue();
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(capture.capture());
        SimpleMailMessage message = capture.getValue();
        assertThat(message.getFrom()).isEqualTo("sender@example.com");
        assertThat(message.getTo()).containsExactly("owner@example.com");
        assertThat(message.getSubject()).isEqualTo("【ToDo作成】<script>alert('test')</script>");
        assertThat(message.getText()).contains("期限: 未設定", "https://todo.example.com/todos/42/edit")
                .doesNotContain("null");
    }

    @Test
    void rendersEscapedThymeleafHtmlAndUtf8PlainTextInMultipartMessage() throws Exception {
        MimeMessage message = new JavaMailSenderImpl().createMimeMessage();
        when(sender.createMimeMessage()).thenReturn(message);
        assertThat(mail.sendDeadlineReminder("owner@example.com", todo(), false)).isTrue();
        verify(sender).send(message);
        message.saveChanges();
        assertThat(message.getSubject()).startsWith("【期限間近】");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("owner@example.com");
        List<Part> parts = new ArrayList<>();
        collect(message, parts);
        Part html = parts.stream().filter(p -> type(p, "text/html")).findFirst().orElseThrow();
        Part text = parts.stream().filter(p -> type(p, "text/plain")).findFirst().orElseThrow();
        assertThat(html.getContentType()).containsIgnoringCase("UTF-8");
        assertThat(html.getContent().toString()).contains("明日が期限です", "&lt;script&gt;", "/todos/42/edit")
                .doesNotContain("<script>");
        assertThat(text.getContent().toString()).contains("明日が期限です", "期限: 2026-10-30");
    }

    @Test
    void dryRunNeverTouchesSmtpForEitherFormat() {
        properties.setEnabled(false);
        assertThat(mail.sendTodoCreatedNotification("owner@example.com", todo())).isFalse();
        assertThat(mail.sendDeadlineReminder("owner@example.com", todo(), true)).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    void retriesSendFailuresWithinLimitAndPropagatesFinalFailure() {
        doThrow(new MailSendException("temporary")).doNothing().when(sender).send(any(SimpleMailMessage.class));
        assertThat(mail.sendTodoCreatedNotification("owner@example.com", todo())).isTrue();
        verify(sender, times(2)).send(any(SimpleMailMessage.class));
        reset(sender);
        doThrow(new MailSendException("persistent")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mail.sendTodoCreatedNotification("owner@example.com", todo()))
                .isInstanceOf(MailSendException.class);
        verify(sender, times(3)).send(any(SimpleMailMessage.class));
    }

    @Test
    void doesNotRetryAuthenticationFailures() {
        doThrow(new MailAuthenticationException("credentials")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> mail.sendTodoCreatedNotification("owner@example.com", todo()))
                .isInstanceOf(MailAuthenticationException.class);
        verify(sender).send(any(SimpleMailMessage.class));
    }

    private Todo todo() {
        Todo todo = new Todo();
        todo.setId(42L);
        todo.setTitle("<script>alert('test')</script>");
        todo.setDetail("日本語のメモ & 確認");
        todo.setDueDate(LocalDate.of(2026, 10, 30));
        return todo;
    }

    private void collect(Part part, List<Part> parts) throws Exception {
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) collect(multipart.getBodyPart(i), parts);
        } else parts.add(part);
    }

    private boolean type(Part part, String mimeType) {
        try { return part.isMimeType(mimeType); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }
}
