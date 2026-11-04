-- 管理员角色、按用户保存的 Agent 令牌哈希，以及 Crab 实例归属。
-- 不改 V1–V5。已有管理员行的 role 默认为 ADMIN。
-- owner_user_id 不进主键；旧的失败、小时计数和版本切换行保持 NULL。
-- H2（MODE=REGULAR）和 MySQL 8.4 都接受这些语句。约束名带 hermit_ 前缀。没有外键。

ALTER TABLE hermit_admin_users
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'ADMIN';

ALTER TABLE hermit_admin_users
    ADD CONSTRAINT ck_hermit_admin_users_role CHECK (role IN ('ADMIN', 'USER'));

CREATE TABLE hermit_agent_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    token_prefix VARCHAR(16) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP NULL,
    revoked_at TIMESTAMP NULL
);

-- 单独建唯一索引，名字在 H2 和 MySQL 上都是 uk_hermit_agent_tokens_hash。
-- 写进 CREATE TABLE 的约束在 H2 里会再加一段生成后缀。
CREATE UNIQUE INDEX uk_hermit_agent_tokens_hash
    ON hermit_agent_tokens (token_hash);

CREATE INDEX idx_hermit_agent_tokens_user
    ON hermit_agent_tokens (user_id);

CREATE TABLE hermit_crab_instances (
    user_id BIGINT NOT NULL,
    instance_id VARCHAR(36) NOT NULL,
    token_id BIGINT NULL,
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    crab_version VARCHAR(32) NULL,
    PRIMARY KEY (user_id, instance_id)
);

ALTER TABLE hermit_failure_reports ADD COLUMN owner_user_id BIGINT NULL;

ALTER TABLE hermit_hourly_counts ADD COLUMN owner_user_id BIGINT NULL;

ALTER TABLE hermit_rollback_events ADD COLUMN owner_user_id BIGINT NULL;
