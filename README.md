# Hermit
一个用于增强老系统功能的服务

## 构建

需要 JDK 17。在仓库根目录执行：

```bash
./mvnw -B verify
```

## Docker 部署

Reef 用 Docker Compose 拉起 `reef`、`nginx`、`mysql`。Crab 要留在能访问老系统的机器上，有两种容器跑法：和 Reef 同机用 `client` profile，或者在另一台机器上只跑 Crab。也可以按下一节用 java 启动，不经过 Docker。

`HERMIT_REEF_AGENT_TOKEN` 必须非空，而且 Reef 和 Crab 用同一个值。Reef 用它拒绝空令牌的 `/agent/**`，Crab 把它写进 `reef.token`。空令牌时两边都不会启动。

Crab 控制台只认套接字上的回环地址，页面是 `http://127.0.0.1:<端口>/console/`。`/console/api` 还要请求头 `X-Hermit-Console: 1`。容器不会为了方便关掉这两条限制，也没有把 `server.host` 改成 `0.0.0.0`。Crab 使用宿主机网络并监听 `127.0.0.1`，这样宿主机上打开控制台时对端仍是回环。不用端口映射：即便写成 `127.0.0.1:7777:7777`，容器里看到的对端也是代理或网桥地址，回环校验会返回 403。Docker Desktop 的 Mac/Windows 上，宿主机网络落在虚拟机里，浏览器打不开 Crab 控制台。

复制环境文件。生产环境必须改掉 `.env.example` 里标明的数据库密码、root 密码、管理员口令和 agent token：

```bash
cp .env.example .env
```

只启动 Reef：

```bash
docker compose up -d --build
```

同一台 Linux 机器上连 Crab 一起启动。Crab 访问的 Reef 地址是 `http://127.0.0.1:$HTTP_PORT`，令牌用同一份 `HERMIT_REEF_AGENT_TOKEN`：

```bash
docker compose --profile client up -d --build
```

浏览器打开 `http://localhost/`（`.env` 里的 `HTTP_PORT` 默认是 80）进入 Reef 管理页。Crab 控制台是 `http://127.0.0.1:7777/console/`（端口由 `HERMIT_CRAB_PORT` 决定）。看日志：

```bash
docker compose logs -f reef
docker compose --profile client logs -f crab
```

在另一台机器上只部署 Crab 时，用独立的 compose。`.env` 里写上 Reef 的地址、同一个令牌，以及管理员口令或公钥文件。实例号和主密钥可选：

```bash
docker compose -f docker-compose.crab.yml up -d --build
```

等价的 `docker run`（把变量换成实际值；公钥文件和主密钥文件二选一，见 `.env.example`）：

```bash
docker build -f Dockerfile.crab -t hermit-crab .
docker run -d --name hermit-crab --network host \
  -e HERMIT_HOME=/var/lib/hermit \
  -e HERMIT_REEF_URL=http://reef.example \
  -e HERMIT_REEF_AGENT_TOKEN='与 Reef 相同的令牌' \
  -e HERMIT_CRAB_PORT=7777 \
  -e HERMIT_CRAB_INSTANCE_ID='11111111-1111-1111-1111-111111111111' \
  -e HERMIT_REEF_PUBLIC_KEY_FILE=/run/hermit/reef.pub \
  -v hermit-crab:/var/lib/hermit \
  -v /path/to/reef.pub:/run/hermit/reef.pub:ro \
  hermit-crab
```

不挂公钥时，加上 `HERMIT_REEF_ADMIN_USER` 和 `HERMIT_REEF_ADMIN_PASSWORD`，第一次启动会登录 Reef 并把签名公钥写入卷里的 `reef.pub`。

数据卷：`reef-mysql` 是 MySQL 数据，`reef-data` 挂在 Reef 的 `/data`（签名密钥和已发布的 YAML），`crab-data`（或 `docker run` 的命名卷）挂在 Crab 的 `HERMIT_HOME`（`config.yml`、`reef.pub`、`data/`）。升级时保留这些卷，否则密钥和库会换掉。

凭证主密钥不进镜像，也不进 `config.yml`。设置 `HERMIT_MASTER_KEY`（32 字节的 Base64）时只用环境变量；不设时 Crab 读写数据卷里的 `data/master.key`，没有就创建。`HERMIT_MASTER_KEY_FILE` 指向容器内已有的密钥文件，入口脚本把它复制到 `data/master.key`。已经有凭证之后不要更换来源。

`HERMIT_CRAB_INSTANCE_ID` 是可选的 UUID。不设则第一次启动写到 `data/instance-id`。卷里已有另一份实例号时不会覆盖。

库里还没有管理员时，第一次启动用 `.env` 的 `HERMIT_REEF_ADMIN_USER` 和 `HERMIT_REEF_ADMIN_PASSWORD` 创建第一个管理员。之后再启动不会改已有管理员的密码。Crab 卷里已有 `config.yml` 时不会覆盖；这时 `HERMIT_REEF_URL`、令牌和端口必须和文件里的一致，否则入口脚本退出。

## 第一期快速开始

下面用命令行和 curl 走通：启动 Reef、初始化管理员、上传并发布带凭证声明的 `legacy_oa`、配置 Crab、创建账号和凭证、创建 API Key，然后不带 `X-Hermit-Account` 调用待办查询。示例里的口令请换成你自己的，不要提交到仓库。

打包：

```bash
./mvnw -pl hermit-reef,hermit-crab -am package -DskipTests
```

启动 Reef，并在空库时用环境变量创建管理员：

```bash
export HERMIT_REEF_ADMIN_USER=admin
export HERMIT_REEF_ADMIN_PASSWORD='替换成管理员口令'
java -jar hermit-reef/target/hermit-reef-0.1.0-SNAPSHOT.jar --server.port=8080 --hermit.reef.data-dir=./reef-data
```

登录，把返回的 `data.token` 记为 `TOKEN`。把 `data.publicKey` 存成文件（生产环境公钥走带外配置，不从 Crab 自动拉取）：

```bash
curl -s -X POST http://127.0.0.1:8080/api/admin/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"替换成管理员口令"}'

curl -s http://127.0.0.1:8080/api/admin/signing-key \
  -H "Authorization: Bearer $TOKEN" \
  -o /tmp/reef-signing-key.json
```

从 JSON 里取出 PEM，写入 `~/.hermit/reef.pub`。

上传并发布示例 Shell（文件在 `hermit-common/src/main/resources/samples/legacy_oa.yaml`）：

```bash
curl -s -X POST http://127.0.0.1:8080/api/admin/shells \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@hermit-common/src/main/resources/samples/legacy_oa.yaml"

curl -s -X POST http://127.0.0.1:8080/api/admin/shells/legacy_oa/versions/1.0.0/publish \
  -H "Authorization: Bearer $TOKEN"
```

Crab 配置 `~/.hermit/config.yml`。`reef.public_key` 可以是 PEM 文本，也可以是上面的文件路径（相对路径相对 `HERMIT_HOME`）：

```yaml
reef:
  url: http://127.0.0.1:8080
  public_key: reef.pub
server:
  host: 127.0.0.1
  port: 7777
sync:
  interval_seconds: 300
report:
  enabled: true
  interval_seconds: 60
```

启动 Crab（jar 不包含依赖，用 Maven 拼 classpath）：

```bash
export HERMIT_HOME="$HOME/.hermit"
./mvnw -pl hermit-crab -q -DincludeScope=runtime dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
CP="hermit-crab/target/hermit-crab-0.1.0-SNAPSHOT.jar:$(cat hermit-crab/target/classpath.txt)"
java -cp "$CP" dev.hermit.crab.CrabMain serve
```

另开一个终端。同步（Crab 已在运行时走控制台；未运行时也可以 `java -cp "$CP" dev.hermit.crab.CrabMain sync`）：

```bash
curl -s -X POST http://127.0.0.1:7777/console/api/sync -H 'X-Hermit-Console: 1'
```

创建 API Key、默认账号，并确认还没写凭证时调用返回 `NOT_CONFIGURED`：

```bash
curl -s -X POST http://127.0.0.1:7777/console/api/apikeys \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' \
  -d '{"name":"caller"}'

curl -s -X POST http://127.0.0.1:7777/console/api/shells/legacy_oa/accounts \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' \
  -d '{"name":"alice"}'

curl -s http://127.0.0.1:7777/api/legacy_oa/list_todo \
  -H "Authorization: Bearer $API_KEY"
```

把老系统地址和账号凭证写入 Crab（凭证只写不回显）。第一个账号是默认账号，调用时不必带 `X-Hermit-Account`：

```bash
curl -s -X PUT http://127.0.0.1:7777/console/api/shells/legacy_oa/config \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' \
  -d '{"base_url":"http://老系统地址"}'

curl -s -X PUT http://127.0.0.1:7777/console/api/shells/legacy_oa/accounts/alice/credentials \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"替换成 OA 口令"}'

curl -s http://127.0.0.1:7777/api/legacy_oa/list_todo \
  -H "Authorization: Bearer $API_KEY"
```

第二个账号要在请求里点名。未知账号名返回 `ACCOUNT_NOT_FOUND`：

```bash
curl -s -X POST http://127.0.0.1:7777/console/api/shells/legacy_oa/accounts \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' \
  -d '{"name":"bob"}'

curl -s -X PUT http://127.0.0.1:7777/console/api/shells/legacy_oa/accounts/bob/credentials \
  -H 'X-Hermit-Console: 1' -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"替换成 OA 口令"}'

curl -s http://127.0.0.1:7777/api/legacy_oa/list_todo \
  -H "Authorization: Bearer $API_KEY" \
  -H 'X-Hermit-Account: bob'

curl -s http://127.0.0.1:7777/api/legacy_oa/list_todo \
  -H "Authorization: Bearer $API_KEY" \
  -H 'X-Hermit-Account: nobody'
```

## 管理页面

Reef 管理端：`http://<reef>:8080/`，用初始化的管理员登录。

Crab 控制台：`http://127.0.0.1:7777/console/`，只能在 Crab 本机打开。

前端开发：`cd hermit-web && npm ci && npm run dev:reef`（代理到 `REEF_URL`，默认 `http://localhost:8080`）/ `npm run dev:crab`（代理到 `CRAB_URL`，默认 `http://127.0.0.1:7777`）。

构建时需要下载 Node。离线或不需要页面时用 `-Dhermit.web.skip=true`。
