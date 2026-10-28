package com.example.todoapp;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:async-test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AsyncProcessingIntegrationTests.ProbeConfig.class)
@WithMockUser(username = "async-owner")
class AsyncProcessingIntegrationTests {
    @Autowired ReportService reports;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired FailureProbe failureProbe;
    @Autowired @Qualifier("taskExecutor") ThreadPoolTaskExecutor executor;
    @MockitoSpyBean TodoService todos;
    @MockitoSpyBean TodoCreatedListener listener;
    Long ownerId;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM todo_history");
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM todos");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users (username, password, role) VALUES"
                + " ('async-owner', 'unused', 'USER'), ('async-other', 'unused', 'USER'), ('async-admin', 'unused', 'ADMIN')");
        ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username='async-owner'", Long.class);
        Long otherId = jdbc.queryForObject("SELECT id FROM users WHERE username='async-other'", Long.class);
        insert(ownerId, false, LocalDate.now().minusDays(1), false);
        insert(ownerId, false, LocalDate.now().plusDays(3), false);
        insert(ownerId, false, LocalDate.now().plusDays(4), false);
        insert(ownerId, true, LocalDate.now().minusDays(1), false);
        insert(ownerId, false, LocalDate.now(), true);
        insert(otherId, false, LocalDate.now(), false);
    }

    @Test
    void propagatesOwnerAndAdminScopesToWorkers() throws Exception {
        Report report = reports.generateReportAsync().get(3, TimeUnit.SECONDS);
        Statistics stats = reports.calculateStatisticsAsync().get(3, TimeUnit.SECONDS);
        assertThat(report.getTotalCount()).isEqualTo(4);
        assertThat(report.getCompletedCount()).isEqualTo(1);
        assertThat(report.getPendingCount()).isEqualTo(3);
        assertThat(stats).isEqualTo(new Statistics(1, 1, 1));

        authenticate("async-admin", "ADMIN");
        assertThat(reports.generateReportAsync().get(3, TimeUnit.SECONDS).getTotalCount()).isEqualTo(5);
        authenticate("async-other", "USER");
        assertThat(reports.generateReportAsync().get(3, TimeUnit.SECONDS).getTotalCount()).isEqualTo(1);

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> reports.generateReportAsync().get(3, TimeUnit.SECONDS))
                .hasCauseInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(executor.submit(() -> SecurityContextHolder.getContext().getAuthentication())
                .get(3, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void startsBothReportsOnSeparateWorkersBeforeEitherCompletes() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        LinkedBlockingQueue<String> threads = new LinkedBlockingQueue<>();
        doAnswer(call -> {
            threads.add(Thread.currentThread().getName());
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("worker not released");
            return call.callRealMethod();
        }).when(todos).search(anyString(), anyString(), anyString(), anyBoolean());

        CompletableFuture<Report> report = reports.generateReportAsync();
        CompletableFuture<Statistics> stats = reports.calculateStatisticsAsync();
        try {
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(report).isNotDone();
            assertThat(stats).isNotDone();
            assertThat(threads).hasSize(2).allMatch(name -> name.startsWith("Async-"));
            assertThat(threads.stream().distinct().count()).isEqualTo(2);
        } finally {
            release.countDown();
            CompletableFuture.allOf(report, stats).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void notifiesOnlyAfterCommitAndUsesEmailPool() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationService.class);
        LinkedBlockingQueue<ILoggingEvent> events = new LinkedBlockingQueue<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            Todo todo = sample();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                todos.create(todo);
                verify(listener, never()).afterCommit(any());
            });
            verify(listener).afterCommit(new TodoCreatedEvent(todo.getId(), ownerId));
            ILoggingEvent event = events.poll(3, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.getThreadName()).startsWith("Email-");
            assertThat(event.getFormattedMessage()).contains("todoId=" + todo.getId());

            clearInvocations(listener);
            Todo rolledBack = sample();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                todos.create(rolledBack);
                status.setRollbackOnly();
            });
            verify(listener, never()).afterCommit(any());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE id=?", Long.class, rolledBack.getId())).isZero();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void rendersTranslatedDashboardAndRequiresLogin() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().isOk()).andExpect(view().name("dashboard"))
                .andExpect(model().attributeExists("report", "stats"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ToDo集計")));
        mvc.perform(get("/dashboard").param("lang", "en")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ToDo dashboard")));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/dashboard").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void notificationQueueRejectionDoesNotTurnCommittedCreationIntoFailure() {
        NotificationService notifications = mock(NotificationService.class);
        doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(notifications).notifyTodoCreatedAsync(any());
        assertThatCode(() -> new TodoCreatedListener(notifications).afterCommit(new TodoCreatedEvent(1L, ownerId)))
                .doesNotThrowAnyException();
    }

    @Test
    void logsUncaughtVoidTaskFailuresWithTheConfiguredHandler() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
        LinkedBlockingQueue<ILoggingEvent> events = new LinkedBlockingQueue<>();
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            failureProbe.failAsync();
            ILoggingEvent event = events.poll(3, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            assertThat(event.getFormattedMessage()).contains("FailureProbe.failAsync");
            assertThat(event.getThreadName()).startsWith("Async-");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean FailureProbe failureProbe() { return new FailureProbe(); }
    }

    static class FailureProbe {
        @Async("taskExecutor")
        public void failAsync() { throw new IllegalStateException("expected async failure"); }
    }

    private void authenticate(String username, String role) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(context);
    }

    private Todo sample() {
        Todo todo = new Todo();
        todo.setTitle("async notification");
        todo.setCategory("プログラミング");
        todo.setPriority(1);
        return todo;
    }

    private void insert(Long userId, boolean completed, LocalDate due, boolean trash) {
        jdbc.update("INSERT INTO todos (user_id,title,category,priority,completed,due_date,deleted_at)"
                + " VALUES (?, 'report item', 'プログラミング', 1, ?, ?, ?)", userId, completed, due,
                trash ? java.time.LocalDateTime.now() : null);
    }
}
