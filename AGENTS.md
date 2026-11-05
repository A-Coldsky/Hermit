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
- Maven 制品版本 `0.1.0-SNAPSHOT` 与规则兼容版本 `CrabVersion.CURRENT = 1.1.0` 用途不同。
- Conch 指 YAML 规则包。`driver: http` 走步骤引擎。`driver: mcp` 由 Crab 按 Streamable HTTP 转发，这一批只做 `headers` 认证；OAuth 登录和工具列表刷新还没有。Reef 管理 API 与 Agent 清单从包内读出上游和工具表，不连上游。它不是操作系统命令脚本，也不支持 stdio。

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
- Crab 是普通 jar；单独解析运行 classpath 前先安装依赖模块，见开发文档。
- 缺陷修复添加针对行为的回归测试；文档修改检查链接、命令与源码一致性。
- 不复制真实 `.env`、`~/.hermit`、`reef-data`、数据库、密钥或凭证到代码/文档/测试输出。测试用临时目录和模拟服务。
- 秘密放 `credentials`；`config` 不允许 `type: secret`。
- 保持验签、哈希、路径与大小限制；Reef 公钥是规则信任根。原生 Java 连接 Reef 或加载已安装正式规则时需预配公钥；容器首次运行可使用挂载公钥，或用管理员登录取得公钥，修改时保持这个初始化边界清晰。
- 输出脱敏只替换已知凭证/Cookie 的字符串值及其 URL 编码形式，长度需至少 4 个字符；不能假定敏感字段名、短秘密或派生值会自动屏蔽，详见规则包（Conch）参考。
- 账号/会话按 `(origin, conch, account)` 隔离；HTTP/MCP 调用共用 `Invoker`，不得绕过权限和能力禁用检查。
- 规则快照/权限修改需检查审计 A01 的版本切换竞态。
- 首次正式发布前，若用户确认没有存量数据，可以就地改写已有迁移；发布之后一律新增版本，不改已经生效的迁移。Reef 用 Flyway，Crab 建表与升级在 `CrabDatabase`，不使用 Flyway。
- `health_check`、debug 状态尚未接通完整执行链，不能标成完整可用功能。`local-conches` 可导入、调用和导出：无签名、只在本机、仅显式 Key。
- 对外行为变化同步更新文档、前端 API 类型和功能状态，报告验证结果及未验证边界。
