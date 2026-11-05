-- 已作废的配置令牌摘要。只存 HERMIT_REEF_AGENT_TOKEN 的 SHA-256，不存明文。
-- 禁用该令牌当时的归属管理员，或管理端作废，都插入当前值的摘要。
-- 配置改成另一串非空值后，新摘要对不上这些行，新值可用；旧值仍然 401。
-- 不改 V1–V6。没有外键。H2（MODE=REGULAR）和 MySQL 8.4 都接受。

CREATE TABLE hermit_voided_config_tokens (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    voided_at TIMESTAMP NOT NULL
);
