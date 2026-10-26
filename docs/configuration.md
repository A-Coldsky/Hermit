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
| `capabilities.disabled` | `[]` | `shell.action` 列表，与数据库禁用状态取并集 |
| `aliases` | `{}` | URL 路径 → `shell.action`；不可占用 api/console/mcp/openapi 路径 |
| `engine.capability_timeout_seconds` | `60` | 正整数；当前不能可靠限制慢速响应的总时间，见审计 A03 |
| `engine.session_idle_minutes` | `10` | 空闲超过此阈值且配置 alive_check 时检测会话 |
| `engine.max_login_failures` | `3` | 连续登录失败后暂停该账号自动登录；test-login 可重置 |

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
```

此例的 disabled 项是路径格式示意，示例 Shell 中不包含 submit_leave。别名仍使用相同的 API Key、账号和能力权限；重启后读取新别名。

`HERMIT_MASTER_KEY` 可覆盖默认主密钥，要求 Base64 解码后恰好 32 字节；否则默认创建 `data/master.key`，POSIX 文件权限为 0600。库内部支持 key-file 参数，但当前 CLI 没有暴露该选项；`HERMIT_MASTER_KEY_FILE` 是容器入口脚本的复制功能，不是 Java 配置项。不要更换密钥后期待旧凭证仍能解密。

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
| `HERMIT_MASTER_KEY` | 非空时优先使用环境主密钥；容器入口将空白值视为未配置 |
| `HERMIT_MASTER_KEY_FILE` | 未使用环境主密钥时，将挂载文件复制到 `data/master.key`；已有内容不同则拒绝覆盖 |

路径变量不会自动挂载文件，需要 Compose override 的只读 bind mount 或 `docker run --mount`。主密钥文件格式沿用 `data/master.key` 的 Base64 文本，不能填原始二进制密钥。

仅在缺少 `config.yml` 时初始化公钥和配置：先保留现有 `reef.pub`，其次复制指定公钥，否则用管理员登录取得 `/api/admin/signing-key`。从远端引导公钥需要可信的 HTTPS 连接；后续启动不自动轮换公钥。已有配置但缺少/损坏公钥时，应恢复可信公钥，不能期待脚本重新登录修复。

入口不会覆盖已有配置，而是按整行文本检查 URL、token、host 和 port；手写 YAML 即使语义相同，缩进/引号不同也可能不通过。变更这些环境变量前停止 Crab 并备份实例目录；同步修改对应配置行，或备份并移走 `config.yml` 后让入口重新生成，再恢复自定义 aliases/engine 等选项。保留 `reef.pub` 和 `data/`，不要为改配置删除数据卷。`reef.token` 还需与 Reef 同步变更。

## Reef 配置

使用 Spring 配置机制：命令行 `--hermit.reef.*`、配置文件或对应环境变量；默认值见 [application.yml](../hermit-reef/src/main/resources/application.yml) 和 [ReefProperties](../hermit-reef/src/main/java/dev/hermit/reef/config/ReefProperties.java)。

| 配置 | 默认/说明 |
| --- | --- |
| `hermit.reef.data-dir` / `HERMIT_REEF_DATA_DIR` | `./reef-data`，存数据库文件、包与密钥 |
| `hermit.reef.agent-token` / `HERMIT_REEF_AGENT_TOKEN` | 默认空；空时拒绝所有 Agent 请求 |
| `HERMIT_REEF_ADMIN_USER` / `HERMIT_REEF_ADMIN_PASSWORD` | 首次空库创建管理员；已有管理员不重设口令 |
| `hermit.reef.jwt-ttl` | `8h`，JWT 有 60 秒时钟容差 |
| `hermit.reef.latest-crab-version` / `crab-download-url` | 默认空；提供版本提示，不自动下载/升级 Crab |
| `hermit.reef.report-retention-days` | `90`，用于 failure_reports/rollback_events 的清理 |
| `spring.datasource.url` | 默认 `jdbc:h2:file:${hermit.reef.data-dir}/db/reef;MODE=REGULAR` |
| `spring.datasource.username` / `password` | H2 默认 `sa` / 空密码；部署 MySQL 时覆盖 |
| `server.port` | Spring Boot 默认 8080 |
| `server.compression.*` | 默认开启，仅对 ≥ 2KB 的 HTML/CSS/JS/SVG 做 gzip，接口 JSON 不压缩；Compose 中 Nginx 另有 gzip。Crab 由 Javalin 默认 gzip（≥ 1500 字节） |

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

具体阈值比较和发布静默以 `AlertService` 为准。去重按 `(kind, shell, shell_version)`，窗口从告警 `created_at` 起算 `dedup-hours`。窗口内已有告警（不论是否已确认）就不再新建，也不再发 webhook。确认不重新计时，也不把剩余时间清掉。失败次数在窗口内继续上涨不会提前再报。`created_at` 早于或等于 `now - dedup-hours` 之后，条件仍然成立才新建下一条并再次通知。未确认但已超出窗口的旧告警同样不再抑制。

Webhook 是当前评估后的发送行为，没有独立持久化重试队列。

## 数据目录与备份

```text
Reef data-dir/
  db/                    # 默认 H2（使用 MySQL 时数据库在外部）
  keys/signing.key       # Ed25519 私钥
  keys/signing.pub       # 对应公钥
  keys/jwt.secret        # JWT HMAC 密钥
  shells/                # 上传 YAML 与发布包

Crab HERMIT_HOME/
  config.yml
  reef.pub               # 示例公钥位置，路径可自定
  data/crab.mv.db
  data/master.key        # 使用环境主密钥时需另行保管该密钥
  data/instance-id
  shells/<shell>/current
  shells/<shell>/<version>/
  local-shells/          # 仅预留目录，当前无加载流程
  logs/                 # 预留目录；调用日志实际存 H2
```

Reef 要一致备份数据库、shells、keys。Compose 的 `reef-mysql` 与 `reef-data` 分别保存 MySQL 与 `/data`，升级必须保留两个卷。容器 Crab 的 `crab-data` 保存整个实例目录；要同时备份外部主密钥（如有），不能只备份数据库。实际卷名通常带 Compose 项目前缀，切换部署目录/项目名时要核对卷，避免意外创建新实例。恢复主密钥、公钥和实例 ID 时保持对应关系。H2 文件复制前停止所属进程，MySQL 使用数据库一致性备份；恢复应先在隔离实例验证。本次审计未实际做生产备份恢复演练。

保留策略：Crab 调用/步骤日志 30 天、幂等响应 24 小时，启动及每小时清理；debug 状态 24 小时自动过期。Reef 每日 UTC 03:00（北京时间 11:00）清理过期失败报告、回滚事件及登录失败记录。两端小时统计以及部分审计/告警表没有全量定期保留策略，不应假定所有数据都会在 90 天删除。

## 部署边界

- Compose 默认 Nginx 仅 HTTP，部署到不可信网络前配置 HTTPS 入口；管理员口令、JWT、Agent token 需要传输保护。
- Crab 控制台按连接回环地址授权；不能将 `/console/**` 无保护转发到公网，回环反向代理会改变实际来源的信任含义。
- 仓库 Crab 容器方案按 Linux host 网络使用；不应改成普通端口映射来绕过控制台的回环校验。macOS/Windows 可直接运行 Java，本次未验证 Docker Desktop 网络行为。
- Reef 的 token 是共享令牌，不是每实例独立证书；实例 UUID 是标识，不能替代鉴权。
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
| Crab 已监听但没有 Shell | 公钥、网络、同步报告；首次同步后台执行 |
| Crab 容器提示配置不一致 | 环境变量与持久化 config.yml 的 URL/token/host/port 行是否一致；入口按文本检查 |
| Crab 容器首次获取公钥失败 | 可信 Reef 地址、管理员凭据/锁定、网络；或改用已挂载的可信 PEM 公钥 |
| `NOT_CONFIGURED` | 默认账号、必填凭证和配置是否齐全 |
| `ACCOUNT_NOT_FOUND` | 请求点名账号是否存在，是否有默认账号 |
| `AUTH_FAILED` / 暂停登录 | 老系统凭证、验证码等变化；修正后 test-login |
| 新版本一直跳过 | min_crab_version、固定回滚状态、相同版本不可覆盖 |
| CLI 报数据库占用 | serve 已持有 H2；使用本机控制台 API 或停止 serve 后操作 |
| `RULE_MISMATCH` | 通过 traceId 查步骤信息，核对页面选择器/输出；可能触发回滚 |

Reef 日志：`docker compose logs -f reef` 或 Java 进程输出。Crab 日志查看进程输出与控制台调用详情；同机容器用 `docker compose --profile client logs -f crab`，独立容器用 `docker compose -f docker-compose.crab.yml logs -f crab`。不要把敏感配置直接附入问题报告。
