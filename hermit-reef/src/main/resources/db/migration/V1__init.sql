-- 时间列都是 UTC。标识列用 AUTO_INCREMENT：H2（MODE=REGULAR）和 MySQL 8.4 都接受，省略 id 时从 1 起生成。
-- hourly_counts 的主键让同一实例、同一小时的重复上报可以按主键覆盖。

CREATE TABLE admin_users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_admin_users_username UNIQUE (username)
);

CREATE TABLE admin_login_failures (
    username VARCHAR(128) NOT NULL,
    failed_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_admin_login_failures_user_time
    ON admin_login_failures (username, failed_at);

CREATE TABLE conch_versions (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conch VARCHAR(128) NOT NULL,
    version VARCHAR(64) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    min_crab_version VARCHAR(64),
    package_path VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    uploaded_by VARCHAR(128) NOT NULL,
    uploaded_at TIMESTAMP NOT NULL,
    published_by VARCHAR(128),
    published_at TIMESTAMP,
    is_current BOOLEAN NOT NULL DEFAULT FALSE,
    validation_json TEXT,
    diff_json TEXT,
    CONSTRAINT uk_conch_versions_conch_version UNIQUE (conch, version),
    CONSTRAINT chk_conch_versions_status CHECK (status IN ('UPLOADED', 'PUBLISHED'))
);

CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    at TIMESTAMP NOT NULL,
    actor VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    conch VARCHAR(128),
    version VARCHAR(64),
    detail TEXT
);

CREATE INDEX idx_audit_log_at ON audit_log (at);

CREATE INDEX idx_audit_log_conch_at ON audit_log (conch, at);

CREATE TABLE failure_reports (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    received_at TIMESTAMP NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    crab_version VARCHAR(64),
    trace_id VARCHAR(128),
    conch VARCHAR(128) NOT NULL,
    conch_version VARCHAR(64),
    action VARCHAR(128),
    step_index INTEGER,
    step_type VARCHAR(64),
    error_code VARCHAR(64),
    summary VARCHAR(1024),
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_failure_reports_conch_version_occurred
    ON failure_reports (conch, conch_version, occurred_at);

CREATE INDEX idx_failure_reports_received_at
    ON failure_reports (received_at);

CREATE TABLE hourly_counts (
    conch VARCHAR(128) NOT NULL,
    conch_version VARCHAR(64) NOT NULL,
    action VARCHAR(128) NOT NULL,
    hour_start TIMESTAMP NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    success BIGINT NOT NULL,
    failure BIGINT NOT NULL,
    avg_millis BIGINT,
    p95_millis BIGINT,
    PRIMARY KEY (conch, conch_version, action, hour_start, instance_id)
);

CREATE INDEX idx_hourly_counts_conch_hour
    ON hourly_counts (conch, hour_start);

CREATE TABLE rollback_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    received_at TIMESTAMP NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    conch VARCHAR(128) NOT NULL,
    from_version VARCHAR(64),
    to_version VARCHAR(64),
    reason VARCHAR(1024),
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_rollback_events_received_at
    ON rollback_events (received_at);

CREATE TABLE alerts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at TIMESTAMP NOT NULL,
    kind VARCHAR(64) NOT NULL,
    conch VARCHAR(128),
    conch_version VARCHAR(64),
    detail_json TEXT,
    acknowledged_at TIMESTAMP
);
