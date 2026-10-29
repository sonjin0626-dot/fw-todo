package com.example.todoapp;

import java.time.LocalDateTime;
import lombok.Data;

/** 操作の試行と、トランザクションの最終結果を保持する。 */
@Data
public class AuditLog {
    private Long id;
    private String action;
    private String entityType;
    private Long entityId;
    private Long userId;
    private String username;
    private String oldValue;
    private String newValue;
    private String ipAddress;
    private String userAgent;
    private String outcome;
    private LocalDateTime createdAt;

    public enum Action { CREATE, UPDATE, DELETE, BULK_DELETE, RESTORE, TOGGLE_PIN }

    public String getOperation() {
        return action != null && action.endsWith("_ATTEMPT")
                ? action.substring(0, action.length() - "_ATTEMPT".length()) : action;
    }
}
