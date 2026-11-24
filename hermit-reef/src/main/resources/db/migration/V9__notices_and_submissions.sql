-- 公共公告（Reef 首页同步消息）与规则包审批单（普通用户提交、管理员批准发布或驳回）。
-- 不改 V1–V8。两张表从空开始，不影响已有管理员、规则包版本或上报数据。
-- H2（MODE=REGULAR）和 MySQL 8.4 都接受这些语句。所有表、索引与约束使用 hermit_ 前缀，没有外键。

CREATE TABLE hermit_announcements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    body TEXT NOT NULL,
    level VARCHAR(16) NOT NULL DEFAULT 'INFO',
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_hermit_announcements_level CHECK (level IN ('INFO', 'WARNING', 'IMPORTANT'))
);

CREATE INDEX idx_hermit_announcements_order
    ON hermit_announcements (pinned, updated_at);

CREATE TABLE hermit_conch_submissions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conch VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    min_crab_version VARCHAR(64) NOT NULL,
    driver VARCHAR(32) NOT NULL DEFAULT 'http',
    title VARCHAR(256) NOT NULL,
    yaml_path VARCHAR(1024) NOT NULL,
    validation_json TEXT,
    diff_json TEXT,
    submit_note VARCHAR(500),
    status VARCHAR(16) NOT NULL,
    submitted_by VARCHAR(128) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    reviewed_by VARCHAR(128),
    reviewed_at TIMESTAMP,
    review_comment VARCHAR(500),
    CONSTRAINT chk_hermit_conch_submissions_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))
);

CREATE INDEX idx_hermit_conch_submissions_status_at
    ON hermit_conch_submissions (status, submitted_at);

CREATE INDEX idx_hermit_conch_submissions_user_at
    ON hermit_conch_submissions (submitted_by, submitted_at);

CREATE INDEX idx_hermit_conch_submissions_conch
    ON hermit_conch_submissions (conch, version);
