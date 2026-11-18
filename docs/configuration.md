# 配置与运维

## Crab 配置

原生 Java 的 `HERMIT_HOME` 默认是 `user.home` 下的 `.hermit`（文档里的 `~/.hermit` 是简称）。读取其中的 `config.yml`。缺文件使用默认值，未知键/错误类型/超过 1,000,000 字节的文件会拒绝。普通字符串会 trim；不支持环境变量插值或从 `.env` 自动导入。配置文件启动时加载。容器入口会先从环境变量生成配置；`.env` 由 Compose 读取，见下面的容器配置。

把 `HERMIT_HOME` 设成字面量 `~/.hermit` 时 Java 不会展开 `~`。Windows 上目录是 `%USERPROFILE%\.hermit` 这种盘符路径。本地 H2 用正斜杠，盘符写成 `jdbc:h2:file:C:/...`，盘符前面不加 `/`。路径里不能有分号 `;`，否则启动直接拒绝，stderr 说明数据目录路径含有分号，不会去连 H2。库文件打不开时，stderr 先写 `database operation failed`，下一行是根因里的路径，不打印堆栈，也不打印凭证。

| 键 | 默认值 | 行为 |
| --- | --- | --- |
| `reef.url` | 空 | 为空不创建同步/上报对象；有值必须有公钥 |
| `reef.token` | 空 | 发送为 `X-Hermit-Token`；当前 Reef 必须配置匹配的非空 token |
| `reef.public_key` | 空 | PEM 文本或文件路径；相对路径基于 HERMIT_HOME；已安装正式规则也需要公钥验证 |
| `server.host` | `127.0.0.1` | HTTP 监听地址，改为其他地址前确认访问边界 |
| `server.port` | `7777` | 0–65535；0 分配随机端口，主要用于测试 |
| `sync.interval_seconds` | `300` | 正整数，周期同步；启动另有一次异步同步 |
| `report.enabled` | `true` | 是否记录/发送运行上报；关闭不影响本地调用日志。控制台 `PUT /console/api/reports/settings` 写在 H2，覆盖这里的值 |
| `report.interval_seconds` | `1800` | 整数，最小 60；小于 60 拒绝加载。发送失败还有退避。控制台设置同样至少 60 秒，并覆盖这里的值。已经写在 config.yml 或 schema 13 里的间隔保持原值 |
| `capabilities.disabled` | `[]` | `conch.action` 列表，与数据库禁用状态取并集 |
| `aliases` | `{}` | URL 路径 → `conch.action`；不可占用 api/console/mcp/openapi 路径 |
| `mcp.allowed_origins` | `[]` | 额外允许访问 `/mcp` 的浏览器 Origin。没有 `Origin` 的客户端仍允许。本机源站见下方 |
| `engine.capability_timeout_seconds` | `60` | 正整数。能力执行的总 deadline，到点返回 `TIMEOUT`。未写步骤 `timeout_ms` 时，上游空闲读也停在这个 deadline，不再先用 15 秒。规则写了更短的 `timeout_ms` 时，空闲读到点是 `UPSTREAM_ERROR`，message 为「步骤超时」。持续慢速输出会在 deadline 被取消。TCP 连接一直挂起没有单独回归 |
| `engine.session_idle_minutes` | `10` | 空闲超过此阈值且配置 alive_check 时检测会话 |
| `engine.max_login_failures` | `3` | 连续登录失败后暂停该账号自动登录；只有测试登录成功才解除，失败不会清掉暂停 |
| `engine.mcp_max_concurrency_per_account` | `4` | 整数 1–32。每个 `(origin, conch, account)` 同时进行的上游 `tools/call` 上限。启动时读取，不热更新。排队也算进能力 deadline |
| `tls.system_trust` | `true` | 为 true 时，出站 HTTPS 在 JDK 默认信任库之外再合并操作系统信任库。false 只停用操作系统来源，JDK 信任库仍在。没有跳过证书校验的开关 |
| `tls.ca_dir` | `$HERMIT_HOME/trust/ca` | 附加 CA 目录。省略或空白用默认目录，启动时创建。相对路径相对 HERMIT_HOME。只读 `.pem`、`.crt`、`.cer`，坏文件跳过，不阻止启动 |

上游 OAuth 的访问令牌和刷新令牌不写在这份文件里。它们在凭证库的 `oauth_token` 字段，按 `(origin, conch, account)` 分开保存，由控制台登录写入。源站若还要客户端机密，在规则包里声明 `credentials.client_secret`，值仍进凭证库，不进 YAML。

完整配置示例：

```yaml
reef:
  url: https://reef.example.com
  token: '替换为Agent令牌'
  public_key: reef.pub
server: {host: 127.0.0.1, port: 7777}
sync: {interval_seconds: 300}
report: {enabled: true, interval_seconds: 1800}
capabilities:
  disabled: [legacy_oa.submit_leave]
aliases:
  /oa/todos: legacy_oa.list_todo
engine:
  capability_timeout_seconds: 60
  session_idle_minutes: 10
  max_login_failures: 3
  mcp_max_concurrency_per_account: 4
mcp:
  allowed_origins: []
tls:
  system_trust: true
  ca_dir: trust/ca
```

此例的 disabled 项是路径格式示意，示例 Conch 中不包含 submit_leave。别名仍使用相同的 API Key、账号和能力权限；重启后读取新别名。

`mcp.allowed_origins` 是 `/mcp` 的额外 Origin 允许列表，默认空。每一项必须是绝对的 `http` 或 `https` 源站，不能带路径、查询、用户信息或片段；写错会拒绝启动。没有 `Origin` 头的客户端仍然允许。本机始终允许 `http://127.0.0.1:<实际监听端口>`、`http://localhost:<实际监听端口>` 和 `http://[::1]:<实际监听端口>`，以及 `http://<server.host>:<实际监听端口>`。`server.host` 为 `0.0.0.0` 或 `::` 时不把通配地址当成源站；从局域网浏览器打开时，把浏览器地址栏对应的源站写进这个列表。反向代理的 `https://` 源站同样写在这里。比较时忽略 scheme 和主机的大小写，80 和 443 可以省略。请求如何被拒绝见[接口参考](api.md)的 MCP 一节。缺 `Origin` 是否拒绝、以及 `initialize` 遇到不认识的 `protocolVersion` 是协商还是拒绝，由 `McpTransportPolicy` 里的两个常量决定，当前分别是允许和协商。

## 内网 HTTPS

规则 HTTP、MCP 转发、MCP OAuth、Reef 同步、上报和引导探测共用一个 TLS 上下文。信任材料是 JDK 默认信任库、操作系统信任库，再加上 `tls.ca_dir` 里的证书。重新加载只替换这份快照，不重建客户端，也不清 Cookie。删掉信任锚时清掉 TLS 会话，并关掉 Apache 的空闲连接。`java.net.http` 没有公开的空闲连接池，会话失效是它能用的手段。

操作系统来源：Windows 读 `Windows-ROOT` 和 `Windows-MY` 里的 CA，macOS 读钥匙串里的 CA，Linux 读系统 CA 包（`/etc/ssl/certs/ca-certificates.crt`、`/etc/pki/tls/certs/ca-bundle.crt`、`/etc/ssl/cert.pem` 中第一个存在的文件）。加载失败只打一条警告，Crab 仍启动。`tls.system_trust: false` 不再读操作系统来源。

附加目录只接受 `.pem`、`.crt`、`.cer`（大小写不限）。一个文件可以有多张证书。单文件最大 64 KiB，最多 64 个文件、256 张证书。含私钥的文件整份跳过。不是 CA 的证书也可以当信任锚，加载结果会标成非 CA。坏文件单独跳过。启动时加载一次，不监视目录，也不按 AIA 下载中间证书。

没有 `insecure_skip_verify`，规则包里也不能关校验。连不上时错误码仍是 `UPSTREAM_ERROR`（超时仍走原来的超时分支），message 会说明是 DNS、拒绝连接、证书不受信任、缺中间证书、主机名不符、过期或其它握手失败。

容器入口用 curl 取 Reef 签名公钥。CA 目录里有证书时，把系统 CA 包和这些证书拼成临时 bundle，再传 `--cacert`（这个参数会替换 curl 的默认信任库，所以必须带上系统包）。没有附加证书时不传 `--cacert`。首次取公钥时通常还没有 `config.yml`，这时用 `$HERMIT_HOME/trust/ca`。已经有配置且写了 `tls.ca_dir` 时，用那个目录。

控制台「HTTPS 信任」页、按主机登记指纹、探测和 `crab trust` 命令不在这一轮。快照里已经按 JDK、操作系统、CA 目录分开计数，供后续接口使用。

`HERMIT_MASTER_KEY` 优先于 `data/master.key`。值必须是 Base64，解码后恰好 32 字节。非法值在启动时报可读原因（不是合法 Base64，或长度不是 32 字节），不会改用文件里的密钥，也不会只打印异常类名。未设置环境变量、也没有 key-file 时：数据目录还没有凭证密文，才会创建 `data/master.key`（POSIX 0600）；已经有凭证但主密钥文件缺失，则拒绝启动并且不生成新密钥，避免旧密文变成无法打开的 500。恢复原来的 `data/master.key` 后重启即可。确认放弃旧凭证时，删除 `data` 目录中的数据库后再启动，这时才按全新安装生成主密钥，然后重新填写账号凭证。已有合法主密钥但解不开已存凭证时，进程仍会启动并在日志里提示；账号 `needsConfiguration` 为 true，`credentialsUnreadable` 为 true，调用返回 `NOT_CONFIGURED` 和「凭证无法解密，需重新配置」。把该账号的凭证字段重新写一遍后恢复。库内部支持 key-file 参数，但当前 CLI 没有暴露该选项；`HERMIT_MASTER_KEY_FILE` 是容器入口脚本的复制功能，不是 Java 配置项。不要更换密钥后期待旧凭证仍能解密。

`HERMIT_CONSOLE_PASSWORD` 是可选的控制台口令。只在 Crab 库里还没有口令哈希时，于进程启动时写入一次；已经有哈希则忽略，避免每次重启都改口令。空字符串视为未设置。长度是 8 到 256 个字符。哈希是 PBKDF2-HMAC-SHA256（16 字节盐、210000 次、32 字节结果），比较用常量时间。明文和哈希都不写入 `config.yml`。容器入口同样不把这个变量抄进 `config.yml`，只把它留在进程环境里；空白值视为未设置。同一操作系统用户看得到这个环境变量，和 `HERMIT_MASTER_KEY` 一样。

忘了口令时先停掉 Crab，再执行 `crab console password`。新口令从终端、无终端时的 stdin，或 `--from-env` 读入，覆盖哈希，不需要旧口令。口令不能写在命令行参数里。已设置口令时，`crab debug status`、`crab debug on`、`crab debug off`、`crab debug run`、`crab debug trace` 和 `crab mcp refresh` 先用同样的方式核对；未设置时不询问。导入、凭证、API Key 等既有命令不查这口令。

未设置口令时，控制台仍只认回环地址和 `X-Hermit-Console: 1`。已设置时，除 `GET /console/api/session` 和 `POST /console/api/session` 外，`/console/api/**` 还要 Cookie `hermit_console`（HttpOnly、`SameSite=Strict`、`Path=/console`，不加 `Secure`）。会话在内存中，自签发起 12 小时绝对到期，进程重启即失效。`POST /console/api/session/password` 体为 `{current, next}`，成功后清掉本进程的内存会话。15 分钟内累计 5 次失败后锁定；第 5 次失败会记下，锁定期间不再追加失败行，因此窗口不会被继续往后推。错误口令和锁定都是 401 `AUTH_FAILED`，message 为「口令错误」。没有 Cookie 或会话过期是 401 `AUTH_FAILED`，message 为「需要控制台登录。」。`/api`、`/mcp` 和 `mcp-stdio` 不认这张 Cookie，仍只认 API Key。`index.html` 和静态资源仍只认回环地址。

## Crab 容器配置

[Dockerfile.crab](../Dockerfile.crab) 将普通 jar 与运行时依赖打包，交给 [crab-entrypoint.sh](../deploy/crab-entrypoint.sh) 初始化。根 Compose 的 `client` profile 用于同机部署；[独立 Compose](../docker-compose.crab.yml) 用于另一台客户端。两者都使用 host 网络，将 `crab-data` 挂到 `/var/lib/hermit`，并固定监听 `127.0.0.1`。

| 环境变量 | 容器入口行为 |
| --- | --- |
| `HERMIT_HOME` | 两份 Compose 都设置为 `/var/lib/hermit` |
| `HERMIT_REEF_URL` | 与令牌一起设置时，同机 profile 自动设为 `http://127.0.0.1:${HTTP_PORT:-80}`，独立部署自行填写。已有 `reef-bootstrap.json`，或 URL 与令牌都未设置时，不再要求它 |
| `HERMIT_REEF_AGENT_TOKEN` | 与 URL 一起设置时必须非空白且与 Reef 相同，写入 `reef.token`。只设置 URL 或只设置令牌、又没有引导文件时拒绝启动。引导文件存在时不把环境变量里的令牌写进新生成的 config.yml |
| `HERMIT_REEF_ADMIN_USER` / `HERMIT_REEF_ADMIN_PASSWORD` | 仅在用环境变量首次生成 config.yml、且还没有公钥时登录 Reef 取公钥；不是 Crab 日常调用的凭据。已有引导文件时不登录。`HERMIT_REEF_URL` 必须是 `https://`，或 `http://` 加 `127.0.0.1`、`localhost`、`[::1]`；其它明文 HTTP 地址拒绝启动，因为中间人可以换掉公钥（规则的信任根）并看到管理员口令 |
| `HERMIT_REEF_PUBLIC_KEY_FILE` | 可选，容器内 PEM 文件路径；独立 Compose 传入此变量，同机 profile 需额外 override。Reef 只有内网明文 HTTP 时推荐用它 |
| `HERMIT_REEF_ALLOW_INSECURE_KEY_FETCH` | 可选。设为 `1` 才允许经远端明文 HTTP 登录取公钥，并打印一行警告。只在确认是可信内网时使用 |
| `HERMIT_CRAB_PORT` | 默认 7777，容器入口要求 1–65535 |
| `HERMIT_CRAB_INSTANCE_ID` | 可选 UUID；已有实例 ID 不同则拒绝覆盖 |
| `HERMIT_MASTER_KEY` | 非空时优先使用环境主密钥，Java 不再读取或生成 `data/master.key`；容器入口将空白值视为未配置。非法值由 Java 拒绝并说明原因 |
| `HERMIT_MASTER_KEY_FILE` | 未使用环境主密钥时，将挂载文件复制到 `data/master.key`；已有内容不同则拒绝覆盖。目标文件不存在且卷里已有凭证时，Java 不会另造一把新密钥 |
| `HERMIT_CONSOLE_PASSWORD` | 可选。仅当库中还没有口令哈希时，启动时哈希入库一次。不写入 config.yml。容器入口不把它抄进文件，空白视为未设置。过短或过长由 Java 拒绝启动，错误信息里没有口令 |

路径变量不会自动挂载文件，需要 Compose override 的只读 bind mount 或 `docker run --mount`。主密钥文件格式沿用 `data/master.key` 的 Base64 文本，不能填原始二进制密钥。

`$HERMIT_HOME/reef-bootstrap.json` 存在时，入口把该文件设为 POSIX 0600，不要求 `HERMIT_REEF_URL` / `HERMIT_REEF_AGENT_TOKEN`，也不为公钥做管理员登录。缺少 `config.yml` 时只写入 server、sync 和 report（上报间隔 1800 秒），不把环境变量中的 URL 或令牌复制进去。Java 启动时用引导文件覆盖 config.yml 里的 `reef.url`、`reef.token` 和 `reef.public_key`。

URL 与令牌都已设置、且没有引导文件时，行为与以前相同：仅在缺少 `config.yml` 时初始化公钥和配置。先保留现有 `reef.pub`，其次复制指定公钥，否则用管理员登录取得 `/api/admin/signing-key`。从远端引导公钥需要可信的 HTTPS 连接；后续启动不自动轮换公钥。已有配置但缺少/损坏公钥时，应恢复可信公钥，不能期待脚本重新登录修复。新生成的 `report.interval_seconds` 为 1800。已有 config.yml 里的间隔不会被入口改写。

URL 与令牌都没设、也没有引导文件时，入口仍会启动。缺少 `config.yml` 时生成一份不含 `reef` 的配置，控制台之后可以导入引导文件。已经写过 `reef` 的旧 `config.yml` 会保留，Java 继续使用其中的 URL、令牌和公钥；入口只检查 `server.host` 与 `server.port`。

入口不会覆盖已有配置。环境变量提供了 URL 和令牌、且没有引导文件时，按整行文本检查 URL、token、host 和 port；手写 YAML 即使语义相同，缩进/引号不同也可能不通过。有引导文件，或两者都没设时，不再对照 URL 和 token 行。变更这些环境变量前停止 Crab 并备份实例目录；同步修改对应配置行，或备份并移走 `config.yml` 后让入口重新生成，再恢复自定义 aliases/engine 等选项。保留 `reef.pub`、`reef-bootstrap.json` 和 `data/`，不要为改配置删除数据卷。`reef.token` 还需与 Reef 同步变更。

## Reef 配置

使用 Spring 配置机制：命令行 `--hermit.reef.*`、配置文件或对应环境变量；默认值见 [application.yml](../hermit-reef/src/main/resources/application.yml) 和 [ReefProperties](../hermit-reef/src/main/java/dev/hermit/reef/config/ReefProperties.java)。

| 配置 | 默认/说明 |
| --- | --- |
| `hermit.reef.data-dir` / `HERMIT_REEF_DATA_DIR` | `./reef-data`，存数据库文件、包与密钥。未配置时用这个默认值。空串或纯空白在创建 `db`、`conches`、`keys` 之前拒绝启动，message 为 `hermit.reef.data-dir must not be blank`，不会把空串解析成进程工作目录 |
| `hermit.reef.agent-token` / `HERMIT_REEF_AGENT_TOKEN` | 默认空。空表示没有配置令牌；库里还有未吊销令牌时，Agent 请求用那些令牌。两者都没有时启动打一条警告，并拒绝所有 `/agent/**` |
| `HERMIT_REEF_ADMIN_USER` / `HERMIT_REEF_ADMIN_PASSWORD` | 首次空库创建管理员；已有管理员不重设口令 |
| `hermit.reef.jwt-ttl` | `8h`，JWT 有 60 秒时钟容差 |
| `hermit.reef.latest-crab-version` / `crab-download-url` | 默认空。空的 `latest-crab-version` 表示不提示升级。非空时必须是 semver（`MAJOR.MINOR.PATCH`，可带预发布后缀，不接受 `+` build metadata），否则拒绝启动并点名 `hermit.reef.latest-crab-version`。只提供版本提示，不自动下载或升级 Crab |
| `hermit.reef.report-retention-days` | `90`，用于 `hermit_failure_reports` / `hermit_rollback_events` 的清理 |
| `hermit.reef.retention-cron` / `HERMIT_REEF_RETENTION_CRON` | `0 0 3 * * *`。Spring 六段 cron（含秒），时区固定 UTC，默认每天 03:00。空白或无法解析的表达式拒绝启动。手动触发见 `POST /api/admin/retention/purge` |
| `logging.pattern.level` | `%5p [%X{traceId}]`。请求处理期间 MDC 的 `traceId` 会出现在每条日志上，与响应里的值相同 |
| `spring.datasource.url` | 默认 `jdbc:h2:file:${hermit.reef.data-dir}/db/reef;MODE=REGULAR` |
| `spring.datasource.username` / `password` | H2 默认 `sa` / 空密码；部署 MySQL 时覆盖 |
| `spring.flyway.table` / `SPRING_FLYWAY_TABLE` | `hermit_flyway_schema_history`。Compose 的 Reef 服务写了同一环境变量，MySQL 与默认 H2 共用这张历史表 |
| `server.port` | Spring Boot 默认 8080 |
| `server.compression.*` | 默认开启，仅对 ≥ 2KB 的 HTML/CSS/JS/SVG 做 gzip，接口 JSON 不压缩；Compose 中 Nginx 另有 gzip。Crab 由 Javalin 默认 gzip（≥ 1500 字节） |

`hermit.reef.data-dir` 的空白检查发生在创建子目录之前，与 `ReefProperties` 使用同一句 `hermit.reef.data-dir must not be blank`。未设置该配置时仍是 `./reef-data`。

`latest-crab-version` 为空白时，`GET /agent/version` 不带升级提示。非空时按规则包同一套 semver 解析：`1.2.3` 与 `1.2.3-alpha.1` 可以通过，`abc`、`1.2`、`v1.0.0` 和 `1.2.3+build` 会拒绝启动。

配置令牌归 id 最小的 `ADMIN`（含已禁用的账号）。该账号已禁用，或这个配置值的 SHA-256 已在 `hermit_voided_config_tokens` 里，令牌都不再通过。禁用这个管理员会写入当前值的摘要；重新启用不删这行，配置值不变则仍然 401。管理端 `POST /api/admin/tokens/config/revoke` 写入同一张表。把 `HERMIT_REEF_AGENT_TOKEN` 改成另一个非空字符串后，新值可用，旧值仍 401。只把这个管理员降成 `USER`、且没有作废当前值时，归属改到下一个 id 最小的 `ADMIN`，原值仍可用。禁用用户会吊销他名下未吊销的库内令牌，重新启用不恢复。库内令牌的明文只在 `POST /api/admin/tokens` 的响应里出现一次，丢失后吊销再签发。Reef 保存 SHA-256 十六进制，`token_prefix` 是明文的前 12 个字符。同一（令牌，实例）60 秒内只更新一次 `last_used_at` 和 `hermit_crab_instances.last_seen_at`，节流表在进程内存里，重启后重新计。这 60 秒内不会把实例行改到另一个用户；换一把令牌会。同一 `instance_id` 只留最后一次写出的用户。实例上的 `crab_version` 最长 32 个字符，上报里更长的版本只在这一列截断。

启动警告只在配置令牌为空白，并且 `hermit_agent_tokens` 里没有 `revoked_at IS NULL` 的行时出现。已经吊销的行不算可用令牌。已经作废的配置值仍然算「配置了令牌」，不会因此触发这条警告；Agent 请求会 401，直到改成新值或另有未吊销的库内令牌。`docker-compose.yml` 里 Reef 的入口仍会在 `HERMIT_REEF_AGENT_TOKEN` 去掉空白后为空时直接退出；那是容器入口的检查，Java 进程本身允许只靠库内令牌启动。

Flyway `V6__users_and_agent_tokens.sql` 给 `hermit_admin_users` 增加 `role`（已有行默认为 `ADMIN`，检查约束 `ck_hermit_admin_users_role` 只允许 `ADMIN` / `USER`），并新建 `hermit_agent_tokens`、`hermit_crab_instances`。失败报告、小时计数和版本切换各增加可空的 `owner_user_id`。没有外键。`V7__voided_config_tokens.sql` 新建 `hermit_voided_config_tokens`，只存配置令牌的 SHA-256 和作废时间。已经跑过 V5 的库启动时补这些对象，不必为 V6 或 V7 重建。V1–V4 的就地改写仍然要求重建更早的库，见下面。

Tide 配置位于 `hermit.reef.tide`：

| 键 | 默认 |
| --- | --- |
| `new-version-min-calls` | 20 |
| `regression-delta` | 0.20 |
| `no-baseline-failure-rate` | 0.50 |
| `baseline-window-days` | 7 |
| `surge-window-minutes` / `surge-min-count` | 15 / 10 |
| `quiet-hours` / `dedup-hours` | 24 / 6 |
| `webhook-url` | 空。空字符串不推送。非空时协议必须是 `http` 或 `https`；`ftp://` 或没有协议的值拒绝启动，异常信息点名 `hermit.reef.tide.webhook-url` |
| `evaluate-cron` | `0 */5 * * * *`，每 5 分钟评估 |

启动时校验这些数。`new-version-min-calls`、`baseline-window-days`、`surge-window-minutes`、`surge-min-count`、`dedup-hours` 必须 ≥ 1，写成 0 或负数会拒绝启动，异常信息带对应配置键。`regression-delta`、`no-baseline-failure-rate`、`quiet-hours` 必须 ≥ 0；负数拒绝，0 合法。`quiet-hours=0` 表示没有发布静默。静默看最近的发布时间，也看历史审计里 `action='ROLLBACK'` 的旧行。新的管理操作不再写 `ROLLBACK`。

具体阈值比较和发布静默以 `AlertService` 为准。去重按 `(kind, conch, conch_version)`，窗口从告警 `created_at` 起算 `dedup-hours`。窗口内已有告警（不论是否已确认）就不再新建，也不再发 webhook。确认不重新计时，也不把剩余时间清掉。失败次数在窗口内继续上涨不会提前再报。`created_at` 早于或等于 `now - dedup-hours` 之后，条件仍然成立才新建下一条并再次通知。未确认但已超出窗口的旧告警同样不再抑制。

`webhook-url` 为空白时不发送。非空值的协议必须是 `http` 或 `https`（忽略大小写），例如 `https://hooks.example/tide`。`ftp://example` 和 `example.com` 会拒绝启动，异常信息包含 `hermit.reef.tide.webhook-url`。

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
  reef-bootstrap.json    # 可选。存在时覆盖 config.yml 的 reef.url / token / public_key
  reef.pub               # 示例公钥位置，路径可自定
  data/crab.mv.db
  data/master.key        # 使用环境主密钥时需另行保管该密钥
  data/instance-id
  conches/<conch>/current   # 已安装版本指针；订阅记录在 H2，不在此目录
  conches/<conch>/<version>/
  local-conches/          # 本机未签名规则，文件名 {conch}.yaml；启动加载，reload 与目录对齐
  trust/ca/               # 附加 CA，默认目录；tls.ca_dir 可以改到别处
  logs/                 # 预留目录；调用日志实际存 H2
```

规则包订阅是 Crab H2 表 `subscriptions`（schema 8）里的行，不是 `config.yml` 的字段。新装 Crab 不订阅任何包。`conches/<conch>/current` 仍表示已安装版本。

schema 10 给 `idempotency` 增加可空列 `body_sha256`（原始请求体的 SHA-256，小写十六进制）。schema 11 给 `call_logs` 增加 `channel`，旧行默认 `HTTP`。两列分属两张表，所以没有并进同一次迁移。`channel` 是调用入口（`HTTP` / `MCP` / `CONSOLE`），`source` 仍是规则包来源（`LOCAL` / `REEF`）。调用记录列表不传 `limit` 时返回 100 条，最多 500 条。控制台调用记录页自己传 100，概览自己传 200；CLI 没有这条列表。

schema 12 删除 `pinned_rollbacks`。判断依据是版本 12 这一行是否已经记下，不看 `MAX(version)`，因此已经记下更高号码、却没有版本 12 的库启动时仍会删表。版本 3 里创建这张表的语句不改写：新库先建再删。规则失配不再切换版本。`mismatch_streak` 还在，不再写入。`rollback_queue` 仍上报版本变化；手动更新成功且上报开启时 reason 为 `MANUAL_UPDATE`。

schema 13 增加 `report_settings`（`id`、`enabled`、`interval_seconds`、`updated_at`）。判断依据是版本 13 这一行是否已经记下，不看 `MAX(version)`。没有 `id = 1` 的行时，`report.enabled` 与 `report.interval_seconds` 仍来自 config.yml。控制台 PUT 写入这一行后覆盖配置文件，并在本进程立即生效。关闭上报不清空 `report_queue` 或 `rollback_queue`。

schema 14 增加 `console_password`（一行：`id`、`salt`、`hash`、`iterations`、`updated_at`）和 `console_login_failures`（只有 `failed_at`）。判断依据是版本 14 这一行是否已经记下，不看 `MAX(version)`，也不改 1–13 的语句。口令不在 `config.yml`。没有 `id = 1` 的行表示尚未设置口令。

schema 15 增加 `dev_traces`（`trace_id`、`origin`、`conch`、`account`、`action`、`created_at`、`expires_at`、`body`）。判断依据是版本 15 这一行是否已经记下，不改 schema 14 的语句。`body` 是已经脱敏的 JSON，不进 `config.yml`，也不上报 Tide。单步正文截断到 256 KiB，整条截断到 2 MiB。`Cookie`、`Set-Cookie`、`Authorization`、`Mcp-Session-Id` 整头不入库。

schema 16 增加 `session_login`（`origin`、`conch`、`account`、`ciphertext`、`updated_at`，主键是前三列）。判断依据是版本 16 这一行是否已经记下，不改 schema 15，也不改 `schema/crab-v15.sql`。升级不回填。密文是一个账号的登录快照，AAD 字段是 `login`，和 Cookie 的 `session` 分开。空快照不插行。解密失败只删这一行。不进 `config.yml`，也不进 `config_audit`。

小时成功/失败计数和失败明细先留在内存。每 30 秒、每次发送前，以及进程正常关闭时，一起写入 H2。批次为空时不向 Reef 发 HTTP。发送的小时统计是合并后的全量，不是增量。异常退出可能丢掉尚未刷盘的最近 30 秒计数，以及还在内存里的失败明细。回滚事件仍在入队时立即写入 H2。导入的 Reef 令牌和 config.yml 里的令牌都会进入脱敏用的已知秘密列表（长度至少 4，并包含其 URL 编码）。签名校验和公钥信任根不变：已安装的正式包在下载和加载时仍用当前公钥验签。

`local-conches/` 只放这一台机器的未签名规则。文件名是 `{conch}.yaml`，不递归，不跟随符号链接。进程启动时加载；`crab local reload` 或 `POST /console/api/local-conches/reload` 让目录里的增删改生效。单个文件上限 1 MiB。控制台上传还受现有请求体上限（1_000_000 字节）约束。与已订阅或已安装的正式包重名时拒绝导入。解析失败、读失败或文件名与包名不符时不另存上一份，该包不能调用；合法文件名仍留在 `GET /console/api/local-conches` 和 `crab local list`，删掉后才能不经确认再导入。控制台同名覆盖要 `confirm=true`，CLI 要 `--replace`。加载和 reload 跳过以 `.` 开头的目录，所以 `local-conches/.history/` 里的备份不会被当成规则包。每次覆盖前把上一份复制进去，每个包保留最近 10 份。`crab local history` 和 `crab local restore` 可以查看和回退。开发者模式下，目的地没变的 `conch_save_local` 可以直接覆盖；目的地扩大且客户端不能确认时，要到控制台确认待覆盖，不要换包名。文件从目录消失则卸载，不再留下坏包记录。

Reef 业务表、索引、唯一约束和检查约束都以 `hermit_` 开头，Flyway 历史表是 `hermit_flyway_schema_history`。V1–V4 在发布前就地改写，没有把旧表改名的迁移。V1 在投产前还给 `hermit_admin_login_failures` 补了自增主键，生产执行的就是这一版 V1–V7。之后一律新增迁移（V8 起），已执行的迁移不再改动，生产升级不需要重建库。投产前用旧 V1 建的开发库启动时会报 V1 校验和不一致，只能重建：删除 `reef-data/db` 下的 H2 文件，或删除并重建 Compose 的 MySQL 卷 `reef-mysql`。`reef-data` 里的 `keys/` 和 `conches/` 可以留下；整卷删除会同时丢掉密钥和已发布包。Crab 的 `crab-data` 不在这次变更里。

之后的升级要一致备份数据库、conches、keys。Compose 的 `reef-mysql` 与 `reef-data` 分别保存 MySQL 与 `/data`，升级必须保留两个卷。容器 Crab 的 `crab-data` 保存整个实例目录；要同时备份外部主密钥（如有），不能只备份数据库。实际卷名通常带 Compose 项目前缀，切换部署目录/项目名时要核对卷，避免意外创建新实例。恢复主密钥、公钥和实例 ID 时保持对应关系。H2 文件复制前停止所属进程，MySQL 使用数据库一致性备份；恢复应先在隔离实例验证。本次审计未实际做生产备份恢复演练。

保留策略：Crab 调用/步骤日志 30 天、幂等响应 24 小时，启动及每小时清理；debug 状态 24 小时自动过期。关掉开发者模式，或这次到期，会立刻删除全部 `dev_traces`。保留任务再删 `expires_at` 已过的追踪。`call_logs` 仍按 30 天清理，不随开关删除。Reef 每日 UTC 03:00（北京时间 11:00）清理过期失败报告、回滚事件及登录失败记录。两端小时统计以及部分审计/告警表没有全量定期保留策略，不应假定所有数据都会在 90 天删除。Crab 的 `config_audit` 可以由 `GET /console/api/audit` 和 CLI `audit list` 读取，内容限于操作者、动作、origin、Conch、账号、时间和字段名，不含凭证或配置值；这张表没有定期清理。

## 部署边界

- Compose 默认 Nginx 仅 HTTP，部署到不可信网络前配置 HTTPS 入口；管理员口令、JWT、Agent token 需要传输保护。
- Crab 控制台按连接回环地址授权，并要求 `Host`（以及带了的 `Origin`）是 `localhost`、`127.0.0.1` 或 `[::1]`，挡住 DNS rebinding：恶意网页把自己的域名解析到 127.0.0.1 后，浏览器发来的 `Host` 仍是那个域名。端口不比较，`ssh -L 9000:127.0.0.1:7777` 后访问 `http://localhost:9000/console/` 仍可用；用自定义主机名访问控制台会是 403。不能将 `/console/**` 无保护转发到公网，回环反向代理会改变实际来源的信任含义。
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
| 启动打印 `database operation failed` 和下一行路径 | 看下一行根因。Windows 盘符路径应是 `C:/...`，不能是 `/C:/...`。数据目录路径含分号时，这一行是「数据目录路径含有分号，无法打开本地数据库。」，不会去连 H2 |
| `RULE_MISMATCH` | 通过 traceId 查步骤信息，核对页面选择器/输出。已安装版本保持不动，用更高版本修复后再手动更新 |
| 上游 HTTP 400，或会话 401/403 | 分别是 `INVALID_INPUT` 与 `SESSION_EXPIRED`。核对参数或凭证；页面结构变化才是 `RULE_MISMATCH` |

Reef 日志：`docker compose logs -f reef` 或 Java 进程输出。Crab 日志查看进程输出与控制台调用详情；同机容器用 `docker compose --profile client logs -f crab`，独立容器用 `docker compose -f docker-compose.crab.yml logs -f crab`。不要把敏感配置直接附入问题报告。
