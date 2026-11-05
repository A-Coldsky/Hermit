# HTTP、CLI 与 MCP 接口

这是源码路由索引和调用约定。响应细节以 Java Controller、[Reef 前端类型](../hermit-web/apps/reef-admin/src/api.ts)、[Crab 前端类型](../hermit-web/apps/crab-console/src/api.ts) 为准。Crab 的 `/openapi.json` 动态描述当前启用的能力；不是 Reef 管理 API 的完整 OpenAPI。

## 认证与响应

| 接口 | 认证 |
| --- | --- |
| Reef `/api/admin/login` | username/password；成功返回 data.token、data.expiresAt。JWT 带 `role`（`ADMIN` 或 `USER`）。每次请求按数据库里的当前角色授权，不看 JWT 里的 `role` |
| Reef 其他 `/api/admin/**` | `Authorization: Bearer <JWT>`。`USER` 可以读规则包、Tide、告警，并管理自己的口令和 Agent 令牌；其余管理写操作、审计、清理和用户管理是 403 `FORBIDDEN`。账号已禁用时已发出的 JWT 也是 401 |
| Reef `/agent/**` | `X-Hermit-Instance: <UUID>` 和 `X-Hermit-Token`。令牌可以是配置里的 `HERMIT_REEF_AGENT_TOKEN`，或某个用户签发且未吊销、用户也未禁用的 Agent 令牌。禁用用户会把他名下未吊销的库内令牌写上 `revoked_at`，重新启用不清除，这些令牌与错误令牌一样 401。配置令牌归 id 最小的 `ADMIN`。这个管理员已禁用，或当前配置值的 SHA-256 在作废表里，配置令牌也 401；重新启用且配置值没改仍然 401。把配置改成另一个非空字符串后，新值可用（归属管理员须为启用），旧值仍 401。库里还没有任何 `ADMIN`、且当前值未作废时，配置令牌仍可通过，上报的 owner 为空，也不记 Crab 实例 |
| Crab `/api/**`、别名、`/mcp` | `Authorization: Bearer <API Key>` 或 `X-Hermit-Key` |
| Crab `/console/api/**` | 实际连接来源必须回环地址，且 `X-Hermit-Console: 1` |
| Crab `/console/` | 页面请求来源必须回环地址；页面 JS 再加控制台头 |
| Crab `/openapi.json` | 当前无需 API Key，可能展示能力元数据 |

常规 JSON envelope：

```json
{"success":true,"data":{"example":"value"},"error":null,"traceId":"..."}
```

失败时 success=false、data 通常为 null、error 包含 code/message。YAML、ZIP、静态页、OpenAPI 原文、304 和 MCP JSON-RPC 不套此格式。上传校验失败可携带结构化 data，请同时保留 traceId。

已登录访问不存在的 Reef `/api/admin/**` 返回 404 `NOT_FOUND` 信封，`traceId` 与请求头 `X-Trace-Id`（合法时）相同。未登录的 `/api/**`（含 `/api` 本身）仍是 401，已登录但路径不在 `/api/admin/**` 下仍是 403；这两类由安全链拒绝，响应同样是信封，不是框架默认 JSON。`/agent/**` 在实例号或令牌无效时仍是 400/401；两者都有效但路径没有对应接口时是 404 `NOT_FOUND`。这些都不是 HTML。

Reef 的 GET 页面（不是 `/api/**`、`/agent/**`、`/assets/**`）一律返回同一份 `index.html`，`Cache-Control: no-cache`，不看登录态。未登录打开未知页面得到的是这页，前端路由再进登录页，不是 401 JSON。已登录也是同一份静态页，响应里没有令牌或业务数据；未知路径由前端显示「页面不存在」。`/assets/**` 仍是静态资源，缺失为 JSON `NOT_FOUND`。`GET /shells` 与 `GET /shells/**` 302 到 `/conches`（固定地址，不保留后面的名字）。前端另把 `/shells` 与 `/shells/:conch` 转到 `/conches`。其它方法的未知页面路径仍由安全链拒绝。

Crab 在回环连接上，`GET /console/**` 除 `/console/api/**` 与 `/console/assets/**` 外返回同一份 `index.html`（包括 `/console/audit` 和未登记的深链）。非回环仍是 403。`/console/api/**` 需要控制台头：没有头是 403，有头但路径不存在是 404 `NOT_FOUND` 信封。`GET /console/shells` 与 `GET /console/shells/**` 302 到 `/console/conches`（固定地址，不保留后面的名字）。页面加载后，未知路径显示「页面不存在」；前端把 `shells` 与 `shells/:conch` 转到 `/conches`。其它方法打到未登记的 `/console/**` 仍是 JSON `NOT_FOUND`。未匹配的网关路径（不是 `/api/{conch}/{action}` 或已登记别名）同样是 404 信封。`traceId` 同时出现在响应体和 `X-Hermit-Trace`。

Reef 的查询参数、路径变量或请求头无法转换成声明类型，或缺少必填项时，`GlobalExceptionHandler` 返回 400 `INVALID_INPUT`。`message` 为 `字段 字段名 无效`（多个字段按字典序用逗号连接，例如 `字段 page, size 无效`），不回显原始参数值，也不把该异常记成 500。例如 `GET /api/admin/audit?size=abc` 的 message 是 `字段 size 无效`。数字越界、空原因等由各接口自己返回 400，message 为 `输入无效`。Bean 校验失败（如超长口令）message 为 `请求校验未通过`。请求体读不成目标类型（坏 JSON、无法识别的枚举值）message 为 `请求体无法解析`，不回显正文。路径不接受该方法时 405，错误码仍是 `INVALID_INPUT`，message 为 `不支持该请求方法`。上传缺少 `file`、空文件或坏 ZIP 仍是 422，message 为 `输入无效`。YAML 解析失败仍是 422，message 为解析器原文；语义校验失败仍是 422，message 为 `输入无效`，明细在 `data`。

Reef 的 4xx 与 5xx 会在服务端日志写一条含同一 `traceId` 的 `request failed status=… traceId=…`。4xx 不写异常消息或请求体。5xx 另记未捕获异常。响应信封仍带 `traceId`。默认 message：401 `UNAUTHORIZED` 为 `未登录或登录已过期`，403 `FORBIDDEN` 为 `没有权限执行这个操作`，404 `NOT_FOUND` 为 `资源不存在`，409 `CONFLICT` 为 `状态冲突`，500 `INTERNAL` 为 `服务器内部错误`。业务接口传入自己的 message 时用那一句，不用这句默认文案。登录失败仍是 `authentication failed`，不走这句默认文案。控制台错误提示在 message 已经表达该错误码时只显示 message，因此这三句不会再变成「无权限：没有权限执行这个操作」「不存在：资源不存在」或「内部错误：服务器内部错误」。message 是另一句说明时仍加短标签。

## Reef 管理与分发

以下管理路由前缀 `/api/admin`：

| 方法 | 路径 | 输入/用途 |
| --- | --- | --- |
| POST | `/login` | `{username,password}`；失败锁定窗口 15 分钟，阈值 5 次 |
| GET | `/me` | `{username, role}`。`role` 为 `ADMIN` 或 `USER` |
| POST | `/me/password` | `{oldPassword, newPassword}`。旧口令不对是 400，message 为 `当前密码不正确`。`Cache-Control: no-store`。改口令不使已发出的 JWT 失效 |
| GET | `/users` | 仅 `ADMIN`。`[{username, role, disabled, createdAt, tokenCount}]`。`tokenCount` 是未吊销的库内令牌数；该用户若是当前配置令牌的归属人（id 最小的 `ADMIN`），且配置了令牌，且当前值未作废，再加 1。不返回口令或令牌明文 |
| POST | `/users` | 仅 `ADMIN`。`{username, password, role}`。用户名非空、最长 128，口令规则与登录相同。重复用户名 409，message 为 `用户名已存在`。不支持删除，只能禁用。`Cache-Control: no-store` |
| PATCH | `/users/{username}` | 仅 `ADMIN`。`{role?, disabled?}`，至少给一个。不能取消最后一个启用中的 `ADMIN`，409，message 为 `至少要保留一个启用中的管理员`。不能改自己的角色，也不能禁用自己，409，message 为 `不能修改自己的角色或禁用自己`。自己就是最后一个启用中的管理员时，先返回前一条。空对象 400，message 为 `输入无效`。不存在 404，message 为 `资源不存在`。从启用改为禁用时，该用户 `revoked_at IS NULL` 的库内令牌全部写上 `revoked_at`，每个令牌一条 `AGENT_TOKEN_REVOKED`（detail 与 `DELETE /tokens/{id}` 相同：id、name、prefix、owner）。若请求开始时该用户是配置令牌的归属人且配置了令牌，同时作废当前配置值，再写一条 `AGENT_TOKEN_REVOKED`，detail 为 `{"name":"HERMIT_REEF_AGENT_TOKEN","owner":"<用户名>","source":"CONFIG"}`。已经禁用再传 `disabled: true` 不重复吊销、不重复审计。`disabled: false` 只清除禁用位，不清除 `revoked_at`，也不删作废摘要；该用户可以再登录，旧令牌仍是 401，需要再签发。只改角色、不禁用时，不吊销库内令牌，也不作废配置令牌。响应仍是用户摘要，不含令牌明文 |
| POST | `/users/{username}/password` | 仅 `ADMIN`。`{password}`，重置别人的口令。重置自己是 400，message 为 `不能重置自己的密码，请用右上角「修改密码」`。`Cache-Control: no-store`。审计 `USER_PASSWORD_RESET`，detail 只有用户名 |
| GET | `/tokens` | 自己的令牌。`ADMIN` 加 `?all=true` 看全部；`USER` 即使带 `all=true` 也只看自己。项为 `{id, owner, name, prefix, createdAt, lastUsedAt, revokedAt, source}`。`source` 为 `DATABASE` 或 `CONFIG`。配置令牌：`id`、`prefix`、`createdAt`、`lastUsedAt` 为 null，`name` 为 `HERMIT_REEF_AGENT_TOKEN`；当前配置值已作废时 `revokedAt` 为作废时间，否则为 null。不能用 `DELETE /tokens/{id}` 作废，走下面的作废接口。已吊销的库内令牌和已作废的配置令牌仍列出。响应和审计都不含明文或摘要 |
| POST | `/tokens` | `{name}`，非空，最长 64。`USER` 和 `ADMIN` 都可以给自己签发。返回 `{token, secret, publicKey}`。`secret` 是 `hat_` 加 32 个随机字节的 base64url（无填充），只出现在这一次响应里。库里只存 SHA-256 的小写十六进制。`publicKey` 与 `GET /signing-key` 的 PEM 相同。`Cache-Control: no-store`。审计 `AGENT_TOKEN_CREATED`，detail 含 id、name、prefix、owner |
| POST | `/tokens/config/revoke` | 仅 `ADMIN`，无请求体。把当前 `HERMIT_REEF_AGENT_TOKEN` 的 SHA-256 写入 `hermit_voided_config_tokens`（Flyway V7）。成功 200，`data` 与列表中的配置令牌项相同，`revokedAt` 为作废时间。响应和审计不含明文，也不含摘要。第一次写入审计 `AGENT_TOKEN_REVOKED`，detail 为 `{"name":"HERMIT_REEF_AGENT_TOKEN","owner":"<当前归属用户名>","source":"CONFIG"}`。同一配置值再调用仍是 200，`revokedAt` 不变，不再写审计。没有配置令牌（空或空白）时 404 `NOT_FOUND`，message 为 `没有配置令牌`。`USER` 为 403 `FORBIDDEN`，message 为 `没有权限执行这个操作`。未登录 401 `UNAUTHORIZED`，message 为 `未登录或登录已过期`。换成新的非空配置值后旧摘要仍作废，新值可用；要停用新值需再调一次 |
| DELETE | `/tokens/{id}` | 归属人或 `ADMIN` 吊销库内令牌。重复吊销仍是 200，只在第一次写 `AGENT_TOKEN_REVOKED`。别人的令牌 403，message 为 `没有权限执行这个操作`。不存在 404，message 为 `资源不存在`。配置令牌没有 id，不能从这里吊销 |
| GET | `/crabs` | `[{owner, instanceId, tokenName, firstSeenAt, lastSeenAt, crabVersion}]`。`USER` 只看自己的。配置令牌记下的行 `tokenName` 为 null。同一 `instance_id` 只保留一行：后一次用有效令牌写出的用户留下，其他用户的同实例行删除。同一（令牌，实例）在进程内 60 秒最多写一次，这 60 秒内的重复访问不会改归属；换一把令牌会立刻改写。重启后重新计。`crabVersion` 在实例行上最长 32 个字符，上报原文更长时截断；失败报告仍保留最多 64 个字符 |
| GET | `/signing-key` | `data.publicKey` PEM；不提供私钥 |
| POST | `/conches` | multipart 字段 `file`，YAML 或仅含 conch.yaml 的 ZIP；上传及解包 YAML 限 1 MiB。同一规则包版本已存在时 409 `CONFLICT`，message 为 `该规则包版本已存在`。版本不高于该包已有的最高版本时 409 `CONFLICT`，message 为 `版本必须高于已有的最高版本` |
| GET | `/conches`、`/conches/{conch}` | 列表/详情。两项都带 `disabled`、`disabledAt`、`disabledBy`、`disabledReason`；未停用时后三个为 null。两项都带 `pullCount`（long，JSON 数字）：该规则包全部版本成功下载次数之和，含尚未发布的版本 |
| GET | `/conches/{conch}/versions` | 版本列表，每项含 `warningCount`、`warnings` 校验警告明细，以及 `pullCount`（该版本成功下载次数，long） |
| GET | `/conches/{conch}/versions/{version}/yaml` | YAML 原文 |
| GET | `/conches/{conch}/diff?from=...&to=...` | 版本差异。`from` 和 `to` 都不传时，按 semver 取该包最高的两个版本，高的为 `to`。只传其中一个仍是 400 `INVALID_INPUT`，message 为 `版本对比需要同时提供 from 和 to`。少于两个版本时 400 `INVALID_INPUT`，message 为 `至少需要两个版本才能对比`。名字不合法仍是 404 |
| POST | `/conches/{conch}/versions/{version}/publish` | 发布已上传版本。必须严格高于当前已发布版本；没有当前版本时可以发布。不高于时 409，`message` 为 `发布版本必须高于当前已发布版本`。已经发布过的版本仍是 409，`message` 为 `只能发布已上传、尚未发布的版本`。Conch 已停用时 409，`message` 为 `规则包已停用`（先于版本比较） |
| DELETE | `/conches/{conch}/versions/{version}` | 仅 `ADMIN`，无请求体。只删除 `status = UPLOADED` 的版本。成功 200，`data` 为 `{conch, version}`。已发布 409 `CONFLICT`，message 为 `只能丢弃已上传、尚未发布的版本`，不删除该版本的 YAML 和签名 ZIP。不存在、重复删除、版本号不是 semver，或名字不是单个路径段时 404 `NOT_FOUND`，message 为 `资源不存在`。`USER` 与未登录分别是 403、401。审计见下文 |
| POST | `/conches/{conch}/disable` | `{reason}`。停用已有 Conch。原因去掉首尾空白后 1–500 个字符，不能含控制字符。已经停用时 409，`message` 为 `规则包已经停用` |
| POST | `/conches/{conch}/enable` | `{reason}`，规则与停用相同。未停用时 409，`message` 为 `规则包尚未停用`。启用原因只写入审计 |
| GET | `/tide/summary` | conch/from/to/bucket |
| GET | `/tide/failures` | conch/version/action/from/to |
| GET | `/tide/compare` | conch/versions（逗号分隔版本） |
| GET | `/tide/rollbacks` | conch/from/to |
| GET | `/alerts` | status |
| POST | `/alerts/{id}/ack` | 确认告警 |
| GET | `/audit` | 管理操作审计。`conch` 可选；`page` 默认 1；`size` 默认 20，最大 200。小于 1 或 `size` 大于 200 为 400。非数字见上面的参数类型错误 |
| POST | `/retention/purge` | 立即清理过期失败报告、回滚事件和锁定窗口外的登录失败。`data` 为 `{failureReports, rollbackEvents, loginFailures}`。写入审计 `RETENTION_PURGE`，detail 为同样的三个计数。需要 `ADMIN` JWT。`USER` 为 403 |

Tide 时间参数使用 ISO-8601 Instant，默认查询跨度 7 天，最大 400 天。bucket 支持 hour/day，默认 day；告警 status 支持 open/all，默认 open。确认告警只记下确认人和时间，不结束去重窗口：同一 `(kind, conch, conch_version)` 自 `created_at` 起 `dedup-hours` 内不再新建。窗口过后条件仍成立才会再出现一条。失败次数上涨不会提前打破这个窗口。

失败分组、分组里的样本，以及版本切换事件带 `owner`（用户名）。旧数据没有归属，`owner` 为 null。同一组的 `owner` 相同；不同用户的失败会拆成两组。小时汇总 `series` 不加 `owner`。`hermit_failure_reports`、`hermit_hourly_counts`、`hermit_rollback_events` 各有可空的 `owner_user_id`，不进主键。小时计数更新时用 `COALESCE`，后来的空归属不会清掉已经记下的用户。

用户和令牌审计还有 `USER_CREATED`、`USER_UPDATED`、`USER_PASSWORD_RESET`、`PASSWORD_CHANGED`、`AGENT_TOKEN_CREATED`、`AGENT_TOKEN_REVOKED`。detail 是 JSON，不含口令、令牌明文或配置令牌摘要。禁用用户时，每个被吊销的库内令牌一条 `AGENT_TOKEN_REVOKED`；若同时作废配置令牌，再加一条，detail 含 `source` 为 `CONFIG`。`USER` 不能读 `GET /audit`。

配置了 `hermit.reef.tide.webhook-url` 时，新建告警会 POST JSON：`id`、`kind`、`conch`、`version`、`detail`、`createdAt`、`adminUrl`。协议须为 `http` 或 `https`，空字符串不推送；其他协议或没有协议时进程拒绝启动。`adminUrl` 固定为相对路径 `/alerts`（Reef 没有管理端对外基址配置）。发送失败不影响告警落库。

停用和重新启用都记审计，`action` 为 `DISABLE` 或 `ENABLE`。`version` 是当时的当前已发布版本，没有当前版本时为 null。`detail` 是 `{"reason":"..."}`。停用不改签名包：清单 ETag 仍是清单 JSON 数组的 SHA-256，条目减少所以 ETag 变化，Crab 不会把停用误当成 304。重新启用后，原来的当前版本回到清单，包的 SHA-256 与停用前相同。停用期间可以上传新版本，但不能发布，仍可以丢弃 `UPLOADED`。坏掉的发布用一个更高版本替换，不再提供管理员回滚。`POST /api/admin/conches/{conch}/rollback` 没有对应接口。页面的 GET 兜底匹配所有路径，所以这个 POST 是 405 `INVALID_INPUT`，message 为 `不支持该请求方法`；对同一路径发 GET 则是 404 `NOT_FOUND`。拉取次数不进入清单，下载成功不会改变 ETag。

`DELETE /api/admin/conches/{conch}/versions/{version}` 成功时写审计 `DISCARD`。`actor` 是操作者，`conch` 与 `version` 是被丢掉的那一版，`detail` 为 null。409、404 和 403 不写这条审计。删掉的是该版本目录里的 `conch.yaml`，以及这个版本还没发布时可能留下的 `package.zip`。已发布版本的 YAML、签名 ZIP 和行上的 `sha256` 保持不变，清单里的 SHA-256 也不变。这个包若已经没有任何版本，`GET /conches/{conch}/versions` 仍是 404，`GET /conches` 不再列出它；其它版本还在时，版本列表只是不再包含被丢掉的那一版。上传规则不变：新版本必须高于该包还在的最高版本。丢掉当前最高的未发布版本后，同一个版本号可以再上传；更高的版本还在时，较低的版本号不能再上传。停用期间丢弃已发布版本的 message 仍是 `只能丢弃已上传、尚未发布的版本`，不是 `规则包已停用`。

Agent 路由：

| 方法 | 路径 | 返回 |
| --- | --- | --- |
| GET | `/agent/version` | Crab 最新版本与下载地址提示。`latest-crab-version` 为空则版本字段为空，表示不提示升级；非空值在启动时必须是 semver |
| GET | `/agent/conches/manifest` | 当前可分发规则清单与 ETag，支持 If-None-Match/304。已停用的 Conch 不在清单里。每项含 `conch`、`version`、`sha256`、`minCrabVersion`、`title`，以及当前发布版本的 `description`（string 或 null）、`capabilityCount`（int，能力个数，与管理端 `capabilityCount` 一样按 actions 计数）和 `requiredCredentials`（字符串数组，当前发布版本里 `required: true` 的凭证名，按字典序）。ETag 覆盖这些字段，字段变化后旧 ETag 不再 304。清单请求和 304 不增加拉取次数。旧 Crab 忽略不认识的字段 |
| GET | `/agent/conches/{conch}/{version}` | 已发布 ZIP。Conch 已停用、版本未发布或不存在时 404，包文件仍留在 Reef。验签失败为 500。只有成功返回 ZIP 时，该版本 `pull_count` 加 1（单条 `UPDATE`，`pull_count = pull_count + 1`）。失败、404 和停用不计。次数是下载成功次数，不按 Crab 实例统计 |
| POST | `/agent/reports` | ReportBatch；instanceId 须与请求头一致 |

ReportBatch 包含 instanceId/crabVersion/sentAt/failures/counts/events，严格 DTO 在 [common/report](../hermit-common/src/main/java/dev/hermit/common/report)。失败按实例+trace 去重，小时统计按复合主键覆盖；版本变化事件仍走原来的 events 通道，按实例和 eventId 去重。Crab 手动更新成功时 reason 为 `MANUAL_UPDATE`。`RULE_MISMATCH` 只进入失败报告，不再产生版本变化事件。

## Crab 能力 API

```text
GET  /api/{conch}/{action}?param=value
POST /api/{conch}/{action}           JSON object body
```

使用 action 的 effectiveMethod，方法不匹配返回 405。账号用请求头 `X-Hermit-Account` 或查询参数 `account` 选择。两个都没有时使用默认账号；只有一个时用那一个；两个都有且相同则用该账号；两个都有且不相同，并且这次调用已经通过权限、能力停用和能力是否存在这些检查时，返回 400 `INVALID_INPUT`，message 为「请求头 X-Hermit-Account 与查询参数 account 不一致。」，不调用上游。没有权限仍是 403，能力已停用或不存在仍是原来的 404。空白的头或查询参数视为没传。选出的账号在组能力入参之前拿掉，所以 `?account=` 不会因为入参 `additionalProperties: false` 变成「输入无效」。GET 的能力入参已经有字段 `account` 时，查询参数改为 `_account`，`account` 仍传给能力；这时冲突 message 点名 `_account`。POST 的查询参数始终是 `account`，JSON 正文里的同名字段仍是能力入参。未知账号是 404 `ACCOUNT_NOT_FOUND`，message 为「没有这个账号。」。HTTP 与 MCP 共用 `Invoker`，不绕过权限和能力停用检查，账号仍按 `(origin, conch, account)` 隔离。动态 `/openapi.json` 在每个能力上声明这个查询参数，并保留请求头。API Key 按 conches/actions/readOnly 限制能力，**没有按账号限制的 scope**；能调用能力的 Key 可以选择该 Conch 下的账号。已经入库的空 `conches` 在 `ApiKeyPrincipal` 里仍表示不按包名限制，空 `actions` 仍表示不按能力名限制；本地包另由 `Invoker` 要求名字出现在 `conches` 里。新 Key 不能再靠留空得到这个范围。`conches` 里的每个名字必须是当前已加载的规则包，或仍在停用墓碑 `disabled_conches` 里的名字；未知名字创建时返回 `INVALID_INPUT`，并在 message 里列出这些名字。`actions` 里的每一项必须是当前已加载能力的 `conch.action`。裸能力名（例如 `list_todo`）或尚不存在的能力在创建时返回 `INVALID_INPUT`，不会先收下再让调用一律 `FORBIDDEN`。停用墓碑只放行规则包名，不放行该包上已经卸下的能力。CLI `apikey create --all` / `--conches` / `--actions` 使用同一规则。

POST 可带 `Idempotency-Key`。缓存键为 origin/conch/account/action/key，有效期 24 小时，另存请求体的 SHA-256（小写十六进制，schema 10 的 `idempotency.body_sha256`）。摘要是网关读到的原始字节，不是规范化 JSON：空格或字段顺序不同算另一个请求体。相同键且摘要相同，回放第一次的响应（含旧 traceId），响应头 `Idempotent-Replayed: true`，不再执行。摘要不同返回 409 `IDEMPOTENCY_CONFLICT`，message 为 `Idempotency-Key was already used with a different request body`，不执行、不覆盖已存响应。升级前写入、摘要为 null 的行也无法证明请求体相同，重放同样是这个冲突，直到 24 小时过期。键仍不包含 API Key 身份或规则版本。

| 错误码 | HTTP 状态 |
| --- | --- |
| INVALID_INPUT | 400（方法错误 405） |
| AUTH_FAILED | 网关 Key 无效 401；上游登录失败 422 |
| FORBIDDEN | 403 |
| NOT_FOUND / ACCOUNT_NOT_FOUND / CAPABILITY_DISABLED | 404 |
| NOT_CONFIGURED / CONCH_DISABLED / IDEMPOTENCY_CONFLICT | 409 |
| SESSION_EXPIRED / RULE_MISMATCH | 422 |
| UPSTREAM_ERROR | 502 |
| TIMEOUT | 504 |
| INTERNAL | 500 |

缺必填、类型无法转换，数值超出参数声明的 `minimum` / `maximum`，或请求里多了未声明字段，都返回 `INVALID_INPUT`。入参 JSON Schema 带 `additionalProperties: false`，运行时不会把负页码、过大的 `pageSize` 或多余字段改成合法值再返回 200。HTTP 与 MCP 共用 `Invoker` → `RuleEngine` → `InputPreparer`。MCP 在这次校验前去掉账号选择：直接工具去掉 `account`，能力入参已经占用 `account` 时去掉 `_account`；目录模式的 `call_capability` 把 `account` 放在能力参数外面。HTTP 同样在校验前拿掉账号选择：GET 去掉查询参数 `account`（入参已占用该名字时去掉 `_account`），POST 只把查询参数 `account` 当作账号，不写入 JSON 正文。MCP 的 `account` / `_account` 参数不变。账号选择因此不会被当成未声明字段。

本机关掉的能力（控制台开关，或 `config.yml` 的 `capabilities.disabled`）返回 404 `CAPABILITY_DISABLED`，`message` 为「该能力已在本机停用，调用前需要重新启用。」。能力不存在、规则包不在注册表且没有停用墓碑，仍是 404 `NOT_FOUND`，`message` 为 `not found`。OpenAPI 不列出已关掉的能力。MCP 在交给 `Invoker` 之前就从目录里去掉这些能力，直接调用隐藏的工具名仍是 `NOT_FOUND`，message 为「没有这个工具，或当前 API Key 看不到它。」。直接模式的 hint 只建议 `tools/list`，不提到 `search_capabilities`；目录模式的 hint 可以建议 `tools/list` 和 `search_capabilities`。若调用已经进入 `Invoker` 后才发现能力被关掉，MCP 的 `code` 与 HTTP 同为 `CAPABILITY_DISABLED`，hint 说明到控制台重新启用，或从 `capabilities.disabled` 里去掉。

`CONCH_DISABLED` 在墓碑还在、注册表已经没有这份包时，`message` 为「Reef 已停用该规则包，本机没有可调用的副本。」。Reef 的 Agent 清单没有停用标志：`ConchDistributionService` 的当前行只列出可分发的包，已停用、从未发布和已撤回的名字都不在清单里，Crab 也读不到 `hermit_conch_availability`。因此 Crab 只在下面这条契约下写 `disabled_conches`：本机注册表里已经有这份包、本机已订阅、来源是 OFFICIAL，并且这次同步的清单里没有这个名字。墓碑是「Reef 已停用」标记。已安装版本留在注册表里，`current` 保留，版本文件、账号和凭证都保留。HTTP 与 MCP 仍经 `Invoker` 调用，权限、能力开关、账号和凭证检查不变。墓碑存在期间，同步不下载、不重试、不重装该包，也不把它算作可重试失败。同步结果里它只出现一行 `kind=DISABLED`、`reason=DISABLED`，`version` 是本机保留的版本，名字不进入 `removed`。随后的 304 原始结果不再带这一行，控制台读取时仍按墓碑投影成一行 `DISABLED`，`removed` 里仍然没有它。`disabled_at` 只记第一次看到缺席的时间，后来的同步不刷新，也不再写第二条审计。

从未装进注册表的名字不写墓碑。清单再次列出该名字时清掉墓碑，然后按普通更新继续：版本与已安装版本相同则 `SKIPPED_SAME`、不下载；更高版本记 `UPDATE_AVAILABLE`，不下载，本机已安装的版本继续可调用；更低版本记 `SKIPPED_OLDER`，不下载、不降级。停用和撤回在清单上相同。已经装在本机的包都按停用处理：继续调用，停止更新。

只有墓碑还在、并且注册表里已经没有这份包时（例如旧版本 Crab 曾经清掉 `current`，或停用后本机文件已经丢失，重启后装不回来），HTTP 才返回 409 `CONCH_DISABLED`，`message` 为「Reef 已停用该规则包，本机没有可调用的副本。」。这条路径不下载、不重装。手动更新在墓碑还在时同样不下载：注册表里还有这份包时 `message` 为「Reef 已停用该规则包，不再下载。」；注册表里已经没有时与调用使用同一句。MCP 对这种未加载的墓碑工具返回 `CONCH_DISABLED`，message 与 HTTP 相同，hint 说明本机没有可调用的已安装版本，重新启用后同步即可装回；已加载包上的未知工具仍是 `NOT_FOUND`。已经拿着授权快照、正在执行的请求仍跑完那份快照，不会在中途换成另一个版本。控制台测试登录和 Conch 详情在注册表里已经没有这份规则时仍是 `NOT_FOUND`。

能力请求已经发出后，上游 HTTP 400 也返回 `INVALID_INPUT`，文案为「上游拒绝了输入（HTTP 400）。请修改参数后重试。」，不再继续提取或断言。它和调用前的参数校验共用错误码，但 `failedStepType` 为 `request`。MCP 对这段文案的 hint 是按该系统的要求修改参数。上游 HTTP 401/403，以及重定向到登录页，都视为会话失效：幂等能力重新登录并重放一次，其它能力返回 `SESSION_EXPIRED` 且不重放。这两类都不会把响应体抽成空列表。500 及以上在提取之前返回 `UPSTREAM_ERROR`，message 为「上游状态码 N」，不看后面的 `status` 断言。其它非 2xx：从这次 request 到下一个 request 之间，若 `assert.status` 的数字或数字列表包含实际状态码，则不拒绝，extract 和 assert 照常执行（所以 `status: 302` 或 `status: 304` 可以通过；期望 200 实际 404 仍是 `UPSTREAM_ERROR`）。含模板的 `status` 不预拒。没有覆盖该状态码的期望时，GET 的 302、304、404、409 是 `UPSTREAM_ERROR`，没有状态断言的 `list_todo` 遇到 404 不再是 HTTP 200 的空列表。非 GET 的 3xx 不是登录重定向时不拒绝，`follow_redirects: false` 的表单 POST 收到 302/303 可以成功。登录步骤不走这套映射，失败仍是 `AUTH_FAILED`；`cookie_exists` 未通过时说明登录未建立会话，并提示核对用户名和密码、重新测试登录，或检查登录页是否改版。`contains`、`not_contains`、`header_exists`、`not_redirected_to` 和 `status` 失败时同样给出下一步说明，不以「assert 未通过」开头，也不写入断言原文、凭证或 Cookie。`status` 会写出期望和实际的状态码；期望值不是数字时不回显原文。2xx 上期望与实际不一致（例如期望 201、实际 200）仍是 `RULE_MISMATCH`。被 status 断言接住的非 2xx 若仍对不上，同样是 `RULE_MISMATCH`。失配只进入失败报告，不会切换已安装版本。未写步骤 `timeout_ms` 时，空闲读超时等于剩余的能力 deadline，到点是 `TIMEOUT`（「执行超时」）。写了 `timeout_ms` 时使用该毫秒数，且不超过剩余 deadline；更短的空闲读到点仍是 `UPSTREAM_ERROR`，message 为「步骤超时」。

## Crab 本机管理 API

下列路由前缀 `/console/api`，全部需要回环连接和控制台头：

| 方法 | 路径 | 输入/用途 |
| --- | --- | --- |
| GET | `/conches`、`/conches/{conch}` | 已加载规则/字段声明/能力状态。列表项含 `subscribed`、`needsConfiguration`、`loginPaused`、`source`、`reefDisabled`、`reefDisabledAt`、`updateAvailable`、`latestVersion`、`newRequiredCredentials`；详情同样含这三项。`updateAvailable` 为 true 表示最近一次同步的清单版本高于已安装版本，且 `min_crab_version` 允许安装。`latestVersion` 是清单版本；本地包，以及清单里已经没有的停用包，为 null。`newRequiredCredentials` 是清单要求、但当前已安装文档未要求的凭证名，已排序。目录里没有该行时这三项为 false、null、空数组。`source` 为 `LOCAL` 或 `REEF`。`reefDisabled` 为 true 时本机仍保留已安装版本并可调用，`reefDisabledAt` 是本机第一次记下停用墓碑的 ISO-8601 时间；未停用时 `reefDisabled` 为 false，`reefDisabledAt` 为 null。本地包这两项为 false 和 null |
| GET | `/catalog` | 最近一次成功清单。`{conches, syncedAt, configured}`。未配置 Reef 时 `configured` 为 false，`conches` 为空。每项含 `conch`、`version`、`title`、`description`、`capabilityCount`、`subscribed`、`installed`、`installedVersion`、`disabled`、`localConflict`、`updateAvailable`、`latestVersion`、`newRequiredCredentials`。`disabled` 与停用墓碑一致。名字已经离开清单缓存、墓碑还在时仍补一行：`disabled` 为 true，`version` 为 null（Reef 没有在发布的版本），`installedVersion` 是本机保留的版本。`localConflict` 表示该名字已是本机本地包 |
| POST | `/conches/{conch}/subscription` | 订阅并立即同步。返回 `{conch, subscribed: true, outcome}`。`outcome` 与同步结果里的一行相同，清单未提到该包时为 JSON `null`。与本地包重名是 409，错误码仍是 `INVALID_INPUT`。第一次订阅安装 Reef 当前版本 |
| POST | `/conches/{conch}/update` | 按最近一次同步的清单安装更高版本，不重新拉取清单。返回 `{conch, outcome}`。成功时 `outcome.kind` 为 `INSTALLED`；注册表没有这份曾经安装的包、且最新已安装 zip 仍能验签时为 `REPAIRED`，`reason` 为 `RESTORED`，`version` 是重新载入的版本，不下载，`current` 不跳到更高的清单版本。zip 验签失败则下载清单版本，成功时仍是 `INSTALLED`。未安装、未订阅、不在目录里是 404；本地包、版本不更高、清单与包不一致、`min_crab_version` 过高是 409 `INVALID_INPUT`；Reef 已停用是 409 `CONCH_DISABLED`，不下载。本机仍加载时 `message` 为「Reef 已停用该规则包，不再下载。」；墓碑还在但本机文件已经丢失、注册表没有这份包时，`message` 为「Reef 已停用该规则包，本机没有可调用的副本。」。CLI `update` 打印同样的 message。默认账号缺少新版本新增的必填凭证时 409 `NOT_CONFIGURED`，`message` 为 `missing credentials for update: a, b`（名字已排序）。这时验过签的包已经写进版本目录，`current` 和注册表不变；补上凭证后再调用一次才会切换。没有默认账号时不拦。已经声明过、只是从可选变成必填的名字，可以在更新前写入 |
| DELETE | `/conches/{conch}/subscription` | 退订。返回 `{conch, subscribed: false}` |
| GET / POST | `/conches/{conch}/accounts` | 列表 / `{name}` 创建。账号行见下方字段 |
| DELETE | `/conches/{conch}/accounts/{account}` | 删除账号/凭证并使会话失效 |
| POST | `/conches/{conch}/accounts/{account}/default` | 设默认账号 |
| GET / PUT | `/conches/{conch}/accounts/{account}/credentials` | 仅字段名 / `{字段:字符串}` 写入；空字符串表示删除。空 body、`{}`，或写入后仍缺必填字段，返回 `INVALID_INPUT`。已保存字段可以只改其中几项。凭证无法解密时，必须把已存字段都重新写成非空值 |
| POST | `/conches/{conch}/accounts/{account}/test-login` | 测试登录。成功才解除自动登录暂停；失败、缺凭证或无法解密都不解除。不写调用记录 |
| GET / PUT | `/conches/{conch}/config` | 配置声明/值 / `{字段:字符串}`；空字符串清除覆盖 |
| POST | `/capabilities/{conch}/{action}/enable`、`/disable` | 数据库开关；不能覆盖 config.yml 的禁用 |
| GET / POST | `/apikeys` | 列表 / 创建。创建体 `{name, readOnly, all, conches, actions}`。`name` 必填。`readOnly` 与 `all` 缺省为 false，必须是布尔。`conches`、`actions` 缺省或 `[]` 都是空集合。只接受这五个字段。嵌套 `scope` 或其他未知字段是 400 `INVALID_INPUT`，message 为 `unknown field: …`。`all` 不是 true 且两个集合都空：400，message 为 `必须指定范围：设置 all 为 true 表示全部正式规则包（不含本地包），或填写 conches、actions`。`all: true` 再带非空 `conches` 或 `actions`：400，message 为 `all 为 true 时不能同时填写 conches 或 actions`。`all: true` 保存空集合，表示全部正式包的全部能力，不含本地包；`readOnly: true` 仍只放行只读能力。只写 `conches` 或只写 `actions` 时 `scope.all` 为 false。`actions` 必须是已加载的 `conch.action`；`conches` 必须是已加载（含本地包）或停用墓碑中的规则包名。裸名、不存在的能力、未知规则包拒绝创建。新 Key 仅返回一次 plaintext。响应与列表的 `scope` 都是 `{all, conches, actions, readOnly}`。`all` 为 true 表示两个集合都空（全部正式包）。早于该字段、两个集合都空的旧行仍可调用，读出来的 `all` 也是 true，不迁移 |
| DELETE | `/apikeys/{id}` | 撤销 |
| GET | `/calls`、`/calls/{traceId}` | `conch`、`action`、`channel`、`limit` 查询及步骤摘要。每行含 `source`（`LOCAL` 或 `REEF`，规则包来源）和 `channel`（`HTTP` 对外 OpenAPI、`MCP`、`CONSOLE`）。`channel` 省略或空白表示不筛选；其他值 400 `INVALID_INPUT`，message 为 `channel is invalid`，不回显参数值。当前只有 HTTP 与 MCP 会写入；控制台没有执行能力的路径，测试登录不写调用记录，所以没有 `CONSOLE` 行，但筛选 `CONSOLE` 仍是空列表而不是 400。schema 11 给旧行默认 `HTTP`。`limit` 省略时 100，最大 500；小于 1、不是整数或大于 500 为 400 `INVALID_INPUT`，message 为「每页条数超出范围。」。控制台调用记录页显式传 100，概览显式传 200。CLI 没有调用记录列表命令。`action` 可以是库存的能力名，或 `conch.action`（按第一个 `.` 拆开后分别匹配）。若同时传入 `conch` 且与 `action` 里的包名不同，返回 400 `INVALID_INPUT`，不会当成空列表 |
| GET | `/audit` | 配置审计，新的在前。参数 `conch`、`account`、`page`（从 1 起，默认 1）、`limit`（默认 50，最大 200）。字段见下 |
| GET / POST | `/debug` | 读状态 / `{enabled}`；当前只存状态，24h 过期 |
| GET / POST | `/sync` | 最近同步结果 / 触发同步 |
| GET | `/reef` | 当前生效的 Reef 连接。`{configured, source, url, owner, tokenName, tokenPrefix, publicKeyFingerprint, instanceId, importedAt}`。`source` 为 `BOOTSTRAP`、`CONFIG`，或未配置时的 JSON `null`。`tokenPrefix` 只在令牌长于 12 个字符时返回前 12 个字符，否则为 `null`。`publicKeyFingerprint` 是公钥 X.509 SubjectPublicKeyInfo 的 SHA-256，小写十六进制。响应没有令牌明文 |
| POST | `/reef/bootstrap` | 导入引导 JSON，正文不超过 64 KiB。校验通过后探测 `GET /agent/conches/manifest`，写入 `$HERMIT_HOME/reef-bootstrap.json`（POSIX 0600），并在本进程热更新 URL、令牌和公钥，然后触发一次同步。成功时响应与 `GET /reef` 相同。非法 JSON、未知字段或公钥不是 Ed25519 PEM 为 400 `INVALID_INPUT`。清单只有 2xx 或 304 才继续写入。401 与 403 为 400 `INVALID_INPUT`，`message` 为 `reef rejected the token`。其他 HTTP 状态（含 404、5xx，以及返回 HTML 的错误页）为 400 `INVALID_INPUT`，`message` 为 `reef url did not answer as Reef (HTTP <状态码>)`，不写引导文件，已有文件也不替换。连不上或超时为 502 `UPSTREAM_ERROR`，`message` 为 `cannot reach reef`。本机已有正式包的 `current` 指针且新公钥与当前公钥不同（含当前没有公钥）为 409 `CONFLICT`，`message` 为 `public key differs from installed conches`。同一把公钥可以改 URL 和令牌。控制台请求体仍有 1 MB 上限，超过该上限时 message 为 `request body too large` |
| GET / PUT | `/reports/settings` | `{enabled, intervalSeconds}`。没有 schema 13 的行时读 config.yml。两者都缺省时 `enabled` 为 true，`intervalSeconds` 为 1800。PUT 两个字段都必填，`intervalSeconds` 为不小于 60 的整数，未知字段 400 `INVALID_INPUT`。写入覆盖 config.yml，并立刻作用于本进程。已写入的间隔保持原值。关闭上报不清空未发送队列；再次开启后仍按原保留策略发送 |
| GET | `/reports/preview` | 待发送批次和丢弃数。上报关闭或尚未连接 Reef 时 409 `NOT_CONFIGURED`，`message` 为「上报未开启，或尚未连接 Reef。」 |
| POST | `/reports/send` | 手动发送，返回 attempted/accepted/status。上报关闭或尚未连接 Reef 时 409 `NOT_CONFIGURED`，`message` 与预览相同。批次为空时不发 HTTP |
| POST | `/local-conches` | 导入。`multipart/form-data` 字段 `file`，或 `text/yaml`、`application/yaml`、`application/x-yaml` 正文。成功 `{conch, version, replaced, warnings}`。校验失败 400 `INVALID_INPUT`，`data` 为 `{errors, warnings}`。与正式包或订阅重名是 409，错误码仍是 `INVALID_INPUT` |
| POST | `/local-conches/reload` | 按 `local-conches/` 目录增删改。`{results:[{file, conch, version, ok, issues}]}` |
| DELETE | `/local-conches/{conch}` | 删除本地包文件和注册表项，保留账号与凭证。非本地包 404 |
| GET | `/local-conches/{conch}/export` | `application/yaml`，`Content-Disposition: attachment; filename="{conch}-{version}.yaml"`，正文与磁盘文件相同 |

Conch 列表的 `needsConfiguration` 在缺必填配置、任一账号缺必填凭证、凭证无法解密，或任一账号自动登录已暂停时为 true。概览待配置按它计数。`loginPaused` 只表示暂停，详情接口同样返回这个布尔值。

账号列表每一行：

| 字段 | 含义 |
| --- | --- |
| `needsConfiguration` | 缺必填凭证，或已存密文无法用当前主密钥解密。自动登录暂停不计入 |
| `missingFields` | 尚未写入的必填凭证名 |
| `credentialsUnreadable` | 已存密文无法解密 |
| `credentialMessage` | 无法解密时为「凭证无法解密，需重新配置」，否则 JSON `null` |
| `loginPaused` | 连续登录失败达到 `engine.max_login_failures` 后为 true |
| `loginPauseReason` | 暂停时为原因，否则 `null` |
| `loginPauseRecovery` | 暂停时说明恢复方法：修正凭证后对该账号执行测试登录，成功才恢复。否则 `null` |

测试登录仍会尝试登录，不看暂停旗标。只有登录成功（`recordAuthSuccess`）才清掉暂停和失败计数。失败、缺配置、缺凭证或无法解密都保持暂停，下一次能力调用仍直接返回「该账号已暂停自动登录」。页面应读这些字段显示「已暂停」和恢复方法，不要只看 `needsConfiguration`。

`GET /console/api/audit` 的 `data` 为 `{entries, page, limit, total}`。`entries` 按 `at`、`id` 从新到旧。空白的 `conch` 或 `account` 表示不过滤。`page` 小于 1、不是整数，或大到无法分页，返回 400 `INVALID_INPUT`，`message` 为「页码超出范围。」。`limit` 小于 1、不是整数或不在 1 到 200，返回 `INVALID_INPUT`，`message` 为「每页条数超出范围。」。`conch` 含 `/`、`\`、控制字符或长于 256 时，`message` 为「规则包名称无效。」。`account` 不是 1 到 64 位的字母、数字、下划线或连字符时，`message` 为「账号名只能包含字母、数字、下划线和连字符，长度 1 到 64。」。

每一行只有谁、做了什么、何时：

| 字段 | 含义 |
| --- | --- |
| `id` | 行 id |
| `at` | 时间 |
| `actor` | `cli`、`console` 或 `sync` |
| `action` | 动作名，例如 `CREDENTIALS_WRITTEN`、`CONFIG_PUT`、`CAPABILITY_DISABLED`、`REEF_CONCH_DISABLED`、`REEF_CONCH_ENABLED`、`CONCH_REPAIR_REQUIRED` |
| `origin` | `OFFICIAL` 或 `LOCAL`。API Key、debug、`SYNC_REQUESTED`，以及凭证声明变更，为 JSON `null`。`REEF_CONCH_DISABLED` 与 `REEF_CONCH_ENABLED` 为 `OFFICIAL` |
| `conch` | 相关 Conch；没有则为 `null` |
| `account` | 账号类事件的账号。配置、能力开关、API Key、debug、同步和凭证声明变更为 `null` |
| `fields` | 名字列表，按字典序。凭证写入包含写入和清除的字段；配置是 key；凭证声明变更是新增必填和移除的字段；能力开关是能力名 |

响应不含 `detail` 原文、配置值、凭证明文、密文，也不含 API Key 明文、哈希、id 或 name。debug 的 `enabled` 同样不返回。历史行的 detail 里如果还有别的键，读取时丢弃。`config_audit` 没有定期清理。

新装 Crab 不订阅任何规则包。订阅只写在本机，Reef 不能代订。`POST /console/api/conches/{conch}/subscription` 要求名字合法且已在目录缓存里；缓存还没有时会先同步一次再判断。与本机 `Origin.LOCAL` 规则包同名时 HTTP 409，`code` 仍是 `INVALID_INPUT`，`message` 为「与本机本地规则包同名」。`CONFLICT` 只用于已安装正式包时拒绝更换另一把 Reef 公钥，不用于重名。导入本地包时，名字已在 `subscriptions` 里同样是 HTTP 409、`INVALID_INPUT`，`message` 为「与已订阅的规则包重名」；与已安装的正式包重名也是 409，`message` 为「与已安装的正式规则包重名」。不在目录里是 404 `NOT_FOUND`，`message` 为 `conch is not in the catalog`。非法名字是 400 `INVALID_INPUT`。未订阅是 404，`message` 为 `not subscribed`。Reef 未配置时目录接口 `configured` 为 false；订阅是 409 `NOT_CONFIGURED`。写上订阅行之后立即同步，并返回该包这一轮的 `outcome`。同步失败时订阅行保留，错误与 `POST /sync` 相同。重复订阅不再写第二条 `CONCH_SUBSCRIBED`，仍会清掉 ETag 再同步。

退订删除订阅行，从注册表移除，删除 `current`，并清掉已有墓碑。退订不新写墓碑，也不写 `REEF_CONCH_ENABLED`。账号、凭证、配置和版本目录保留。此后调用是 `NOT_FOUND`。已经进入引擎的请求仍使用当时的授权快照。

`CONCH_SUBSCRIBED` 与 `CONCH_UNSUBSCRIBED` 的审计 target 是 `OFFICIAL/{conch}`，因此读取时 origin 为 `OFFICIAL`、conch 为包名、account 为空。detail 只有包名，不是 JSON，所以 `fields` 为空。响应仍不返回 detail 原文。`REEF_CONCH_DISABLED` 在本机第一次看到已订阅正式包从清单消失时由 actor `sync` 写一条，target 同样是 `OFFICIAL/{conch}`，detail 是包名。`REEF_CONCH_ENABLED` 在清单再次列出该名字、墓碑被清掉时写一条，actor、target 和 detail 相同。后来的同步不重复这两条。退订只写 `CONCH_UNSUBSCRIBED`。`CONCH_REPAIR_REQUIRED` 在同步发现已订阅、曾经安装、但注册表没有这份包时由 actor `sync` 写一条，target 为 `OFFICIAL/{conch}`。detail 是 `{"reason":"..."}`。读取时丢弃未知键，所以 `fields` 为空；原因以同步结果的 `reason` 为准。同一原因在包重新载入或退订之前不重复写。

同步结果里每个 Conch 有 `conch`、`version`、`kind`、`reason`，整份结果另有 `error`（`null` 或 `{code,message}`）。`kind` 为 `INSTALLED`、`SKIPPED_SAME`、`SKIPPED_MIN_VERSION`、`UPDATE_AVAILABLE`、`SKIPPED_OLDER`、`SKIPPED_LOCAL`、`NOT_SUBSCRIBED`、`REJECTED`、`DISABLED`、`REPAIR_REQUIRED`、`REPAIRED` 之一。已订阅且已安装的正式包，清单版本更高时为 `UPDATE_AVAILABLE`，`version` 是本机已安装版本，不下载。清单版本更低时为 `SKIPPED_OLDER`，同样不下载、不降级。`min_crab_version` 高于本机 Crab 时为 `SKIPPED_MIN_VERSION`，不下载。清单里的名字已经是本机本地包时为 `SKIPPED_LOCAL`，不下载、不覆盖，也不写入 ETag 快照。`NOT_SUBSCRIBED` 表示清单里有这个包，但本机未订阅，因此没有下载。已订阅且 `installed_versions` 有记录、注册表没有这份正式包时，这一行为 `REPAIR_REQUIRED`，不下载。`version` 是上次安装且能解析的版本，不是清单上的更高版本：指针丢失、版本无法解析或路径不安全时取安装记录里最新且能解析的版本，否则取 `current` 上能解析的版本。`reason` 为 `POINTER_MISSING`、`VERSION_INVALID`、`PACKAGE_MISSING`、`NOT_LOADED`、`ZIP_TOO_LARGE`、`PATH_UNSAFE`、`MANIFEST_ENTRY_MISMATCH`，或 `PackageReason` 的名字。`REPAIR_REQUIRED` 可重试，发现它的那次同步不记 ETag，`notModified` 为 false。`GET /console/api/sync` 经 `visibleStatus` 读取：记下的 304 即使没有 Conch 行，仍会补上 `REPAIR_REQUIRED`，这时响应里的 `notModified` 可以仍为 true，不能单凭它显示「已是最新」。包已重新载入时去掉过期的修复行。停用墓碑不另补修复行。`REPAIRED` 只出现在手动更新重新载入本机 zip 时，定时同步不会产生它，也不可重试。Reef 把已订阅且已安装的正式包从清单去掉时，这一行的 `kind` 为 `DISABLED`，`reason` 为 `DISABLED`，`version` 是本机保留的版本，名字不出现在 `removed`。`DISABLED` 不可重试，并把它的本机版本写入 ETag 快照，因此 `current` 仍匹配时后续同步可以 304，且不会下载。随后的 304 原始结果不再带这行，控制台读取时仍按墓碑投影成一行 `DISABLED`，`removed` 里仍然没有它。Reef 不可达时 POST 返回 502 `UPSTREAM_ERROR`，`message` 写明原因和下一步；GET 记下这次失败，不再返回上次成功。Reef 已经返回 HTTP 状态时，手动同步仍是 500 `INTERNAL`，状态保留上次成功。


清单再次列出该名字时，Crab 清掉停用墓碑并写一条 `REEF_CONCH_ENABLED`，然后按普通更新处理。版本与已安装版本相同则 `SKIPPED_SAME`，不重新下载。更高版本记 `UPDATE_AVAILABLE`，不自动安装。更低版本记 `SKIPPED_OLDER`。账号、凭证和配置在停用期间不删除。本地包不写墓碑，同步也不覆盖、不删除它们。

手动更新使用最近一次成功同步留下的目录缓存，不在更新请求里再拉清单。注册表里仍有这份包、且清单版本更高时，它先下载并验签，再用验过的包计算新增必填凭证。注册表没有这份曾经安装的包时，先用本机记录里最新一份 zip 验签：通过则重新载入并写回 `current`，返回的 `outcome.kind` 为 `REPAIRED`。这次不写 `CONCH_UPDATED`，版本没有变化，也不入队 `MANUAL_UPDATE`。不通过才下载清单版本；下载成功写 `CONCH_UPDATED`，只有记下的版本和清单版本不同才入队 `MANUAL_UPDATE`。同版本因缺少新增必填凭证而停住时不覆盖已记录的 zip，避免稍后的重新载入绕过凭证检查。目录里的 `requiredCredentials` 若存在且与验过的包不一致，拒绝安装，reason 为 `MANIFEST_ENTRY_MISMATCH`。旧缓存没有这个字段时不当成不一致。默认账号是 `AccountStore.resolve(OFFICIAL, conch, empty)`。没有默认账号就不检查凭证。某个名字在保险库里有字段（密文打不开也算有）即视为已填。缺的名字会让这次更新停在版本目录里，不改 `current`。凭证写入接受已安装文档声明的字段，也接受一份已暂存、且重新验签通过的更高版本所声明的字段；两边都声明时用已安装的那份做形状检查。控制台「写完后必填都在」仍只看已安装文档。第二次更新再次验签，默认账号已经具备每个新增必填名时才切换。成功切换写一条 `CONCH_UPDATED`，actor 为 `console` 或 `cli`，target 为 `OFFICIAL/{conch}`，detail 为 `{"from","to"}`。上报开启时另入队一条 reason 为 `MANUAL_UPDATE` 的版本变化。`pull_count` 在这次下载成功时加 1，包括随后因缺凭证而没有切换的那次。

`RULE_MISMATCH` 不再切换版本，也不再写 `pinned_rollbacks`。schema 12 删掉这张表。成功的正式调用仍写入 `version_success`，版本保留用来保护最近一次成功的版本。`mismatch_streak` 表还在，不再写入。`rollback_queue` 只在手动更新把版本换成另一个版本、且上报开启时写入。重新载入同一份已验签的 zip 不写这条。

## CLI

命令前缀为 `java -cp "$CP" dev.hermit.crab.CrabMain`，CP 准备见[快速开始](getting-started.md)。

```text
--help
serve
sync
reef import <file>
catalog
subscribe <conch>
unsubscribe <conch>
update <conch>
account list <conch>
account add|remove|default <conch> <name>
credential set <conch> <account> <field> [--from-env VAR]
credential list <conch> <account>
config get <conch> [key]
config set <conch> <key> <value>
config unset <conch> <key>
audit list [--conch NAME] [--account NAME] [--limit N] [--page N]
apikey create <name> [--all] [--conches a,b] [--actions conch.action] [--read-only]
apikey list
apikey revoke <id>
local import <file>
local reload
local delete <conch>
local export <conch> [--out file]
mcp-stdio [--help] [--url http://127.0.0.1:7777/mcp] [--account NAME]
```

普通管理 CLI 直接打开 H2；serve 已运行时使用控制台 API，不能依靠另一个 CLI 共享库。凭证通过隐藏输入、stdin 或命名环境变量输入，不接受明文命令行值。不带参数，或参数只有 `--help` / `-h`，把用法打到 stdout，退出 0。未知命令，以及子命令参数不够，仍把同一段用法打到 stderr，退出 1。这段用法以「用法：」开头，后面仍是英文命令和选项名。普通错误退出 1，数据库被占用退出 2；stdio 的连接失败也有独立退出约定。

`catalog` 打印目录缓存，一行一个包：`包名 版本`，后面按情况接 `subscribed`、`installed 版本`、`disabled`、`local-conflict`、`update-available 清单版本`，最后是标题。缓存还没有时没有输出，退出 0。`subscribe` 成功时打印 `包名 版本 KIND`；这一轮结果里没有该包时打印 `包名 subscribed`。`unsubscribe` 成功时打印包名。`update` 成功时打印 `包名 版本`。它用的是最近一次同步的清单，不会在这条命令里再拉 Reef。未配置 `reef.url` 时 `catalog`、`subscribe` 和 `update` 与 `sync` 一样在 stderr 打印 `reef.url is not configured` 并退出 1。`unsubscribe` 在未配置 Reef 时仍可用。超过 200 字或含换行的错误被收成「输入无效。」。

`audit list` 与 `GET /console/api/audit` 是同一份投影，新的在前。`--limit` 默认 50、最大 200，`--page` 从 1 开始。`--page` 小于 1、不是整数，或大到无法分页时，stderr 为「页码超出范围。」。`--limit` 小于 1、不是整数或大于 200 时，stderr 为「每页条数超出范围。」。选项没有值时为「缺少 --page 的值。」这种句子，同一选项写两次为「--page 重复了。」。非法 `conch` 过滤为「规则包名称无效。」。非法账号名为「账号名只能包含字母、数字、下划线和连字符，长度 1 到 64。」。`audit` 后面不是 `list`，或带了不认识的参数时，stderr 是同一段以「用法：」开头的用法。每一行是 `时间 actor action origin conch account 字段名`，空值打印 `-`，多个字段名用逗号连接。不打印配置值、凭证明文或密文。

`config get` 只读取已经加载的规则包。注册表里没有这个名字时，stderr 打印 `本机没有这个规则包`，名字符合 `^[a-z][a-z0-9_]{1,63}$` 时再接 `: 名字`，退出 1，不会把空配置当成成功。已加载但还没有保存过配置时没有输出，退出 0。指定的 key 尚未设置时仍是 stderr `config key is not set`，退出 1。

`apikey create` 不认识的选项（包括 `--scope`）在打开数据库前退出 1，stderr 为「不认识的选项 --scope。」，不会建出全范围 Key。缺少选项值时 stderr 为「缺少 --conches 的值。」这种句子。必须带 `--all`，或非空的 `--conches` / `--actions`。`--all` 是全部正式包的全部能力，不含本地包，可与 `--read-only` 同用；再带 `--conches` 或 `--actions` 则退出 1，stderr 为 `all 为 true 时不能同时填写 conches 或 actions`。什么都不带时退出 1，stderr 为 `必须指定范围：设置 all 为 true 表示全部正式规则包（不含本地包），或填写 conches、actions`，不写库。`--from-env` 只属于 `credential set`。用在 `apikey create`（即使同时有 `--all`）、`apikey list` 或 `apikey revoke` 上会打印用法并退出 1，不创建 Key，也不读取该变量的值。`apikey list` 在 id、前缀和名字之后打印 `all=true|false`。`all=true` 表示两个集合都空：`--all` 新建的 Key，以及早于该字段、库存里没有 `all` 的空范围旧行。这一行不打印 `conches=` 和 `actions=`，不用 `-` 表示全部。`all=false` 时再打印 `conches=`、`actions=`，多个名字按字典序用逗号连接，空的那一维打印 `-`。然后始终打印 `read-only=true|false`。已吊销的行末再加 `revoked`。明文只在 `create` 时打印一次，列表只有前 8 个字符的前缀。CLI 没有调用记录列表；条数默认和上限只在 `GET /console/api/calls`。

## MCP

`/mcp` 为 Streamable HTTP（MCP 2025-11-25）：POST JSON-RPC、GET SSE、DELETE 会话，使用 `Mcp-Session-Id`；每次仍校验 API Key，并将会话绑定 Key 身份。空闲 1 小时过期，最多 1000 会话。实现使用 SDK 的 Schema 类型和自有 Javalin 传输层，不直接使用 SDK Servlet transport。

`Origin` 与 `MCP-Protocol-Version` 按这一版传输规范检查：

- 没有 `Origin` 的请求允许通过。CLI、桌面客户端和 `mcp-stdio` 都不带这个头。是否拒绝缺头只由 `McpTransportPolicy.ALLOW_MISSING_ORIGIN` 决定，当前为允许。
- `Origin` 有值时必须命中下面之一，否则 HTTP 403，body 为 JSON-RPC 错误（`id` 为 null），并且不创建会话：`http://127.0.0.1:<实际监听端口>`、`http://localhost:<实际监听端口>`、`http://[::1]:<实际监听端口>`、`http://<server.host>:<实际监听端口>`（`server.host` 为 `0.0.0.0` 或 `::` 时没有这一条），以及 `mcp.allowed_origins` 里的源站。比较忽略 scheme 和主机大小写，`http` 的 80 与 `https` 的 443 可省略。IPv6 回环按 `[::1]` 比较。Crab 自身只提供 HTTP；反向代理上的 `https://` 源站写入允许列表。多个 `Origin` 头拒绝。
- 非 `initialize` 的 POST，以及 GET、DELETE：带了 `MCP-Protocol-Version` 且不是 `2024-11-05`、`2025-03-26`、`2025-06-18`、`2025-11-25` 时返回 HTTP 400。不带该头时不拒绝，继续使用该会话在 `initialize` 里协商到的版本。多个版本头同样拒绝。已支持但和会话不一致的版本头放行，不改写会话版本。
- `initialize` 不看 `MCP-Protocol-Version` 头。请求体里的 `protocolVersion` 不在支持列表时，响应仍返回一个支持的版本（当前 `2025-11-25`）。是否改为直接拒绝只由 `McpTransportPolicy.NEGOTIATE_UNKNOWN_PROTOCOL` 决定，当前为协商。

- 可见能力不超过 40 个时，直接工具名 `conch__action`。本地包的直接工具说明、资源说明、`list_systems` 里该包的说明，以及 `describe_capability` 的说明，都以 `[本地] ` 开头。使用指南里也会标明这是本地包。`all: true` 以及旧的空 `conches` Key 仍能看见这些工具；调用时 `Invoker` 返回 403 `FORBIDDEN`。显式写出包名的 Key 可以调用。
- 超过 40 个时，提供 `list_systems`、`search_capabilities`、`describe_capability`、`call_capability` 四个目录工具。
- 目录模式调用非只读能力需要支持 elicitation 且保持 SSE，明确确认后执行；直接模式没有同样的服务端确认步骤，客户端应依据工具注解交互。
- guide resource 为 `hermit://conches/{conch}/guide`，由说明、参数、示例生成。
- 规则更新/能力开关变化推送列表变更通知。
- 直接调用的账号参数为 `account`；若 action 自带 account 输入，则使用 `_account`，也支持 X-Hermit-Account。这个字段在校验前拿掉。直接工具的 `inputSchema` 与能力入参一样设置 `additionalProperties: false`，并声明账号选择，所以账号不是多余字段。HTTP 的查询参数不改变这些工具参数。未知账号的 message 与 HTTP 同为「没有这个账号。」。
- `tools/call` 不传 `Idempotency-Key`。同键回放只在 HTTP POST。

协议级失败是 JSON-RPC error，不使用能力 API 的 success/error envelope。`error.code` 是 JSON-RPC 整数；`error.data.code` 是稳定字符串，`error.data.hint` 是下一步。HTTP 401 的 body 同样是这个 JSON-RPC 错误。

| 场景 | HTTP | JSON-RPC `error.code` | `error.data.code` |
| --- | ---: | ---: | --- |
| 未带 API Key | 401 | -32001 | `AUTH_MISSING`。说明使用 `Authorization: Bearer <API Key>` 或 `X-Hermit-Key` |
| Key 无效或已吊销 | 401 | -32001 | `AUTH_INVALID`。到 Crab 本机控制台 `/console/apikeys`，或 CLI `apikey create` 重新签发。Key 由 Crab 签发，不是 Reef |
| Key 与会话不匹配 | 401 | -32001 | `SESSION_MISMATCH`。换创建该会话的 Key，或重新 `initialize` |
| 尚未 `initialize` | 400 | -32600 | `SESSION_REQUIRED`。先调用 `initialize`，再带响应头 `Mcp-Session-Id` |
| 会话不存在或过期 | 404 | -32600 | `SESSION_NOT_FOUND`。重新 `initialize` |
| JSON-RPC 参数类型不合法 | 200 | -32602 | `INVALID_PARAMS` |
| `Origin` 不在允许范围 | 403 | -32003 | `ORIGIN_REJECTED`。不创建会话。没有 `Origin` 仍允许 |
| 非 `initialize` 的 `MCP-Protocol-Version` 不受支持 | 400 | -32600 | `UNSUPPORTED_PROTOCOL`。去掉该头，或改成上面四个版本之一 |

工具执行失败仍是 JSON-RPC 成功响应里的 tool result：`isError: true`，`structuredContent` 为 `{error:{code,message,hint,traceId,field?}}`，`content[0].text` 是同一 error 对象的 JSON。`code` 与能力 API 的错误码一致，例如 `INVALID_INPUT`、`NOT_FOUND`、`CAPABILITY_DISABLED`、`FORBIDDEN`，和上面的 `AUTH_MISSING` / `AUTH_INVALID` 不同。`field` 只在能定位到一个参数时出现。`traceId` 与这次响应的 `X-Hermit-Trace` 相同；若这次调用写了调用记录，记录里也是这个编号。协议错误的 `error.data` 同样带 `traceId`，并与该头相同。成功的 JSON-RPC 响应也写 `X-Hermit-Trace`，不把 `traceId` 放进业务结果。

成功调用在 `content[0].text` 之外带 `structuredContent` 对象。协商到 `2025-06-18` 或 `2025-11-25` 时，`tools/list` 为直接工具以及 `list_systems`、`search_capabilities`、`describe_capability` 声明 `outputSchema`。`2024-11-05` 与 `2025-03-26` 不声明该字段。`call_capability` 的返回随被调能力变化，不声明单一 `outputSchema`。直接工具的 `outputSchema` 只列 Conch `output` 的字段名和说明，不带类型约束，因为模板渲染不会把提取值转换成声明类型；目录工具的 `outputSchema` 带类型。`outputSchema` 描述成功结果；失败结果看 `isError` 和 `structuredContent.error`。

stdio 是对运行中 Crab 的桥接，不单独启动执行器。`mcp-stdio --help` 把默认 URL、环境变量 `HERMIT_API_KEY` 和 `--account` 打到 stdout，退出 0，不要求已经设置 Key，也不连接 Crab。通过 `HERMIT_API_KEY` 提供 Key，stdout 仅协议消息，诊断走 stderr。未设置 Key、URL 不是 http/https、Crab 没有在监听时，stderr 分别是中文的「未设置环境变量 HERMIT_API_KEY…」「URL 不合法…」「Crab 没有运行…」。缺少 `--url` 或 `--account` 的值时是「缺少 --url 的值。」，不认识的选项是「不认识的选项 --scope。」，多出来的位置参数是「多余的参数。」，不回显参数内容。未设置 Key 与非法 URL 退出 1，连不上退出 2。HTTP 401 时先把 Crab 返回的 JSON-RPC 错误写到 stdout，再在 stderr 打印 `authentication failed`，退出码 1。鉴权发生在读 body 之前，响应里的 `id` 为 null；stdio 若能解析出这次请求的 id，就填回去，已有非空 id 时不改。响应没有可解析的 `error` 对象时，stdout 仍写一条 `jsonrpc: 2.0`、`error.code` 为 `-32001`、message 为 `authentication failed` 的对象，`id` 取自本次请求；然后退出：

```bash
export HERMIT_API_KEY='调用端安全保存的APIKey'
java -cp "$CP" dev.hermit.crab.CrabMain mcp-stdio --url http://127.0.0.1:7777/mcp
```
