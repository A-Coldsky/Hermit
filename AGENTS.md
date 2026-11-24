# Hermit：AI 接手指南

## 阅读顺序

1. `README.md`：定位和文档导航。
2. `docs/architecture.md`：模块边界、核心流程与源码入口。
3. `docs/feature-status.md`：已实现的能力与预留结构。
4. `docs/audits/2026-10-16.md`：审计基线和未修复问题；历史观察需要结合后续代码重新判断。
5. 按任务查 `docs/development.md`、`docs/configuration.md`、`docs/conch-reference.md`、`docs/api.md`。

文档用中文，代码命名延续现有英文。文档与代码冲突时，以源码和可复现测试为准，同时更新文档。

## 项目事实

- Java 17 / Maven 多模块。`hermit-web` 的静态资源 jar 被 Reef 和 Crab 共同依赖。
- `hermit-common`：不可变规则模型、YAML 解析/校验、模板、SemVer、签名包、上报 DTO。
- `hermit-reef`：Spring Boot；Flyway + JDBC；默认 H2，Compose 使用 MySQL。
- `hermit-crab`：Javalin、CLI、HTTP 规则引擎、MCP、H2 本地存储、同步/上报。
- `hermit-it`：独立 Reef 进程、Crab 和模拟 OA 的跨模块验收。
- 前端使用 Vitest + Vue Test Utils + jsdom，`npm test`，也接入 Maven test 阶段。
- Crab 容器入口为 `deploy/crab-entrypoint.sh`；Compose `client` profile 用于同机部署，`docker-compose.crab.yml` 用于独立客户端。
- Maven 制品版本 `0.1.0-SNAPSHOT` 与规则兼容版本 `CrabVersion.CURRENT = 1.12.0` 用途不同。1.1.0 起可载入 `driver: mcp`；1.2.0 起可载入脚本库；1.3.0 起可载入 `login` 与非空 `auth.cookies`；1.4.0 起可载入登录步骤 `ask`。1.5.0 起 query 默认按 URL 编码，可用 `query_style: form` 退回。1.6.0 起可载入引号下标；模板里出现引号下标时 `min_crab_version` 必须不低于 1.6.0。1.7.0 起可载入 request 的原始 `body` 和 `xml_escape`；用了其中之一时 `min_crab_version` 必须不低于 1.7.0。headers 里的 Content-Type 覆盖 form/json 默认值不设门槛。1.8.0 起 `driver: http` 可载入 `type: file` 入参、request 的 `multipart` 和 `download`（文件走 Crab 文件暂存区）；用了其中之一时 `min_crab_version` 必须不低于 1.8.0，`driver: mcp` 的包不能用。1.9.0 起可载入步骤 `if`、`repeat`、request 的 `retry` 和 `auth.concurrent_reads`；用了其中之一时 `min_crab_version` 必须不低于 1.9.0，并且不低于 1.9.0 的包里 request、extract、assert 的未知键是错误（更低的包仍是警告）。1.10.0 起可载入顶层 `groups`（最多两级目录）与 action 的 `group`（`driver: http` 与 `driver: mcp` 均支持）；用了其中之一时 `min_crab_version` 必须不低于 1.10.0。未分组的动作保持平铺在顶层且不告警；不新增 Reef 或 Crab 数据库迁移。1.11.0 起 `driver: http` 可载入 `auth.origins`（登录步骤可以访问声明的门户、SSO 地址，并在 `base_url` 与这些地址之间跟随 301/302/303；能力步骤和 `alive_check` 仍只能访问 `base_url`）；用了它时 `min_crab_version` 必须不低于 1.11.0，`driver: mcp` 的包不能用。1.12.0 起 `driver: http` 可载入 `auth.expired_when`、`auth.alive_check.expect` 以及 `assert` 的 `on_fail: session_expired` 和变量断言键（`equals`/`not_equals`/`present`/`not_present`），用于识别 HTTP 200 业务错误码、JS/HTML 登录页、响应头或 419/440/400 等非 401/403 会话失效响应；用了其中之一时 `min_crab_version` 必须不低于 1.12.0，`driver: mcp` 不能用，不新增数据库迁移。
- Conch 指 YAML 规则包。`driver: http` 走步骤引擎。`driver: mcp` 由 Crab 按 Streamable HTTP 转发：`headers` 用请求头模板；`oauth` 在控制台做 PKCE，令牌在凭证库。开发者模式打开时，本地 MCP 包可以试跑，也可以刷新上游 `tools/list`（OAuth 包复用已连接的令牌和 401 后的刷新）。Reef 管理 API 与 Agent 清单从包内读出上游和工具表，不连上游。它不是操作系统命令脚本，也不支持 stdio。

## 修改入口

| 任务 | 优先查看 |
| --- | --- |
| 规则字段/步骤 | common `model`、`parse`、`validate`；crab `engine`；MCP/OpenAPI 投影 |
| 请求与权限 | `CrabHttpServer` → `Invoker` → `RuleEngine`；`ApiKeyPrincipal` |
| 同步/手动更新 | `ConchSync`、`InstalledConches`、`VersionSuccess`、`SyncStore` |
| 凭证与账号 | `ConchAdmin`、`AccountStore`、`CredentialVault`、`CredentialCipher` |
| Reef 发布 | `ConchUploadService`、`ConchPublishService`、`ConchDistributionService` |
| 上报/告警 | Crab `report`、Reef `tide`、common `report` DTO |
| 页面/API | `hermit-web/apps/*/src/api.ts` 及对应 Controller/ConsoleApi |

## 构建与约束

- 完整验证：`./mvnw -B verify`，先确认 `JAVA_HOME` 为 JDK 17。
- 模块验证示例：`./mvnw -pl hermit-crab -am test -Dhermit.web.skip=true`。
- `hermit-it` 依赖已打包 Reef jar 和 Crab test-jar；干净目录全量验证用 `verify`。
- Crab 构建产出普通 jar（供 `hermit-it` 与开发 classpath 使用）以及附加产物 `hermit-crab-<version>-all.jar`（独立可执行 fat JAR，仅需 JDK 17 运行，排除了 `hermit-web/reef-admin/**`）；若需通过 Reef「下载 Crab」分发，手动将 fat JAR 放入 Reef `data-dir/downloads/` 目录。
- 缺陷修复添加针对行为的回归测试；文档修改检查链接、命令与源码一致性。
- 不复制真实 `.env`、`~/.hermit`、`reef-data`、数据库、密钥或凭证到代码/文档/测试输出。测试用临时目录和模拟服务。
- 秘密放 `credentials`；`config` 不允许 `type: secret`。
- 保持验签、哈希、路径与大小限制；Reef 公钥是规则信任根。原生 Java 连接 Reef 或加载已安装正式规则时需预配公钥；容器首次运行可使用挂载公钥，或用管理员登录取得公钥，修改时保持这个初始化边界清晰。
- 输出脱敏替换已知凭证/Cookie 的字符串值及其 URL 编码形式，以及登录步骤、能力步骤里从凭证换来的值（extract，以及凭证请求之后的 script；能力步骤里引用 `login.*` 的 `set` 同样登记秘密并阻断流入普通 `script`，其余 `set` 除外）。长度需至少 4 个字符。不能假定敏感字段名、短秘密或其它派生值会自动屏蔽，详见规则包（Conch）参考。
- 账号/会话按 `(origin, conch, account)` 隔离；使用者在控制台把几个 `driver: http` 包的账号绑到同一个身份后，这些账号共用 Cookie、HTTP 客户端和账号锁（`identities`、`identity_members`、`identity_sessions`，schema 21，三张表从空开始、不回填），凭证、登录变量、验证码等待、登录失败计数和探活仍按账号，不同身份之间不共用。只有控制台能绑定，绑定要求地址有交集并带 `confirm`。已绑定共享身份的本地包覆盖（含同边界）必须经控制台或 elicitation 确认并自动移出共享身份。HTTP/MCP 调用共用 `Invoker`，不得绕过权限和能力禁用检查。
- 规则快照/权限修改需检查审计 A01 的版本切换竞态。
- Reef 和 Crab 已经投产。生产基线：Reef Flyway V1–V9（V1 含 `hermit_admin_login_failures` 的自增主键；V8 是 `admin_token_version`，给 `hermit_admin_users` 增加 `token_version`；V9 是 `notices_and_submissions`，新增 `hermit_announcements` 与 `hermit_conch_submissions`，两张表从空开始不回填），Crab `schema_version` 1–22（16 是 `session_login`，不回填；17 是 `tls_trusted_hosts`；18 是 `conch_switches`，空表，存量规则包保持启用；19 是 `conch_active_origin`，空表，没有记录时正式包生效；20 是 `file_stage_settings`，空表用默认值 1 小时、50 MiB、1 GiB；21 是 `identities`、`identity_members`、`identity_sessions`，空表不回填；22 给 `idempotency` 增加 `api_key_id VARCHAR(64) NOT NULL DEFAULT ''` 并重建联合主键 `(origin, conch, account, action, api_key_id, "key")`，存量行回填空串 `''`、不与带真实 Key 的请求碰撞并按原 TTL 自动过期；冻结的升级起点仍是 `schema/crab-v15.sql`）。已发布的迁移和升级步骤一律不改，结构变化只新增更高版本，并写清它对存量数据的效果（新列默认值、旧行回填、约束在已有数据上能否成立）。Flyway 校验已执行迁移的校验和，就地改过的迁移会让生产 Reef 起不来；Crab 就地改旧步骤只影响新装，已有客户端拿不到。Reef 的 `MigrationHistoryTest` 钉住已合入迁移的哈希并从 V7 带数据升级，新迁移合入时追加哈希、补上数据效果断言；Crab 的 `CrabSchemaHistoryTest` 从冻结的已发布结构（`schema/crab-v15.sql`）升级，结果须与全新安装一致，发布新版本时另存一份快照。需要按库现状判断的变更用 Java 迁移，并在 H2 和 MySQL 8.4 上验证。Crab 建表与升级在 `CrabDatabase`，不使用 Flyway。
- Crab 文件暂存区（`dev.hermit.crab.files`）：文件在 `$HERMIT_HOME/data/files`，重启清空、到期删除，不进数据库；设置在 `file_stage_settings`（schema 20，空表用默认值 1 小时、50 MiB、1 GiB）。`fileId` 只有创建它的 API Key 能用，控制台显式上传的所有 Key 可用，控制台试跑下载的文件仅归属内部试跑身份不对 API Key 共享；`POST /api/-/files` 拒绝只读 API Key；下载链接 `/api/-/files/{token}` 不要 Key。追踪、调用记录和上报只记文件名、大小、类型和 SHA-256；输出脱敏不扫描文件内容。
- `health_check`、debug 状态尚未接通完整执行链，不能标成完整可用功能。`local-conches` 可导入、调用和导出：无签名、只在本机。`all: true` 与旧的空范围 Key 可以调用；有限范围仍要在 `conches` 里点名。
- 对外行为变化同步更新文档、前端 API 类型和功能状态，报告验证结果及未验证边界。
