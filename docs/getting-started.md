# 快速开始

需要 JDK 17、curl、jq。以下命令从仓库根目录执行，示例适用于 macOS/Linux；Windows 的 classpath 分隔符使用 `;`。`HERMIT_HOME` 在 Windows 上用盘符路径，例如 `%USERPROFILE%\.hermit`，路径本身不要包含分号。先看[工具链说明](development.md)，确保终端 `java` 也是 JDK 17。

示例 `legacy_oa` 只有 `list_todo`。完整自动验收使用测试中的模拟 OA：`./mvnw -B verify`。手工调用需要可访问、页面结构与示例匹配的老系统。

## 1. 构建并安装依赖

```bash
./mvnw -B -pl hermit-reef,hermit-crab -am install -DskipTests
./mvnw -q -pl hermit-crab dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile=target/classpath.txt
```

这里使用 `install`，因为后面的独立 Crab classpath 命令从本地 Maven 仓库解析 `hermit-common` 与 `hermit-web`。只跑 `package` 在全新环境中不足以支持它。正式验证使用不跳过测试的 `./mvnw -B verify`。

## 2. 启动 Reef

终端 A（新建本地测试库；如果目录已有管理员，环境变量不会重置其密码）：

```bash
export HERMIT_REEF_ADMIN_USER=admin
export HERMIT_REEF_ADMIN_PASSWORD='替换为管理员口令'
export HERMIT_REEF_AGENT_TOKEN='替换为随机的非空Agent令牌'
java -jar hermit-reef/target/hermit-reef-0.1.0-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port=8080 \
  --hermit.reef.data-dir=./reef-data
```

必须设置 Agent token。未配置时 Reef 仍可启动、管理员仍能登录，但 `/agent/**` 拒绝请求。`.env` 不会被直接运行的 Java 自动加载。

## 3. 登录、取得公钥、发布规则

终端 B 中设置与终端 A 相同的管理员口令，选择专用 Crab 目录，避免误用已有实例：

```bash
export HERMIT_HOME="$HOME/.hermit-demo"
mkdir -p "$HERMIT_HOME"
chmod 700 "$HERMIT_HOME"
export REEF_URL=http://127.0.0.1:8080
export HERMIT_REEF_ADMIN_USER=admin
export HERMIT_REEF_ADMIN_PASSWORD='与终端A相同的管理员口令'

TOKEN=$(jq -n '{username:env.HERMIT_REEF_ADMIN_USER,password:env.HERMIT_REEF_ADMIN_PASSWORD}' \
  | curl -fsS "$REEF_URL/api/admin/login" -H 'Content-Type: application/json' --data-binary @- \
  | jq -er '.data.token')

curl -fsS "$REEF_URL/api/admin/signing-key" -H "Authorization: Bearer $TOKEN" \
  | jq -er '.data.publicKey' > "$HERMIT_HOME/reef.pub"

curl -fsS -X POST "$REEF_URL/api/admin/conches" \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@hermit-common/src/main/resources/samples/legacy_oa.yaml'

curl -fsS -X POST "$REEF_URL/api/admin/conches/legacy_oa/versions/1.0.0/publish" \
  -H "Authorization: Bearer $TOKEN"
```

登录响应只有 token 与过期时间，公钥来自独立的 `/signing-key` 响应。上面从自己启动的本机 Reef 取公钥；实际部署应通过可信渠道确认并分发公钥，不让 Crab 自动相信远端返回的新公钥。已有同版本时不要重复上传/发布，先在管理页面查看状态。

## 4. 配置并启动 Crab

新建 `$HERMIT_HOME/config.yml`，将 token 替换为与 Reef 一致的值。下面是文件内容，Crab 不做 `${ENV}` 插值：

```yaml
reef:
  url: http://127.0.0.1:8080
  token: '与Reef相同的Agent令牌'
  public_key: reef.pub
server:
  host: 127.0.0.1
  port: 7777
sync:
  interval_seconds: 300
report:
  enabled: true
  interval_seconds: 1800
```

`reef.pub` 相对 `HERMIT_HOME` 解析。也可以不手写 `reef.url`、`reef.token` 和 `reef.public_key`，启动后用控制台 `POST /console/api/reef/bootstrap` 或 CLI `reef import <file>` 导入引导 JSON。文件落在 `$HERMIT_HOME/reef-bootstrap.json`，存在时覆盖配置文件里的这三项。引导 JSON 的字段见[接口参考](api.md)。配置写好后：

```bash
chmod 600 "$HERMIT_HOME/config.yml"
# 方式一（推荐，无需拼 classpath）：直接运行独立可执行 JAR
java -jar hermit-crab/target/hermit-crab-0.1.0-SNAPSHOT-all.jar serve
# Windows PowerShell（可选指定不含分号的独立数据目录）：
# $env:HERMIT_HOME=".\crab-data"; java -jar .\hermit-crab-0.1.0-SNAPSHOT-all.jar serve

# 方式二：用普通 jar + 依赖 classpath 运行
# CP="hermit-crab/target/hermit-crab-0.1.0-SNAPSHOT.jar:$(cat hermit-crab/target/classpath.txt)"
# java -cp "$CP" dev.hermit.crab.CrabMain serve
```

也可以将 `hermit-crab/target/hermit-crab-0.1.0-SNAPSHOT-all.jar` 放入 Reef 数据目录的 `downloads/` 子目录中，团队成员登录 Reef 后即可在「下载 Crab」页面直接下载并用本机 JDK 17 启动，无需 Docker 或本地 Maven 构建。

浏览器访问 Reef `http://127.0.0.1:8080/`、Crab `http://127.0.0.1:7777/console/`。

内网 HTTPS 的信任在 Crab 本机控制台管理。打开 `http://127.0.0.1:7777/console/`，进入「连接 Reef」旁边的「HTTPS 信任」。那里可以看到 JDK、操作系统和附加 CA 目录各有多少张证书，也可以粘贴或选择 PEM 导入颁发者，或者先检测规则、MCP 上游和 Reef 用到的地址，再确认只信任这张证书。API Key 和 MCP 不能添加信任。

Crab 启动时后台同步目录。新装默认不订阅，未订阅的规则包不会下载安装。可在本机控制台「可订阅规则包」页点「订阅」，或用下面的订阅接口安装；同步失败不一定导致进程退出，应检查返回的各 Conch 结果。Reef 连不上时，手动同步返回 502 `UPSTREAM_ERROR`，并在同步状态里留下失败原因，而不是继续显示上次成功。

## 5. 同步、创建账号/API Key、配置老系统

终端 C（每个终端的变量需要单独设置）：

```bash
export CRAB_URL=http://127.0.0.1:7777
curl -fsS -X POST "$CRAB_URL/console/api/sync" -H 'X-Hermit-Console: 1'
# 同步只刷新目录。第一次订阅会安装 Reef 当前版本。之后的更高版本不会自动安装
curl -fsS -X POST "$CRAB_URL/console/api/conches/legacy_oa/subscription" \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' -d '{}'
# 目录里 updateAvailable 为 true 时，再手动安装清单上的更高版本：
# curl -fsS -X POST "$CRAB_URL/console/api/conches/legacy_oa/update" -H 'X-Hermit-Console: 1'
curl -fsS "$CRAB_URL/console/api/conches" -H 'X-Hermit-Console: 1'

API_KEY=$(curl -fsS -X POST "$CRAB_URL/console/api/apikeys" \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' \
  -d '{"name":"demo-read","conches":["legacy_oa"],"actions":["legacy_oa.list_todo"],"readOnly":true}' \
  | jq -er '.data.plaintext')

curl -fsS -X POST "$CRAB_URL/console/api/conches/legacy_oa/accounts" \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' -d '{"name":"alice"}'

# 此时缺凭证，应返回 HTTP 409 / NOT_CONFIGURED；因此这里不用 curl -f
curl -sS -i "$CRAB_URL/api/legacy_oa/list_todo" -H "Authorization: Bearer $API_KEY"
```

用控制台页面录入真实账号/密码，或以下命令从输入读取（口令不会进入命令行参数）：

```bash
export OA_URL='http://你的老系统地址'
export OA_USER='alice'
read -r -s OA_PASSWORD
export OA_PASSWORD

jq -n '{base_url:env.OA_URL}' \
  | curl -fsS -X PUT "$CRAB_URL/console/api/conches/legacy_oa/config" \
    -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' --data-binary @-

jq -n '{username:env.OA_USER,password:env.OA_PASSWORD}' \
  | curl -fsS -X PUT "$CRAB_URL/console/api/conches/legacy_oa/accounts/alice/credentials" \
    -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' --data-binary @-
unset OA_PASSWORD

curl -fsS "$CRAB_URL/api/legacy_oa/list_todo?page=1" -H "Authorization: Bearer $API_KEY"
```

第一个账号成为默认账号，不必传 `X-Hermit-Account`。创建并配置第二个账号后，可加 `-H 'X-Hermit-Account: bob'` 点名；不存在的账号返回 `ACCOUNT_NOT_FOUND`。API Key 明文仅创建时返回一次，请保存到调用端的秘密配置中。

## Docker 部署

默认部署 Reef、Nginx 和 MySQL：

```bash
test -e .env || cp .env.example .env
# 编辑 .env：所有示例口令/token 均替换
docker compose up -d --build
docker compose logs -f reef
```

访问 `http://localhost/`，端口由 `HTTP_PORT` 控制。Crab 应放在能访问老系统的机器上，可按前文直接运行 Java，也可使用仓库提供的容器。

在 Linux 上让 Crab 与 Reef 同机运行：

```bash
docker compose --profile client up -d --build
docker compose --profile client logs -f crab
```

Crab 通过宿主机 Nginx 端口访问 Reef，与 Reef 共用 `.env` 中的 `HERMIT_REEF_AGENT_TOKEN`。没有 `reef-bootstrap.json` 时，首次启动会用管理员账号登录 Reef，取得签名公钥，生成 `/var/lib/hermit/config.yml`。数据卷里已经有引导文件时，入口不再要求这两个环境变量，也不为公钥登录。两者都没有时 Crab 仍会启动，控制台可以之后导入。本机控制台为 `http://127.0.0.1:7777/console/`，端口可用 `HERMIT_CRAB_PORT` 设置。

在另一台 Linux 客户端机器上只部署 Crab，先在该机器的 `.env` 中设置 `HERMIT_REEF_URL`、与 Reef 相同的 Agent token，以及首次公钥引导所需的管理员账号/口令：

```bash
docker compose -f docker-compose.crab.yml up -d --build
docker compose -f docker-compose.crab.yml logs -f crab
```

也可挂载可信公钥并指定 `HERMIT_REEF_PUBLIC_KEY_FILE`，避免首次登录取公钥；变量填写容器内路径，仍需额外配置文件挂载。已有 `reef.pub` 会被保留，不会自动信任远端的新公钥。连接远端 Reef 的公钥引导依赖可信的 HTTPS 连接和管理员凭据。

两个 Crab Compose 方案都使用 host 网络和回环监听；不要同时运行并占用相同端口/数据卷。本说明按 Linux 宿主机使用，macOS/Windows 本地开发可使用前面的 Java 启动流程；本次未验证 Docker Desktop 的网络行为。用环境变量提供了 URL 和令牌、且没有引导文件时，已有配置与环境变量不一致会拒绝启动。完整配置、卷保留、更新和备份恢复见[配置与运维](configuration.md)。
