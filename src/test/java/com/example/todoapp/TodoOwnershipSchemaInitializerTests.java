package com.example.todoapp;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class TodoOwnershipSchemaInitializerTests {
    @Test
    void migratesExistingRowsToSonjinAndPreservesAccountsAndOwnersOnRestart() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:legacy-ownership;MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/users.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE users DROP COLUMN updated_at");
        jdbc.execute("ALTER TABLE users DROP COLUMN email");
        jdbc.execute("CREATE TABLE todos (id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255), "
                + "completed BOOLEAN DEFAULT FALSE, completed_at DATETIME, deleted_at DATETIME, "
                + "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO todos (title) VALUES ('legacy-active'), ('legacy-trash')");
        jdbc.update("UPDATE todos SET completed = TRUE, completed_at = '2026-10-01 12:00:00', "
                + "deleted_at = '2026-10-02 12:00:00', updated_at = '2026-10-03 12:00:00' WHERE title = 'legacy-trash'");
        var original = jdbc.queryForMap("SELECT * FROM todos WHERE title = 'legacy-trash'");
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        TodoOwnershipSchemaInitializer initializer = new TodoOwnershipSchemaInitializer(dataSource, encoder);
        initializer.afterPropertiesSet();
        assertNull(jdbc.queryForObject("SELECT email FROM users LIMIT 1", String.class));
        jdbc.update("UPDATE users SET email = 'existing@example.com'");
        assertNull(jdbc.queryForObject("SELECT reminder_notified_date FROM todos LIMIT 1", Object.class));
        assertNull(jdbc.queryForObject("SELECT overdue_notified_date FROM todos LIMIT 1", Object.class));
        Long sonjinId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'sonjin'", Long.class);
        String hash = jdbc.queryForObject("SELECT password FROM users WHERE username = 'sonjin'", String.class);
        assertTrue(encoder.matches("password", hash));
        assertNotEquals("password", hash);
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM todos WHERE user_id = ?", Integer.class, sonjinId));
        var migrated = jdbc.queryForMap("SELECT * FROM todos WHERE title = 'legacy-trash'");
        for (String field : original.keySet()) {
            assertEquals(original.get(field), migrated.get(field), field);
        }
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO todos (title, user_id) VALUES ('invalid', -1)"));
        jdbc.update("INSERT INTO users (username, password, role) VALUES ('other', 'existing-hash', 'USER')");
        Long otherId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'other'", Long.class);
        jdbc.update("UPDATE todos SET user_id = ? WHERE title = 'legacy-active'", otherId);
        jdbc.update("UPDATE users SET password = 'changed-password-hash' WHERE id = ?", sonjinId);
        initializer.afterPropertiesSet();
        assertEquals("existing@example.com", jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, sonjinId));
        assertEquals(otherId, jdbc.queryForObject("SELECT user_id FROM todos WHERE title = 'legacy-active'", Long.class));
        assertEquals("changed-password-hash", jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, sonjinId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", Object.class, sonjinId));
        // 管理画面での改名・削除後に初期ユーザーを再作成しない。
        jdbc.update("UPDATE users SET username = 'renamed-sonjin' WHERE id = ?", sonjinId);
        initializer.afterPropertiesSet();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'sonjin'", Integer.class));
        jdbc.update("DELETE FROM todos WHERE user_id = ?", sonjinId);
        jdbc.update("DELETE FROM users WHERE id = ?", sonjinId);
        initializer.afterPropertiesSet();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'sonjin'", Integer.class));
    }
}
