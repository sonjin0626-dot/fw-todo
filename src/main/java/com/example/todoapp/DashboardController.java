package com.example.todoapp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class DashboardController {
    private final ReportService reports;
    private final long timeoutSeconds;

    public DashboardController(ReportService reports,
            @Value("${app.async.report-timeout-seconds:3}") long timeoutSeconds) {
        this.reports = reports;
        this.timeoutSeconds = timeoutSeconds;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, HttpServletResponse response) {
        try {
            CompletableFuture<Report> report = reports.generateReportAsync();
            CompletableFuture<Statistics> stats = reports.calculateStatisticsAsync();
            // 両方を開始してから、全体で最大3秒（設定値）だけ待つ。
            CompletableFuture.allOf(report, stats).get(timeoutSeconds, TimeUnit.SECONDS);
            model.addAttribute("report", report.get());
            model.addAttribute("stats", stats.get());
        } catch (TimeoutException exception) {
            log.warn("ダッシュボード集計がタイムアウトしました");
            showError(model, response, 503, "dashboard.timeout");
        } catch (TaskRejectedException exception) {
            log.warn("ダッシュボード集計のキューが満杯です", exception);
            showError(model, response, 503, "dashboard.busy");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            showError(model, response, 503, "dashboard.failed");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AccessDeniedException denied) {
                throw denied;
            }
            log.error("ダッシュボード集計に失敗しました", exception.getCause());
            showError(model, response, 500, "dashboard.failed");
        }
        return "dashboard";
    }

    private void showError(Model model, HttpServletResponse response, int status, String key) {
        response.setStatus(status);
        model.addAttribute("errorKey", key);
    }
}
