package com.example.todoapp;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 既存DBを破棄せずに所有者・アカウント・メール通知用の列と外部キーを追加する。 */
@Component
@DependsOnDatabaseInitialization
public class TodoOwnershipSchemaInitializer implements InitializingBean {
    private final DataSource dataSource;
    private final PasswordEncoder passwordEncoder;

    public TodoOwnershipSchemaInitializer(DataSource dataSource, PasswordEncoder passwordEncoder) {
        this.dataSource = dataSource;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String table = metadata.storesUpperCaseIdentifiers() ? "TODOS" : "todos";
            String column = metadata.storesUpperCaseIdentifiers() ? "USER_ID" : "user_id";
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            try (Statement statement = connection.createStatement()) {
                String usersTable = metadata.storesUpperCaseIdentifiers() ? "USERS" : "users";
                String emailColumn = metadata.storesUpperCaseIdentifiers() ? "EMAIL" : "email";
                try (ResultSet columns = metadata.getColumns(catalog, schema, usersTable, emailColumn)) {
                    if (!columns.next()) {
                        statement.executeUpdate("ALTER TABLE users ADD COLUMN email VARCHAR(254) NULL");
                    }
                }
                for (String notificationColumn : java.util.List.of("reminder_notified_date", "overdue_notified_date")) {
                    String name = metadata.storesUpperCaseIdentifiers() ? notificationColumn.toUpperCase(java.util.Locale.ROOT) : notificationColumn;
                    try (ResultSet columns = metadata.getColumns(catalog, schema, table, name)) {
                        if (!columns.next()) {
                            statement.executeUpdate("ALTER TABLE todos ADD COLUMN " + notificationColumn + " DATE NULL");
                        }
                    }
                }
                String enabledColumn = metadata.storesUpperCaseIdentifiers() ? "ENABLED" : "enabled";
                try (ResultSet columns = metadata.getColumns(catalog, schema, usersTable, enabledColumn)) {
                    if (!columns.next()) {
                        statement.executeUpdate("ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true");
                    }
                }
                String updatedColumn = metadata.storesUpperCaseIdentifiers() ? "UPDATED_AT" : "updated_at";
                try (ResultSet columns = metadata.getColumns(catalog, schema, usersTable, updatedColumn)) {
                    if (!columns.next()) {
                        statement.executeUpdate("ALTER TABLE users ADD COLUMN updated_at TIMESTAMP NOT NULL "
                                + "DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
                    }
                }
                try (ResultSet columns = metadata.getColumns(catalog, schema, table, column)) {
                    if (!columns.next()) {
                        // 既存データは削除せず、列追加後に指定の作成者へ移行する。
                        statement.executeUpdate("ALTER TABLE todos ADD COLUMN user_id BIGINT NULL");
                    }
                }
                boolean foreignKeyExists = false;
                try (ResultSet keys = metadata.getImportedKeys(catalog, schema, table)) {
                    while (keys.next()) {
                        if ("user_id".equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                                && "users".equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) {
                            foreignKeyExists = true;
                        }
                    }
                }
                if (!foreignKeyExists) {
                    statement.executeUpdate("ALTER TABLE todos ADD CONSTRAINT fk_todos_user "
                            + "FOREIGN KEY (user_id) REFERENCES users(id)");
                }
            }
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        // 移行が不要なら、管理画面で削除・改名した初期アカウントを再作成しない。
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) > 0
                && jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE user_id IS NULL", Long.class) == 0) {
            return;
        }
        // 指定された既存データの作成者。既存アカウントのパスワードは変更しない。
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, "sonjin") == 0) {
            jdbc.update("INSERT INTO users (username, password, role) VALUES (?, ?, ?)",
                    "sonjin", passwordEncoder.encode("password"), "USER");
        }
        Long ownerId = jdbc.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, "sonjin");
        // MySQLのON UPDATEによって、所有者移行だけで更新日時が変わらないようにする。
        jdbc.update("UPDATE todos SET user_id = ?, updated_at = updated_at WHERE user_id IS NULL", ownerId);
    }
}
