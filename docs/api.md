# HTTP、CLI 与 MCP 接口

这是源码路由索引和调用约定。响应细节以 Java Controller、[Reef 前端类型](../hermit-web/apps/reef-admin/src/api.ts)、[Crab 前端类型](../hermit-web/apps/crab-console/src/api.ts) 为准。Crab 的 `/openapi.json` 动态描述当前启用的能力；不是 Reef 管理 API 的完整 OpenAPI。

## 认证与响应

| 接口 | 认证 |
| --- | --- |
| Reef `/api/admin/login` | username/password；成功返回 data.token、data.expiresAt |
| Reef 其他 `/api/admin/**` | `Authorization: Bearer <JWT>` |
| Reef `/agent/**` | `X-Hermit-Instance: <UUID>` 和 `X-Hermit-Token: <共享令牌>`；未配令牌也拒绝 |
| Crab `/api/**`、别名、`/mcp` | `Authorization: Bearer <API Key>` 或 `X-Hermit-Key` |
| Crab `/console/api/**` | 实际连接来源必须回环地址，且 `X-Hermit-Console: 1` |
| Crab `/console/` | 页面请求来源必须回环地址；页面 JS 再加控制台头 |
| Crab `/openapi.json` | 当前无需 API Key，可能展示能力元数据 |

常规 JSON envelope：

```json
{"success":true,"data":{"example":"value"},"error":null,"traceId":"..."}
```

失败时 success=false、data 通常为 null、error 包含 code/message。YAML、ZIP、静态页、OpenAPI 原文、304 和 MCP JSON-RPC 不套此格式。上传校验失败可携带结构化 data，请同时保留 traceId。

已登录访问不存在的 Reef `/api/admin/**` 返回 404 `NOT_FOUND` 信封，`traceId` 与请求头 `X-Trace-Id`（合法时）相同。未登录的 `/api/**` 仍是 401，已登录但路径不在 `/api/admin/**` 下仍是 403；这两类由安全链拒绝，响应同样是信封，不是框架默认 JSON。页面路由（`/`、`/login`、`/shells` 等）继续返回 `index.html`。Crab 在带控制台头的回环连接上，未匹配的 `/console/api/**` 返回 404 `NOT_FOUND` 信封；未匹配的网关路径（不是 `/api/{shell}/{action}` 或已登记别名）同样如此。`traceId` 同时出现在响应体和 `X-Hermit-Trace`。`/console/` 下已注册的页面仍返回 `index.html`。

Reef 的 4xx 与 5xx 会在服务端日志写一条含同一 `traceId` 的 `request failed status=… traceId=…`。4xx 不写异常消息或请求体。5xx 另记未捕获异常，响应正文仍只有 `INTERNAL`。

## Reef 管理与分发

以下管理路由前缀 `/api/admin`：

| 方法 | 路径 | 输入/用途 |
| --- | --- | --- |
| POST | `/login` | `{username,password}`；失败锁定窗口 15 分钟，阈值 5 次 |
| GET | `/me` | 当前身份 |
| GET | `/signing-key` | `data.publicKey` PEM；不提供私钥 |
| POST | `/shells` | multipart 字段 `file`，YAML 或仅含 shell.yaml 的 ZIP；上传及解包 YAML 限 1 MiB |
| GET | `/shells`、`/shells/{shell}` | 列表/详情 |
| GET | `/shells/{shell}/versions` | 版本列表，每项含 `warningCount` 和 `warnings` 校验警告明细 |
| GET | `/shells/{shell}/versions/{version}/yaml` | YAML 原文 |
| GET | `/shells/{shell}/diff?from=...&to=...` | 版本差异 |
| POST | `/shells/{shell}/versions/{version}/publish` | 发布已上传版本 |
| POST | `/shells/{shell}/rollback` | `{version}`，切回已发布且非当前版本 |
| GET | `/tide/summary` | shell/from/to/bucket |
| GET | `/tide/failures` | shell/version/action/from/to |
| GET | `/tide/compare` | shell/versions（逗号分隔版本） |
| GET | `/tide/rollbacks` | shell/from/to |
| GET | `/alerts` | status |
| POST | `/alerts/{id}/ack` | 确认告警 |
| GET | `/audit` | 管理操作审计；参数见 AuditController |
| POST | `/retention/purge` | 立即清理过期失败报告、回滚事件和锁定窗口外的登录失败。`data` 为 `{failureReports, rollbackEvents, loginFailures}`。写入审计 `RETENTION_PURGE`，detail 为同样的三个计数。需要管理员 JWT |

Tide 时间参数使用 ISO-8601 Instant，默认查询跨度 7 天，最大 400 天。bucket 支持 hour/day，默认 day；告警 status 支持 open/all，默认 open。确认告警只记下确认人和时间，不结束去重窗口：同一 `(kind, shell, shell_version)` 自 `created_at` 起 `dedup-hours` 内不再新建。窗口过后条件仍成立才会再出现一条。失败次数上涨不会提前打破这个窗口。

配置了 `hermit.reef.tide.webhook-url` 时，新建告警会 POST JSON：`id`、`kind`、`shell`、`version`、`detail`、`createdAt`、`adminUrl`。`adminUrl` 固定为相对路径 `/alerts`（Reef 没有管理端对外基址配置）。发送失败不影响告警落库。

Agent 路由：

| 方法 | 路径 | 返回 |
| --- | --- | --- |
| GET | `/agent/version` | Crab 最新版本与下载地址提示 |
| GET | `/agent/shells/manifest` | 当前规则清单与 ETag，支持 If-None-Match/304 |
| GET | `/agent/shells/{shell}/{version}` | 已发布 ZIP |
| POST | `/agent/reports` | ReportBatch；instanceId 须与请求头一致 |

ReportBatch 包含 instanceId/crabVersion/sentAt/failures/counts/events，严格 DTO 在 [common/report](../hermit-common/src/main/java/dev/hermit/common/report)。失败按实例+trace 去重，小时统计按复合主键覆盖；回滚事件按实例和 eventId 去重。

## Crab 能力 API

```text
GET  /api/{shell}/{action}?param=value
POST /api/{shell}/{action}           JSON object body
```

使用 action 的 effectiveMethod，方法不匹配返回 405。可选头 `X-Hermit-Account` 指定账号，省略使用默认账号。API Key 按 shells/actions/readOnly 限制能力，**没有按账号限制的 scope**；能调用能力的 Key 可以选择该 Shell 下的账号。空 shells/actions 集合表示不限对应维度。`actions` 里的每一项必须是当前已加载能力的 `shell.action`。裸能力名（例如 `list_todo`）或尚不存在的能力在创建时返回 `INVALID_INPUT`，不会先收下再让调用一律 `FORBIDDEN`。CLI `apikey create --actions` 使用同一规则。

POST 可带 `Idempotency-Key`，当前缓存键为 origin/shell/account/action/key，有效期 24 小时，不包含 API Key 身份、请求体摘要或规则版本。相同键重放保存的响应（含旧 traceId），不同业务操作不要复用键；修复方向见审计待确认设计项。

| 错误码 | HTTP 状态 |
| --- | --- |
| INVALID_INPUT | 400（方法错误 405） |
| AUTH_FAILED | 网关 Key 无效 401；上游登录失败 422 |
| FORBIDDEN | 403 |
| NOT_FOUND / ACCOUNT_NOT_FOUND | 404 |
| NOT_CONFIGURED | 409 |
| SESSION_EXPIRED / RULE_MISMATCH | 422 |
| UPSTREAM_ERROR | 502 |
| TIMEOUT | 504 |
| INTERNAL | 500 |

缺必填、类型无法转换，或数值超出参数声明的 `minimum` / `maximum`，都返回 `INVALID_INPUT`。运行时不会把负页码或过大的 `pageSize` 夹进范围内再返回 200。HTTP 与 MCP 共用 `Invoker` → `RuleEngine` → `InputPreparer`。

能力请求已经发出后，上游 HTTP 400 也返回 `INVALID_INPUT`，文案为「上游拒绝了输入（HTTP 400）。请修改参数后重试。」，不再继续提取或断言。它和调用前的参数校验共用错误码，但 `failedStepType` 为 `request`。MCP 对这段文案的 hint 是按该系统的要求修改参数。上游 HTTP 401/403，以及重定向到登录页，都视为会话失效：幂等能力重新登录并重放一次，其它能力返回 `SESSION_EXPIRED` 且不重放。这两类都不会把响应体抽成空列表，也不计入 `RollbackGuard`。登录步骤不走这套映射，失败仍是 `AUTH_FAILED`；`cookie_exists` 未通过时说明登录未建立会话，并提示核对用户名和密码、重新测试登录，或检查登录页是否改版。期望状态与实际状态不一致（例如期望 200 实际 404）仍是 `RULE_MISMATCH`，累计 5 次且前一版本曾成功时仍会自动回滚。

## Crab 本机管理 API

下列路由前缀 `/console/api`，全部需要回环连接和控制台头：

| 方法 | 路径 | 输入/用途 |
| --- | --- | --- |
| GET | `/shells`、`/shells/{shell}` | 已加载规则/字段声明/能力状态。列表项含 `needsConfiguration`、`loginPaused`；详情含 `loginPaused` |
| GET / POST | `/shells/{shell}/accounts` | 列表 / `{name}` 创建。账号行见下方字段 |
| DELETE | `/shells/{shell}/accounts/{account}` | 删除账号/凭证并使会话失效 |
| POST | `/shells/{shell}/accounts/{account}/default` | 设默认账号 |
| GET / PUT | `/shells/{shell}/accounts/{account}/credentials` | 仅字段名 / `{字段:字符串}` 写入；空字符串表示删除。空 body、`{}`，或写入后仍缺必填字段，返回 `INVALID_INPUT`。已保存字段可以只改其中几项。凭证无法解密时，必须把已存字段都重新写成非空值 |
| POST | `/shells/{shell}/accounts/{account}/test-login` | 重置登录暂停并测试登录 |
| GET / PUT | `/shells/{shell}/config` | 配置声明/值 / `{字段:字符串}`；空字符串清除覆盖 |
| POST | `/capabilities/{shell}/{action}/enable`、`/disable` | 数据库开关；不能覆盖 config.yml 的禁用 |
| GET / POST | `/apikeys` | 列表 / `{name,shells,actions,readOnly}`；新 Key 仅返回一次 plaintext。`actions` 必须是已加载的 `shell.action`，裸名或不存在的能力拒绝创建 |
| DELETE | `/apikeys/{id}` | 撤销 |
| GET | `/calls`、`/calls/{traceId}` | shell/action/limit 查询及步骤摘要 |
| GET / POST | `/debug` | 读状态 / `{enabled}`；当前只存状态，24h 过期 |
| GET / POST | `/sync` | 最近同步结果 / 触发同步 |
| GET | `/reports/preview` | 待发送批次和丢弃数 |
| POST | `/reports/send` | 手动发送，返回 attempted/accepted/status |

Shell 列表的 `needsConfiguration` 在缺必填配置、任一账号缺必填凭证、凭证无法解密，或任一账号自动登录已暂停时为 true。概览待配置按它计数。`loginPaused` 只表示暂停，详情接口同样返回这个布尔值。

账号列表每一行：

| 字段 | 含义 |
| --- | --- |
| `needsConfiguration` | 缺必填凭证，或已存密文无法用当前主密钥解密。自动登录暂停不计入 |
| `missingFields` | 尚未写入的必填凭证名 |
| `credentialsUnreadable` | 已存密文无法解密 |
| `credentialMessage` | 无法解密时为「凭证无法解密，需重新配置」，否则 JSON `null` |
| `loginPaused` | 连续登录失败达到 `engine.max_login_failures` 后为 true |
| `loginPauseReason` | 暂停时为原因，否则 `null` |
| `loginPauseRecovery` | 暂停时说明恢复方法：修正凭证后对该账号执行测试登录。否则 `null` |

测试登录会先清掉暂停再尝试登录。页面应读这些字段显示「已暂停」和恢复方法，不要只看 `needsConfiguration`。

同步结果里每个 Shell 有 `shell`、`version`、`kind`、`reason`，整份结果另有 `error`（`null` 或 `{code,message}`）。自动回滚尚未解除固定时，该 Shell 的 `version` 是实际安装版本，`kind` 为 `ROLLED_BACK`，`reason` 为 `RULE_MISMATCH`。Reef 不可达时 POST 返回 502 `UPSTREAM_ERROR`，`message` 写明原因和下一步；GET 记下这次失败，不再返回上次成功。Reef 已经返回 HTTP 状态时，手动同步仍是 500 `INTERNAL`，状态保留上次成功。

## CLI

命令前缀为 `java -cp "$CP" dev.hermit.crab.CrabMain`，CP 准备见[快速开始](getting-started.md)。

```text
serve
sync
account list <shell>
account add|remove|default <shell> <name>
credential set <shell> <account> <field> [--from-env VAR]
credential list <shell> <account>
config get <shell> [key]
config set <shell> <key> <value>
config unset <shell> <key>
apikey create <name> [--shells a,b] [--actions shell.action] [--read-only]
apikey list
apikey revoke <id>
mcp-stdio [--url http://127.0.0.1:7777/mcp] [--account NAME]
```

普通管理 CLI 直接打开 H2；serve 已运行时使用控制台 API，不能依靠另一个 CLI 共享库。凭证通过隐藏输入、stdin 或命名环境变量输入，不接受明文命令行值。普通错误退出 1，数据库被占用退出 2；stdio 的连接失败也有独立退出约定。

## MCP

`/mcp` 为 Streamable HTTP：POST JSON-RPC、GET SSE、DELETE 会话，使用 `Mcp-Session-Id`；每次仍校验 API Key，并将会话绑定 Key 身份。空闲 1 小时过期，最多 1000 会话。实现使用 SDK 的 Schema 类型和自有 Javalin 传输层，不直接使用 SDK Servlet transport。

- 可见能力不超过 40 个时，直接工具名 `shell__action`。
- 超过 40 个时，提供 `list_systems`、`search_capabilities`、`describe_capability`、`call_capability` 四个目录工具。
- 目录模式调用非只读能力需要支持 elicitation 且保持 SSE，明确确认后执行；直接模式没有同样的服务端确认步骤，客户端应依据工具注解交互。
- guide resource 为 `hermit://shells/{shell}/guide`，由说明、参数、示例生成。
- 规则更新/能力开关变化推送列表变更通知。
- 直接调用的账号参数为 `account`；若 action 自带 account 输入，则使用 `_account`，也支持 X-Hermit-Account。

协议级失败是 JSON-RPC error，不使用能力 API 的 success/error envelope。`error.code` 是 JSON-RPC 整数；`error.data.code` 是稳定字符串，`error.data.hint` 是下一步。HTTP 401 的 body 同样是这个 JSON-RPC 错误。

| 场景 | HTTP | JSON-RPC `error.code` | `error.data.code` |
| --- | ---: | ---: | --- |
| 未带 API Key | 401 | -32001 | `AUTH_MISSING`。说明使用 `Authorization: Bearer <API Key>` 或 `X-Hermit-Key` |
| Key 无效或已吊销 | 401 | -32001 | `AUTH_INVALID`。到 Crab 本机控制台 `/console/apikeys`，或 CLI `apikey create` 重新签发。Key 由 Crab 签发，不是 Reef |
| Key 与会话不匹配 | 401 | -32001 | `SESSION_MISMATCH`。换创建该会话的 Key，或重新 `initialize` |
| 尚未 `initialize` | 400 | -32600 | `SESSION_REQUIRED`。先调用 `initialize`，再带响应头 `Mcp-Session-Id` |
| 会话不存在或过期 | 404 | -32600 | `SESSION_NOT_FOUND`。重新 `initialize` |
| JSON-RPC 参数类型不合法 | 200 | -32602 | `INVALID_PARAMS` |

工具执行失败仍是 JSON-RPC 成功响应里的 tool result：`isError: true`，`structuredContent` 为 `{error:{code,message,hint,field?}}`，`content[0].text` 是同一 error 对象的 JSON。`code` 与能力 API 的错误码一致，例如 `INVALID_INPUT`、`NOT_FOUND`、`FORBIDDEN`，和上面的 `AUTH_MISSING` / `AUTH_INVALID` 不同。`field` 只在能定位到一个参数时出现。

成功调用在 `content[0].text` 之外带 `structuredContent` 对象。协商到 `2025-06-18` 或 `2025-11-25` 时，`tools/list` 为直接工具以及 `list_systems`、`search_capabilities`、`describe_capability` 声明 `outputSchema`。`2024-11-05` 与 `2025-03-26` 不声明该字段。`call_capability` 的返回随被调能力变化，不声明单一 `outputSchema`。直接工具的 `outputSchema` 只列 Shell `output` 的字段名和说明，不带类型约束，因为模板渲染不会把提取值转换成声明类型；目录工具的 `outputSchema` 带类型。`outputSchema` 描述成功结果；失败结果看 `isError` 和 `structuredContent.error`。

stdio 是对运行中 Crab 的桥接，不单独启动执行器。通过 `HERMIT_API_KEY` 提供 Key，stdout 仅协议消息，诊断走 stderr：

```bash
export HERMIT_API_KEY='调用端安全保存的APIKey'
java -cp "$CP" dev.hermit.crab.CrabMain mcp-stdio --url http://127.0.0.1:7777/mcp
```
