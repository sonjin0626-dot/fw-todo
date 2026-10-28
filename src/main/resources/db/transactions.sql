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
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) DEFAULT CHARACTER SET utf8mb4;
