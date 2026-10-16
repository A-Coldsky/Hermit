# 功能实现状态

初次审计基线：`0c1bbcf`；功能状态已同步到 `48daca7`，日期：2026-10-16（北京时间）。仓库没有完整产品需求/验收清单，下面依据现有源码、示例和测试判断，不给缺乏依据的“完成百分比”。“已实现”表示存在可运行链路，不代表不存在缺陷或已在生产验收。

## 主要链路

| 能力 | 状态 | 证据与边界 |
| --- | --- | --- |
| Shell 模型/严格解析/语义校验/版本差异 | 已实现 | common parse/validate；对应测试齐全，部分步骤错误在运行时检查 |
| 模板、内置过滤器、输入 Schema | 已实现 | TemplateEngine/InputPreparer；不提供完整最终输出 Schema 验证 |
| HTTP 请求、登录、alive_check、Cookie 会话 | 已实现，有缺陷 | RuleEngine/HttpCalls/SessionManager；慢速总超时、Cookie 历史增长见 A03/A04 |
| CSS/XPath/JSONPath/regex 提取 | 已实现 | Extractors + EngineUnitTest/LegacyOaEngineTest |
| assert/set/script | 已实现 | StepRunner/ScriptStep；脚本 Java 隔离和时间/结果限制有测试，无进程级内存隔离 |
| 多账号/默认账号/凭证/配置 | 已实现 | ShellAdmin + store；credentials 加密只写 |
| API Key 创建、撤销、scope | 已实现，有缺陷 | ApiKeyService/Principal；按 Shell/action/只读限制，未按账号限制；版本切换风险 A01 |
| HTTP 网关/别名/OpenAPI | 已实现 | GatewayHttpTest；OpenAPI 无鉴权且仅描述能力接口 |
| POST 幂等响应缓存 | 已实现，有缺陷 | 24h 密文缓存；无参数摘要/版本区分，锁不回收见 A05 |
| MCP HTTP/SSE/工具/资源 | 已实现 | McpHttpTest；≤40 直接工具，>40 目录模式，确认行为不同 |
| MCP stdio 桥接 | 已实现 | McpStdio；需要已运行的 HTTP Crab，CLI/验收覆盖不足以代表所有真实客户端兼容 |
| Reef 管理员初始化、JWT、锁定、审计 | 已实现 | AdminBootstrap/AdminApiTest；没有完整管理员生命周期管理 API |
| 上传、签名发布、分发、管理员回滚 | 已实现 | ShellApiTest/ShellConcurrencyTest/ShellSelfCheckFailureTest |
| Crab 定时/手动同步、ETag、验签 | 已实现 | ShellSyncTest/CrabWiringTest；可重试失败不记 ETag，304 核对 current |
| 本地规则失配回滚、坏版本固定 | 已实现 | RollbackGuardTest；要求前一版本成功，5 次失配；不等于发布前健康检查 |
| 上报队列、聚合、退避、预览 | 已实现 | ReportSenderTest；失败/事件队列上限 10000，超限丢弃计数；小时统计单独存储 |
| Reef Tide 查询、失败聚合、版本对比 | 已实现 | TideQueryTest；聚合延迟不等同于全量原始样本精确分位数 |
| 告警评估、确认、Webhook | 已实现，有边界 | AlertEvaluationTest；Webhook 没有持久化重试队列；事件重报 A06 |
| Reef 管理页面 | 已实现 | 登录、仪表盘、Shell/上传/版本及校验警告明细、Tide、告警、审计、设置说明 |
| Crab 本机控制台 | 已实现 | 概览、Shell、账号/凭证/配置、能力、API Key、调用、上报 |
| Docker Reef + Nginx + MySQL / Crab | 已配置，运行未验收 | Crab 同机 client profile / 独立 Compose / Dockerfile 已提供；配置语法通过，尚未启动容器栈 |

## 部分实现与缺口

| 项目 | 实际状态 | 后续工作 |
| --- | --- | --- |
| `health_check` | Parser/Model 接收，示例声明；无执行方 | 定义执行时机、账号选择、失败门槛，并接入发布/安装流程，或移除误导性声明 |
| `local-shells` / Origin.LOCAL | 路径/存储命名空间与底层测试存在，产品调用仅 OFFICIAL | 明确本地规则的加载、信任、编辑和路由设计 |
| debug | API/持久化/24h 过期存在；执行引擎/日志不读这个状态；新 UI 明示未接入调用记录 | 定义调试行为及脱敏边界，再接入日志采集 |
| `mcp.destructive` | 可解析，但 tool annotation 从 readOnly/idempotent 推导 | 对齐字段声明与实际注解；确认模式一致性需产品决定 |
| Crab 升级 | Reef 提供最新版本/下载地址，Crab 展示提示 | 无自动下载/升级器；继续手工升级或另行实现 |
| 管理员生命周期 | 空库初始化 + 登录/me | 没有改密码/创建/禁用/注销令牌的完整管理 API |
| 配置批量原子更新 | 控制台 PUT 逐字段调用 putConfig | 校验/写入失败可能部分生效；补事务行为测试 |
| 数据保留 | 部分表定时清理 | 两端小时统计及部分审计/告警数据仍可持续增长 |
| 前端交互测试 | 类型检查、构建、后端静态资源测试和 Vitest/jsdom 组件测试 | 仍缺真实浏览器 E2E |
| 交付工程化 | Maven 构建/Java/Vitest 测试和 Crab Docker 运行镜像已提供 | 无 CI workflow/覆盖率门槛/自动依赖扫描；原生 Crab 仍为普通 jar |

`legacy_oa.yaml` 只实现待办查询。其 Shell 描述涉及审批/请假、related 引用 approve/list_done，都不能作为对应动作已实现的证据。

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
