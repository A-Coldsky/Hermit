-- Conch 是否停用。停用不改写已签名的版本包，只影响清单和发布。
-- disabled 为 FALSE 时，时间、操作人和原因都是 NULL。

CREATE TABLE hermit_conch_availability (
    conch VARCHAR(128) NOT NULL PRIMARY KEY,
    disabled BOOLEAN NOT NULL,
    disabled_at TIMESTAMP,
    disabled_by VARCHAR(128),
    disabled_reason VARCHAR(1024)
);
