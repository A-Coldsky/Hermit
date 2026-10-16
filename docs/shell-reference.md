# Shell 规则参考

规则以 [ShellParser](../hermit-common/src/main/java/dev/hermit/common/parse/ShellParser.java)、[ShellValidator](../hermit-common/src/main/java/dev/hermit/common/validate/ShellValidator.java) 和 Crab 引擎为准。这里覆盖当前实现，不是未来功能设计。完整可发布例子：[legacy_oa.yaml](../hermit-common/src/main/resources/samples/legacy_oa.yaml)。

## 最小规则

下面的规则无需老系统认证，返回一个常量，可用于检查发布、同步和网关链路。Crab 仍要求创建一个账号作为执行上下文。

```yaml
shell: demo_system
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
| `shell`、action 名 | `^[a-z][a-z0-9_]{1,63}$` |
| `version`、`min_crab_version` | SemVer；当前引擎兼容版本为 1.0.0 |
| `driver` | 只能 `http` |
| `title`、`description` | Shell/action 均需要；输入参数和输出说明也有描述要求 |
| `credentials` | 必须显式声明，无凭证时写 `{}`；遗漏会报错 |
| `config` | 可选字段映射；字段支持默认值；不用于保存秘密 |
| `auth.steps` | 登录步骤；可省略；没有登录步骤也会创建账号会话 |
| `auth.alive_check` | 请求及 `expect_not_redirect`，空闲超时后检测会话 |
| `actions` | 能力映射，每个包含输入、步骤、输出和示例等 |
| `health_check` | 目前仅解析/保存，没有自动执行器 |
| `owner`、`tags`、`changelog` | 管理元数据 |

兼容别名：`connector` → `shell`、`min_agent_version` → `min_crab_version`。新旧键同时出现且值不一致会拒绝。解析器对各已定义层的未知键较严格，步骤内部的完整语义则部分留到运行时校验，不能假定“可上传”就等于能正确执行。

`credentials`/`config` 字段接受 `type`、`label`、`required`、`description`、`options`；config 额外支持 `default`。类型有 text/secret/select/url。**当前 config 的 secret 也明文保存且可能原样输出**，见审计 A02；密码、token 必须使用 credentials。配置值优先级为用户值 → 声明 default → 顶层 base_url（仅 base_url 字段）；引擎也可直接回退顶层 base_url。

Action 还支持 `method`、`input`、`output`、`steps`、`idempotent`、`examples`、`notes`、`related`、`mcp`。缺 examples 是校验错误；notes/related 等说明质量问题可能产生警告。示例中 related 指向不存在的能力产生警告，不代表那些能力已实现。

输入参数可用 `type`、`description`、`default`、`enum`、`minimum`、`maximum`、`items`、`required`；由 `InputPreparer` 转换并按生成的 JSON Schema 校验。GET 查询参数以字符串进入，数值/布尔转换由输入层处理。

## HTTP 方法、只读和幂等是不同概念

外部方法：显式 method 优先；否则 `mcp.read_only` 或 `idempotent` 为 true 时选 GET，其余 POST。方法枚举只支持 GET/POST，这与步骤内可配置的上游 HTTP method 不同。

- 权限中的只读仅由 `mcp.read_only` 决定，不能因为 GET 或 idempotent 就推断只读。
- `idempotent` 决定会话失效后是否允许重新登录并重放，默认 false。
- POST 的 `Idempotency-Key` 是网关独立缓存机制，不等于上述声明。
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

request 的 path 与 url 必须二选一，form 与 json 互斥。默认 GET、UTF-8、15,000ms、不跟随重定向；响应最多 5 MiB。解码优先 Content-Type charset，其次 HTML meta，再使用步骤 encoding。开启重定向最多 5 跳，只允许同源；Cookie 由账号客户端管理，规则不能自行覆盖 Cookie 头。

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

CSS/各提取器的详细参数看 [Extractors](../hermit-crab/src/main/java/dev/hermit/crab/engine/Extractors.java) 与 `LegacyOaEngineTest`；request 失败会形成步骤索引、类型和错误摘要。总 deadline 目前存在慢速响应漏洞，不能作为可靠的硬时间边界。

## 模板、输出与脚本

模板如 `{{input.page}}`、`{{credential.password | md5}}`、`{{config.base_url}}`、`{{items}}`。支持的内置过滤器为 md5、sha256、base64、urlencode、trim、default、date、rsa；具体参数见 [BuiltinFilters](../hermit-common/src/main/java/dev/hermit/common/template/BuiltinFilters.java)。完整表达式保留原值类型，拼接字符串则转成文本。

`input`、`credential`、`config`、`response`、`now` 是保留变量，不能通过 set/extract/script.into 覆盖。request 更新 response，extract/set 产生后续步骤可用的变量。

输出可采用映射模板，也可用说明式字段。说明式字段省略 value 时引用同名变量；写 `value: '{{other}}'` 可显式映射，`value: ready` 返回常量。字段描述生成输出 Schema，但正常执行没有统一的最终 output Schema 校验；不要只依靠声明约束返回值。禁止直接把 credential 引用放进输出，执行后还会对已知凭证/Cookie 和敏感字段做脱敏。

```yaml
- script:
    code: |
      return vars.items.filter(function (item) { return item.active; });
    into: active_items
    timeout_ms: 1000
```

脚本在解释模式与受限全局对象中执行，不提供 Java 类、文件或网络访问入口，vars 不包含 credential 顶层。默认 1000ms、最大 5000ms，源码最多 64 KiB，结果最多 1 MiB。`into` 可保存结果；省略时要求适合合并的对象结果。脚本执行仍在同一 JVM，未提供进程级内存隔离。

## 发布包与验收

ZIP 只包含 `shell.yaml`、`manifest.json`、`signature.sig`；manifest format=1，包含规则身份、最低版本和 YAML SHA-256，签名对 manifest 字节做 Ed25519。每个条目最多 1 MiB，并验证未知/重复条目、路径、签名、摘要与 YAML 元数据一致性。

修改规则应增加版本号，通过 Reef 上传检查 errors/warnings，再发布并在隔离 Crab 验证实际登录、输入/输出、错误路径和回滚。单纯发布成功不会执行 `health_check` 或测试真实凭证。
