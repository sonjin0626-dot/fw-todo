-- 履歴上のIDは、ユーザー・ToDoの物理削除後やロールバック後も保持する。
-- 独立トランザクションから未コミットの行を参照しないよう外部キーは設けない。
CREATE TABLE IF NOT EXISTS todo_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    todo_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    action VARCHAR(40) NOT NULL,
    todo_id BIGINT NULL,
    actor_user_id BIGINT NOT NULL,
    entity_type VARCHAR(50) NOT NULL DEFAULT 'TODO',
    entity_id BIGINT NULL,
    user_id BIGINT NULL,
    username VARCHAR(100) NULL,
    old_value TEXT NULL,
    new_value TEXT NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,
    outcome VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    INDEX idx_audit_entity (entity_type, entity_id),
    INDEX idx_audit_user (user_id),
    INDEX idx_audit_created (created_at, id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) DEFAULT CHARACTER SET utf8mb4;
