-- 某个已发布版本被成功下载的次数。按版本累计，不按 Crab 实例。
-- 失败、404、停用、清单和 304 不计入。管理端规则包的 pullCount 是各版本之和。

ALTER TABLE hermit_conch_versions ADD COLUMN pull_count BIGINT NOT NULL DEFAULT 0;
