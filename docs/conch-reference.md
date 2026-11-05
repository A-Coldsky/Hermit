# 规则包（Conch）参考

规则包（Conch）以 [ConchParser](../hermit-common/src/main/java/dev/hermit/common/parse/ConchParser.java)、[ConchValidator](../hermit-common/src/main/java/dev/hermit/common/validate/ConchValidator.java) 和 Crab 引擎为准。这里覆盖当前实现，不是未来功能设计。完整可发布例子：[legacy_oa.yaml](../hermit-common/src/main/resources/samples/legacy_oa.yaml)。

## 最小规则

下面的规则无需老系统认证，返回一个常量，可用于检查发布、同步和网关链路。Crab 仍要求创建一个账号作为执行上下文。

```yaml
conch: demo_system
version: 1.0.0
driver: http
min_crab_version: 1.0.0
base_url: http://127.0.0.1:9000
title: 演示系统
description: 验证规则发布和调用链路。
credentials: {}
actions:
  show_status:
    title: 查看状态
    description: 返回固定状态，不调用上游服务。
    method: GET
    mcp: {expose: true, read_only: true}
    idempotent: true
    steps:
      - set: {message: ready}
    output:
      message: {type: string, description: 状态文本}
    examples:
      - input: {}
        output: {message: ready}
    notes: 仅用于本地验证。
```

## 字段与语义

| 字段 | 当前规则 |
| --- | --- |
| `conch`、action 名 | `^[a-z][a-z0-9_]{1,63}$` |
| `version`、`min_crab_version` | SemVer；当前引擎兼容版本为 1.0.0 |
| `driver` | 只能 `http` |
| `title`、`description` | Conch/action 均需要；输入参数和输出说明也有描述要求 |
| `credentials` | 必须显式声明，无凭证时写 `{}`；遗漏会报错 |
| `config` | 可选字段映射；字段支持默认值；不用于保存秘密 |
| `auth.steps` | 登录步骤；可省略；没有登录步骤也会创建账号会话 |
| `auth.alive_check` | 请求及 `expect_not_redirect`，空闲超时后检测会话 |
| `actions` | 能力映射，每个包含输入、步骤、输出和示例等 |
| `health_check` | 目前仅解析/保存，没有自动执行器 |
| `owner`、`tags`、`changelog` | 管理元数据 |

兼容别名：`connector` → `conch`、`min_agent_version` → `min_crab_version`。新旧键同时出现且值不一致会拒绝。解析器对各已定义层的未知键较严格，步骤内部的完整语义则部分留到运行时校验，不能假定“可上传”就等于能正确执行。

`credentials`/`config` 字段接受 `type`、`label`、`required`、`description`、`options`；config 额外支持 `default`。类型有 text/secret/select/url。校验会拒绝 `config` 的 `type: secret`，秘密必须放在 `credentials`。配置值优先级为用户值 → 声明 default → 顶层 base_url（仅 base_url 字段）；引擎也可直接回退顶层 base_url。

`description` 会原样显示在 Crab 控制台的账号凭证对话框里，是写给填表的人看的。只写要填什么，例如示例 `legacy_oa` 的密码写「填原始密码」；md5、加密、明文提交这类处理细节由步骤模板的过滤器负责，不写进说明。

凭证、配置、账号和能力开关会在 Crab 的 `config_audit` 追加一条记录。记录里是字段名和动作，不是凭证明文、密文或配置值。本机 `GET /console/api/audit` 和 CLI `audit list` 按时间倒序读取，结果同样只有操作者、动作、origin、Conch、账号、时间和字段名。detail 里其它键不会被读出来。

Action 还支持 `method`、`input`、`output`、`steps`、`idempotent`、`examples`、`notes`、`related`、`mcp`。缺 examples 是校验错误；notes/related 等说明质量问题可能产生警告。不写 related 时警告是「缺少 related」；related 指向包内没有的能力时警告是「related 指向不存在的能力 …」，发布仍然可以通过。示例 `legacy_oa` 只有 `list_todo`，所以不写 related。

输入参数可用 `type`、`description`、`default`、`enum`、`minimum`、`maximum`、`items`、`required`；由 `InputPreparer` 转换并按生成的 JSON Schema 校验。生成的 schema 含 `additionalProperties: false`。GET 查询参数以字符串进入，数值/布尔转换由输入层处理。缺必填、类型无法转换、小于 `minimum` 或大于 `maximum`（例如负页码、过大的 `pageSize`），以及未声明字段，都会抛出输入错误，最终是 `INVALID_INPUT`，不会把越界值夹进范围内、也不会丢掉多余字段再当成功。HTTP 网关和 MCP 都经过 `Invoker` 与 `RuleEngine`，所以这条校验两边相同。MCP 在校验前去掉账号选择（直接工具的 `account`，或能力入参已占用该名字时的 `_account`）。MCP 工具失败时 `isError` 为 true，`structuredContent.error` 含 `code`、`message`、`hint`、`traceId`，能定位到单个字段时还有 `field`。`traceId` 与响应头 `X-Hermit-Trace` 相同。未声明 `minimum` / `maximum` 的整数没有额外的隐式上下界。页码、每页条数这类参数应写明范围，否则负值会原样传给上游，由老系统自行处理；示例 `legacy_oa` 的 `page` 声明了 `minimum: 1`。

## HTTP 方法、只读和幂等是不同概念

外部方法：显式 method 优先；否则 `mcp.read_only` 或 `idempotent` 为 true 时选 GET，其余 POST。方法枚举只支持 GET/POST，这与步骤内可配置的上游 HTTP method 不同。

- 权限中的只读仅由 `mcp.read_only` 决定，不能因为 GET 或 idempotent 就推断只读。
- `idempotent` 决定能力步骤执行中检测到会话失效后，是否允许重新登录并重放一次，默认 false。会话失效包括重定向到 `alive_check.expect_not_redirect` 前缀，以及能力请求返回 HTTP 401/403。执行前的首次登录或 alive_check 失效后登录不受此开关限制。
- POST 的 `Idempotency-Key` 是网关独立缓存机制，不等于上述声明。同键换请求体返回 `IDEMPOTENCY_CONFLICT`，见 [api.md](api.md)。
- `mcp.expose` 默认 true，read_only/destructive 默认 false。当前 MCP destructiveHint 从“非只读且非幂等”计算，声明的 destructive 字段没有独立传入注解，见功能状态。

## 步骤

每个步骤是一个单键映射，支持五类：

| 类型 | 示例/能力 |
| --- | --- |
| request | method、path/url、query、headers、form/json、encoding、follow_redirects、timeout_ms |
| extract | CSS、XPath、JSONPath、regex；可 each 遍历及 filter 转换 |
| assert | status、contains、not_contains、cookie_exists、header_exists、not_redirected_to |
| set | 设置后续步骤/输出使用的变量 |
| script | Rhino JavaScript，对 vars 运算并 return 结果；可指定 into |

request 的 path 与 url 必须二选一，form 与 json 互斥。默认 GET、UTF-8、不跟随重定向；响应最多 5 MiB。未写 `timeout_ms` 时，空闲读超时等于剩余的能力 deadline（`engine.capability_timeout_seconds`，默认 60 秒），不再固定 15 秒，到点是 `TIMEOUT`。写了 `timeout_ms` 时使用该毫秒数，且不超过剩余 deadline；更短的空闲读到点是 `UPSTREAM_ERROR`，message 为「步骤超时」。解码优先 Content-Type charset，其次 HTML meta，再使用步骤 encoding。开启重定向最多 5 跳，只允许同源；Cookie 由账号客户端管理，规则不能自行覆盖 Cookie 头。最终响应仍是 3xx 时：非 GET 且不是登录重定向（表单常见 `follow_redirects: false` 的 302/303）不拒绝；GET 只在后面没有覆盖该状态码的 `assert.status` 时才是 `UPSTREAM_ERROR`。

能力请求返回 HTTP 400 时，引擎以 `INVALID_INPUT` 结束，不把响应交给后续 extract/assert，也不切换已安装版本。HTTP 401 与 403 视为会话失效，处理方式与重定向到登录页相同，因此读操作不会在会话失效时返回成功的空列表。500 及以上同样在提取之前结束，错误码是 `UPSTREAM_ERROR`，message 为「上游状态码 N」，不看后面的 `status` 断言。其它非 2xx 按下面这条决定是否在提取前结束：从这次 `request` 到下一个 `request` 之间，若有 `assert` 的 `status` 期望包含实际状态码，就不拒绝，extract 和 assert 照常执行。期望写成数字或数字列表（例如 `302`、`304`、`[200, 201]`）。写了模板等非字面量时不预拒，由 assert 决定。期望是别的数字（例如期望 200、实际 404）仍是 `UPSTREAM_ERROR`，不会把错误页抽成空列表。没有这种 `status` 期望时，GET 的 302、304、404、409 同样是 `UPSTREAM_ERROR`。非 GET 的 3xx（301、302、303、304、307、308）不是登录重定向时不拒绝，所以 `follow_redirects: false` 的表单 POST 收到 303 可以成功；若跳到登录页，仍是会话失效。登录步骤不走上述映射，这些状态码仍交给 assert，失败是 `AUTH_FAILED`。`cookie_exists` 失败时，登录结果说明「登录未建立会话：上游没有发回会话 Cookie」，并提示核对用户名和密码；凭证正确时重新测试登录，或检查登录页是否已改版。`contains`、`not_contains`、`header_exists`、`not_redirected_to` 和 `status` 失败时同样是下一步说明，不以「assert 未通过」开头，文案不包含断言原文、凭证或 Cookie。`status` 写出期望和实际状态码。2xx 上期望与实际不一致（例如期望 201、实际 200）仍是 `RULE_MISMATCH`。失配不会把 Crab 切回旧版本。响应体不会写入错误文案。

```yaml
steps:
  - request:
      method: GET
      path: /api/todos
      query: {page: '{{input.page}}'}
  - extract:
      items: {jsonpath: '$.items'}
  - assert: {status: 200}
```

CSS/各提取器的详细参数看 [Extractors](../hermit-crab/src/main/java/dev/hermit/crab/engine/Extractors.java) 与 `LegacyOaEngineTest`；request 失败会形成步骤索引、类型和错误摘要。持续慢速输出会在能力 deadline 被取消并返回 `TIMEOUT`。TCP 连接一直挂起没有单独回归。

## 模板、输出与脚本

模板如 `{{input.page}}`、`{{credential.password | md5}}`、`{{config.base_url}}`、`{{items}}`。支持的内置过滤器为 md5、sha256、base64、urlencode、trim、default、date、rsa；具体参数见 [BuiltinFilters](../hermit-common/src/main/java/dev/hermit/common/template/BuiltinFilters.java)。完整表达式保留原值类型，拼接字符串则转成文本。

`input`、`credential`、`config`、`response`、`now` 是保留变量，不能通过 set/extract/script.into 覆盖。request 更新 response，extract/set 产生后续步骤可用的变量。

输出可采用映射模板，也可用说明式字段。说明式字段省略 value 时引用同名变量；写 `value: '{{other}}'` 可显式映射，`value: ready` 返回常量。字段描述生成输出 Schema，但正常执行没有统一的最终 output Schema 校验；不要只依靠声明约束返回值。校验器禁止在输出中直接引用 credential。

实际输出由 `OutputMask` 遍历字符串值，替换长度至少 4 个字符的已知凭证/Cookie 值及其 URL 编码形式。它不按 `password`、`token` 等字段名屏蔽内容，也不替换 Map 的键；短值、尚未登记的上游秘密及哈希等派生值没有普遍保护。`Redactor.redactFields` 虽提供字段名屏蔽能力，但当前输出链没有调用它。规则应只返回业务需要的数据，不能依靠字段名自动脱敏。

```yaml
- script:
    code: |
      return vars.items.filter(function (item) { return item.active; });
    into: active_items
    timeout_ms: 1000
```

脚本在解释模式与受限全局对象中执行，不提供 Java 类、文件或网络访问入口，vars 不包含 credential 顶层。默认 1000ms、最大 5000ms，源码最多 64 KiB，结果最多 1 MiB。`into` 可保存结果；省略时要求适合合并的对象结果。脚本执行仍在同一 JVM，未提供进程级内存隔离。

## 发布包与验收

ZIP 只包含 `conch.yaml`、`manifest.json`、`signature.sig`；manifest format=1，包含规则身份、最低版本和 YAML SHA-256，签名对 manifest 字节做 Ed25519。每个条目最多 1 MiB，并验证未知/重复条目、路径、签名、摘要与 YAML 元数据一致性。

修改规则应增加版本号，通过 Reef 上传检查 errors/warnings，再发布一个更高版本，并在隔离 Crab 上手动更新后验证实际登录、输入/输出和错误路径。单纯发布成功不会执行 `health_check` 或测试真实凭证，也不会让已安装的 Crab 自动换版本。

## 本地开发与导出

本机可以导入未签名的 YAML，用来在这一台 Crab 上试规则。来源是控制台上传，或 `HERMIT_HOME/local-conches/{conch}.yaml` 加上重新加载。没有在线编辑器。文件名必须等于包名，只认直接放在该目录下的 `*.yaml`。

导入走与 Reef 上传相同的解析和 `ConchValidator`。`config` 仍然不能写 `type: secret`。单文件上限 1 MiB。控制台这次请求还受 Crab 现有请求体上限（1_000_000 字节）约束。校验失败返回 400 `INVALID_INPUT`，`data` 为 `{errors, warnings}`，每项含 `code`、`path`、`message`。

名字只和本机注册表里的正式包、以及 `subscriptions` 里的名字冲突。冲突是 HTTP 409，错误码仍是 `INVALID_INPUT`。`CONFLICT` 只用于已安装正式包时拒绝更换另一把 Reef 公钥。订阅一个已经导入的本地包同样是 409，`message` 为「与本机本地规则包同名」。与已有本地包同名则替换，版本以 YAML 为准。账号和凭证留在 `(LOCAL, conch, account)` 上，删除本地包也不清它们。

导出的是磁盘上的原始字节，`Content-Type` 为 `application/yaml`。本地包可以经 HTTP 和 MCP 调用，但 API Key 的 `conches` 必须写上这个名字。`all: true`（以及更早存下来、`conches` 与 `actions` 都为空的 Key）只覆盖正式包，不含本地包。MCP 工具和资源说明以 `[本地] ` 开头。调用记录的 `source` 为 `LOCAL`，不会进入 Tide 上报。同步不会下载、覆盖或卸下本地包。
