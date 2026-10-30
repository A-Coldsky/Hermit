# 功能实现状态

初次审计基线：`0c1bbcf`；功能状态已同步到 `48daca7`，日期：2026-10-16（北京时间）。仓库没有完整产品需求/验收清单，下面依据现有源码、示例和测试判断，不给缺乏依据的“完成百分比”。“已实现”表示存在可运行链路，不代表不存在缺陷或已在生产验收。

## 主要链路

| 能力 | 状态 | 证据与边界 |
| --- | --- | --- |
| Conch 模型/严格解析/语义校验/版本差异 | 已实现 | common parse/validate；对应测试齐全，部分步骤错误在运行时检查 |
| 模板、内置过滤器、输入 Schema | 已实现 | TemplateEngine/InputPreparer；不提供完整最终输出 Schema 验证 |
| HTTP 请求、登录、alive_check、Cookie 会话 | 已实现，有缺陷 | RuleEngine/HttpCalls/SessionManager。能力请求的上游 HTTP 400 为 `INVALID_INPUT`；401/403 与登录重定向为会话失效（幂等重放一次，否则 `SESSION_EXPIRED`），不计入回滚。选择器失配等仍是 `RULE_MISMATCH`。慢速响应的总 deadline 仍不可靠 |
| CSS/XPath/JSONPath/regex 提取 | 已实现 | Extractors + EngineUnitTest/LegacyOaEngineTest |
| assert/set/script | 已实现 | StepRunner/ScriptStep；脚本 Java 隔离和时间/结果限制有测试，无进程级内存隔离 |
| 多账号/默认账号/凭证/配置 | 已实现 | ConchAdmin + store；credentials 加密只写。主密钥文件缺失且已有密文时拒绝启动，不另造新密钥；密钥对不上时账号 `credentialsUnreadable`，调用 `NOT_CONFIGURED` |
| API Key 创建、撤销、scope | 已实现，有缺陷 | ApiKeyService/Principal；按 Conch/action/只读限制，未按账号限制。创建时拒绝裸能力名和不存在的 `conch.action` |
| 自动登录暂停状态 | 已实现 | 连续登录失败达上限后暂停。控制台账号行返回 `loginPaused`、`loginPauseReason`、`loginPauseRecovery`；Conch 列表 `needsConfiguration` 计入暂停，概览待配置因此计入 |
| HTTP 网关/别名/OpenAPI | 已实现 | GatewayHttpTest；OpenAPI 无鉴权且仅描述能力接口 |
| POST 幂等响应缓存 | 已实现，有缺陷 | 24h 密文缓存；无参数摘要/版本区分 |
| MCP HTTP/SSE/工具/资源 | 已实现 | McpHttpTest；≤40 直接工具，>40 目录模式，确认行为不同。协议错误是 JSON-RPC `error.data.code`/`hint`（鉴权 HTTP 401 也是该 body）；工具失败为 `isError` + `structuredContent.error`。`outputSchema` 只在协商协议 `2025-06-18` 与 `2025-11-25` 声明。声明了 minimum/maximum 的入参越界会拒绝。带有且不在回环、监听地址同源或 `mcp.allowed_origins` 内的 `Origin` 返回 403 `ORIGIN_REJECTED`，不创建会话；没有 `Origin` 仍允许。非 `initialize` 请求的 `MCP-Protocol-Version` 不是支持版本时 400；缺该头沿用会话里协商的版本。`initialize` 的未知 `protocolVersion` 仍回落协商。这两处放行写在 `McpTransportPolicy` |
| MCP stdio 桥接 | 已实现 | McpStdio；需要已运行的 HTTP Crab，CLI/验收覆盖不足以代表所有真实客户端兼容 |
| Reef 管理员初始化、JWT、锁定、审计 | 已实现 | AdminBootstrap/AdminApiTest。4xx/5xx 服务端日志带与响应相同的 traceId，4xx 不记请求体。已登录的未知 `/api/admin/**` 为 404 `NOT_FOUND` 信封。没有完整管理员生命周期管理 API |
| 上传、签名发布、分发、管理员回滚 | 已实现 | ConchApiTest/ConchConcurrencyTest/ConchSelfCheckFailureTest |
| 停用 / 重新启用 Conch | 已实现 | Reef 管理端 Conch 详情页提供停用/启用（需填原因），停用后显示原因与操作人、版本表的发布和回滚不可点，目录标「已停用」。管理 API 记录 `DISABLE`/`ENABLE` 和原因。停用后清单不含该 Conch，Agent 下载 404，发布和回滚 409 `conch is disabled`，签名包字节不变。Crab 同步后卸下注册表、清 `current`、保留版本文件和账号；调用 409 `CONCH_DISABLED`；同步状态 `kind=DISABLED` 且出现在 `removed`。重新启用后重新验签安装。回滚固定未解除、且清单版本不高于坏版本时，重新启用不会把 Conch 装回去 |
| Crab 定时/手动同步、ETag、验签 | 已实现 | ConchSyncTest/CrabWiringTest；可重试失败不记 ETag，304 核对 current。Reef 不可达时手动同步为 502 `UPSTREAM_ERROR`，状态改为这次失败；Reef 的 HTTP 错误仍保留上次成功 |
| 本地规则失配回滚、坏版本固定 | 已实现 | RollbackGuardTest；要求前一版本成功，5 次 `RULE_MISMATCH`；上游 400 与会话失效不计数；不等于发布前健康检查。回滚后控制台同步状态显示已安装版本和 `ROLLED_BACK`，固定解除前保持 |
| 上报队列、聚合、退避、预览 | 已实现 | ReportSenderTest；失败/事件队列上限 10000，超限丢弃计数；小时统计单独存储 |
| Reef Tide 查询、失败聚合、版本对比 | 已实现 | TideQueryTest；聚合延迟不等同于全量原始样本精确分位数 |
| 告警评估、确认、Webhook | 已实现，有边界 | AlertEvaluationTest。确认后，创建时间起算的去重窗口剩余时间内不新建、不推送；窗口结束后条件仍在才新建。失败上涨不提前打破窗口。Webhook 含告警 id 与相对路径 `/alerts`（无对外基址配置），没有持久化重试队列。负阈值，以及必须为正的窗口写成 0，启动时拒绝 |
| Reef 管理页面 | 已实现 | 登录、仪表盘、Conch/上传/版本及校验警告明细、Tide、告警、审计、设置说明。GET 且不是 `/api/**`、`/agent/**`、`/assets/**` 的路径返回同一份 `index.html`，未登录不是 401 JSON，由已有前端路由进入登录页。`GET /shells` 与 `/shells/**` 302 到 `/conches`。页面响应不带账号或令牌。未知前端路径仍重定向到首页，没有单独的 404 视图 |
| Crab 本机控制台 | 已实现 | 概览、Conch、账号/凭证/配置、能力、API Key、调用、上报。Conch 详情与列表显示自动登录「已暂停」及恢复方法，账号行标出凭证无法解密。详情横幅按默认账号与具体原因写标题并点名账号；凭证无法解密时对话框要求全部重填。概览把「账号暂停」与「缺凭证或配置」分开提示，同步卡有停用项时不显示「已是最新」。两端的审计、调用、上报时间列显示浏览器本地时间和时区，悬停显示 UTC。「配置审计」页按 Conch/账号筛选分页查看变更记录。未登记的 `GET /console/**`（不含 `/console/api/**` 与 `/console/assets/**`）返回同一份 `index.html`。`GET /console/shells` 与其子路径 302 到 `/console/conches`。未知前端路径仍重定向到概览，没有单独的 404 视图 |
| Crab 配置审计查询 | 已实现 | 控制台「配置审计」页、`GET /console/api/audit` 与 CLI `audit list`。可按 conch/account 分页，新的在前。只返回 id、时间、actor、action、origin、conch、account、字段名；不返回 detail 原文、配置值、凭证明文或密文。`config_audit` 仍无定期清理 |
| Docker Reef + Nginx + MySQL / Crab | 已配置，运行未验收 | Crab 同机 client profile / 独立 Compose / Dockerfile 已提供；配置语法通过，尚未启动容器栈 |

## 部分实现与缺口

| 项目 | 实际状态 | 后续工作 |
| --- | --- | --- |
| `health_check` | Parser/Model 接收，示例声明；无执行方 | 定义执行时机、账号选择、失败门槛，并接入发布/安装流程，或移除误导性声明 |
| `local-conches` / Origin.LOCAL | 路径/存储命名空间与底层测试存在，产品调用仅 OFFICIAL | 明确本地规则的加载、信任、编辑和路由设计 |
| debug | API/持久化/24h 过期存在；执行引擎/日志不读这个状态；新 UI 明示未接入调用记录 | 定义调试行为及脱敏边界，再接入日志采集 |
| `mcp.destructive` | 可解析，但 tool annotation 从 readOnly/idempotent 推导 | 对齐字段声明与实际注解；确认模式一致性需产品决定 |
| Crab 升级 | Reef 提供最新版本/下载地址，Crab 展示提示 | 无自动下载/升级器；继续手工升级或另行实现 |
| 管理员生命周期 | 空库初始化 + 登录/me | 没有改密码/创建/禁用/注销令牌的完整管理 API |
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

2026-10-30 MCP Origin 与协议版本头以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 125、hermit-crab 217、hermit-it 1，失败/错误/跳过均为 0；Vitest 63（12 个文件）。Java 小计 386，合计 449。本次新增 Crab 的 Origin、协议版本头和 `mcp.allowed_origins` 用例。未用真实 MCP 客户端或浏览器复测，也未启动 MySQL 或 Compose。

2026-10-30 页面深链回落以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 127、hermit-crab 210、hermit-it 1，失败/错误/跳过均为 0；Vitest 63（12 个文件）。覆盖 Crab `/console/audit`、`/console/nope` 及多段路径返回 `index.html`，`/console/shells/**` 302 到 `/console/conches`，`/console/api/**` 与非 GET 仍是 JSON；Reef 未登录未知页是同一份静态页而不是 401，已登录响应字节相同且不含令牌，`/api` 与 `/agent` 未知路径仍是 JSON，只有 POST 的管理接口 GET 仍是 405。未启动 MySQL 或 Compose，也未在浏览器里点选深链。前端未知路径仍重定向到首页。合入 main 的 Origin 与主题改动后，计数见下一条。

2026-10-30 合入 main（MCP Origin、主题与时区文案）后再以 JDK 17.0.20.1 执行 `./mvnw -B verify` 通过。Surefire：hermit-common 43、hermit-reef 127、hermit-crab 218、hermit-it 1，失败/错误/跳过均为 0；Vitest 74（13 个文件）。Java 小计 389，合计 463。这是当前整棵树的计数。未启动 MySQL 或 Compose，也未在浏览器里点选。前端未知路径仍重定向到首页。
