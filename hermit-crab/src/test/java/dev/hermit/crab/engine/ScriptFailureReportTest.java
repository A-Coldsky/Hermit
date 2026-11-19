package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.store.Origin;

/**
 * 单引号 JS 字面量进脚本后的失败报告。解析器只在测试里，按报告里的库重建。
 */
@Timeout(60)
class ScriptFailureReportTest {

    private static final String BODY = """
            {'code':'S_OK','var':[{'fid':1,'name':'收件箱','flags':{'unread':2}}],count:3,"note":"ok"}""";

    private static final String TICKET = "session-token-zz";

    /** 顶层不是 `{` 时抛 `bad object at`。单引号、双引号、未加引号的键、嵌套对象和数组都能读。 */
    private static final String JSPARSE = """
            function parseJs(text) {
              var s = String(text);
              var i = 0;
              skip();
              if (s.charAt(i) !== '{') throw new Error('bad object at ' + i);
              return parseObject();
              function skip() {
                while (i < s.length) {
                  var c = s.charAt(i);
                  if (c === ' ' || c === '\\n' || c === '\\r' || c === '\\t') i++;
                  else break;
                }
              }
              function parseObject() {
                if (s.charAt(i) !== '{') throw new Error('bad object at ' + i);
                i++;
                var obj = {};
                skip();
                if (s.charAt(i) === '}') { i++; return obj; }
                while (i < s.length) {
                  skip();
                  var key = parseKey();
                  skip();
                  if (s.charAt(i) !== ':') throw new Error('bad object at ' + i);
                  i++;
                  obj[key] = parseValue();
                  skip();
                  if (s.charAt(i) === ',') { i++; continue; }
                  if (s.charAt(i) === '}') { i++; return obj; }
                  throw new Error('bad object at ' + i);
                }
                throw new Error('bad object at ' + i);
              }
              function parseArray() {
                if (s.charAt(i) !== '[') throw new Error('bad array at ' + i);
                i++;
                var arr = [];
                skip();
                if (s.charAt(i) === ']') { i++; return arr; }
                while (i < s.length) {
                  arr.push(parseValue());
                  skip();
                  if (s.charAt(i) === ',') { i++; continue; }
                  if (s.charAt(i) === ']') { i++; return arr; }
                  throw new Error('bad array at ' + i);
                }
                throw new Error('bad array at ' + i);
              }
              function parseValue() {
                skip();
                var c = s.charAt(i);
                if (c === '{') return parseObject();
                if (c === '[') return parseArray();
                if (c === '"' || c === "'") return parseString();
                if (c === '-' || (c >= '0' && c <= '9')) return parseNumber();
                if (s.substring(i, i + 4) === 'true') { i += 4; return true; }
                if (s.substring(i, i + 5) === 'false') { i += 5; return false; }
                if (s.substring(i, i + 4) === 'null') { i += 4; return null; }
                throw new Error('bad value at ' + i);
              }
              function parseKey() {
                skip();
                var c = s.charAt(i);
                if (c === '"' || c === "'") return parseString();
                var start = i;
                while (i < s.length) {
                  var k = s.charAt(i);
                  var word = (k >= 'a' && k <= 'z') || (k >= 'A' && k <= 'Z') || (k >= '0' && k <= '9') || k === '_' || k === '$';
                  if (!word) break;
                  i++;
                }
                if (i === start) throw new Error('bad key at ' + i);
                return s.substring(start, i);
              }
              function parseString() {
                var q = s.charAt(i);
                i++;
                var out = '';
                while (i < s.length) {
                  var c = s.charAt(i);
                  if (c === q) { i++; return out; }
                  out += c;
                  i++;
                }
                throw new Error('bad string at ' + i);
              }
              function parseNumber() {
                var start = i;
                if (s.charAt(i) === '-') i++;
                while (i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                if (i < s.length && s.charAt(i) === '.') {
                  i++;
                  while (i < s.length && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                }
                return Number(s.substring(start, i));
              }
            }
            """;

    private static final String PAD = """
            function pad() {
              return 1;
            }
            """;

    private static final int BAD_OBJECT_LINE = lineOf(JSPARSE, "throw new Error('bad object at '");

    @Test
    void responseObjectIsNotTheBodyAndReservedPropertyParses() throws Exception {
        HttpServer server = server();
        try {
            ExecutionResult result = run(conch(library("jsparse", JSPARSE), """
                    - request:
                        method: POST
                        path: /s
                        query:
                          func: mbox:getAllFolders
                    - assert:
                        status: 200
                    - script:
                        use: [jsparse]
                        code: |
                          var text = String(vars.response);
                          var o = parseJs(String(vars.response.body));
                          var folders = o.var;
                          if (!folders || !folders.length) {
                            throw new Error('未解析到文件夹列表');
                          }
                          return {
                            text: text,
                            body: String(vars.response.body),
                            status: vars.response.status,
                            name: folders[0].name,
                            fid: folders[0].fid,
                            unread: folders[0].flags.unread,
                            count: o.count,
                            note: o.note,
                            code: o.code,
                            literal: '{{not_a_template}}'
                          };
                        into: value
                    """), base(server), null);
            assertThat(result.success()).as(error(result)).isTrue();
            Map<String, Object> value = map(map(result.data()).get("value"));
            assertThat(value.get("text")).isEqualTo("[object Object]");
            assertThat(value.get("body")).isEqualTo(BODY.stripIndent().strip());
            assertThat(((Number) value.get("status")).longValue()).isEqualTo(200L);
            assertThat(value.get("name")).isEqualTo("收件箱");
            assertThat(((Number) value.get("fid")).longValue()).isEqualTo(1L);
            assertThat(((Number) value.get("unread")).longValue()).isEqualTo(2L);
            assertThat(((Number) value.get("count")).longValue()).isEqualTo(3L);
            assertThat(value.get("note")).isEqualTo("ok");
            assertThat(value.get("code")).isEqualTo("S_OK");
            assertThat(value.get("literal")).isEqualTo("{{not_a_template}}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void stringifyingTheResponseFailsInsideTheLibrary() throws Exception {
        HttpServer server = server();
        MemoryTrace trace = new MemoryTrace();
        try {
            ExecutionResult result = run(conch(library("jsparse", JSPARSE), """
                    - request:
                        method: POST
                        path: /s
                        query:
                          func: mbox:getAllFolders
                    - assert:
                        status: 200
                    - script:
                        use: [jsparse]
                        code: |
                          var o = parseJs(String(vars.response));
                          var folders = o.var;
                          if (!folders || !folders.length) {
                            throw new Error('未解析到文件夹列表');
                          }
                          return folders[0].name;
                        into: value
                    """), base(server), trace);
            String expected = "脚本库 jsparse 第 " + BAD_OBJECT_LINE + " 行：Error: bad object at 0";
            assertThat(result.success()).isFalse();
            assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().failedStepIndex()).isEqualTo(2);
            assertThat(result.error().failedStepType()).isEqualTo("script");
            assertThat(result.error().message()).isEqualTo(expected);
            assertThat(result.error().summary()).isEqualTo(expected);
            assertThat(result.error().message())
                    .doesNotContain("收件箱")
                    .doesNotContain("S_OK")
                    .doesNotContain("[object Object]")
                    .doesNotContain("未解析到文件夹列表")
                    .doesNotContain("org.mozilla")
                    .doesNotContain("java.lang")
                    .doesNotContain("at script")
                    .doesNotContain("JavaScriptException")
                    .doesNotContain("Rhino ")
                    .doesNotContain("第 0 行");
            assertThat(result.stepLogs()).anySatisfy(log -> {
                assertThat(log.type()).isEqualTo("script");
                assertThat(log.index()).isEqualTo(2);
                assertThat(log.status()).isNull();
            });

            JsonNode root = ApiJson.tree(trace.body);
            JsonNode script = step(root, "script");
            assertThat(script.path("passed").asBoolean()).isFalse();
            assertThat(script.has("status")).isFalse();
            JsonNode reported = script.get("scriptError");
            assertThat(reported.path("source").asText()).isEqualTo("jsparse");
            assertThat(reported.path("line").asInt()).isEqualTo(BAD_OBJECT_LINE);
            assertThat(reported.get("column").isNull()).isTrue();
            assertThat(reported.path("message").asText()).isEqualTo("Error: bad object at 0");
            assertThat(script.has("error")).isFalse();
            assertThat(names(script)).doesNotContain("into").contains("use");
            assertThat(script.toString()).doesNotContain("收件箱").doesNotContain("[object Object]");
            assertThat(step(root, "assert").path("passed").asBoolean()).isTrue();
            assertThat(step(root, "request").path("status").asInt()).isEqualTo(200);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void throwAfterParsingTheBodyIsTheCodeLine() throws Exception {
        HttpServer server = server();
        try {
            ExecutionResult result = run(conch(library("jsparse", JSPARSE), """
                    - request:
                        method: POST
                        path: /s
                        query:
                          func: mbox:getAllFolders
                    - script:
                        use: [jsparse]
                        code: |
                          var o = parseJs(String(vars.response.body));
                          throw new Error('未解析到文件夹列表');
                        into: value
                    """), base(server), null);
            assertThat(result.success()).isFalse();
            assertThat(result.error().message()).isEqualTo("脚本 code 第 2 行：Error: 未解析到文件夹列表");
            assertThat(result.error().message()).doesNotContain("脚本库").doesNotContain("收件箱").doesNotContain("org.mozilla");
            assertThat(result.error().failedStepIndex()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void secondLibraryKeepsItsOwnLine() {
        ExecutionResult result = run(conch(library("pad", PAD) + library("jsparse", JSPARSE), """
                - script:
                    use: [pad, jsparse]
                    code: |
                      parseJs('[object Object]');
                    into: value
                """), "http://127.0.0.1", null);
        assertThat(result.error().message())
                .isEqualTo("脚本库 jsparse 第 " + BAD_OBJECT_LINE + " 行：Error: bad object at 0");
        assertThat(result.error().message()).doesNotContain("脚本 code").doesNotContain("第 0 行");
    }

    @Test
    void thrownMessageMasksSecretsAndTheirEncodings() {
        MemoryTrace trace = new MemoryTrace();
        ExecutionResult result = run(conch("", """
                - script:
                    credential: true
                    code: |
                      return '%s';
                    into: ticket
                - script:
                    credential: true
                    code: |
                      throw new Error(vars.credential.password + '|' + vars.credential.password.replace('!', '%%21') + '|' + vars.ticket + '|cat');
                    into: value
                """.formatted(TICKET)), "http://127.0.0.1", trace);
        String masked = "***|***|***|cat";
        assertThat(result.error().message()).isEqualTo("脚本 code 第 1 行：Error: " + masked);
        String flat = EngineTestSupport.flatten(result);
        EngineTestSupport.secretsAbsent(flat, EngineTestSupport.PASSWORD);
        assertThat(flat).doesNotContain(TICKET).doesNotContain(".replace").doesNotContain("%21").contains("cat");
        assertThat(URLEncoder.encode(EngineTestSupport.PASSWORD, StandardCharsets.UTF_8)).isEqualTo("S3cret%21pwd");
        String body = trace.body;
        assertThat(body).contains(masked).doesNotContain(EngineTestSupport.PASSWORD).doesNotContain("S3cret%21pwd");
        assertThat(body).doesNotContain(TICKET).doesNotContain(".replace");
        JsonNode reported = step(ApiJson.tree(body), "script").get("scriptError");
        assertThat(reported.path("source").asText()).isEqualTo("code");
        assertThat(reported.get("column").isNull()).isTrue();
        assertThat(reported.path("message").asText()).isEqualTo("Error: " + masked);
    }

    @Test
    void longThrownMessageIsClippedAfterMasking() {
        String xs = "x".repeat(9000);
        MemoryTrace trace = new MemoryTrace();
        ExecutionResult result = run(conch("", """
                - script:
                    credential: true
                    code: |
                      throw new Error(vars.credential.password + '%s');
                    into: value
                """.formatted(xs)), "http://127.0.0.1", trace);
        String message = result.error().message();
        assertThat(message.getBytes(StandardCharsets.UTF_8)).hasSize(4096);
        assertThat(message).startsWith("脚本 code 第 1 行：Error: ***");
        EngineTestSupport.secretsAbsent(message, EngineTestSupport.PASSWORD);
        JsonNode reported = step(ApiJson.tree(trace.body), "script").get("scriptError");
        String detail = reported.path("message").asText();
        assertThat(detail).startsWith("Error: ***").doesNotContain(EngineTestSupport.PASSWORD);
        assertThat(detail.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(4096);
    }

    @Test
    void referenceErrorNamesTheCodeLineWithoutAColumn() {
        ExecutionResult result = run(conch("", """
                - script:
                    code: |
                      nope;
                    into: value
                """), "http://127.0.0.1", null);
        assertThat(result.error().message()).startsWith("脚本 code 第 1 行：ReferenceError:");
        assertThat(result.error().message()).contains("nope").doesNotContain("列").doesNotContain("org.mozilla");
    }

    @Test
    void failedExtractMasksTheSelectorAndDoesNotCopyTheBody() throws Exception {
        HttpServer server = server();
        MemoryTrace trace = new MemoryTrace();
        try {
            String secret = EngineTestSupport.PASSWORD;
            ExecutionResult result = run(conch("", """
                    - request:
                        method: POST
                        path: /s
                    - extract:
                        name: { regex: "%s" }
                    """.formatted(secret)), base(server), trace);
            String masked = "提取 name 失败：正则 *** 匹配 0 条";
            assertThat(result.success()).isFalse();
            assertThat(result.error().message()).isEqualTo(masked);
            assertThat(result.error().message()).doesNotContain("收件箱");
            EngineTestSupport.secretsAbsent(result.error().message(), secret);
            JsonNode extract = only(ApiJson.tree(trace.body), "extract");
            assertThat(extract.path("passed").asBoolean()).isFalse();
            assertThat(extract.path("extractCount").asInt()).isZero();
            assertThat(extract.path("extractors").get(0).asText()).isEqualTo("regex");
            assertThat(extract.path("error").asText()).isEqualTo(masked);
            assertThat(extract.path("variables")).isEmpty();
            assertThat(extract.has("responseBody")).isFalse();
            assertThat(extract.toString()).doesNotContain("收件箱");
            EngineTestSupport.secretsAbsent(extract.toString(), secret);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedSetAndAssertAreMarkedWithoutValues() {
        MemoryTrace setTrace = new MemoryTrace();
        ExecutionResult set = run(conch("", """
                - set:
                    page: "{{response.missing}}"
                """), "http://127.0.0.1", setTrace);
        assertThat(set.success()).isFalse();
        assertThat(set.error().message()).isEqualTo("引用了未定义的变量 response.missing。");
        assertThat(set.error().message()).doesNotContain(EngineTestSupport.PASSWORD);
        JsonNode setStep = only(ApiJson.tree(setTrace.body), "set");
        assertThat(setStep.path("passed").asBoolean()).isFalse();
        assertThat(setStep.path("error").asText()).isEqualTo(set.error().message());
        assertThat(names(setStep)).containsExactly("page");
        assertThat(setStep.path("variables").get(0).get("summary").isNull()).isTrue();
        assertThat(setStep.toString()).doesNotContain("{{response.missing}}");

        MemoryTrace assertTrace = new MemoryTrace();
        ExecutionResult missed = run(conch("", """
                - assert:
                    status: 200
                """), "http://127.0.0.1", assertTrace);
        assertThat(missed.error().message()).isEqualTo("没有响应");
        JsonNode assertion = only(ApiJson.tree(assertTrace.body), "assert");
        assertThat(assertion.path("passed").asBoolean()).isFalse();
        assertThat(assertion.path("assertions").get(0).asText()).isEqualTo("status");
        assertThat(assertion.has("error")).isFalse();
        assertThat(assertion.toString()).doesNotContain("没有响应");
    }

    @Test
    void scriptTimeoutAndReturnTypesKeepTheSentenceAndSkipInto() {
        MemoryTrace timeoutTrace = new MemoryTrace();
        long started = System.nanoTime();
        ExecutionResult timed = run(conch("", """
                - script:
                    code: |
                      while (true) {}
                    timeout_ms: 80
                    into: page
                """), "http://127.0.0.1", timeoutTrace);
        long elapsed = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(timed.error().message()).isEqualTo(ScriptStep.SCRIPT_TIMEOUT);
        assertThat(elapsed).isLessThan(2_000);
        JsonNode timeout = only(ApiJson.tree(timeoutTrace.body), "script");
        assertScriptFault(timeout, ScriptStep.SCRIPT_TIMEOUT);
        assertThat(names(timeout)).doesNotContain("into", "page");

        assertReturn("return 'hello';", null, ScriptStep.RETURN_OBJECT, "hello");
        assertReturn("return hermit.utf8('ab');", "value", ScriptStep.RETURN_BYTES, null);
        assertReturn("return { raw: hermit.utf8('ab') };", "value", ScriptStep.RETURN_BYTES, null);
        assertReturn("return new Date();", "value", ScriptStep.RETURN_DATE, "GMT");
        assertReturn("return { when: new Date() };", "value", ScriptStep.RETURN_DATE, "GMT");

        MemoryTrace functionTrace = new MemoryTrace();
        ExecutionResult function = run(conch("", """
                - script:
                    code: |
                      return function () { return 1; };
                    into: value
                """), "http://127.0.0.1", functionTrace);
        assertThat(function.error().message()).isEqualTo("脚本返回值类型无效");
        JsonNode plain = only(ApiJson.tree(functionTrace.body), "script");
        assertThat(plain.path("passed").asBoolean()).isFalse();
        assertThat(plain.has("scriptError")).isFalse();
        assertThat(plain.path("error").asText()).isEqualTo("脚本返回值类型无效");
        assertThat(names(plain)).doesNotContain("into");
        assertThat(plain.toString()).doesNotContain("function");
    }

    @Test
    void memoryLimitIsAScriptFailureOnCodeWithoutAColumn() {
        long previous = ScriptStep.maxAllocatedBytes;
        ScriptStep.maxAllocatedBytes = 8L * 1024 * 1024;
        MemoryTrace trace = new MemoryTrace();
        try {
            ExecutionResult result = run(conch("", """
                    - script:
                        code: |
                          var kept = [];
                          while (true) { kept.push(new Array(4096).join('x')); }
                        timeout_ms: 5000
                        into: bucket
                    """), "http://127.0.0.1", trace);
            assertThat(result.error().message()).isEqualTo(ScriptStep.MEMORY_EXCEEDED);
            JsonNode script = only(ApiJson.tree(trace.body), "script");
            assertScriptFault(script, ScriptStep.MEMORY_EXCEEDED);
            assertThat(names(script)).doesNotContain("into", "bucket");
            assertThat(script.toString()).doesNotContain("xxxx");
        } finally {
            ScriptStep.maxAllocatedBytes = previous;
        }
    }

    @Test
    void badCaptchaImageIsFailedAskWithoutBytes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] marker = "not-an-image-zz".getBytes(StandardCharsets.UTF_8);
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1};
        server.createContext("/bad", exchange -> respond(exchange, marker, "text/html"));
        server.createContext("/ok", exchange -> respond(exchange, png, "image/png"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            MemoryTrace failedTrace = new MemoryTrace();
            ExecutionResult failed = run(imageConch("/bad"), base, failedTrace);
            assertThat(failed.error().code()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(failed.error().message()).isEqualTo(CaptchaImages.UNSUPPORTED);
            JsonNode ask = only(ApiJson.tree(failedTrace.body), "ask");
            assertThat(ask.path("passed").asBoolean()).isFalse();
            assertThat(ask.path("error").asText()).isEqualTo(CaptchaImages.UNSUPPORTED);
            assertThat(ask.path("kind").asText()).isEqualTo("image");
            assertThat(ask.path("label").asText()).isEqualTo("验证码");
            assertThat(failedTrace.body).doesNotContain("not-an-image-zz").doesNotContain("PNG");
            EngineTestSupport.secretsAbsent(failedTrace.body, EngineTestSupport.PASSWORD);

            MemoryTrace waitingTrace = new MemoryTrace();
            ExecutionResult waiting = run(imageConch("/ok"), base, waitingTrace);
            assertThat(waiting.error().code()).isEqualTo(ErrorCode.LOGIN_INPUT_REQUIRED);
            JsonNode paused = only(ApiJson.tree(waitingTrace.body), "ask");
            assertThat(paused.has("passed")).isFalse();
            assertThat(paused.has("error")).isFalse();
            assertThat(waitingTrace.body).doesNotContain("PNG").doesNotContain("not-an-image-zz");
        } finally {
            server.stop(0);
        }
    }

    private static void assertReturn(String code, String into, String sentence, String absent) {
        MemoryTrace trace = new MemoryTrace();
        String destination = into == null ? "" : "\n    into: " + into;
        ExecutionResult result = run(conch("", """
                - script:
                    code: "%s"%s
                """.formatted(code, destination)), "http://127.0.0.1", trace);
        assertThat(result.error().message()).as(code).isEqualTo(sentence);
        JsonNode script = only(ApiJson.tree(trace.body), "script");
        assertScriptFault(script, sentence);
        assertThat(names(script)).doesNotContain("into");
        if (absent != null) {
            assertThat(result.error().message()).doesNotContain(absent);
            assertThat(script.toString()).doesNotContain(absent);
        }
    }

    private static void assertScriptFault(JsonNode script, String sentence) {
        assertThat(script.path("passed").asBoolean()).isFalse();
        assertThat(script.has("error")).isFalse();
        JsonNode reported = script.get("scriptError");
        assertThat(reported.path("source").asText()).isEqualTo("code");
        assertThat(reported.path("line").asInt()).isZero();
        assertThat(reported.get("column").isNull()).isTrue();
        assertThat(reported.path("message").asText()).isEqualTo(sentence);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body, String type) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String imageConch(String path) {
        return """
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.4.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                auth:
                  steps:
                    - request: { method: GET, path: %s }
                    - ask:
                        into: captcha
                        kind: image
                        label: 验证码
                actions:
                  run:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    related: [run]
                    steps:
                      - set: { marker: ok }
                """.formatted(path);
    }

    private static HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = BODY.stripIndent().strip().getBytes(StandardCharsets.UTF_8);
        server.createContext("/s", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.getResponseHeaders().set("Connection", "close");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String library(String name, String source) {
        String text = source.stripIndent();
        if (!text.endsWith("\n")) {
            text = text + "\n";
        }
        return name + ": |\n" + text.indent(2);
    }

    private static ExecutionResult run(String yaml, String baseUrl, MemoryTrace trace) {
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(yaml));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults(), KnownSecrets.empty());
            if (trace != null) {
                engine.bindDevTraceSink(trace);
            }
            return engine.execute(EngineTestSupport.request(
                    trace == null ? Origin.OFFICIAL : Origin.LOCAL,
                    "sample_conch",
                    "run",
                    "alice",
                    Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, baseUrl)));
        }
    }

    private static String conch(String scripts, String steps) {
        String library = scripts == null || scripts.isBlank() ? "" : "scripts:\n" + scripts.stripIndent().indent(2);
        return """
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.2.0
                title: 标题
                description: 说明
                %scredentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                actions:
                  run:
                    title: 能力
                    description: 说明
                    output:
                      value: { type: string, description: 结果 }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    related: [run]
                    steps:
                %s
                """.formatted(library, steps.stripIndent().indent(6).stripTrailing());
    }

    private static int lineOf(String source, String needle) {
        String text = source.stripIndent();
        int index = text.indexOf(needle);
        if (index < 0) {
            throw new AssertionError(needle);
        }
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static JsonNode only(JsonNode root, String type) {
        JsonNode found = null;
        for (JsonNode step : root.path("steps")) {
            if (!type.equals(step.path("type").asText())) {
                continue;
            }
            if (found != null) {
                throw new AssertionError("多了步骤 " + type);
            }
            found = step;
        }
        if (found == null) {
            throw new AssertionError("没有步骤 " + type + " " + root);
        }
        return found;
    }

    private static List<String> names(JsonNode step) {
        ArrayList<String> names = new ArrayList<>();
        for (JsonNode row : step.path("variables")) {
            names.add(row.path("name").asText());
        }
        return names;
    }

    private static JsonNode step(JsonNode root, String type) {
        for (JsonNode step : root.get("steps")) {
            if (!type.equals(step.path("type").asText())) {
                continue;
            }
            if ("script".equals(type) && !step.has("scriptError")) {
                continue;
            }
            return step;
        }
        throw new AssertionError("没有步骤 " + type + " " + root);
    }

    private static String error(ExecutionResult result) {
        return result.error() == null ? "" : result.error().message();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static final class MemoryTrace implements DevTraceSink {
        private String body;

        @Override
        public DevTraceDraft open(ExecutionRequest request) {
            return new DevTraceDraft(Instant.parse("2026-11-19T00:00:00Z"));
        }

        @Override
        public void save(ExecutionRequest request, Instant expiresAt, String body) {
            this.body = body;
        }
    }
}
