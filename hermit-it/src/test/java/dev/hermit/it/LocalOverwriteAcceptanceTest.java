package dev.hermit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import dev.hermit.crab.CrabMain;
import dev.hermit.crab.sync.LocalConchException;
import dev.hermit.crab.sync.LocalConches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * 开发者模式下的本地包覆盖。Crab 以独立进程跑 {@code crab serve}，上游只有两个回环模拟站。
 * 同目的地覆盖不询问确认，旧文件进 {@code .history}。当前文件之外只留最近 2 份。扩大目的地且客户端没有 elicitation 时返回
 * {@code CONFIRM_REQUIRED}；控制台按 sha256 确认后，下一次调用打到新站，并且不带旧 Cookie。
 * 待确认过期依赖进程内的 {@code Clock.systemUTC()}，这里不等 30 分钟；重启会清掉内存里的待确认。
 * 客户端 elicitation 和覆盖进行中的调用分别由 {@code AuthoringMcpTest}、{@code LocalConchReplaceSessionTest} 覆盖。
 */
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class LocalOverwriteAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONCH = "site_hop";
    private static final String OTHER = "other_pack";
    private static final String ACCOUNT = "ada";
    private static final String ALPHA = "alpha-session-cookie";
    private static final String BRAVO = "bravo-session-cookie";
    private static final String CONFIRM_PARAM = "不能用参数确认覆盖。覆盖已有本地规则包时，要在客户端里确认。";
    private static final String READ_ONLY_SAVE = "这把 API Key 只允许调用只读能力，不能保存本地规则包。";
    private static final String SAME_NAME = "改已有的包就用同一个名字保存；如果需要确认，请让用户确认，不要换名字。";
    private static final String NOT_IN_SCOPE = "这把 API Key 不能使用本地规则包 " + CONCH
            + "。请用全部权限的 Key，或在 conches 里点名这个包的 Key。";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path temp;

    private Path home;
    private CrabProc crab;
    private Site alpha;
    private Site bravo;
    private String allKey;

    @Test
    void sameBoundaryReplacesAndWidenedOverwriteNeedsConsoleConfirm() throws Exception {
        home = temp.resolve("crab");
        Files.createDirectories(home);
        Files.writeString(home.resolve("config.yml"), """
                server:
                  host: 127.0.0.1
                  port: 0
                report:
                  enabled: false
                """);
        try (Site first = new Site(ALPHA); Site second = new Site(BRAVO)) {
            alpha = first;
            bravo = second;
            crab = CrabProc.start(home);
            try {
                exercise();
            } finally {
                crab.close();
            }
        }
    }

    private void exercise() throws Exception {
        enableDevMode();
        allKey = createKey("{\"name\":\"caller\",\"all\":true}");
        McpClient agent = new McpClient(allKey);

        String v1 = hop("1.0.0", alpha.port(), "第一版");
        JsonNode created = save(agent, v1);
        assertThat(created.path("isError").asBoolean(false)).isFalse();
        JsonNode createdBody = created.path("structuredContent");
        assertThat(createdBody.path("ok").asBoolean()).as(created.toString()).isTrue();
        assertThat(createdBody.path("replaced").asBoolean()).isFalse();
        assertThat(createdBody.path("version").asText()).isEqualTo("1.0.0");
        assertThat(createdBody.has("backupId")).isFalse();
        assertThat(packageFile()).isEqualTo(v1.getBytes(StandardCharsets.UTF_8));

        JsonNode account = consoleOk("POST", "/console/api/conches/" + CONCH + "/accounts",
                "{\"name\":\"" + ACCOUNT + "\"}");
        assertThat(account.path("data").path("default").asBoolean()).isTrue();
        callPing();
        assertThat(alpha.logins()).isEqualTo(1);
        assertThat(alpha.pings()).isEqualTo(1);
        assertThat(alpha.lastCookie("/ping")).contains(ALPHA);
        assertThat(bravo.hits()).isEmpty();

        String v11 = hop("1.0.1", alpha.port(), "同站覆盖");
        JsonNode replaced = save(agent, v11);
        assertThat(replaced.path("isError").asBoolean(false)).isFalse();
        JsonNode replacedBody = replaced.path("structuredContent");
        assertThat(replacedBody.path("ok").asBoolean()).as(replaced.toString()).isTrue();
        assertThat(replacedBody.path("replaced").asBoolean()).isTrue();
        assertThat(replacedBody.path("version").asText()).isEqualTo("1.0.1");
        assertThat(replacedBody.path("previousVersion").asText()).isEqualTo("1.0.0");
        String backupId = replacedBody.path("backupId").asText();
        assertThat(backupId).isNotBlank();
        Path backup = home.resolve("local-conches").resolve(".history").resolve(CONCH).resolve(backupId + ".yaml");
        assertThat(backup).isRegularFile();
        assertThat(Files.readString(backup)).isEqualTo(v1);
        JsonNode history = history();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("id").asText()).isEqualTo(backupId);
        assertThat(history.get(0).path("version").asText()).isEqualTo("1.0.0");
        assertThat(pending()).isEmpty();
        int loginsAfterSame = alpha.logins();
        callPing();
        assertThat(alpha.logins()).isEqualTo(loginsAfterSame);
        assertThat(alpha.lastCookie("/ping")).contains(ALPHA);
        assertThat(bravo.hits()).isEmpty();

        JsonNode other = save(agent, otherPack());
        assertThat(other.path("structuredContent").path("ok").asBoolean()).as(other.toString()).isTrue();
        String namedKey = createKey("{\"name\":\"named\",\"conches\":[\"" + CONCH + "\"]}");
        String otherKey = createKey("{\"name\":\"other\",\"conches\":[\"" + OTHER + "\"]}");
        String readOnlyKey = createKey("{\"name\":\"reader\",\"all\":true,\"readOnly\":true}");
        String v12 = hop("1.0.2", alpha.port(), "点名覆盖");
        JsonNode denied = save(new McpClient(otherKey), v12);
        assertToolError(denied, "FORBIDDEN", NOT_IN_SCOPE);
        assertThat(packageFile()).isEqualTo(v11.getBytes(StandardCharsets.UTF_8));
        JsonNode readOnly = save(new McpClient(readOnlyKey), v12);
        assertToolError(readOnly, "FORBIDDEN", READ_ONLY_SAVE);
        JsonNode confirmArg = saveConfirm(agent, v12);
        assertToolError(confirmArg, "INVALID_INPUT", CONFIRM_PARAM);
        assertThat(packageFile()).isEqualTo(v11.getBytes(StandardCharsets.UTF_8));

        JsonNode named = save(new McpClient(namedKey), v12);
        assertThat(named.path("isError").asBoolean(false)).isFalse();
        assertThat(named.path("structuredContent").path("replaced").asBoolean()).isTrue();
        assertThat(named.path("structuredContent").path("previousVersion").asText()).isEqualTo("1.0.1");
        assertThat(loadedVersion()).isEqualTo("1.0.2");
        callPing();
        assertThat(alpha.logins()).isEqualTo(loginsAfterSame);
        assertThat(alpha.lastCookie("/ping")).contains(ALPHA);

        String widened = hop("1.1.0", bravo.port(), "换到新站");
        JsonNode held = save(agent, widened);
        assertToolError(held, "CONFIRM_REQUIRED", null);
        assertThat(held.path("structuredContent").path("error").path("message").asText())
                .contains(bravo.origin())
                .contains("不要换包名重新保存")
                .doesNotContain("换一个新的包名");
        assertThat(held.path("structuredContent").path("error").path("hint").asText()).isEqualTo(SAME_NAME);
        assertThat(packageFile()).isEqualTo(v12.getBytes(StandardCharsets.UTF_8));
        JsonNode staged = onlyPending();
        assertThat(staged.path("conch").asText()).isEqualTo(CONCH);
        assertThat(staged.path("sha256").asText()).isEqualTo(sha256(widened.getBytes(StandardCharsets.UTF_8)));
        assertThat(staged.path("baseSha").asText()).isEqualTo(sha256(packageFile()));
        assertThat(staged.path("fromVersion").asText()).isEqualTo("1.0.2");
        assertThat(staged.path("toVersion").asText()).isEqualTo("1.1.0");
        assertThat(staged.path("keyName").asText()).isEqualTo("caller");
        assertThat(staged.path("undetermined").asBoolean()).isFalse();
        assertThat(texts(staged.path("addedDestinations"))).containsExactly(bravo.origin());
        Instant submitted = Instant.parse(staged.path("submittedAt").asText());
        assertThat(Instant.parse(staged.path("expiresAt").asText())).isEqualTo(submitted.plus(LocalConches.PENDING_TTL));
        byte[] beforeRestart = packageFile();

        restart();
        agent = new McpClient(allKey);
        assertThat(pending()).isEmpty();
        assertThat(packageFile()).isEqualTo(beforeRestart);
        assertThat(loadedVersion()).isEqualTo("1.0.2");
        int loginsAfterRestart = alpha.logins();
        callPing();
        assertThat(alpha.logins()).as("重启不清同边界会话").isEqualTo(loginsAfterRestart);
        assertThat(alpha.lastCookie("/ping")).contains(ALPHA);

        staged = stage(agent, widened);
        String liveSha = staged.path("sha256").asText();
        HttpResponse<String> wrongSha = console("POST", "/console/api/local-conches/" + CONCH + "/pending/confirm",
                "{\"sha256\":\"" + "0".repeat(64) + "\"}");
        assertThat(wrongSha.statusCode()).isEqualTo(409);
        assertThat(json(wrongSha).path("error").path("code").asText()).isEqualTo("CONFLICT");
        assertThat(json(wrongSha).path("error").path("message").asText()).isEqualTo(LocalConchException.PENDING_CHANGED);
        assertThat(onlyPending().path("sha256").asText()).isEqualTo(liveSha);
        assertThat(packageFile()).isEqualTo(v12.getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> rejected = console("DELETE", "/console/api/local-conches/" + CONCH + "/pending", null);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(json(rejected).path("data").path("rejected").asBoolean()).isTrue();
        assertThat(pending()).isEmpty();
        HttpResponse<String> rejectedAgain = console("DELETE", "/console/api/local-conches/" + CONCH + "/pending", null);
        assertThat(rejectedAgain.statusCode()).isEqualTo(404);
        assertThat(json(rejectedAgain).path("error").path("message").asText()).isEqualTo(LocalConchException.NO_PENDING);
        assertThat(packageFile()).isEqualTo(v12.getBytes(StandardCharsets.UTF_8));

        staged = stage(agent, widened);
        byte[] intact = packageFile();
        int historyBeforeStale = history().size();
        int loginsBeforeStale = alpha.logins();
        Files.write(packagePath(), (new String(intact, StandardCharsets.UTF_8) + "\n# stale\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> stale = console("POST", "/console/api/local-conches/" + CONCH + "/pending/confirm",
                "{\"sha256\":\"" + staged.path("sha256").asText() + "\"}");
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).path("error").path("message").asText()).isEqualTo(LocalConchException.STALE);
        assertThat(pending()).isEmpty();
        Files.write(packagePath(), intact);
        assertThat(history()).hasSize(historyBeforeStale);
        callPing();
        assertThat(alpha.logins()).isEqualTo(loginsBeforeStale);
        assertThat(alpha.lastCookie("/ping")).contains(ALPHA);
        assertThat(bravo.hits()).isEmpty();

        staged = stage(agent, widened);
        int alphaHits = alpha.hits().size();
        HttpResponse<String> confirmed = console("POST", "/console/api/local-conches/" + CONCH + "/pending/confirm",
                "{\"sha256\":\"" + staged.path("sha256").asText() + "\"}");
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        JsonNode applied = json(confirmed).path("data");
        assertThat(applied.path("version").asText()).isEqualTo("1.1.0");
        assertThat(applied.path("replaced").asBoolean()).isTrue();
        assertThat(applied.path("previousVersion").asText()).isEqualTo("1.0.2");
        assertThat(applied.path("backupId").asText()).isNotBlank();
        assertThat(pending()).isEmpty();
        assertThat(loadedVersion()).isEqualTo("1.1.0");
        assertThat(new String(packageFile(), StandardCharsets.UTF_8)).contains("127.0.0.1:" + bravo.port());
        callPing();
        assertThat(alpha.hits()).hasSize(alphaHits);
        assertThat(bravo.logins()).isEqualTo(1);
        assertThat(bravo.pings()).isEqualTo(1);
        assertThat(cookiesSince(bravo, 0)).allSatisfy(cookie -> assertThat(cookie).doesNotContain(ALPHA));
        assertThat(bravo.lastCookie("/ping")).contains(BRAVO);
        assertThat(bravo.lastCookie("/login")).doesNotContain(ALPHA);

        assertThat(versions(history())).containsExactly("1.0.2", "1.0.1");
        String restoreA = historyId("1.0.1");
        HttpResponse<String> restored = console(
                "POST", "/console/api/local-conches/" + CONCH + "/history/" + restoreA + "/restore", "");
        assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
        assertThat(json(restored).path("data").path("version").asText()).isEqualTo("1.0.1");
        assertThat(json(restored).path("data").path("replaced").asBoolean()).isTrue();
        assertThat(loadedVersion()).isEqualTo("1.0.1");
        int bravoHits = bravo.hits().size();
        callPing();
        assertThat(bravo.hits()).hasSize(bravoHits);
        assertThat(alpha.logins()).isEqualTo(loginsBeforeStale + 1);
        assertThat(alpha.lastCookie("/ping")).contains(ALPHA);
        assertThat(alpha.lastCookie("/login")).doesNotContain(BRAVO);
        // 回退先备份当前的 1.1.0，同一把锁里只留最近 2 份，刚取回的 1.0.1 不再留在历史里。
        assertThat(versions(history())).containsExactly("1.1.0", "1.0.2");
        String restoreB = historyId("1.1.0");

        crab.close();
        CliResult listed = cli("local", "history", CONCH);
        assertThat(listed.code()).as(listed.err()).isZero();
        assertThat(listed.out()).contains(restoreB + " 1.1.0").doesNotContain(restoreA + " 1.0.1");
        assertThat(listed.out()).contains("1.0.2");
        CliResult cliRestored = cli("local", "restore", CONCH, restoreB);
        assertThat(cliRestored.code()).as(cliRestored.err()).isZero();
        assertThat(cliRestored.out()).isEqualTo(CONCH + " 1.1.0 已回退\n");
        assertThat(Files.readString(packagePath())).contains("version: 1.1.0").contains("127.0.0.1:" + bravo.port());

        crab = CrabProc.start(home);
        enableDevMode();
        assertThat(loadedVersion()).isEqualTo("1.1.0");
        int alphaAfterCli = alpha.hits().size();
        callPing();
        assertThat(alpha.hits()).hasSize(alphaAfterCli);
        assertThat(bravo.logins()).isEqualTo(2);
        assertThat(cookiesSince(bravo, bravoHits)).allSatisfy(cookie -> assertThat(cookie).doesNotContain(ALPHA));
        assertThat(bravo.lastCookie("/ping")).contains(BRAVO);
    }

    private JsonNode stage(McpClient agent, String yaml) throws Exception {
        JsonNode held = save(agent, yaml);
        assertToolError(held, "CONFIRM_REQUIRED", null);
        return onlyPending();
    }

    private void restart() throws Exception {
        crab.close();
        crab = CrabProc.start(home);
        enableDevMode();
    }

    private void enableDevMode() throws Exception {
        JsonNode debug = consoleOk("POST", "/console/api/debug", "{\"enabled\":true}");
        assertThat(debug.path("data").path("enabled").asBoolean()).isTrue();
    }

    private String createKey(String body) throws Exception {
        JsonNode created = consoleOk("POST", "/console/api/apikeys", body);
        String plaintext = created.path("data").path("plaintext").asText();
        assertThat(plaintext).startsWith("hk_");
        return plaintext;
    }

    private JsonNode save(McpClient client, String yaml) throws Exception {
        return tool(client, "conch_save_local", Map.of("yaml", yaml));
    }

    private JsonNode saveConfirm(McpClient client, String yaml) throws Exception {
        return tool(client, "conch_save_local", Map.of("yaml", yaml, "confirm", true));
    }

    private JsonNode tool(McpClient client, String name, Map<String, Object> arguments) throws Exception {
        ObjectNode params = JSON.createObjectNode();
        params.put("name", name);
        params.set("arguments", JSON.valueToTree(arguments));
        JsonNode rpc = client.call("tools/call", params);
        assertThat(rpc.has("error")).as(rpc.toString()).isFalse();
        return rpc.path("result");
    }

    private static void assertToolError(JsonNode result, String code, String message) {
        assertThat(result.path("isError").asBoolean()).as(result.toString()).isTrue();
        JsonNode error = result.path("structuredContent").path("error");
        assertThat(error.path("code").asText()).isEqualTo(code);
        if (message != null) {
            assertThat(error.path("message").asText()).isEqualTo(message);
        }
    }

    private void callPing() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(crab.base + "/api/" + CONCH + "/ping"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + allKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(detail(response.body())).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.path("success").asBoolean()).as(detail(response.body())).isTrue();
        assertThat(body.path("data").path("ok").asBoolean()).isTrue();
    }

    private String loadedVersion() throws Exception {
        for (JsonNode row : consoleOk("GET", "/console/api/conches", null).path("data").path("conches")) {
            if (CONCH.equals(row.path("conch").asText())) {
                return row.path("version").asText();
            }
        }
        return null;
    }

    private static List<String> versions(JsonNode history) {
        ArrayList<String> versions = new ArrayList<>();
        for (JsonNode row : history) {
            versions.add(row.path("version").asText());
        }
        return versions;
    }

    private JsonNode history() throws Exception {
        return consoleOk("GET", "/console/api/local-conches/" + CONCH + "/history", null).path("data").path("history");
    }

    private String historyId(String version) throws Exception {
        String id = null;
        for (JsonNode row : history()) {
            if (version.equals(row.path("version").asText())) {
                id = row.path("id").asText();
                break;
            }
        }
        assertThat(id).as("history version " + version).isNotBlank();
        return id;
    }

    private JsonNode pending() throws Exception {
        return consoleOk("GET", "/console/api/local-conches/pending", null).path("data").path("pending");
    }

    private JsonNode onlyPending() throws Exception {
        JsonNode rows = pending();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private JsonNode consoleOk(String method, String path, String body) throws Exception {
        HttpResponse<String> response = console(method, path, body);
        assertThat(response.statusCode()).as(detail(response.body())).isEqualTo(200);
        return json(response);
    }

    private HttpResponse<String> console(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab.base + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Hermit-Console", "1");
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private CliResult cli(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaBin());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CrabMain.class.getName());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(home.toFile());
        prepareEnv(builder);
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        Process process = builder.start();
        process.getOutputStream().close();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outPump = pump(process.getInputStream(), out);
        Thread errPump = pump(process.getErrorStream(), err);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        outPump.join(2_000);
        errPump.join(2_000);
        assertThat(finished).as(text(err)).isTrue();
        return new CliResult(process.exitValue(), text(out), text(err));
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private void prepareEnv(ProcessBuilder builder) {
        builder.environment().put("HERMIT_HOME", home.toAbsolutePath().toString());
        builder.environment().remove("HERMIT_MASTER_KEY");
        builder.environment().remove("HERMIT_CONSOLE_PASSWORD");
        builder.environment().remove("HERMIT_API_KEY");
    }

    private Path packagePath() {
        return home.resolve("local-conches").resolve(CONCH + ".yaml");
    }

    private byte[] packageFile() throws Exception {
        return Files.readAllBytes(packagePath());
    }

    private static String sha256(byte[] raw) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
    }

    private static List<String> texts(JsonNode array) {
        ArrayList<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return values;
    }

    private static List<String> cookiesSince(Site site, int from) {
        ArrayList<String> cookies = new ArrayList<>();
        List<Hit> hits = site.hits();
        for (int i = from; i < hits.size(); i++) {
            cookies.add(hits.get(i).cookie());
        }
        return cookies;
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private String detail(String body) {
        String logs = crab == null ? "" : crab.logs();
        if (logs.length() > 4000) {
            logs = logs.substring(logs.length() - 4000);
        }
        return redact(body) + "\n" + logs;
    }

    private static String redact(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("hk_[A-Za-z0-9_\\-]+", "hk_[redacted]");
    }

    private static String hop(String version, int port, String note) {
        return """
                conch: site_hop
                version: %s
                driver: http
                min_crab_version: 1.3.0
                title: 站点跳转
                description: %s
                base_url: http://127.0.0.1:%d/
                auth:
                  steps:
                    - request:
                        method: GET
                        path: /login
                    - set:
                        marker: ready
                    - assert:
                        cookie_exists: sid
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 说明
                    method: POST
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: /ping
                      - assert:
                          status: 200
                    output:
                      ok: { type: boolean, description: 结果, value: true }
                    examples:
                      - input: {}
                        output: { ok: true }
                    notes: 注意
                    related: [ping]
                """.formatted(version, note, port);
    }

    private static String otherPack() {
        return """
                conch: other_pack
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 另一包
                description: 只用来限制 Key 范围。
                base_url: http://127.0.0.1:9/
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 不调用。
                    steps:
                      - set:
                          marker: ok
                    output:
                      marker: { type: string, description: 标记, value: ok }
                    examples:
                      - input: {}
                        output: { marker: ok }
                    notes: 范围
                    related: []
                """;
    }

    private final class McpClient {
        private final String apiKey;
        private String session;

        private McpClient(String apiKey) {
            this.apiKey = apiKey;
        }

        private JsonNode call(String method, JsonNode params) throws Exception {
            if (session == null) {
                JsonNode opened = post("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"hermit-it","version":"0"}}}
                        """);
                assertThat(opened.path("result").path("protocolVersion").asText()).isEqualTo("2025-11-25");
                assertThat(opened.path("result").path("capabilities").has("elicitation")).isFalse();
                post("""
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        """);
            }
            ObjectNode body = JSON.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", 2);
            body.put("method", method);
            if (params != null) {
                body.set("params", params);
            }
            return post(JSON.writeValueAsString(body));
        }

        private JsonNode post(String body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(crab.base + "/mcp"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream");
            if (session != null) {
                builder.header("Mcp-Session-Id", session);
            }
            HttpResponse<String> response = http.send(
                    builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
            assertThat(response.statusCode()).as(redact(response.body())).isIn(200, 202);
            if (response.body() == null || response.body().isBlank()) {
                return JSON.createObjectNode();
            }
            return JSON.readTree(response.body());
        }
    }

    private record Hit(String path, String cookie) {
    }

    private static final class Site implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "mock-upstream");
            thread.setDaemon(true);
            return thread;
        });
        private final List<Hit> hits = new CopyOnWriteArrayList<>();

        private Site(String cookieValue) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                hits.add(new Hit(path, cookie == null ? "" : cookie));
                if ("/login".equals(path)) {
                    exchange.getResponseHeaders().add("Set-Cookie", "sid=" + cookieValue + "; Path=/");
                } else if (!"/ping".equals(path)) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private String origin() {
            return "http://127.0.0.1:" + port();
        }

        private List<Hit> hits() {
            return List.copyOf(hits);
        }

        private int logins() {
            return count("/login");
        }

        private int pings() {
            return count("/ping");
        }

        private int count(String path) {
            int count = 0;
            for (Hit hit : hits) {
                if (path.equals(hit.path())) {
                    count++;
                }
            }
            return count;
        }

        private String lastCookie(String path) {
            String cookie = null;
            for (Hit hit : hits) {
                if (path.equals(hit.path())) {
                    cookie = hit.cookie();
                }
            }
            return cookie == null ? "" : cookie;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private record CliResult(int code, String out, String err) {
    }

    private static final class CrabProc implements AutoCloseable {
        private final Process process;
        private final StringBuilder out;
        private final StringBuilder err;
        private final String base;
        private boolean closed;

        private CrabProc(Process process, StringBuilder out, StringBuilder err, String base) {
            this.process = process;
            this.out = out;
            this.err = err;
            this.base = base;
        }

        private static CrabProc start(Path home) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(
                    javaBin(),
                    "-Dfile.encoding=UTF-8",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CrabMain.class.getName(),
                    "serve");
            builder.directory(home.toFile());
            builder.environment().put("HERMIT_HOME", home.toAbsolutePath().toString());
            builder.environment().remove("HERMIT_MASTER_KEY");
            builder.environment().remove("HERMIT_CONSOLE_PASSWORD");
            builder.environment().remove("HERMIT_API_KEY");
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
            Process process = builder.start();
            process.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            pump(process.getInputStream(), out);
            pump(process.getErrorStream(), err);
            try {
                String line = awaitListening(process, out, err);
                String base = "http://" + line.substring("listening on ".length()).trim();
                return new CrabProc(process, out, err, base);
            } catch (Throwable ex) {
                process.destroyForcibly();
                throw ex;
            }
        }

        private static String awaitListening(Process process, StringBuilder out, StringBuilder err) throws InterruptedException {
            long end = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < end) {
                synchronized (out) {
                    for (String line : out.toString().split("\n", -1)) {
                        if (line.startsWith("listening on 127.0.0.1:")) {
                            return line;
                        }
                    }
                    if (!process.isAlive()) {
                        throw new AssertionError("crab exited " + process.exitValue() + "\n" + text(out) + text(err));
                    }
                    out.wait(200);
                }
            }
            throw new AssertionError("crab did not listen\n" + text(out) + text(err));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        }

        private String logs() {
            return redact(text(out) + text(err));
        }
    }

    private static Thread pump(InputStream input, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                        sink.notifyAll();
                    }
                }
            } catch (Exception ex) {
                synchronized (sink) {
                    sink.append(ex.getClass().getName()).append('\n');
                    sink.notifyAll();
                }
            }
        }, "crab-log");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String text(StringBuilder builder) {
        synchronized (builder) {
            return builder.toString();
        }
    }
}
