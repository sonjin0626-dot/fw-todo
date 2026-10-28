package com.example.todoapp;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardControllerTests {
    ReportService reports = mock(ReportService.class);
    ExtendedModelMap model = new ExtendedModelMap();
    MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void retrievesBothResults() {
        Report report = new Report(3, 1, 2, LocalDateTime.now());
        Statistics stats = new Statistics(1, 1, 4);
        when(reports.generateReportAsync()).thenReturn(CompletableFuture.completedFuture(report));
        when(reports.calculateStatisticsAsync()).thenReturn(CompletableFuture.completedFuture(stats));

        assertThat(new DashboardController(reports, 3).dashboard(model, response)).isEqualTo("dashboard");
        assertThat(model).containsEntry("report", report).containsEntry("stats", stats);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void timesOutAcrossBothTasksWithoutPublishingPartialResults() {
        when(reports.generateReportAsync()).thenReturn(CompletableFuture.completedFuture(
                new Report(0, 0, 0, LocalDateTime.now())));
        when(reports.calculateStatisticsAsync()).thenReturn(new CompletableFuture<>());

        new DashboardController(reports, 0).dashboard(model, response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(model).containsEntry("errorKey", "dashboard.timeout").doesNotContainKeys("report", "stats");
    }

    @Test
    void handlesFutureFailureWithoutExposingInternalDetails() {
        when(reports.generateReportAsync()).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("private database details")));
        when(reports.calculateStatisticsAsync()).thenReturn(CompletableFuture.completedFuture(new Statistics(0, 0, 0)));

        new DashboardController(reports, 3).dashboard(model, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(model).containsEntry("errorKey", "dashboard.failed");
        assertThat(model.toString()).doesNotContain("private database details");
    }

    @Test
    void preservesAccessDeniedFromWorker() {
        when(reports.generateReportAsync()).thenReturn(CompletableFuture.failedFuture(new AccessDeniedException("denied")));
        when(reports.calculateStatisticsAsync()).thenReturn(CompletableFuture.completedFuture(new Statistics(0, 0, 0)));

        assertThatThrownBy(() -> new DashboardController(reports, 3).dashboard(model, response))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void handlesExecutorSaturation() {
        when(reports.generateReportAsync()).thenThrow(new TaskRejectedException("full"));
        new DashboardController(reports, 3).dashboard(model, response);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(model).containsEntry("errorKey", "dashboard.busy");
    }

    @Test
    void restoresInterruptStatus() {
        when(reports.generateReportAsync()).thenReturn(new CompletableFuture<>());
        when(reports.calculateStatisticsAsync()).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        new DashboardController(reports, 3).dashboard(model, response);

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(response.getStatus()).isEqualTo(503);
    }
}
