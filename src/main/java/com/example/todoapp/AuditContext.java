package com.example.todoapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 同じ呼び出し内の試行ログをAOPの前後スナップショットに関連付ける。 */
public final class AuditContext implements AutoCloseable {
    private static final ThreadLocal<AuditContext> CURRENT = new ThreadLocal<>();
    private final AuditContext previous;
    final Auditable annotation;
    final Map<Long, String> before = new HashMap<>();
    final List<AuditLog> entries = new ArrayList<>();

    AuditContext(Auditable annotation) {
        this.annotation = annotation;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static AuditContext current() { return CURRENT.get(); }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
