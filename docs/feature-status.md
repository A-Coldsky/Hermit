# 功能实现状态

初次审计基线：`0c1bbcf`；功能状态已同步到 `48daca7`，日期：2026-10-16（北京时间）。仓库没有完整产品需求/验收清单，下面依据现有源码、示例和测试判断，不给缺乏依据的“完成百分比”。“已实现”表示存在可运行链路，不代表不存在缺陷或已在生产验收。

## 主要链路

| 能力 | 状态 | 证据与边界 |
| --- | --- | --- |
| Conch 模型/严格解析/语义校验/版本差异 | 已实现 | common parse/validate；对应测试齐全，部分步骤错误在运行时检查 |
| 模板、内置过滤器、输入 Schema | 已实现 | TemplateEngine/InputPreparer；不提供完整最终输出 Schema 验证 |
| HTTP 请求、登录、alive_check、Cookie 会话 | 已实现，有缺陷 | RuleEngine/HttpCalls/SessionManager。能力请求的上游 HTTP 400 为 `INVALID_INPUT`；401/403 与登录重定向为会话失效（幂等重放一次，否则 `SESSION_EXPIRED`），不计入回滚。选择器失配等仍是 `RULE_MISMATCH`，不会切换已安装版本。慢速响应的总 deadline 仍不可靠 |
| CSS/XPath/JSONPath/regex 提取 | 已实现 | Extractors + EngineUnitTest/LegacyOaEngineTest |
| assert/set/script | 已实现 | StepRunner/ScriptStep。断言失败给出下一步中文说明，不以「assert 未通过」开头，也不写入凭证或 Cookie 值。脚本 Java 隔离和时间/结果限制有测试，无进程级内存隔离 |
| 多账号/默认账号/凭证/配置 | 已实现 | ConchAdmin + store；credentials 加密只写。主密钥文件缺失且已有密文时拒绝启动，不另造新密钥；密钥对不上时账号 `credentialsUnreadable`，调用 `NOT_CONFIGURED` |
| API Key 创建、撤销、scope | 已实现，有缺陷 | ApiKeyService/Principal；按 Conch/action/只读限制，未按账号限制。创建时拒绝裸能力名、不存在的 `conch.action`，以及未加载且不在 `disabled_conches` 墓碑中的规则包名。墓碑中的停用包名可以写入 `conches`。不写 `conches` / `actions` 仍是全部正式包，不含本地包。控制台创建体里的未知字段（含嵌套 `scope`）是 400 `INVALID_INPUT`；CLI 未知选项退出 1，不会静默变成全范围 |
| 自动登录暂停状态 | 已实现 | 连续登录失败达上限后暂停。控制台账号行返回 `loginPaused`、`loginPauseReason`、`loginPauseRecovery`；Conch 列表 `needsConfiguration` 计入暂停，概览待配置因此计入。测试登录只有成功才解除暂停；失败、缺凭证或无法解密都保持暂停。暂停只在进程内，重启后清空 |
| HTTP 网关/别名/OpenAPI | 已实现 | GatewayHttpTest；OpenAPI 无鉴权且仅描述能力接口 |
| POST 幂等响应缓存 | 已实现 | 24h 密文缓存。schema 10 另存原始请求体 SHA-256。同键同摘要回放第一次响应；摘要不同，或升级前摘要为 null，返回 409 `IDEMPOTENCY_CONFLICT`，不执行。键仍不含 API Key 身份和规则版本 |
| MCP HTTP/SSE/工具/资源 | 已实现 | McpHttpTest；≤40 直接工具，>40 目录模式，确认行为不同。协议错误是 JSON-RPC `error.data.code`/`hint`（鉴权 HTTP 401 也是该 body）；工具失败为 `isError` + `structuredContent.error`。`outputSchema` 只在协商协议 `2025-06-18` 与 `2025-11-25` 声明。声明了 minimum/maximum 的入参越界会拒绝。带有且不在回环、监听地址同源或 `mcp.allowed_origins` 内的 `Origin` 返回 403 `ORIGIN_REJECTED`，不创建会话；没有 `Origin` 仍允许。非 `initialize` 请求的 `MCP-Protocol-Version` 不是支持版本时 400；缺该头沿用会话里协商的版本。`initialize` 的未知 `protocolVersion` 仍回落协商。这两处放行写在 `McpTransportPolicy` |
| MCP stdio 桥接 | 已实现 | McpStdio；需要已运行的 HTTP Crab。HTTP 401 时先把 JSON-RPC 错误写到 stdout（没有可解析的错误体时自写一条 `-32001`），再退出 1。CLI/验收覆盖不足以代表所有真实客户端兼容 |
| Reef 管理员、角色、JWT、Agent 令牌 | 已实现 | AdminBootstrap/AdminApiTest/ReefUsersAndTokensTest。角色是 `ADMIN` 或 `USER`。JWT 签发时带 `role`，每次请求按数据库里的当前角色授权；账号禁用后，已发出的 JWT 和该用户的 Agent 令牌都是 401。`USER` 可以浏览规则包、Tide、告警，并管理自己的口令和令牌；上传、发布、停用、确认告警、审计、清理和用户管理是 403。令牌明文只在创建时返回一次，库里只存 SHA-256。`HERMIT_REEF_AGENT_TOKEN` 仍可用，归 id 最小的 `ADMIN`。不能删除用户，不能取消最后一个启用中的管理员，也不能自己降级或禁用自己。4xx/5xx 服务端日志带与响应相同的 traceId，4xx 不记请求体。已登录的未知 `/api/admin/**` 为 404 `NOT_FOUND` 信封。查询参数类型不匹配或缺少必填参数时 400 `INVALID_INPUT`，message 带字段名，不回显参数值。管理页面还没有用户和令牌界面 |
| 上传、签名发布、分发 | 已实现 | ConchApiTest/ConchConcurrencyTest/ConchSelfCheckFailureTest。发布版本必须严格高于当前已发布版本，没有当前版本时可以发布。不提供管理员回滚；坏版本用更高版本替换。`GET /diff` 在 `from` 与 `to` 都缺省时按 semver 对比最高两个版本，高的为 to；只缺一个仍 400；少于两个版本 400，message 为 `at least two versions are required to diff` |
| 规则包拉取次数与清单元数据 | 已实现 | Flyway V5 给 `hermit_conch_versions` 增加 `pull_count`（默认 0）。成功下载已发布 ZIP 时该版本加 1；清单、304、404、停用和验签失败不计，也不按实例去重。`GET /api/admin/conches` 与详情的 `pullCount` 是各版本之和，版本列表是该版本自己的次数。清单每项带当前发布版本的 `title`、`description`、`capabilityCount`，ETag 随这些字段变化，不随拉取次数变化。管理端规则包卡片、列表和详情显示合计次数，版本表「拉取」列显示各版本次数。上传页说明 Crab 导出的本地规则包 YAML 可以直接上传。已跑过 V1–V4 的库启动时补列，不必为这一列重建；更早的无 `hermit_` 前缀库仍要按配置文档重建 |
| 停用 / 重新启用 Conch | 已实现 | Reef 管理端 Conch 详情页提供停用/启用（需填原因），停用后显示原因与操作人、版本表的发布不可点，目录标「已停用」。管理 API 记录 `DISABLE`/`ENABLE` 和原因。停用后清单不含该 Conch，Agent 下载 404，发布 409 `conch is disabled`，签名包字节不变。Agent 清单不能区分停用、从未发布和撤回。Crab 只对已在注册表、已订阅、来源 OFFICIAL、且这次从清单消失的包写 `disabled_conches`。注册表和 `current` 保留，HTTP/MCP 仍经 `Invoker` 调用。同步不下载、不重试、不重装；结果只有一行 `kind=DISABLED`，不进入 `removed`。控制台列表和详情带 `reefDisabled`、`reefDisabledAt`；目录 `disabled` 与墓碑一致，离开清单缓存的名字仍补一行且 `version` 为 null。第一次停用写 `REEF_CONCH_DISABLED`，清单再次出现并清墓碑时写 `REEF_CONCH_ENABLED`。相同版本恢复为 `SKIPPED_SAME`，更高版本记 `UPDATE_AVAILABLE` 且不自动安装，更低版本记 `SKIPPED_OLDER`。本机已安装版本继续可调用。退订仍卸下并清墓碑，不写 `REEF_CONCH_ENABLED`。本地包不写墓碑。只有墓碑还在且注册表已无此包时，调用才是 409 `CONCH_DISABLED`。Reef 管理页的停用说明改为「Crab 标记失效、已安装版本仍可调用、不再更新」。Crab 控制台列表与详情标「已失效」，详情说明仍可调用和发现停用的时间；概览同步卡、可订阅目录与配置审计有对应中文标签 |
| Crab 定时/手动同步、ETag、验签 | 已实现 | ConchSyncTest/CrabWiringTest；只为已订阅的规则包刷新目录。第一次订阅安装当前版本。已安装包遇到更高清单版本记 `UPDATE_AVAILABLE`，不下载；更低版本记 `SKIPPED_OLDER`。可重试失败不记 ETag，`NOT_SUBSCRIBED` 不阻止记 ETag。304 沿用目录缓存。Reef 不可达时手动同步为 502 `UPSTREAM_ERROR`，状态改为这次失败；Reef 的 HTTP 错误仍保留上次成功 |
| Crab 规则包订阅 | 已实现 | 订阅只在本机 H2 `subscriptions`（schema 8）。新装默认不订阅。未订阅不下载；订阅后立即同步。退订卸下注册表、清 `current`、不写停用墓碑，账号/凭证/配置保留，调用变为 `NOT_FOUND`。`GET /console/api/catalog`、订阅/退订 API，以及 CLI `catalog` / `subscribe` / `unsubscribe`。控制台「可订阅规则包」页列出目录缓存，可订阅、取消订阅、从 Reef 刷新；与本地包同名时订阅按钮不可用。规则包详情可取消订阅。概览同步卡不逐条列出未订阅项，只提示数量。`NOT_SUBSCRIBED`、`SKIPPED_LOCAL` 与订阅、本地包审计类型有中文标签。schema 8 升级把已有 `current` 指针的合法包名记为 actor `migration` |
| 手动更新与失配保持 | 已实现 | `POST /console/api/conches/{conch}/update` 与 CLI `update <conch>` 安装最近一次同步清单里的更高版本，先验签。默认账号缺少新增必填凭证时 409，包留在版本目录，`current` 不变；补凭证后再次更新才切换。没有默认账号不拦。`RULE_MISMATCH` 只上报失败，不切换版本。schema 12 删除 `pinned_rollbacks`。成功调用仍写 `version_success`。手动更新成功写 `CONCH_UPDATED`，上报开启时版本变化 reason 为 `MANUAL_UPDATE`。控制台 API 类型带 `updateAvailable`、`latestVersion`、`newRequiredCredentials`；Reef 管理端版本表不再调用回滚接口 |
| 上报队列、聚合、退避、预览 | 已实现 | ReportSenderTest；失败/事件队列上限 10000，超限丢弃计数；小时统计单独存储 |
| Reef Tide 查询、失败聚合、版本对比 | 已实现 | TideQueryTest。失败分组、样本和版本切换带 `owner`（旧数据为 null，不同用户拆成不同组）。小时汇总不加 `owner`。聚合延迟不等同于全量原始样本精确分位数 |
| 告警评估、确认、Webhook | 已实现，有边界 | AlertEvaluationTest。确认后，创建时间起算的去重窗口剩余时间内不新建、不推送；窗口结束后条件仍在才新建。失败上涨不提前打破窗口。Webhook 含告警 id 与相对路径 `/alerts`（无对外基址配置），没有持久化重试队列。负阈值，以及必须为正的窗口写成 0，启动时拒绝 |
| Reef 管理页面 | 已实现 | 登录、仪表盘、Conch/上传/版本及校验警告明细、Tide、告警、审计、设置说明。GET 且不是 `/api/**`、`/agent/**`、`/assets/**` 的路径返回同一份 `index.html`，未登录不是 401 JSON，由已有前端路由进入登录页。`GET /shells` 与 `/shells/**` 302 到 `/conches`。页面响应不带账号或令牌。前端把 `/shells` 与 `/shells/:conch` 转到 `/conches`；已登录的未知路径显示应用内「页面不存在」 |
| Crab 本机控制台 | 已实现 | 概览、Conch、账号/凭证/配置、能力、API Key、调用、上报。调用记录的 `action` 筛选接受能力名或 `conch.action`；与 `conch` 冲突时 400。每条记录另有 `channel`（`HTTP` 对外 OpenAPI、`MCP`、预留的 `CONSOLE`），可按 `channel` 筛选；省略 `limit` 时 100，最大 500。控制台调用页与概览都传 100；调用页有「渠道」列和筛选，详情抽屉列出原始错误码，标题只显示中文。目前没有写入 `CONSOLE` 的路径，测试登录不写调用记录。Conch 详情与列表显示自动登录「已暂停」及恢复方法，账号行标出凭证无法解密。详情横幅按默认账号与具体原因写标题并点名账号；凭证无法解密时对话框要求全部重填。概览把「账号暂停」与「缺凭证或配置」分开提示，同步卡有停用项时不显示「已是最新」。两端的审计、调用、上报时间列显示浏览器本地时间和时区，悬停显示 UTC。「配置审计」页按 Conch/账号筛选分页查看变更记录。未登记的 `GET /console/**`（不含 `/console/api/**` 与 `/console/assets/**`）返回同一份 `index.html`。`GET /console/shells` 与其子路径 302 到 `/console/conches`。前端把 `shells` 与 `shells/:conch` 转到 `/conches`；未知路径显示应用内「页面不存在」 |
| Crab 配置审计查询 | 已实现 | 控制台「配置审计」页、`GET /console/api/audit` 与 CLI `audit list`。可按 conch/account 分页，新的在前。只返回 id、时间、actor、action、origin、conch、account、字段名；不返回 detail 原文、配置值、凭证明文或密文。`config_audit` 仍无定期清理 |
| 本地规则包 `local-conches` | 已实现 | 控制台上传或 `local-conches/{conch}.yaml` 导入后，HTTP 与 MCP 经 `Invoker` 调用。无签名、只在本机。API Key 的 `conches` 必须显式包含包名，空范围不含本地包。导出是磁盘上的原始 YAML。同步不覆盖、不删除：移除循环同时跳过未订阅和 `Origin.LOCAL`。与已订阅或已安装的正式包重名是 409 `INVALID_INPUT`；订阅已导入的本地包同样是 409。schema 9 给 `call_logs` 增加 `source`。调用记录 `source=LOCAL`，不上报 Tide，也不记入 `version_success`。控制台「规则包与账号」可导入 YAML、重新加载本地目录，导入失败时列出校验错误；本地包在列表、详情、调用记录和 API Key 范围选择里带「本地」标签。详情可导出 YAML、删除。创建 API Key 不选规则包时提示本地包需要明确选中 |
| Docker Reef + Nginx + MySQL / Crab | 已配置，运行未验收 | Crab 同机 client profile / 独立 Compose / Dockerfile 已提供；配置语法通过，尚未启动容器栈 |

## 部分实现与缺口

| 项目 | 实际状态 | 后续工作 |
| --- | --- | --- |
| `health_check` | Parser/Model 接收，示例声明；无执行方 | 定义执行时机、账号选择、失败门槛，并接入发布/安装流程，或移除误导性声明 |
| debug | API/持久化/24h 过期存在；执行引擎/日志不读这个状态；新 UI 明示未接入调用记录 | 定义调试行为及脱敏边界，再接入日志采集 |
| `mcp.destructive` | 可解析，但 tool annotation 从 readOnly/idempotent 推导 | 对齐字段声明与实际注解；确认模式一致性需产品决定 |
| Crab 升级 | Reef 提供最新版本/下载地址，Crab 展示提示 | 无自动下载/升级器；继续手工升级或另行实现 |
| 管理员页面 | API 已有，Vue 页面未做 | 改自己的口令、创建用户、调整角色和禁用、重置他人口令、签发和吊销 Agent 令牌、按用户查看 Crab 实例都有管理 API。`apps/reef-admin/src/api.ts` 有对应类型。页面还没做 |
| 配置批量原子更新 | 控制台 PUT 逐字段调用 putConfig | 校验/写入失败可能部分生效；补事务行为测试 |
| 数据保留 | `hermit_failure_reports`、`hermit_rollback_events` 按保留天数清理，登录失败按锁定窗口清理。定时默认每天 03:00 UTC，`hermit.reef.retention-cron` 可改；管理员 `POST /api/admin/retention/purge` 返回删除条数并写审计 | 两端小时统计及部分审计/告警数据仍可持续增长 |
| 前端交互测试 | 类型检查、构建、后端静态资源测试和 Vitest/jsdom 组件测试 | 仍缺真实浏览器 E2E |
| 交付工程化 | Maven 构建/Java/Vitest 测试和 Crab Docker 运行镜像已提供 | 无 CI workflow/覆盖率门槛/自动依赖扫描；原生 Crab 仍为普通 jar |

`legacy_oa.yaml` 只实现待办查询。其 Conch 描述涉及审批/请假、related 引用 approve/list_done，都不能作为对应动作已实现的证据。

## 验证记录

初次基线 `0c1bbcf` 的 309 项 Java 测试通过。2026-10-16 同步到 `48daca7` 后，以 JDK 17、Maven Wrapper 3.9.16 重新执行 `./mvnw -B verify`：

| 模块 | 测试数 | 失败/错误/跳过 |
| --- | ---: | --- |
| hermit-common | 41 | 0 / 0 / 0 |
| hermit-reef | 97 | 0 / 0 / 0 |
| hermit-crab | 170 | 0 / 0 / 0 |
| hermit-it | 1 | 0 / 0 / 0 |
| Java 小计 | **309** | **0 / 0 / 0** |
| hermit-web（Vitest，4 个文件） | 18 | 0 / 0 / 0 |
| 合计 | **327** | **0 / 0 / 0** |

hermit-web 的 `npm ci`、vue-tsc、两个 Vite 构建和 Vitest 通过；Docker Compose 使用 `.env.example` 校验同机 `client` profile 与独立 Crab 配置通过，入口脚本 `sh -n` 通过。额外探针复现 6 项代码问题，详见[审计报告](audits/2026-10-16.md)。没有覆盖率百分比、真实老系统/真实 MCP 客户端验收、浏览器交互验收、MySQL 运行验证或生产压测结果。

2026-10-26 上游错误映射以 JDK 17.0.20 执行 `./mvnw -B verify` 通过：hermit-common 43、hermit-reef 100、hermit-crab 187、hermit-it 1，Vitest 30（6 个文件）。未覆盖真实老系统，以及除 HTTP 400/401/403 以外的 4xx。

2026-10-26 Conch 停用以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过：hermit-common 43、hermit-reef 119、hermit-crab 203、hermit-it 1，Vitest 43（7 个文件）。停用覆盖 Reef H2 的停用/启用、清单排除、签名包不变、发布与回滚 409，以及和发布的并发；Crab 卸下注册表、304 后仍显示 `DISABLED`、重启不加载、调用 409 `CONCH_DISABLED`；hermit-it 在告警回滚后停用并核对调用与同步状态。未覆盖 MySQL、浏览器页面（未做 Vue 界面）、MCP HTTP 端到端、停用瞬间已经持有授权快照的在途请求、回滚固定挡住重新安装、告警仍按当前已发布版本评估。旧 Crab 会从清单消失路径清掉注册表，但若仍保留 `current`，重启后可能重新加载。

2026-10-26 配置审计读取以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过：hermit-common 43、hermit-reef 117、hermit-crab 205、hermit-it 1，Vitest 43（7 个文件）。这是当前整棵树的计数。本次新增 `ConfigAuditReadTest`、`ConsoleAuditTest` 和 CLI `audit list` 用例。配置审计页面随后合入，未在浏览器里点选；`config_audit` 仍无定期清理。未覆盖真实老系统。

2026-10-26 错误日志、未知路由信封与保留任务以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过：hermit-common 43、hermit-reef 122、hermit-crab 202、hermit-it 1，Vitest 46（8 个文件）。未启动定时器到点执行，也未跑 MySQL/Compose。小时统计、审计和告警仍不在清理范围内。

2026-10-26 合入 main 后，`disabled_conches` 改为 Crab schema 7（版本 6 仍只建 `config_audit_at_idx`，已应用的版本 6 不重跑）。JDK 17.0.20.1 执行 `./mvnw -B verify` 通过：hermit-common 43、hermit-reef 124、hermit-crab 209、hermit-it 1，Vitest 49（9 个文件）。当时 Reef Flyway 仍是 V1–V4，V4 为 `conch_availability`。未覆盖 MySQL，也未在浏览器点选停用或审计标签。

Reef 表、索引、唯一约束和检查约束现为 `hermit_` 前缀，Flyway 历史表为 `hermit_flyway_schema_history`，V4 表为 `hermit_conch_availability`。V1–V4 就地改写，没有数据迁移。已有 `reef-data` 中的 H2 和 Compose MySQL 库必须重建。Crab 本地表名不变。

2026-10-29 表前缀以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 125、hermit-crab 209、hermit-it 1，失败/错误/跳过均为 0；Vitest 51（10 个文件）。同一 JDK 先跑 `./mvnw -B -pl hermit-reef -am test -Dhermit.web.skip=true`：hermit-common 43、hermit-reef 125（打包页面测试 1 项因跳过前端资源而跳过），失败 0。未启动 MySQL 或 Compose。

2026-10-30 查询参数、API Key 规则包范围、调用记录筛选与断言说明以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 128、hermit-crab 214、hermit-it 1，失败/错误/跳过均为 0；Vitest 51（10 个文件）。Java 小计 386，连同 Vitest 合计 437。未启动 MySQL 或 Compose，也未做浏览器验收（未改 Vue）。

2026-10-30 MCP Origin 与协议版本头以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 125、hermit-crab 217、hermit-it 1，失败/错误/跳过均为 0；Vitest 63（12 个文件）。Java 小计 386，合计 449。本次新增 Crab 的 Origin、协议版本头和 `mcp.allowed_origins` 用例。未用真实 MCP 客户端或浏览器复测，也未启动 MySQL 或 Compose。

2026-10-30 页面深链回落以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 127、hermit-crab 210、hermit-it 1，失败/错误/跳过均为 0；Vitest 63（12 个文件）。覆盖 Crab `/console/audit`、`/console/nope` 及多段路径返回 `index.html`，`/console/shells/**` 302 到 `/console/conches`，`/console/api/**` 与非 GET 仍是 JSON；Reef 未登录未知页是同一份静态页而不是 401，已登录响应字节相同且不含令牌，`/api` 与 `/agent` 未知路径仍是 JSON，只有 POST 的管理接口 GET 仍是 405。未启动 MySQL 或 Compose，也未在浏览器里点选深链。前端未知路径仍重定向到首页。合入 main 的 Origin 与主题改动后，计数见下一条。

2026-10-30 合入 main（MCP Origin、主题与时区文案）后再以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 127、hermit-crab 218、hermit-it 1，失败/错误/跳过均为 0；Vitest 74（13 个文件）。Java 小计 389，合计 463。未启动 MySQL 或 Compose，也未在浏览器里点选。前端未知路径仍重定向到首页。

2026-10-30 合入 main（#46–#48、#51）后以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 128、hermit-crab 222、hermit-it 1，失败/错误/跳过均为 0；Vitest 75（14 个文件）。Java 小计 394，连同 Vitest 合计 469。未启动 MySQL 或 Compose，也未做浏览器验收。

2026-10-30 再合入 origin/main `34f5b01`（#50、#51）后以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 130、hermit-crab 223、hermit-it 1，失败/错误/跳过均为 0；Vitest 75（14 个文件）。Java 小计 397，合计 472。服务端页面回落与 shells 的 302 保持不变；应用内「页面不存在」与前端 `/shells` 跳转来自 #51，未改 Vue。未启动 MySQL 或 Compose，也未在浏览器里点选。

2026-10-30 Crab 规则包订阅（schema 8）以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 130、hermit-crab 237、hermit-it 1，失败/错误/跳过均为 0；Vitest 75（14 个文件）。Java 小计 411，连同 Vitest 合计 486。覆盖未订阅不下载、订阅后安装、退订后 `NOT_FOUND` 且账号仍可在重订阅后调用、schema 8 保留已安装包、与 LOCAL 同名拒绝订阅，以及 hermit-it 在同步安装前先订阅。未改 Vue，未在浏览器点选；未启动 MySQL 或 Compose。没有用真实 Reef 复测（FakeReef 与 hermit-it 的本机 Reef）。退订当时已经进入引擎的请求、退订后回滚固定是否仍挡住重装，都没有单独压测。

2026-10-30 规则包拉取次数以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 135、hermit-crab 223、hermit-it 1，失败/错误/跳过均为 0；Vitest 75（14 个文件）。Java 小计 402，连同 Vitest 合计 477。覆盖成功下载加一、未发布与停用 404 不计、清单和 304 不计、验签失败不计、各版本之和、清单 `description`/`capabilityCount` 进入 ETag，以及 16 路并发下载。未启动 MySQL 或 Compose。管理页面类型已有 `pullCount`，界面未展示，也未在浏览器里点选。停用与下载并发、以及响应还没写完客户端就断开，没有覆盖。合入订阅分支后的计数见下一条。

2026-10-30 合入 origin/main（#52 拉取次数）后以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 135、hermit-crab 237、hermit-it 1，失败/错误/跳过均为 0；Vitest 75（14 个文件）。Java 小计 416，连同 Vitest 合计 491。订阅与拉取次数两侧的功能行、接口说明和上面两段验证记录都保留。本地包合入后的计数见下一条。未改 Vue，未在浏览器点选；未启动 MySQL 或 Compose。没有用真实 Reef 复测。退订当时已经进入引擎的请求、退订后回滚固定，以及停用与下载并发、响应未写完就断开，都没有单独压测。

2026-10-30 本地规则包（schema 9）合入 origin/main（#52 拉取次数、#53 订阅 schema 8）后以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 135、hermit-crab 245、hermit-it 1，失败/错误/跳过均为 0；Vitest 75（14 个文件）。Java 小计 424，连同 Vitest 合计 499。停用后仍保留已安装版本的计数见文末。schema 9 在 schema 8 之后执行；schema 8 改为按自身行是否缺失来补，已记下版本 9 的库仍会建 `subscriptions`。同步移除循环只卸下已订阅且不是 `Origin.LOCAL` 的包。订阅已导入的本地包，以及导入已订阅或已安装正式包的名字，都是 HTTP 409、错误码 `INVALID_INPUT`。注册表沿用 `put(doc, Origin)` 与 `origin(name)`。未改 Vue，未在浏览器点选；未启动 MySQL 或 Compose。没有用真实 MCP 客户端复测。控制台请求体上限仍是 1_000_000 字节，逻辑上限是 1 MiB。停用墓碑同名不视为重名。删除或重载时已经进入引擎的请求没有单独压测。

2026-10-30 控制台订阅与本地规则包页面、Reef 拉取次数展示后执行 `./mvnw -B verify` 通过。本机只有 JDK 21.0.12.1，未在 JDK 17 上复跑；这次只改了前端和文档，Java 代码与上一条 JDK 17 验证相同。Surefire：hermit-common 43、hermit-reef 135、hermit-crab 245、hermit-it 1，失败/错误/跳过均为 0；Vitest 84（15 个文件），`vue-tsc` 通过。新增用例覆盖目录未配置、订阅后刷新、本地同名不可订阅、本地标签、导入校验错误列表、本地详情导出/删除与 Reef 详情取消订阅、API Key 本地包提示、版本表拉取列。未在浏览器里点选，未检查 1024/375 宽度与深色模式，导出 YAML 的浏览器下载没有测试。

2026-10-30 Reef 停用后 Crab 保留已安装版本，以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 135、hermit-crab 247、hermit-it 1，失败/错误/跳过均为 0；Vitest 84（15 个文件），`vue-tsc` 通过。Java 小计 426，连同 Vitest 合计 510。这是当前整棵树的计数。Crab 覆盖清单消失后注册表和 `current` 仍在、`removed` 为空、只有一行 `DISABLED`、不下载、304 时快照仍含该版本、`disabled_at` 与 `REEF_CONCH_DISABLED` 不重复、重启仍加载、配好账号后调用 200；同版本重新出现为 `SKIPPED_SAME` 并写一条 `REEF_CONCH_ENABLED`，更高版本才下载。控制台列表和详情返回 `reefDisabled` / `reefDisabledAt`，目录 `disabled` 为 true 且 `version` 为 null。退订清掉墓碑，不写 `REEF_CONCH_ENABLED`。本地包不写墓碑。已加载包上关掉能力是 `NOT_FOUND`，Key 范围不含该包是 `FORBIDDEN`，注册表里已经没有这份包才是 `CONCH_DISABLED`。hermit-it 在告警回滚后停用，调用仍成功；重新启用后因回滚固定停在 1.0.0，`reefDisabled` 为 false，调用仍成功。未改 Vue，未在浏览器点选；未启动 MySQL 或 Compose。没有用真实 MCP 客户端复测。停用瞬间已经持有授权快照的在途请求，以及旧库里 `current` 已被清掉的墓碑，没有单独压测。

2026-10-30 停用失效提示的界面改动后 Vitest 85（15 个文件）、`vue-tsc` 通过；只改前端与文档，Java 计数同上一条。未在浏览器点选。

2026-11-03 幂等请求体冲突、测试登录才解除暂停、MCP stdio 的 401、版本 diff 缺省、API Key 未知字段、调用 `channel` 与列表上限，以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 136、hermit-crab 251、hermit-it 1，失败/错误/跳过均为 0；Vitest 85（15 个文件），`vue-tsc` 通过。Java 小计 431，连同 Vitest 合计 516。这是当前整棵树的计数。覆盖同键同体回放（含 `Idempotent-Replayed`）、空格或换体为 409 `IDEMPOTENCY_CONFLICT` 且不打上游、升级前 `body_sha256` 为 null 的重放同样冲突；测试登录失败保持暂停、成功才解除；stdio 在 401 有错误体和无 body 时都先写 JSON-RPC 再退出 1；diff 两端缺省取最高两个 semver（高的为 to），只缺一端或少于两个版本为 400；创建 API Key 的未知字段（含嵌套 `scope`）与 CLI `--scope` 都不会变成全范围；调用列表可按 `channel` 筛选，省略 `limit` 为 100、大于 500 为 400，HTTP 与 MCP 分别写入。schema 10 是请求体摘要，schema 11 是 `channel`，旧调用行默认 `HTTP`。未改 Vue，未在浏览器点选；未启动 MySQL 或 Compose。没有用真实 MCP 客户端复测 stdio。`CONSOLE` 没有写入方。概览页仍显式请求 200 条。共享错误文案表还没有 `IDEMPOTENCY_CONFLICT` 的中文标签。

2026-11-03 版本只向前：Reef 不再提供管理员回滚，发布必须严格高于当前已发布版本；Crab 同步只刷新目录，更高版本记 `UPDATE_AVAILABLE` 且不下载，由手动更新安装。以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 137、hermit-crab 249、hermit-it 1，失败/错误/跳过均为 0；Vitest 85（15 个文件），`vue-tsc` 通过。Java 小计 430，连同 Vitest 合计 515。这是当前整棵树的计数。没有当前版本时可以发布；目标版本不高于当前已发布版本为 409，message 为 `published version must be higher than the current version`。并发发布较低版本不会把 current 拉回去。`POST /api/admin/conches/{conch}/rollback` 为 405 `INVALID_INPUT`（管理端 GET 兜底占住该路径），同路径 GET 为 404。清单项带排序后的 `requiredCredentials`。第一次订阅仍安装当前版本；已安装且清单更高为 `UPDATE_AVAILABLE`（结果里的版本是已安装版本），更低为 `SKIPPED_OLDER`，不降级。`POST /console/api/conches/{conch}/update` 与 CLI `update` 用最近一次同步的清单，不现场重拉；先验签，目录里已知的必填凭证名与包内不一致则拒绝且不切换。默认账号缺少新增必填凭证时 409 `missing credentials for update: …`，已验签的包留在版本目录，`current` 不变；凭证写入接受已安装声明，或已暂存且用 Reef 公钥重新验签通过、版本等于清单最新且高于已安装版本的声明。再次更新才切换。没有默认账号不拦。`RULE_MISMATCH` 只上报，不切换版本；成功调用仍写 `version_success`。schema 12 在版本行缺失时仍删除 `pinned_rollbacks`（schema 10、11 保持不动）。手动更新成功写 `CONCH_UPDATED`，上报开启时版本变化 reason 为 `MANUAL_UPDATE`。hermit-it：订阅安装 1.0.0，发布有问题的 1.1.0 后同步仍停在 1.0.0 且调用成功，手动更新后连续失配不退回，再发布 1.2.0 并手动更新后调用成功；停用后版本仍是 1.2.0 且可调用。Reef 版本表去掉回滚按钮，发布确认改为说明需要手动更新。Crab 控制台 API 增加 `update`，类型带 `updateAvailable`、`latestVersion`、`newRequiredCredentials`；目录页没有更新按钮。未在浏览器点选；未启动 MySQL 或 Compose。没有用真实 MCP 客户端复测。手动更新当时已经持有授权快照的在途请求没有单独压测。无法解密的密文仍算该凭证已填写，没有单独用例。目录在下次同步前可能过期。Reef 管理端 `UploadView`、`ConchDetailView`、`LoginView`、`AuditView`、`TideView`、`DashboardView` 和 `labels.ts` 仍有「回滚」或「下次同步时切换」的文案。

2026-11-03 评估 faa86e2 的界面修复与手动更新界面：Reef 管理端去掉所有「回滚」与「下次同步时切换」文案，发布确认（版本表与上传页）列出相对当前版本新增的必填凭证；概览与 Tide 的「自动回退」改为「版本切换」，原因 `MANUAL_UPDATE` 显示为「使用者手动更新」。Crab 控制台的目录、列表与详情标「有新版本 vX」，目录与详情可以更新，详情列出新版本需要的凭证；更新返回 409 缺凭证时提示补齐哪些凭证。取消订阅、删除账号、吊销 Key 的确认按钮为危险色，删除账号说明不可恢复；「Conch」表头统一为「规则包」；「本地」标签改为中性色，「已失效」改为警告色；同步结果的原因码显示中文；全局错误提示不再拼接原始错误码。只改前端与文档：Vitest 89（15 个文件）、`vue-tsc` 与两端构建通过；Java 与上一条相同，未复跑 `./mvnw -B verify`。未在浏览器点选，未检查 375 宽度。

2026-11-04 Reef 用户、角色和 Agent 令牌以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 150、hermit-crab 249、hermit-it 1，失败/错误/跳过均为 0；Vitest 89（15 个文件），`vue-tsc` 通过。Java 小计 443，连同 Vitest 合计 532。这是当前整棵树的计数。Flyway V6 给已有管理员补 `role=ADMIN`，新建 `hermit_agent_tokens` 与 `hermit_crab_instances`，失败报告、小时计数和版本切换增加可空 `owner_user_id`。覆盖令牌签发后 Agent 调用、60 秒内不重复写 `last_used_at`、吊销后与错误令牌同一 401、重复吊销只记一条审计、禁用用户的令牌和旧 JWT 都是 401、`USER` 对上传/发布/停用/确认告警/审计/清理/用户管理为 403、最后一个启用中的管理员不能被取消、明文不出现在列表、审计和日志里。未改 `hermit-crab`，也没有做用户或令牌的 Vue 页面。`shared/http.ts` 增加了 `patch`，管理端 `api.ts` 才能调用 `PATCH /users/{username}`。未启动 MySQL 或 Compose，也未在浏览器点选。`docker-compose.yml` 的 Reef 入口仍然拒绝空的 `HERMIT_REEF_AGENT_TOKEN`；Java 进程本身允许只靠库内令牌启动。库里还没有 `ADMIN` 时，配置令牌仍可通过，但 owner 为空、不记实例。
