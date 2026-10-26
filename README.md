# Hermit

Hermit 用声明式 YAML 规则包（Conch）描述老系统的登录、请求、数据提取和业务能力，再由部署在内网的 **Crab** 提供 HTTP API 与 MCP 接口。**Reef** 负责规则管理、签名发布、版本分发及运行反馈。

规则包（Conch）不是操作系统 shell 脚本。当前支持 HTTP 驱动，包含 Vue 管理页面、账号与凭证管理、自动同步、失配回滚、统计与告警。实现边界见[功能状态](docs/feature-status.md)。

## 从哪里开始

| 目的 | 文档 |
| --- | --- |
| AI 或新开发者接手 | [AGENTS.md](AGENTS.md) → [架构与代码地图](docs/architecture.md) |
| 本地启动并完成首次调用 | [快速开始](docs/getting-started.md) |
| 构建、测试、定位修改点 | [开发与测试](docs/development.md) |
| 查配置、部署、备份和排障 | [配置与运维](docs/configuration.md) |
| 编写 YAML 规则 | [规则包（Conch）参考](docs/conch-reference.md) |
| 接入 HTTP、控制台或 MCP | [接口参考](docs/api.md) |
| 了解已实现与未完成的能力 | [功能状态与测试证据](docs/feature-status.md) |
| 查看当前风险和修复顺序 | [2026-10-16 项目审计](docs/audits/2026-10-16.md) |

## 构建

使用 **JDK 17**，在仓库根目录执行：

```bash
./mvnw -B verify
```

Maven Wrapper 使用 Maven 3.9.16；前端插件会下载 Node 22.14.0，执行 `npm ci`、类型检查、两套页面构建及 Vitest 测试。首次构建需要网络访问 Maven Central、Node 和 npm 仓库。仅跳过前端可加 `-Dhermit.web.skip=true`，这不代表全部 Maven 依赖都能离线获取。

初次审计基线 `0c1bbcf`，同步到 `48daca7` 后重新完整验证：**309 项 Java 测试和 18 项前端测试通过，0 失败、0 跳过**。额外审计探针仍复现了代码问题，详见审计报告。测试通过不等于没有缺陷。

## 运行形态

```text
管理员 → Reef 管理页面 → 上传/发布签名规则包
                           ↓ 同步规则 ↑ 上报统计
调用方 / AI → HTTP / MCP → Crab → 内网老系统
                           ↑
                    本机控制台管理凭证
```

默认入口：Reef `http://127.0.0.1:8080/`；Crab `http://127.0.0.1:7777/console/`。Crab 凭证保存在本机，Reef 不负责保存老系统账号密码。

Docker Compose 默认提供 Reef、Nginx 和 MySQL：

```bash
test -e .env || cp .env.example .env
# 编辑 .env：设置数据库密码、管理员口令及非空 Agent token
docker compose up -d --build
```

Linux 同机部署 Crab 可用 `docker compose --profile client up -d --build`；单独部署 Crab 可用 `docker compose -f docker-compose.crab.yml up -d --build`。Crab 使用 host 网络并监听回环地址，需部署在能访问老系统的机器上。

Reef 默认访问 `http://localhost/`。升级保留 `reef-mysql`、`reef-data`，以及运行 Crab 时的 `crab-data` 卷；首次公钥引导、共享 Agent token、独立部署变量及平台边界见[快速开始](docs/getting-started.md)和[配置与运维](docs/configuration.md)。
