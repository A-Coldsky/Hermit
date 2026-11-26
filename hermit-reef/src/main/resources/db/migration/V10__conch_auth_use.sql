-- 规则包版本引用的授权器（规则里的 auth.use）。不改 V1–V9。
-- 新列可空、没有默认值：已有的版本行都是 NULL，表示不引用授权器。
-- 这之前 Reef 拒绝上传带 auth.use 的规则包，所以存量行不需要回填。
-- H2（MODE=REGULAR）和 MySQL 8.4 都接受这些语句。

ALTER TABLE hermit_conch_versions ADD COLUMN auth_use VARCHAR(128);

CREATE INDEX idx_hermit_conch_versions_auth_use ON hermit_conch_versions (auth_use);
