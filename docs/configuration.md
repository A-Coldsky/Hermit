# 配置与运维

## Crab 配置

原生 Java 的 `HERMIT_HOME` 默认 `~/.hermit`；读取其中的 `config.yml`。缺文件使用默认值，未知键/错误类型/超过 1,000,000 字节的文件会拒绝。普通字符串会 trim；不支持环境变量插值或从 `.env` 自动导入。配置文件启动时加载。容器入口会先从环境变量生成配置；`.env` 由 Compose 读取，见下面的容器配置。

| 键 | 默认值 | 行为 |
| --- | --- | --- |
| `reef.url` | 空 | 为空不创建同步/上报对象；有值必须有公钥 |
| `reef.token` | 空 | 发送为 `X-Hermit-Token`；当前 Reef 必须配置匹配的非空 token |
| `reef.public_key` | 空 | PEM 文本或文件路径；相对路径基于 HERMIT_HOME；已安装正式规则也需要公钥验证 |
| `server.host` | `127.0.0.1` | HTTP 监听地址，改为其他地址前确认访问边界 |
| `server.port` | `7777` | 0–65535；0 分配随机端口，主要用于测试 |
| `sync.interval_seconds` | `300` | 正整数，周期同步；启动另有一次异步同步 |
| `report.enabled` | `true` | 是否记录/发送运行上报；关闭不影响本地调用日志 |
| `report.interval_seconds` | `60` | 正整数；发送失败还有退避 |
| `capabilities.disabled` | `[]` | `conch.action` 列表，与数据库禁用状态取并集 |
| `aliases` | `{}` | URL 路径 → `conch.action`；不可占用 api/console/mcp/openapi 路径 |
| `mcp.allowed_origins` | `[]` | 额外允许访问 `/mcp` 的浏览器 Origin。没有 `Origin` 的客户端仍允许。本机源站见下方 |
| `engine.capability_timeout_seconds` | `60` | 正整数；当前不能可靠限制慢速响应的总时间，见审计 A03 |
| `engine.session_idle_minutes` | `10` | 空闲超过此阈值且配置 alive_check 时检测会话 |
| `engine.max_login_failures` | `3` | 连续登录失败后暂停该账号自动登录；只有测试登录成功才解除，失败不会清掉暂停 |

完整配置示例：

```yaml
reef:
  url: https://reef.example.com
  token: '替换为Agent令牌'
  public_key: reef.pub
server: {host: 127.0.0.1, port: 7777}
sync: {interval_seconds: 300}
report: {enabled: true, interval_seconds: 60}
capabilities:
  disabled: [legacy_oa.submit_leave]
aliases:
  /oa/todos: legacy_oa.list_todo
engine:
  capability_timeout_seconds: 60
  session_idle_minutes: 10
  max_login_failures: 3
mcp:
  allowed_origins: []
```

此例的 disabled 项是路径格式示意，示例 Conch 中不包含 submit_leave。别名仍使用相同的 API Key、账号和能力权限；重启后读取新别名。

`mcp.allowed_origins` 是 `/mcp` 的额外 Origin 允许列表，默认空。每一项必须是绝对的 `http` 或 `https` 源站，不能带路径、查询、用户信息或片段；写错会拒绝启动。没有 `Origin` 头的客户端仍然允许。本机始终允许 `http://127.0.0.1:<实际监听端口>`、`http://localhost:<实际监听端口>` 和 `http://[::1]:<实际监听端口>`，以及 `http://<server.host>:<实际监听端口>`。`server.host` 为 `0.0.0.0` 或 `::` 时不把通配地址当成源站；从局域网浏览器打开时，把浏览器地址栏对应的源站写进这个列表。反向代理的 `https://` 源站同样写在这里。比较时忽略 scheme 和主机的大小写，80 和 443 可以省略。请求如何被拒绝见[接口参考](api.md)的 MCP 一节。缺 `Origin` 是否拒绝、以及 `initialize` 遇到不认识的 `protocolVersion` 是协商还是拒绝，由 `McpTransportPolicy` 里的两个常量决定，当前分别是允许和协商。

`HERMIT_MASTER_KEY` 优先于 `data/master.key`。值必须是 Base64，解码后恰好 32 字节。非法值在启动时报可读原因（不是合法 Base64，或长度不是 32 字节），不会改用文件里的密钥，也不会只打印异常类名。未设置环境变量、也没有 key-file 时：数据目录还没有凭证密文，才会创建 `data/master.key`（POSIX 0600）；已经有凭证但主密钥文件缺失，则拒绝启动并且不生成新密钥，避免旧密文变成无法打开的 500。恢复原来的 `data/master.key` 后重启即可。确认放弃旧凭证时，删除 `data` 目录中的数据库后再启动，这时才按全新安装生成主密钥，然后重新填写账号凭证。已有合法主密钥但解不开已存凭证时，进程仍会启动并在日志里提示；账号 `needsConfiguration` 为 true，`credentialsUnreadable` 为 true，调用返回 `NOT_CONFIGURED` 和「凭证无法解密，需重新配置」。把该账号的凭证字段重新写一遍后恢复。库内部支持 key-file 参数，但当前 CLI 没有暴露该选项；`HERMIT_MASTER_KEY_FILE` 是容器入口脚本的复制功能，不是 Java 配置项。不要更换密钥后期待旧凭证仍能解密。

## Crab 容器配置

[Dockerfile.crab](../Dockerfile.crab) 将普通 jar 与运行时依赖打包，交给 [crab-entrypoint.sh](../deploy/crab-entrypoint.sh) 初始化。根 Compose 的 `client` profile 用于同机部署；[独立 Compose](../docker-compose.crab.yml) 用于另一台客户端。两者都使用 host 网络，将 `crab-data` 挂到 `/var/lib/hermit`，并固定监听 `127.0.0.1`。

| 环境变量 | 容器入口行为 |
| --- | --- |
| `HERMIT_HOME` | 两份 Compose 都设置为 `/var/lib/hermit` |
| `HERMIT_REEF_URL` | 必填；同机 profile 自动设为 `http://127.0.0.1:${HTTP_PORT:-80}`，独立部署自行填写 |
| `HERMIT_REEF_AGENT_TOKEN` | 必须非空白且与 Reef 相同，写入 `reef.token` |
| `HERMIT_REEF_ADMIN_USER` / `HERMIT_REEF_ADMIN_PASSWORD` | 首次无公钥时登录 Reef 取公钥；不是 Crab 日常调用的凭据 |
| `HERMIT_REEF_PUBLIC_KEY_FILE` | 可选，容器内 PEM 文件路径；独立 Compose 传入此变量，同机 profile 需额外 override |
| `HERMIT_CRAB_PORT` | 默认 7777，容器入口要求 1–65535 |
| `HERMIT_CRAB_INSTANCE_ID` | 可选 UUID；已有实例 ID 不同则拒绝覆盖 |
| `HERMIT_MASTER_KEY` | 非空时优先使用环境主密钥，Java 不再读取或生成 `data/master.key`；容器入口将空白值视为未配置。非法值由 Java 拒绝并说明原因 |
| `HERMIT_MASTER_KEY_FILE` | 未使用环境主密钥时，将挂载文件复制到 `data/master.key`；已有内容不同则拒绝覆盖。目标文件不存在且卷里已有凭证时，Java 不会另造一把新密钥 |

路径变量不会自动挂载文件，需要 Compose override 的只读 bind mount 或 `docker run --mount`。主密钥文件格式沿用 `data/master.key` 的 Base64 文本，不能填原始二进制密钥。

仅在缺少 `config.yml` 时初始化公钥和配置：先保留现有 `reef.pub`，其次复制指定公钥，否则用管理员登录取得 `/api/admin/signing-key`。从远端引导公钥需要可信的 HTTPS 连接；后续启动不自动轮换公钥。已有配置但缺少/损坏公钥时，应恢复可信公钥，不能期待脚本重新登录修复。

入口不会覆盖已有配置，而是按整行文本检查 URL、token、host 和 port；手写 YAML 即使语义相同，缩进/引号不同也可能不通过。变更这些环境变量前停止 Crab 并备份实例目录；同步修改对应配置行，或备份并移走 `config.yml` 后让入口重新生成，再恢复自定义 aliases/engine 等选项。保留 `reef.pub` 和 `data/`，不要为改配置删除数据卷。`reef.token` 还需与 Reef 同步变更。

## Reef 配置

使用 Spring 配置机制：命令行 `--hermit.reef.*`、配置文件或对应环境变量；默认值见 [application.yml](../hermit-reef/src/main/resources/application.yml) 和 [ReefProperties](../hermit-reef/src/main/java/dev/hermit/reef/config/ReefProperties.java)。

| 配置 | 默认/说明 |
| --- | --- |
| `hermit.reef.data-dir` / `HERMIT_REEF_DATA_DIR` | `./reef-data`，存数据库文件、包与密钥 |
| `hermit.reef.agent-token` / `HERMIT_REEF_AGENT_TOKEN` | 默认空。空表示没有配置令牌；库里还有未吊销令牌时，Agent 请求用那些令牌。两者都没有时启动打一条警告，并拒绝所有 `/agent/**` |
| `HERMIT_REEF_ADMIN_USER` / `HERMIT_REEF_ADMIN_PASSWORD` | 首次空库创建管理员；已有管理员不重设口令 |
| `hermit.reef.jwt-ttl` | `8h`，JWT 有 60 秒时钟容差 |
| `hermit.reef.latest-crab-version` / `crab-download-url` | 默认空；提供版本提示，不自动下载/升级 Crab |
| `hermit.reef.report-retention-days` | `90`，用于 `hermit_failure_reports` / `hermit_rollback_events` 的清理 |
| `hermit.reef.retention-cron` / `HERMIT_REEF_RETENTION_CRON` | `0 0 3 * * *`。Spring 六段 cron（含秒），时区固定 UTC，默认每天 03:00。空白或无法解析的表达式拒绝启动。手动触发见 `POST /api/admin/retention/purge` |
| `logging.pattern.level` | `%5p [%X{traceId}]`。请求处理期间 MDC 的 `traceId` 会出现在每条日志上，与响应里的值相同 |
| `spring.datasource.url` | 默认 `jdbc:h2:file:${hermit.reef.data-dir}/db/reef;MODE=REGULAR` |
| `spring.datasource.username` / `password` | H2 默认 `sa` / 空密码；部署 MySQL 时覆盖 |
| `spring.flyway.table` / `SPRING_FLYWAY_TABLE` | `hermit_flyway_schema_history`。Compose 的 Reef 服务写了同一环境变量，MySQL 与默认 H2 共用这张历史表 |
| `server.port` | Spring Boot 默认 8080 |
| `server.compression.*` | 默认开启，仅对 ≥ 2KB 的 HTML/CSS/JS/SVG 做 gzip，接口 JSON 不压缩；Compose 中 Nginx 另有 gzip。Crab 由 Javalin 默认 gzip（≥ 1500 字节） |

配置令牌归 id 最小的 `ADMIN`（含已禁用的账号，但该账号已禁用时这个令牌不再通过）。把它降成 `USER` 之后，归属改到下一个 id 最小的 `ADMIN`。明文只在 `POST /api/admin/tokens` 的响应里出现一次，丢失后吊销再签发。Reef 保存 SHA-256 十六进制，`token_prefix` 是明文的前 12 个字符。同一（令牌，实例）60 秒内只更新一次 `last_used_at` 和 `hermit_crab_instances.last_seen_at`，节流表在进程内存里，重启后重新计。实例上的 `crab_version` 最长 32 个字符，上报里更长的版本只在这一列截断。

启动警告只在配置令牌为空白，并且 `hermit_agent_tokens` 里没有 `revoked_at IS NULL` 的行时出现。已经吊销的行不算可用令牌。`docker-compose.yml` 里 Reef 的入口仍会在 `HERMIT_REEF_AGENT_TOKEN` 去掉空白后为空时直接退出；那是容器入口的检查，Java 进程本身允许只靠库内令牌启动。

Flyway `V6__users_and_agent_tokens.sql` 给 `hermit_admin_users` 增加 `role`（已有行默认为 `ADMIN`，检查约束 `ck_hermit_admin_users_role` 只允许 `ADMIN` / `USER`），并新建 `hermit_agent_tokens`、`hermit_crab_instances`。失败报告、小时计数和版本切换各增加可空的 `owner_user_id`。没有外键。已经跑过 V5 的库启动时补这些对象，不必为 V6 重建。V1–V4 的就地改写仍然要求重建更早的库，见下面。

Tide 配置位于 `hermit.reef.tide`：

| 键 | 默认 |
| --- | --- |
| `new-version-min-calls` | 20 |
| `regression-delta` | 0.20 |
| `no-baseline-failure-rate` | 0.50 |
| `baseline-window-days` | 7 |
| `surge-window-minutes` / `surge-min-count` | 15 / 10 |
| `quiet-hours` / `dedup-hours` | 24 / 6 |
| `webhook-url` | 空，设置后发送告警通知 |
| `evaluate-cron` | `0 */5 * * * *`，每 5 分钟评估 |

启动时校验这些数。`new-version-min-calls`、`baseline-window-days`、`surge-window-minutes`、`surge-min-count`、`dedup-hours` 必须 ≥ 1，写成 0 或负数会拒绝启动，异常信息带对应配置键。`regression-delta`、`no-baseline-failure-rate`、`quiet-hours` 必须 ≥ 0；负数拒绝，0 合法。`quiet-hours=0` 表示没有发布静默。静默看最近的发布时间，也看历史审计里 `action='ROLLBACK'` 的旧行。新的管理操作不再写 `ROLLBACK`。

具体阈值比较和发布静默以 `AlertService` 为准。去重按 `(kind, conch, conch_version)`，窗口从告警 `created_at` 起算 `dedup-hours`。窗口内已有告警（不论是否已确认）就不再新建，也不再发 webhook。确认不重新计时，也不把剩余时间清掉。失败次数在窗口内继续上涨不会提前再报。`created_at` 早于或等于 `now - dedup-hours` 之后，条件仍然成立才新建下一条并再次通知。未确认但已超出窗口的旧告警同样不再抑制。

Webhook 在新建告警并提交后 POST JSON，字段为 `id`、`kind`、`conch`、`version`、`detail`、`createdAt`、`adminUrl`。`id` 是 `hermit_alerts` 主键。`adminUrl` 是管理端告警页路径。Reef 自身没有对外源站配置：Crab 的 `reef.url` / `HERMIT_REEF_URL` 只存在于 Crab，不会传进 Reef，因此这里只写相对路径 `/alerts`。接收方按自己打开 Reef 的源站拼接，例如源站是 `https://reef.example` 时链接为 `https://reef.example/alerts`。超时 5 秒，失败只记日志，不重试，不影响告警落库。没有独立持久化重试队列。

## 数据目录与备份

```text
Reef data-dir/
  db/                    # 默认 H2（使用 MySQL 时数据库在外部）
  keys/signing.key       # Ed25519 私钥
  keys/signing.pub       # 对应公钥
  keys/jwt.secret        # JWT HMAC 密钥
  conches/                # 上传 YAML 与发布包

Crab HERMIT_HOME/
  config.yml
  reef.pub               # 示例公钥位置，路径可自定
  data/crab.mv.db
  data/master.key        # 使用环境主密钥时需另行保管该密钥
  data/instance-id
  conches/<conch>/current   # 已安装版本指针；订阅记录在 H2，不在此目录
  conches/<conch>/<version>/
  local-conches/          # 本机未签名规则，文件名 {conch}.yaml；启动加载，reload 与目录对齐
  logs/                 # 预留目录；调用日志实际存 H2
```

规则包订阅是 Crab H2 表 `subscriptions`（schema 8）里的行，不是 `config.yml` 的字段。新装 Crab 不订阅任何包。`conches/<conch>/current` 仍表示已安装版本。

schema 10 给 `idempotency` 增加可空列 `body_sha256`（原始请求体的 SHA-256，小写十六进制）。schema 11 给 `call_logs` 增加 `channel`，旧行默认 `HTTP`。两列分属两张表，所以没有并进同一次迁移。`channel` 是调用入口（`HTTP` / `MCP` / `CONSOLE`），`source` 仍是规则包来源（`LOCAL` / `REEF`）。调用记录列表不传 `limit` 时返回 100 条，最多 500 条。控制台调用记录页自己传 100，概览自己传 200；CLI 没有这条列表。

schema 12 删除 `pinned_rollbacks`。判断依据是版本 12 这一行是否已经记下，不看 `MAX(version)`，因此已经记下更高号码、却没有版本 12 的库启动时仍会删表。版本 3 里创建这张表的语句不改写：新库先建再删。规则失配不再切换版本。`mismatch_streak` 还在，不再写入。`rollback_queue` 仍上报版本变化；手动更新成功且上报开启时 reason 为 `MANUAL_UPDATE`。

`local-conches/` 只放这一台机器的未签名规则。文件名是 `{conch}.yaml`，不递归，不跟随符号链接。进程启动时加载；`crab local reload` 或 `POST /console/api/local-conches/reload` 让目录里的增删改生效。单个文件上限 1 MiB。控制台上传还受现有请求体上限（1_000_000 字节）约束。与已订阅或已安装的正式包重名时拒绝导入。

Reef 业务表、索引、唯一约束和检查约束都以 `hermit_` 开头，Flyway 历史表是 `hermit_flyway_schema_history`。V1–V4 在发布前就地改写，没有把旧表改名的迁移。已经用旧表名启动过的库需要重建：删除 `reef-data/db` 下的 H2 文件，或删除并重建 Compose 的 MySQL 卷 `reef-mysql`。`reef-data` 里的 `keys/` 和 `conches/` 可以留下；整卷删除会同时丢掉密钥和已发布包。Crab 的 `crab-data` 不在这次变更里。

之后的升级要一致备份数据库、conches、keys。Compose 的 `reef-mysql` 与 `reef-data` 分别保存 MySQL 与 `/data`，升级必须保留两个卷。容器 Crab 的 `crab-data` 保存整个实例目录；要同时备份外部主密钥（如有），不能只备份数据库。实际卷名通常带 Compose 项目前缀，切换部署目录/项目名时要核对卷，避免意外创建新实例。恢复主密钥、公钥和实例 ID 时保持对应关系。H2 文件复制前停止所属进程，MySQL 使用数据库一致性备份；恢复应先在隔离实例验证。本次审计未实际做生产备份恢复演练。

保留策略：Crab 调用/步骤日志 30 天、幂等响应 24 小时，启动及每小时清理；debug 状态 24 小时自动过期。Reef 每日 UTC 03:00（北京时间 11:00）清理过期失败报告、回滚事件及登录失败记录。两端小时统计以及部分审计/告警表没有全量定期保留策略，不应假定所有数据都会在 90 天删除。Crab 的 `config_audit` 可以由 `GET /console/api/audit` 和 CLI `audit list` 读取，内容限于操作者、动作、origin、Conch、账号、时间和字段名，不含凭证或配置值；这张表没有定期清理。

## 部署边界

- Compose 默认 Nginx 仅 HTTP，部署到不可信网络前配置 HTTPS 入口；管理员口令、JWT、Agent token 需要传输保护。
- Crab 控制台按连接回环地址授权；不能将 `/console/**` 无保护转发到公网，回环反向代理会改变实际来源的信任含义。
- 仓库 Crab 容器方案按 Linux host 网络使用；不应改成普通端口映射来绕过控制台的回环校验。macOS/Windows 可直接运行 Java，本次未验证 Docker Desktop 网络行为。
- Reef 的配置令牌是可选的共享令牌，另外每个用户可以签发自己的 Agent 令牌。实例 UUID 是标识，不能替代鉴权；Crab 的身份是用户加上 `instanceId`。
- 规则签名证明发布来源与完整性，不证明规则业务正确；上线新规则仍要针对老系统验收。
- `config` 的 `type: secret` 会被校验拒绝；秘密必须放 `credentials`。

## 排障

| 现象 | 检查 |
| --- | --- |
| 构建提示 release 17/类版本不支持 | `JAVA_HOME`、`java -version`、`./mvnw -version` |
| Crab classpath 找不到本项目依赖 | 先 `install` web/common/crab 依赖链，不能仅 package |
| 页面是占位页 | 是否使用 web.skip；不带该参数重新构建 |
| Reef 登录成功但 Crab 同步 401 | Reef token 是否非空，Crab `reef.token` 是否匹配 |
| Agent 请求 400 | 是否有合法 UUID 格式的 `X-Hermit-Instance` |
| Crab 已监听但没有 Conch | 公钥、网络、同步报告；首次同步后台执行 |
| Crab 容器提示配置不一致 | 环境变量与持久化 config.yml 的 URL/token/host/port 行是否一致；入口按文本检查 |
| Crab 容器首次获取公钥失败 | 可信 Reef 地址、管理员凭据/锁定、网络；或改用已挂载的可信 PEM 公钥 |
| `NOT_CONFIGURED` | 默认账号、必填凭证和配置是否齐全；也包括主密钥对不上、凭证无法解密 |
| 启动提示主密钥文件缺失 / 凭证无法解密 | 恢复原来的 `data/master.key` 或原来的 `HERMIT_MASTER_KEY`。确认放弃旧凭证时删除 `data` 里的数据库后再启动，全新安装才会重新生成主密钥 |
| `ACCOUNT_NOT_FOUND` | 请求点名账号是否存在，是否有默认账号 |
| `AUTH_FAILED` / 暂停登录 | 老系统凭证、验证码等变化；修正后测试登录成功才恢复 |
| 新版本没有装上 | 同步只刷新目录。已安装的包要手动 `update`。同时看 `min_crab_version`、Reef 是否停用、默认账号是否缺新增必填凭证 |
| CLI 报数据库占用 | serve 已持有 H2；使用本机控制台 API 或停止 serve 后操作 |
| `RULE_MISMATCH` | 通过 traceId 查步骤信息，核对页面选择器/输出。已安装版本保持不动，用更高版本修复后再手动更新 |
| 上游 HTTP 400，或会话 401/403 | 分别是 `INVALID_INPUT` 与 `SESSION_EXPIRED`。核对参数或凭证；页面结构变化才是 `RULE_MISMATCH` |

Reef 日志：`docker compose logs -f reef` 或 Java 进程输出。Crab 日志查看进程输出与控制台调用详情；同机容器用 `docker compose --profile client logs -f crab`，独立容器用 `docker compose -f docker-compose.crab.yml logs -f crab`。不要把敏感配置直接附入问题报告。
