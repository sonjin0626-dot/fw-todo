package com.example.todoapp;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mail-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.mail.enabled=true", "app.mail.reminders-enabled=true", "app.mail.reminder-cron=-",
        "app.mail.overdue-initial-delay-ms=86400000"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser(username = "mail-owner")
class MailNotificationIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoService service;
    @Autowired TodoMapper todos;
    @Autowired UserMapper users;
    @Autowired ReminderScheduler scheduler;
    @Autowired MailProperties properties;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean JavaMailSender sender;
    @MockitoBean Clock reminderClock;
    @MockitoSpyBean TodoCreatedListener listener;
    Long ownerId;
    final LocalDate today = LocalDate.of(2026, 10, 29);

    @BeforeEach
    void seed() {
        properties.setEnabled(true);
        jdbc.update("DELETE FROM todo_history");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (username, email, password, role, enabled) VALUES"
                + " ('mail-owner', 'owner@example.com', 'unused', 'USER', true),"
                + " ('mail-other', 'other@example.com', 'unused', 'USER', true),"
                + " ('mail-disabled', 'disabled@example.com', 'unused', 'USER', false),"
                + " ('mail-no-email', NULL, 'unused', 'USER', true),"
                + " ('mail-admin', NULL, 'unused', 'ADMIN', true)");
        ownerId = users.findByUsername("mail-owner").getId();
        Clock fixed = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneId.of("Asia/Tokyo"));
        when(reminderClock.instant()).thenReturn(fixed.instant());
        when(reminderClock.getZone()).thenReturn(fixed.getZone());
        when(sender.createMimeMessage()).thenAnswer(call -> new JavaMailSenderImpl().createMimeMessage());
    }

    @Test
    void sendsCreationToOwnerOnEmailWorkerOnlyAfterCommitAndNotAfterRollback() {
        Todo todo = sample(ownerId, null);
        String callerThread = Thread.currentThread().getName();
        var workerThread = new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> { workerThread.set(Thread.currentThread().getName()); return null; })
                .when(sender).send(any(SimpleMailMessage.class));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.create(todo);
            verifyNoInteractions(sender);
        });
        var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, timeout(5000)).send(capture.capture());
        assertThat(capture.getValue().getTo()).containsExactly("owner@example.com");
        assertThat(workerThread.get()).startsWith("Email-").isNotEqualTo(callerThread);
        clearInvocations(listener);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.create(sample(ownerId, null));
            status.setRollbackOnly();
        });
        verify(listener, never()).afterCommit(any());
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void dailyReminderSelectsTomorrowInJapanExcludesIneligibleAndRecordsOnlySuccessfulDelivery() throws Exception {
        Todo eligible = insert("mail-owner", today.plusDays(1), false, false);
        insert("mail-other", today.plusDays(1), false, false);
        insert("mail-owner", today, false, false);
        insert("mail-owner", today.plusDays(2), false, false);
        insert("mail-owner", null, false, false);
        insert("mail-owner", today.plusDays(1), true, false);
        insert("mail-owner", today.plusDays(1), false, true);
        insert("mail-disabled", today.plusDays(1), false, false);
        insert("mail-no-email", today.plusDays(1), false, false);
        Object before = jdbc.queryForObject("SELECT updated_at FROM todos WHERE id=?", Object.class, eligible.getId());
        scheduler.sendDailyReminders();
        var capture = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(capture.capture());
        assertThat(capture.getAllValues().stream().map(m -> {
            try { return m.getAllRecipients()[0].toString(); }
            catch (Exception e) { throw new AssertionError(e); }
        }).toList()).containsExactly("owner@example.com", "other@example.com");
        assertThat(jdbc.queryForObject("SELECT reminder_notified_date FROM todos WHERE id=?", LocalDate.class, eligible.getId()))
                .isEqualTo(today.plusDays(1));
        assertThat(jdbc.queryForObject("SELECT updated_at FROM todos WHERE id=?", Object.class, eligible.getId())).isEqualTo(before);
        clearInvocations(sender);
        scheduler.sendDailyReminders();
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void overdueFailureIsRetriedNextRunAndDoesNotStopOtherOwnersOrMarkFailedTodo() {
        Todo failed = insert("mail-owner", today.minusDays(1), false, false);
        Todo success = insert("mail-other", today.minusDays(2), false, false);
        doAnswer(call -> {
            MimeMessage message = call.getArgument(0);
            if (message.getAllRecipients()[0].toString().equals("owner@example.com")) throw new MailSendException("temporary");
            return null;
        }).when(sender).send(any(MimeMessage.class));
        scheduler.checkOverdueTodos();
        verify(sender, times(4)).send(any(MimeMessage.class));
        assertThat(jdbc.queryForObject("SELECT overdue_notified_date FROM todos WHERE id=?", LocalDate.class, failed.getId())).isNull();
        assertThat(jdbc.queryForObject("SELECT overdue_notified_date FROM todos WHERE id=?", LocalDate.class, success.getId()))
                .isEqualTo(today.minusDays(2));
        doNothing().when(sender).send(any(MimeMessage.class));
        clearInvocations(sender);
        scheduler.checkOverdueTodos();
        verify(sender).send(any(MimeMessage.class));
        clearInvocations(sender);
        scheduler.checkOverdueTodos();
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void dryRunDoesNotMarkDeliveredAndChangingDeadlineOrReopeningResetsNotification() {
        Todo todo = insert("mail-owner", today.minusDays(1), false, false);
        properties.setEnabled(false);
        scheduler.checkOverdueTodos();
        verifyNoInteractions(sender);
        assertThat(todos.findOverdue(today)).extracting(Todo::getId).contains(todo.getId());
        properties.setEnabled(true);
        scheduler.checkOverdueTodos();
        assertThat(todos.findOverdue(today)).isEmpty();
        todo = todos.findById(todo.getId());
        todo.setDueDate(today.minusDays(2));
        todos.update(todo);
        assertThat(todos.findOverdue(today)).extracting(Todo::getId).containsExactly(todo.getId());
        scheduler.checkOverdueTodos();
        todo.setCompleted(true);
        todos.update(todo);
        todo.setCompleted(false);
        todos.update(todo);
        assertThat(todos.findOverdue(today)).extracting(Todo::getId).containsExactly(todo.getId());
    }

    @Test
    void validatesAndPersistsOptionalEmailOnRegistrationAndAdminEdit() throws Exception {
        mvc.perform(post("/register").with(anonymous()).with(csrf())
                .param("username", "mail-new").param("email", "new@example.com")
                .param("password", "password123").param("passwordConfirmation", "password123"))
                .andExpect(status().is3xxRedirection());
        assertThat(users.findByUsername("mail-new").getEmail()).isEqualTo("new@example.com");
        mvc.perform(post("/register").with(anonymous()).with(csrf())
                .param("username", "mail-invalid").param("email", "invalid-address")
                .param("password", "password123").param("passwordConfirmation", "password123"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("registrationForm", "email"));
        assertThat(users.findByUsername("mail-invalid")).isNull();
        mvc.perform(get("/admin/users/{id}/edit", ownerId).with(user("mail-admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("owner@example.com")));
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("mail-admin").roles("ADMIN")).with(csrf())
                .param("role", "USER").param("enabled", "true"))
                .andExpect(status().is3xxRedirection());
        assertThat(users.findById(ownerId).getEmail()).isEqualTo("owner@example.com");
        mvc.perform(post("/admin/users/{id}", ownerId).with(user("mail-admin").roles("ADMIN")).with(csrf())
                .param("username", "mail-owner").param("role", "USER").param("enabled", "true").param("email", ""))
                .andExpect(status().is3xxRedirection());
        assertThat(users.findById(ownerId).getEmail()).isEmpty();
    }

    private Todo sample(Long userId, LocalDate dueDate) {
        Todo todo = new Todo();
        todo.setUserId(userId);
        todo.setTitle("日本語の通知");
        todo.setDetail("通知テスト");
        todo.setCategory("プログラミング");
        todo.setPriority(1);
        todo.setDueDate(dueDate);
        return todo;
    }

    private Todo insert(String username, LocalDate due, boolean completed, boolean deleted) {
        Todo todo = sample(users.findByUsername(username).getId(), due);
        todo.setCompleted(completed);
        todos.insert(todo);
        if (deleted) todos.deleteById(todo.getUserId(), todo.getId());
        return todo;
    }
}
