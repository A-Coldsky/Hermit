# Hermit
一个用于增强老系统功能的服务

## 构建

需要 JDK 17。在仓库根目录执行：

```bash
./mvnw -B verify
```

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
