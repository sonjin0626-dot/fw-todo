package com.example.todoapp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;

/** 認可後に作成された試行ログだけに変更前後と確定結果を補う。 */
@Aspect
@Component
@Order(300)
@Slf4j
public class AuditAspect {
    private final AuditMapper mapper;
    private final AuditService audit;
    private final JsonMapper json;

    public AuditAspect(AuditMapper mapper, AuditService audit, JsonMapper json) {
        this.mapper = mapper;
        this.audit = audit;
        this.json = json;
    }

    @Around("@annotation(auditable)")
    public Object record(ProceedingJoinPoint call, Auditable auditable) throws Throwable {
        if (!"TODO".equals(auditable.entityType())) {
            throw new IllegalArgumentException("監査対象のスナップショット取得処理が未定義です");
        }
        try (AuditContext context = new AuditContext(auditable)) {
            // トランザクション内で読み、外部へ返さない。認可失敗時はログを作成しない。
            if (auditable.action() != AuditLog.Action.CREATE) {
                for (Object argument : call.getArgs()) captureBefore(context, argument);
            }
            boolean returned = false;
            try {
                Object result = call.proceed();
                returned = true;
                for (AuditLog entry : context.entries) {
                    if (auditable.action() == AuditLog.Action.CREATE) {
                        for (Object argument : call.getArgs()) {
                            if (argument instanceof Todo todo) entry.setEntityId(todo.getId());
                        }
                    }
                    entry.setNewValue(snapshot(entry.getEntityId()));
                }
                return result;
            } finally {
                finish(context.entries, returned);
            }
        }
    }

    private void captureBefore(AuditContext context, Object argument) {
        if (argument instanceof Todo todo) captureBefore(context, todo.getId());
        else if (argument instanceof Long id) context.before.put(id, snapshot(id));
        else if (argument instanceof List<?> ids) ids.stream().distinct().forEach(id -> captureBefore(context, id));
    }

    private String snapshot(Long id) {
        Todo todo = id == null ? null : mapper.snapshot(id);
        if (todo == null) return null;
        // 保存対象を明示し、画面向けの計算プロパティや認証情報は含めない。
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", todo.getId());
        value.put("userId", todo.getUserId());
        value.put("title", todo.getTitle());
        value.put("detail", todo.getDetail());
        value.put("category", todo.getCategory());
        value.put("priority", todo.getPriority());
        value.put("dueDate", todo.getDueDate());
        value.put("completed", todo.getCompleted());
        value.put("completedAt", todo.getCompletedAt());
        value.put("deletedAt", todo.getDeletedAt());
        value.put("pinned", todo.getPinned());
        value.put("createdAt", todo.getCreatedAt());
        value.put("updatedAt", todo.getUpdatedAt());
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private void finish(List<AuditLog> entries, boolean returned) {
        if (entries.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    String outcome = status == STATUS_COMMITTED ? "COMMITTED"
                            : status == STATUS_ROLLED_BACK ? "ROLLED_BACK" : "UNKNOWN";
                    complete(entries, outcome);
                }
            });
        } else {
            complete(entries, returned ? "COMMITTED" : "FAILED");
        }
    }

    private void complete(List<AuditLog> entries, String outcome) {
        for (AuditLog entry : entries) {
            entry.setOutcome("COMMITTED".equals(outcome) && Objects.equals(entry.getOldValue(), entry.getNewValue())
                    ? "NO_CHANGE" : outcome);
            try {
                audit.complete(entry);
            } catch (RuntimeException failure) {
                // 本体の確定後なので、元の試行ログを保持して結果補完の失敗を記録する。
                log.error("監査ログの結果保存に失敗 auditId={}", entry.getId(), failure);
            }
        }
    }
}
