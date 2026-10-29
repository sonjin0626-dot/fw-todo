package com.example.todoapp;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

class AuditSchemaInitializerTests {
    @Test
    void migratesLegacyAuditTableWithoutLosingRecordsAndCanRunAgain() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:legacy-audit;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE audit_logs (id BIGINT AUTO_INCREMENT PRIMARY KEY, action VARCHAR(40) NOT NULL,"
                + " todo_id BIGINT NULL, actor_user_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO audit_logs (action, todo_id, actor_user_id, created_at)"
                + " VALUES ('UPDATE_ATTEMPT', 44, 55, '2026-01-01 12:34:56'), ('CREATE_ATTEMPT', NULL, 66, '2026-01-01 13:00:00')");
        var before = jdbc.queryForList("SELECT id, action, todo_id, actor_user_id, created_at FROM audit_logs ORDER BY id");
        var initializer = new AuditSchemaInitializer(dataSource);
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();
        assertThat(jdbc.queryForList("SELECT id, action, todo_id, actor_user_id, created_at FROM audit_logs ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT entity_id FROM audit_logs ORDER BY id", Long.class)).containsExactly(44L, null);
        assertThat(jdbc.queryForList("SELECT user_id FROM audit_logs ORDER BY id", Long.class)).containsExactly(55L, 66L);
        assertThat(jdbc.queryForList("SELECT outcome FROM audit_logs", String.class)).containsOnly("LEGACY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'AUDIT_LOGS' AND INDEX_NAME LIKE 'IDX_AUDIT_%'", Long.class)).isEqualTo(3);
    }
}
