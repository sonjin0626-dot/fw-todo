package com.example.todoapp;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

/** 既存の試行ログを削除せず、新しい監査列と検索索引を追加する。 */
@Component
@DependsOnDatabaseInitialization
public class AuditSchemaInitializer implements InitializingBean {
    private final DataSource dataSource;

    public AuditSchemaInitializer(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            var metadata = connection.getMetaData();
            String table = metadata.storesUpperCaseIdentifiers() ? "AUDIT_LOGS" : "audit_logs";
            Map<String, String> columns = new LinkedHashMap<>();
            columns.put("entity_type", "VARCHAR(50) NOT NULL DEFAULT 'TODO'");
            columns.put("entity_id", "BIGINT NULL");
            columns.put("user_id", "BIGINT NULL");
            columns.put("username", "VARCHAR(100) NULL");
            columns.put("old_value", "TEXT NULL");
            columns.put("new_value", "TEXT NULL");
            columns.put("ip_address", "VARCHAR(45) NULL");
            columns.put("user_agent", "VARCHAR(500) NULL");
            columns.put("outcome", "VARCHAR(20) NOT NULL DEFAULT 'LEGACY'");
            for (var column : columns.entrySet()) {
                String name = metadata.storesUpperCaseIdentifiers() ? column.getKey().toUpperCase(Locale.ROOT) : column.getKey();
                try (ResultSet found = metadata.getColumns(connection.getCatalog(), connection.getSchema(), table, name)) {
                    if (!found.next()) statement.executeUpdate("ALTER TABLE audit_logs ADD COLUMN " + column.getKey() + " " + column.getValue());
                }
            }
            // 再起動時にも安全。旧ログの作成日時・種別・IDは変えない。
            statement.executeUpdate("UPDATE audit_logs SET entity_id = todo_id WHERE entity_id IS NULL AND todo_id IS NOT NULL");
            statement.executeUpdate("UPDATE audit_logs SET user_id = actor_user_id WHERE user_id IS NULL");
            Map<String, String> indexes = Map.of("idx_audit_entity", "entity_type, entity_id",
                    "idx_audit_user", "user_id", "idx_audit_created", "created_at, id");
            var existing = new java.util.HashSet<String>();
            try (ResultSet found = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(), table, false, false)) {
                while (found.next()) {
                    String name = found.getString("INDEX_NAME");
                    if (name != null) existing.add(name.toLowerCase(Locale.ROOT));
                }
            }
            for (var index : indexes.entrySet()) {
                if (!existing.contains(index.getKey())) statement.executeUpdate("CREATE INDEX " + index.getKey()
                        + " ON audit_logs (" + index.getValue() + ")");
            }
        }
    }
}
