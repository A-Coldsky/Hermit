package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.Step;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.template.FilterRegistry;
import dev.hermit.common.template.TemplateEngine;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.files.FileStage;
import dev.hermit.crab.files.FileStageSettings;
import dev.hermit.crab.files.StagedFile;
import dev.hermit.crab.store.Origin;

class FileStepsTest {
    @Test
    void syntaxStaysAsciiAndValuesFollowTheStepEncoding() throws Exception {
        FileSteps.Multipart utf16 = FileSteps.multipart(Map.of("note", "你好"), StandardCharsets.UTF_16BE);
        String boundary = utf16.contentType().substring(utf16.contentType().indexOf("boundary=") + 9);
        byte[] utf16Bytes = write(utf16.entity());
        assertThat(utf16.entity().getContentLength()).isEqualTo(utf16Bytes.length);
        assertThat(utf16.entity().isChunked()).isFalse();
        assertThat(utf16.entity().isRepeatable()).isTrue();
        String raw = new String(utf16Bytes, StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("--" + boundary + "\r\nContent-Disposition: form-data; name=\"");
        assertThat(raw).endsWith("\r\n--" + boundary + "--\r\n");
        assertThat(utf16Bytes).containsSequence("你好".getBytes(StandardCharsets.UTF_16BE));

        FileSteps.Multipart gbk = FileSteps.multipart(Map.of("note", "你好"), Charset.forName("GBK"));
        byte[] gbkBytes = write(gbk.entity());
        assertThat(gbk.entity().getContentLength()).isEqualTo(gbkBytes.length);
        assertThat(gbkBytes).containsSequence("你好".getBytes(Charset.forName("GBK")));
    }

    @Test
    void streamsStagedFilesWithExactLengthAndRepeatableReads(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        byte[] payload = new byte[150_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        byte[] marker = "PAYLOAD-INNER-MARKER-77".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, payload, 70_000, marker.length);
        StagedFile big = stage.store(payload, "report.bin", "application/octet-stream", "k1");
        StagedFile empty = stage.store(new byte[0], "empty.txt", "text/plain", "k1");

        FileScope previous = FileScope.begin(stage, "k1", "http://127.0.0.1:8080");
        try {
            LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
            spec.put("note", "hello");
            spec.put("file", Map.of("file", big.fileId()));
            spec.put("zero", Map.of("file", empty.fileId()));

            FileSteps.Multipart part = FileSteps.multipart(spec, StandardCharsets.UTF_8);
            HttpEntity entity = part.entity();
            assertThat(entity.isChunked()).isFalse();
            assertThat(entity.isRepeatable()).isTrue();
            assertThat(entity.isStreaming()).isFalse();

            byte[] first = write(entity);
            byte[] second = write(entity);
            byte[] streamed;
            try (InputStream in = entity.getContent()) {
                streamed = in.readAllBytes();
            }

            assertThat(entity.getContentLength()).isEqualTo(first.length);
            assertThat(second).isEqualTo(first);
            assertThat(streamed).isEqualTo(first);
            assertThat(first).containsSequence(payload);
            assertThat(part.wire())
                    .contains("report.bin")
                    .contains(String.valueOf(payload.length))
                    .contains(big.sha256())
                    .doesNotContain("PAYLOAD-INNER-MARKER-77");
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void sanitizesControlCharsAndQuotesInMultipartHeaders(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        StagedFile staged = stage.store("abc".getBytes(StandardCharsets.UTF_8), "orig.txt", "text/plain", "k1");

        FileScope previous = FileScope.begin(stage, "k1", "http://127.0.0.1:8080");
        try {
            LinkedHashMap<String, Object> filePart = new LinkedHashMap<>();
            filePart.put("file", staged.fileId());
            filePart.put("filename", "a\r\n\0\\b\"c\u007f.txt");
            filePart.put("content_type", "text/plain\r\n\0\t\u007f; charset=utf-8");
            Map<String, Object> spec = Map.of("f\r\n\t\0\"\\x\u007f", filePart);

            FileSteps.Multipart part = FileSteps.multipart(spec, StandardCharsets.UTF_8);
            String raw = new String(write(part.entity()), StandardCharsets.UTF_8);
            assertThat(raw).contains("Content-Disposition: form-data; name=\"f%22%5Cx\"; filename=\"a%5Cb%22c.txt\"\r\n");
            assertThat(raw).contains("Content-Type: text/plain; charset=utf-8\r\n\r\n");
            assertThat(raw).doesNotContain("\0").doesNotContain("\u007f");
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void unencodableTextFailsInsteadOfBecomingQuestionMarks() {
        assertThatThrownBy(() -> FileSteps.multipart(Map.of("note", "😀"), Charset.forName("GBK")))
                .isInstanceOf(RuleProblem.class)
                .hasMessageContaining("multipart.note 含有编码无法表示的字符");
    }

    @Test
    void readsFileNamesFromContentDisposition() {
        assertThat(FileSteps.dispositionName("attachment; filename*=UTF-8''%E6%8A%A5%E8%A1%A8.pdf")).isEqualTo("报表.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename=\"a b.pdf\"")).isEqualTo("a b.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename=a.pdf")).isEqualTo("a.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename*=UTF-8''%20%20; filename=\"fallback.pdf\""))
                .isEqualTo("fallback.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename*=UTF-8''; filename=bare.pdf"))
                .isEqualTo("bare.pdf");
        assertThat(FileSteps.dispositionName("inline")).isNull();
    }

    @Test
    void saveDiscardsSpoolOnEarlyExitOrUnexpectedFailure(@TempDir Path temp) {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());

        FileStage.Spool missingScopeSpool = stage.spool();
        missingScopeSpool.write(new byte[32], 0, 32);
        HttpResult ok = new HttpResult(
                200, "http://127.0.0.1/doc.pdf", "GET", "/doc.pdf", Map.of(), "", null, false,
                new byte[0], List.of(), null, missingScopeSpool);
        assertThatThrownBy(() -> FileSteps.save(Map.of("download", true), ok))
                .isInstanceOf(RuleProblem.class)
                .hasMessage(FileSteps.NO_SCOPE);
        assertThat(stage.usage()).containsEntry("bytes", 0L).containsEntry("files", 0);

        FileStage.Spool non2xxSpool = stage.spool();
        non2xxSpool.write(new byte[48], 0, 48);
        HttpResult notFound = new HttpResult(
                404, "http://127.0.0.1/doc.pdf", "GET", "/doc.pdf", Map.of(), "", null, false,
                new byte[0], List.of(), null, non2xxSpool);
        assertThat(FileSteps.save(Map.of("download", true), notFound)).isNull();
        assertThat(stage.usage()).containsEntry("bytes", 0L).containsEntry("files", 0);

        // 在解析文件名阶段抛错时也必须回收 spool，不能泄漏容量。
        FileStage.Spool brokenHeaderSpool = stage.spool();
        brokenHeaderSpool.write(new byte[64], 0, 64);
        HttpResult brokenHeaders = new HttpResult(
                200, "http://127.0.0.1/doc.pdf", "GET", "/doc.pdf", null, "", null, false,
                new byte[0], List.of(), null, brokenHeaderSpool);
        assertThatThrownBy(() -> FileSteps.save(Map.of("download", true), brokenHeaders))
                .isInstanceOf(NullPointerException.class);
        assertThat(stage.usage()).containsEntry("bytes", 0L).containsEntry("files", 0);
    }

    @Test
    void multipartRedirectToGetDropsRequestEntityAndWireBody(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        StagedFile staged = stage.store("upload-payload-body".getBytes(StandardCharsets.UTF_8), "doc.txt", "text/plain", "k1");

        FileScope previous = FileScope.begin(stage, "k1", "http://127.0.0.1:8080");
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ScriptedLoopback.response(302, null, new byte[0], "Location: /done"));
            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));

            SessionKey key = new SessionKey(Origin.LOCAL, "upload_conch", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
                spec.put("method", "POST");
                spec.put("path", "/upload");
                spec.put("follow_redirects", true);
                spec.put("multipart", Map.of("file", Map.of("file", staged.fileId()), "note", "hi"));

                HttpResult result = HttpCalls.execute(
                        spec,
                        sessions.client(key),
                        () -> {
                        },
                        URI.create(loop.base()),
                        null,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

                assertThat(result.status()).isEqualTo(200);
                assertThat(result.method()).isEqualTo("GET");
                assertThat(result.wire()).containsEntry("method", "GET");
                assertThat(result.wire().get("body")).isNull();

                List<ScriptedLoopback.Hit> hits = loop.hits();
                assertThat(hits).hasSize(2);
                assertThat(hits.get(0).method()).isEqualTo("POST");
                assertThat(hits.get(0).header("content-type")).startsWith("multipart/form-data; boundary=");
                assertThat(hits.get(0).header("content-length")).isNotNull();
                assertThat(Integer.parseInt(hits.get(0).header("content-length"))).isEqualTo(hits.get(0).body().length);
                assertThat(new String(hits.get(0).body(), StandardCharsets.UTF_8)).contains("upload-payload-body");

                assertThat(hits.get(1).method()).isEqualTo("GET");
                assertThat(hits.get(1).target()).isEqualTo("/done");
                assertThat(hits.get(1).body()).isEmpty();
                assertThat(hits.get(1).header("content-type")).isNull();
            } finally {
                sessions.unlock(key);
            }
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void downloaded204WithoutSpoolReturnsNullAndSkipsBodyExpiryRules(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        FileScope previous = FileScope.begin(stage, "k1", "");
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n");
            SessionKey key = new SessionKey(Origin.LOCAL, "dl_204", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                HttpResult res = HttpCalls.execute(
                        Map.of("method", "GET", "path", "/empty", "download", true),
                        sessions.client(key),
                        () -> {
                        },
                        URI.create(loop.base()),
                        null,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                assertThat(res.status()).isEqualTo(204);
                assertThat(res.downloaded()).isTrue();
                assertThat(res.spool()).isNull();
                assertThat(FileSteps.save(Map.of("download", true), res)).isNull();
                assertThat(stage.list()).isEmpty();
                // downloaded=true 的 204 不应按空文本正文误触发 not_contains
                int matched = SessionExpiry.matchExpired(
                        List.of(Map.of("status", 204, "not_contains", "MUST_EXIST")),
                        res,
                        new BasicCookieStore(),
                        URI.create(loop.base()),
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                assertThat(matched).isEqualTo(-1);
            } finally {
                sessions.unlock(key);
            }
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void downloadStatusRetryDiscardsIntermediateSpool(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        FileScope previous = FileScope.begin(stage, "k1", "");
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            byte[] pendingBody = "pending-payload-must-be-discarded".getBytes(StandardCharsets.UTF_8);
            byte[] finalBody = "final-pdf".getBytes(StandardCharsets.UTF_8);
            loop.reply(ScriptedLoopback.response(202, "application/octet-stream", pendingBody));
            loop.reply(ScriptedLoopback.response(200, "application/pdf", finalBody));

            SessionKey key = new SessionKey(Origin.LOCAL, "dl_retry", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                FilterRegistry filters = FilterRegistry.defaults();
                StepRunner runner = new StepRunner(new TemplateEngine(filters), filters);
                LinkedHashMap<String, Object> req = new LinkedHashMap<>();
                req.put("method", "GET");
                req.put("path", "/export");
                req.put("download", true);
                req.put("retry", Map.of("times", 1, "delay_ms", 0, "on", List.of(202)));
                LinkedHashMap<String, Object> vars = new LinkedHashMap<>();

                Outcome outcome = runner.run(
                        new Step("request", req),
                        vars,
                        null,
                        sessions.client(key),
                        () -> {
                        },
                        sessions.cookies(key),
                        URI.create(loop.base()),
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                assertThat(outcome.status()).isEqualTo(200);
                assertThat(loop.hits()).hasSize(2);
                assertThat(stage.list()).hasSize(1);
                assertThat(stage.usage()).containsEntry("files", 1).containsEntry("bytes", (long) finalBody.length);
            } finally {
                sessions.unlock(key);
            }
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void failedAndReplayedDownloadsRollbackStagedFilesAndOfficialLogsScrubTokens(@TempDir Path temp) throws Exception {
        AtomicInteger docHits = new AtomicInteger();
        AtomicInteger checkHits = new AtomicInteger();
        AtomicReference<String> boomPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/login".equals(path)) {
                exchange.getResponseHeaders().add("Set-Cookie", "SID=s1; Path=/");
                reply(exchange, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ("/doc.pdf".equals(path)) {
                int n = docHits.incrementAndGet();
                byte[] body = ("pdf-" + "x".repeat(n * 10)).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"doc.pdf\"");
                reply(exchange, 200, "application/pdf", body);
                return;
            }
            if ("/check".equals(path)) {
                if (checkHits.incrementAndGet() == 1) {
                    reply(exchange, 401, "text/plain", "expired".getBytes(StandardCharsets.UTF_8));
                } else {
                    reply(exchange, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if (path.startsWith("/boom")) {
                boomPath.set(path);
                reply(exchange, 500, "text/plain", ("err:" + path).getBytes(StandardCharsets.UTF_8));
                return;
            }
            reply(exchange, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String yaml = """
                conch: dl_guard
                version: 1.0.0
                driver: http
                min_crab_version: 1.9.0
                base_url: %s
                title: 下载保护
                description: 测试下载回滚与脱敏
                credentials: {}
                auth:
                  steps:
                    - request: {method: POST, path: /login}
                    - assert: {status: 200, cookie_exists: SID}
                actions:
                  fail_after_download:
                    title: 失败回滚
                    description: 下载后失败回滚
                    idempotent: true
                    steps:
                      - request: {method: GET, path: /doc.pdf, download: true}
                      - set: {saved_url: "{{response.file.url}}"}
                      - request: {method: GET, path: "/boom{{saved_url}}"}
                    output: {ok: {type: string, description: ok, value: ok}}
                    examples: [{input: {}, output: {ok: ok}}]
                    notes: 测试
                  ok_with_echo:
                    title: 成功保留返回值
                    description: 日志脱敏但返回值保留下载链接
                    idempotent: true
                    steps:
                      - request: {method: GET, path: /doc.pdf, download: true}
                      - set: {saved_file: "{{response.file}}", saved_url: "{{response.file.url}}"}
                      - request: {method: GET, path: "/echo{{saved_url}}"}
                    output: {file: {type: file, description: 文件, value: "{{saved_file}}"}}
                    examples: [{input: {}, output: {file: {fileId: "f1", name: "doc.pdf", size: 1, mime: "application/pdf", sha256: "0", url: "/api/-/files/x"}}}]
                    notes: 测试
                  download_then_expire:
                    title: 会话失效回滚重放
                    description: 会话失效时回滚首轮下载文件
                    idempotent: true
                    steps:
                      - request: {method: GET, path: /doc.pdf, download: true}
                      - set: {saved_file: "{{response.file}}"}
                      - request: {method: GET, path: /check}
                    output: {file: {type: file, description: 文件, value: "{{saved_file}}"}}
                    examples: [{input: {}, output: {file: {fileId: "f1", name: "doc.pdf", size: 1, mime: "application/pdf", sha256: "0", url: "/api/-/files/x"}}}]
                    notes: 测试
                """.formatted(base);
        ConchDoc doc = ConchParser.parse(yaml);
        assertThat(ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT)).errors()).isEmpty();
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        AccountContext account = new EngineTestSupport.MapAccount(Map.of(), Map.of());
        try (SessionManager sessions = new SessionManager()) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(doc, Origin.OFFICIAL);
            RuleEngine engine = new RuleEngine(registry, sessions);

            // 1. 下载后后续步骤 500 失败：已暂存文件必须回滚删除，且正式包 StepLog.path 和 ExecutionError.message 均脱敏令牌
            FileScope prev = FileScope.begin(stage, "k1", "");
            try {
                ExecutionResult failed = engine.execute(EngineTestSupport.request(
                        Origin.OFFICIAL, "dl_guard", "fail_after_download", "alice", Map.of(), account));
                assertThat(failed.success()).isFalse();
                assertThat(failed.error().code()).isEqualTo(ErrorCode.UPSTREAM_ERROR);
                assertThat(stage.list()).isEmpty();
                assertThat(stage.usage()).containsEntry("files", 0).containsEntry("bytes", 0L);

                String rawBoomPath = boomPath.get();
                assertThat(rawBoomPath).startsWith("/boom/api/-/files/");
                String leakedToken = rawBoomPath.substring("/boom/api/-/files/".length());
                assertThat(failed.stepLogs().get(2).path()).isEqualTo("/boom/api/-/files/***");
                assertThat(failed.error().message()).contains("/boom/api/-/files/***").doesNotContain(leakedToken);
                assertThat(failed.error().summary()).contains("/boom/api/-/files/***").doesNotContain(leakedToken);
            } finally {
                FileScope.restore(prev);
            }

            // 2. 下载成功时：返回给调用方的 data 仍包含真实下载链接，而 StepLog.path 中令牌被脱敏
            prev = FileScope.begin(stage, "k1", "");
            try {
                ExecutionResult ok = engine.execute(EngineTestSupport.request(
                        Origin.OFFICIAL, "dl_guard", "ok_with_echo", "alice", Map.of(), account));
                assertThat(ok.success()).as(String.valueOf(ok.error())).isTrue();
                assertThat(stage.list()).hasSize(1);
                String realToken = stage.list().get(0).token();
                @SuppressWarnings("unchecked")
                Map<String, Object> fileView = (Map<String, Object>) ((Map<?, ?>) ok.data()).get("file");
                assertThat(fileView.get("url")).isEqualTo("/api/-/files/" + realToken);
                assertThat(ok.stepLogs().get(2).path()).isEqualTo("/echo/api/-/files/***");
                stage.delete(stage.list().get(0).fileId());
            } finally {
                FileScope.restore(prev);
            }

            // 3. 首轮下载后因 401 触发 SessionLost 重放：首轮暂存文件回滚，最终只留重放成功的那 1 份
            prev = FileScope.begin(stage, "k1", "");
            try {
                ExecutionResult replayed = engine.execute(EngineTestSupport.request(
                        Origin.OFFICIAL, "dl_guard", "download_then_expire", "alice", Map.of(), account));
                assertThat(replayed.success()).as(String.valueOf(replayed.error())).isTrue();
                assertThat(stage.list()).hasSize(1);
                assertThat(stage.list().get(0).size())
                        .isEqualTo(("pdf-" + "x".repeat(docHits.get() * 10)).getBytes(StandardCharsets.UTF_8).length);
            } finally {
                FileScope.restore(prev);
            }
        } finally {
            server.stop(0);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static byte[] write(HttpEntity entity) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        entity.writeTo(out);
        return out.toByteArray();
    }
}

