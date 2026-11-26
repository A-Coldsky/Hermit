# 开发与测试

## 工具链

使用 JDK 17 和仓库 Maven Wrapper。检查 `java -version` 与 `./mvnw -version`：系统、IDE、Homebrew Maven 可能选到不同 JDK。本次机器默认 java 为 8、系统 Maven 使用 26，显式选择 17 后构建通过。

macOS/Homebrew 示例，其他系统替换为实际 JDK 17 路径：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -version
./mvnw -B verify
```

Maven 前端插件固定 Node 22.14.0，本地前端开发可使用相同版本。当前 lockfile 中 Vitest 的 Node 约束为 `^22.12.0 || ^24.0.0 || >=26.0.0`，不能理解为任意高于 22 的版本都支持；升级工具链时同时检查 Vite、jsdom 等依赖的 engines。

## 常用命令

仓库根目录：

```bash
# 完整页面构建、Vitest、Java 测试及跨进程验收
./mvnw -B verify

# 公共模型
./mvnw -B -pl hermit-common -am test

# Crab / Reef 模块及其依赖
./mvnw -B -pl hermit-crab -am test -Dhermit.web.skip=true
./mvnw -B -pl hermit-reef -am test -Dhermit.web.skip=true

# 定点测试，上游模块没有同名测试时不报错
./mvnw -B -pl hermit-crab -am test -Dhermit.web.skip=true \
  -Dtest=GatewayHttpTest -Dsurefire.failIfNoSpecifiedTests=false

# Crab 运行 classpath：先把本项目依赖安装到本地仓库
./mvnw -B -pl hermit-crab -am install -DskipTests
./mvnw -q -pl hermit-crab dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/classpath.txt
```

`hermit-it` 用 Surefire 执行，但依赖上游 package 阶段生成的 Reef 可执行 jar 和 Crab test-jar；干净仓库全量测试入口是 `verify`，不能以根目录 `test` 替代。该模块没有主代码，`package` 仍生成不含 class 的 jar，供根目录 `install`（含 `-DskipTests`）写入本地仓库。

`-DskipTests` 跳过 Java 测试执行，并通过 Maven profile 跳过前端测试，仍可能编译 Java 测试。`-Dhermit.web.skip=true` 跳过 npm/前端测试/页面资源复制，并清掉旧的 classpath 页面资源；缺页面时有占位页。仅跳过 Vitest 可用 `-Dhermit.web.skipTests=true`。完整发布验证不要带这些跳过参数。跳过前端不等于离线模式。

注意 `skip-web-tests` profile 按 `skipTests` 属性是否存在激活，所以 `-DskipTests=false` 也会跳过 Vitest，即使 Java 测试正常执行。全量验证最直接的方式是不传 `skipTests`；必须保留此参数时，同时显式设置 `-Dhermit.web.skipTests=false`，并检查构建日志确实执行了 Vitest。

## 前端开发

```bash
cd hermit-web
npm ci
npm run dev:reef
# 另一终端可以 npm run dev:crab
```

| 应用 | 开发入口 | 代理环境变量 | 默认后端 |
| --- | --- | --- | --- |
| reef-admin | `http://localhost:5173/` | `REEF_URL` | `http://localhost:8080` |
| crab-console | `http://localhost:5174/console/` | `CRAB_URL` | `http://127.0.0.1:7777` |

`npm run build` 执行 vue-tsc 和两套 Vite 构建；`npm test` 执行 Vitest（Vue Test Utils + jsdom），并已接入 Maven test 阶段。前端测试覆盖加载/空态、文案、主题、版本警告详情和图表刻度。仍没有真实浏览器 E2E；Java UI 测试主要覆盖资源路由、缓存和 SPA 返回，不能等同于浏览器交互验收。

页面约定：

- Element Plus 按需引入。两套 Vite 配置共用 [vite.shared.ts](../hermit-web/vite.shared.ts)，模板中的 `el-*` 组件和 `v-loading` 由 `unplugin-vue-components` 自动引入脚本与样式；`ElMessage`、`ElMessageBox` 这类函数式调用不经过模板，样式在 [shared/boot.ts](../hermit-web/shared/boot.ts) 中引入，新增其他函数式组件（如 `ElNotification`）时也在那里补样式。中文语言包通过 `provideGlobalConfig` 全局提供。
- 颜色统一用 [theme.css](../hermit-web/shared/theme.css) 中的 `--hm-*` 与 `--el-*` token，不在组件中写死色值。浅色 token 位于 `html:root`，暗色位于 `html.dark`；Element 组件样式随页面分块按需加载，排在 `theme.css` 之后，覆盖 Element 类名的规则需加 `html` 前缀。明暗阶由 `theme.test.ts` 按 Element 算法校验。
- 品牌图标用 [HermitMark](../hermit-web/shared/components/HermitMark.vue) 与双角色合影 [HermitDuet](../hermit-web/shared/components/HermitDuet.vue)：`crab` 是背着奶油珍珠斜尖海螺的萌芽眼丘小寄居蟹，`reef` 是外展海扇与左下嵌螺礁丘珊瑚，两者对齐于同一 `64×64` 光学网格与底基线 `y=55.5`；独立图形分别在 `MascotCrab.vue`、`MascotShell.vue`、`MascotReef.vue`，`index.html` 的 favicon 及 `docs/assets/hermit-banner.svg` / `hermit-badge.svg` 是同一套图形的静态副本。404 页面按应用通过 `mark` 属性区分 `crab` / `reef`。Crab 同步时的换壳加载（`ShellSwapLoader`）和开发者模式的挑壳小蟹（`ShellPicker`）只用 SVG 与 CSS transform/opacity 动画，挑壳播两轮后停下；系统开启减少动态效果时都不播放。目录页订阅安装和详情页更新也复用 `ShellSwapLoader`：订阅时用 `compact` 放在该行的本机状态列，更新在确认之后、请求进行中才挂载，同样用 `compact` 排在更新按钮右边，不推动下方内容。
- 明暗模式默认跟随系统，顶栏可切换，选择记在浏览器 `localStorage` 的 `hermit.color-mode`；`index.html` 中的首帧脚本使用同一键，避免闪烁。侧栏折叠状态记在 `hermit.sidebar-collapsed`，窄屏（≤ 900px）下侧栏改为抽屉。

## 测试地图

| 层 | 主要测试 | 重点 |
| --- | --- | --- |
| common | `ConchParserTest`、`McpConchParserTest`、`ConchValidatorTest`、`McpConchValidatorTest`、`ConchDiffTest`、`McpConchDiffTest`、`ConchPackageTest`、`ToolSnapshotsTest`、`McpAddressesTest`、`TemplateEngineTest` | 严格字段、MCP 包校验与摘要、版本差异、签名、模板 |
| Reef | `AdminApiTest`、`AgentToken*Test`、`ConchApiTest`、`ConchConcurrencyTest`、`SigningKeyStoreTest` | 鉴权、令牌缺失拒绝、发布必须更高、并发、密钥 |
| Tide | `ReportIngestTest`、`TideQueryTest`、`AlertEvaluationTest`、`TidePropertiesTest` | 上报校验、失败去重、聚合与告警、阈值负值拒绝 |
| 执行 | `EngineUnitTest`、`LegacyOaEngineTest`、`ScriptStepTest`、`CapabilityDeadlineTest`、`StepTimeoutTest` | 模拟 OA、登录隔离、编码/提取、脚本、显式 `status` 断言与非 GET 3xx 放行、没有覆盖期望的 GET 非 2xx 不抽成空列表、步骤超时与能力 deadline |
| 数据 | `SecretFlowTest`、`CredentialCipherTest`、`MasterKeyProviderTest`、`*StoreTest`、`CrabDatabaseUrlTest` | 加密、绑定、账户、主密钥、日志。H2 文件 URL 在 Linux 上只断言字符串（盘符、Unix 绝对路径、UNC、分号拒绝），不连接 `C:/`。真正开库的用例是 `@EnabledOnOs(WINDOWS)`，Linux CI 会跳过 |
| 网关/MCP | `GatewayHttpTest`、`ConsoleHttpTest`、`LoopbackTest`、`McpHttpTest`、`McpForwardTest`、`McpOAuthTest`、`McpRefreshTest`、`McpToolMergeTest` | 作用域、幂等冲突与同体回放、本机限制、调用渠道、MCP 模式与 SSE、未知工具 hint、traceId、多余入参、stdio `--help` 与 401 的 JSON-RPC 请求 id、OAuth 令牌、本地 `tools/list` 刷新与合并 |
| CLI | `CliTest` | 未知包 `config get` 退出 1；`apikey list` 对全部正式包打印 `all=true`（不打印 `conches=-`），有限范围打印名字、只读和吊销且不明文；`--help` 与无参数把用法打到 stdout 并退出 0，未知命令退出 1 |
| 前端 | `loading-empty.test.ts`、`copy.test.ts`、`theme.test.ts`、`version-panel.test.ts`、`bar-chart.test.ts`、`crab-detail-tabs.test.ts` | 初始加载、说明文案、浅色/暗色颜色映射、版本警告详情、图表刻度，以及 Crab 详情标签的默认页、红点和验证码弹窗 |
| 同步/上报 | `ConchSyncTest`、`ConchRepairTest`、`SubscriptionTest`、`InstalledConchesTest`、`RuleMismatchStaysTest`、`ReportSenderTest`、`CrabWiringTest`、`SyncExceptionTest` | ETag、订阅安装、手动更新、丢失安装不下载、失败重试、验签、失配不切换版本、Reef 不可达、定时任务 |
| 验收 | [Phase1AcceptanceTest](../hermit-it/src/test/java/dev/hermit/it/Phase1AcceptanceTest.java) | 独立 Reef + Crab + 模拟 OA，发布后手动更新、失配不回退、反馈与敏感数据检查 |
| 验收 | [BosLoginAcceptanceTest](../hermit-it/src/test/java/dev/hermit/it/BosLoginAcceptanceTest.java) | 本机模拟 BOS：脚本库加密登录，`{{login.sid}}` 与 Cookie 同一会话，幂等重放，口令和令牌打码 |
| 验收 | [LoginCaptchaAcceptanceTest](../hermit-it/src/test/java/dev/hermit/it/LoginCaptchaAcceptanceTest.java) | 本机图片验证码与短信验证码：409 后在控制台提交，再调用成功；输错、换图或重发、版本更新丢弃旧待输入；验证码和登录令牌不进日志 |
| 验收 | [RequestEncodingAcceptanceTest](../hermit-it/src/test/java/dev/hermit/it/RequestEncodingAcceptanceTest.java) | 独立 Crab 进程对回环老网关核对请求行：query 与 path 编码、`query_style`、GBK 表单和查询值，开发追踪 `wire` 与线上一致且凭证已打码 |
| 验收 | [IntranetTlsAcceptanceTest](../hermit-it/src/test/java/dev/hermit/it/IntranetTlsAcceptanceTest.java) | 独立 Crab 进程对回环 HTTPS：私有 CA、自签名指纹、删除信任后立即失效，API Key 不能访问 `/console/api/tls` |
| 执行 | `FlowStepsEngineTest`、`FlowStepValidationTest` | `if`、`repeat`、`retry`：分支、汇总与截断、再试的条件和追踪；1.9.0 门槛、未知键报错与提示 |
| 写规则测验 | [RuleQuizTest](../hermit-it/src/test/java/dev/hermit/it/quiz/RuleQuizTest.java) | 9 道题的参考答案在本机模拟站点上得满分，典型写错能被校验或试跑判出来 |

Java 结果位于各模块 `target/surefire-reports/`。2026-10-16 同步到 `48daca7` 后完整 verify：common 41、Reef 97、Crab 170、验收 1，共 309 项 Java 测试，以及 4 个前端测试文件中的 18 项 Vitest 测试均通过，无失败或跳过。没有 JaCoCo 配置，测试条数不是行/分支覆盖率。

## 写规则测验

规则主要由模型来写。测验用来量「只看规则说明的模型，第一次能不能写对」，题面在 `hermit-it/src/test/resources/quiz/<题号>.md`，参考答案在 `quiz/answers/`，站点是测试里的 `QuizSite`。平时的 `verify` 只验证题目和判分本身，不调用模型。

给一个模型打分：

1. 新开一个不带仓库上下文的模型会话，只给它 `docs/conch-reference.md`（等同 `hermit://conches/hermit_dev/guide`）和一道题的题面，让它交一份 `conch.yaml`，存成 `<目录>/<题号>/1.yaml`。
2. 运行 `./mvnw -pl hermit-it -am test -Dhermit.web.skip=true -Dtest=RuleQuizTest -Dsurefire.failIfNoSpecifiedTests=false -Dquiz.answers=<目录的绝对路径>`。每份答案旁边会写出 `1.feedback.txt`：校验错误，或试跑的输出、请求和期望的差别。
3. 没通过的题，把 feedback 原样交还给同一个会话，让它改，存成 `2.yaml`，再运行。最多 3 轮。
4. `<目录>/report.md` 是汇总：第 1 轮校验通过率、第 1 轮试跑正确率、最终正确率和平均轮数。

哪个键错得多，就改它的名字、报错或说明里的例子，再测一遍。改了规则语法或说明的 PR，在说明里写上测验结果和用的模型。

## 维护方式

- 使用 `@TempDir`、`LegacyOaFixture`、`FakeReef`、`MutableClock`，不要对真实数据执行验收。
- Reef 表、索引、唯一约束和检查约束使用 `hermit_` 前缀，Flyway 历史表是 `hermit_flyway_schema_history`。发布前 V1–V4 已就地改写，没有数据迁移；投产前的开发库要重建，见配置文档。Reef 生产执行过 V1–V7，之后只新增迁移（V8 起），改了已合入的迁移 `MigrationHistoryTest` 会失败。Crab 的 `CrabDatabase` 保持原表名。schema 10 起新增版本，不改已经生效的迁移：10 是幂等请求体摘要，11 是调用记录 `channel`。已发布客户端在 15，`CrabSchemaHistoryTest` 从冻结的 15 版结构升级并与全新安装比对。
- 缺陷修复添加行为回归测试，特别关注权限、失败与并发交错。
- DTO/Schema 修改检查双方；HTTP 响应修改同步两套前端 `api.ts`。
- 涉及包格式/同步/发布/网关的改动，定点测试后再跑完整 verify。
- 当前没有 CI workflow、覆盖率门槛和依赖漏洞扫描配置。

## 审计探针

完整 verify 后，使用 JDK 17 和 Python 3 运行 `python3 docs/audits/repro/run.py`。脚本从 Surefire XML 取依赖 classpath，使用临时数据及回环随机端口。它打印现有行为，退出 0 只表示探针完成，不表示缺陷修复；不纳入默认测试套件。观察值与修复验收标准见[审计报告](audits/2026-10-16.md)。
