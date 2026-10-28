package com.example.todoapp;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReportService {
    private final TodoService todos;

    @Async("taskExecutor")
    public CompletableFuture<Report> generateReportAsync() {
        // TodoServiceを経由し、本人のみ／管理者は全件という既存の認可を適用する。
        var items = todos.search("", "", "asc", true);
        long completed = items.stream().filter(todo -> Boolean.TRUE.equals(todo.getCompleted())).count();
        return CompletableFuture.completedFuture(new Report(
                items.size(), completed, items.size() - completed, LocalDateTime.now()));
    }

    @Async("taskExecutor")
    public CompletableFuture<Statistics> calculateStatisticsAsync() {
        var items = todos.search("", "", "asc", false);
        return CompletableFuture.completedFuture(new Statistics(
                items.stream().filter(Todo::isOverdue).count(),
                items.stream().filter(Todo::isNearDeadline).count(), todos.countTrash("", "")));
    }
}
