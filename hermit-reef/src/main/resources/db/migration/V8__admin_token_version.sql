-- 管理员 JWT 的版本号。改口令或被管理员重置口令时加一，签发时写进 JWT 的 tv，
-- 鉴权时对不上就是 401：旧令牌不再等到 8 小时过期才失效。
-- 不改 V1–V7。已有行从 0 开始；没有 tv 的旧令牌按 0 处理。H2（MODE=REGULAR）和 MySQL 8.4 都接受。

ALTER TABLE hermit_admin_users
    ADD COLUMN token_version INT NOT NULL DEFAULT 0;
