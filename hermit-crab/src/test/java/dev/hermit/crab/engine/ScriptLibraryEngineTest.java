package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ScriptLibraries;
import dev.hermit.crab.secure.KnownSecrets;
import dev.hermit.crab.store.Origin;

@Timeout(180)
class ScriptLibraryEngineTest {

    private static final String BLOB = "cipher-value-zz";
    private static final String SPACED = "cipher value zz";
    private static final String PLUS = "cipher+value+zz";
    private static final String PCT = "cipher%20value%20zz";
    private static final String TINY = "ab";

    @Test
    void librariesRunInOrderInTheSameScope() {
        String first = """
                function tag() { return "A"; }
                """;
        String second = """
                function tag() { return "B"; }
                """;
        String shared = """
                var hidden = "kept-literal";
                function reveal() { return hidden; }
                """;
        ExecutionResult forward = run(libraries("first", first, "second", second), """
                - script:
                    use: [first, second]
                    code: |
                      return tag();
                    into: value
                """);
        ExecutionResult reverse = run(libraries("first", first, "second", second), """
                - script:
                    use: [second, first]
                    code: |
                      return tag();
                    into: value
                """);
        ExecutionResult scope = run(libraries("shared", shared), """
                - script:
                    use: [shared]
                    code: |
                      return reveal();
                    into: value
                """);
        assertThat(value(forward)).isEqualTo("B");
        assertThat(value(reverse)).isEqualTo("A");
        assertThat(value(scope)).isEqualTo("kept-literal");
    }

    @Test
    void librarySourceIsNotATemplate() {
        ExecutionResult result = run(libraries("raw", """
                function raw() { return "X{{credential.password}}Y"; }
                """), """
                - script:
                    use: [raw]
                    code: |
                      return raw();
                    into: value
                """);
        assertThat(value(result)).isEqualTo("X{{credential.password}}Y");
        EngineTestSupport.secretsAbsent(EngineTestSupport.flatten(result), EngineTestSupport.PASSWORD);
    }

    @Test
    void credentialCopyDoesNotChangeLaterTemplates() {
        ExecutionResult result = run("", """
                - script:
                    credential: true
                    code: |
                      var seen = vars.credential.password === "%s";
                      vars.credential.password = "mutated-password";
                      return { seen: seen };
                - set:
                    marker: "{{credential.password}}"
                - script:
                    code: |
                      return {
                        intact: vars.marker === "%s",
                        hidden: typeof vars.credential === "undefined"
                      };
                """.formatted(EngineTestSupport.PASSWORD, EngineTestSupport.PASSWORD), """
                seen: { type: boolean, description: 读到凭证 }
                intact: { type: boolean, description: 模板仍是原值 }
                hidden: { type: boolean, description: 第二次看不到凭证 }
                """);
        Map<String, Object> data = map(result.data());
        assertThat(data).containsEntry("seen", true).containsEntry("intact", true).containsEntry("hidden", true);
        EngineTestSupport.secretsAbsent(EngineTestSupport.flatten(result), EngineTestSupport.PASSWORD);
    }

    @Test
    void credentialScriptOutputIsMaskedOnCallsAndTraces() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String yaml = maskedConch(true);
            KnownSecrets known = KnownSecrets.empty();
            MemoryTrace trace = new MemoryTrace();
            ExecutionResult official = execute(yaml, "run", Map.of(), Origin.OFFICIAL, null, Clock.systemUTC(), known, base);
            assertMasked(official);
            assertThat(official.stepLogs()).anySatisfy(log -> assertThat(log.path()).isEqualTo("/p/***"));

            ExecutionResult local = execute(yaml, "run", Map.of(), Origin.LOCAL, trace, Clock.systemUTC(), known, base);
            assertMasked(local);
            assertThat(trace.body).isNotNull();
            assertSecretsAbsent(trace.body);
            assertThat(trace.body).contains("/p/***");

            ExecutionResult login = testLogin(loginConch(), known);
            assertThat(login.success()).isFalse();
            assertSecretsAbsent(login.error().message());
            assertThat(login.error().message()).contains("***");
            assertThat(known.copy()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scriptOutputWithoutCredentialFlagStaysVisible() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            MemoryTrace trace = new MemoryTrace();
            ExecutionResult result = execute(
                    maskedConch(false), "run", Map.of(), Origin.LOCAL, trace, Clock.systemUTC(), KnownSecrets.empty(), base);
            assertThat(result.success()).as(error(result)).isTrue();
            Map<String, Object> data = map(result.data());
            assertThat(data).containsEntry("blob", BLOB).containsEntry("spaced", SPACED).containsEntry("tiny", TINY);
            assertThat(data).containsEntry("plus", PLUS).containsEntry("pct", PCT);
            assertThat(trace.body).contains(BLOB).contains(SPACED);
            EngineTestSupport.secretsAbsent(EngineTestSupport.flatten(result), EngineTestSupport.PASSWORD);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hermitCodecsRoundTripAndStayFrozen() {
        byte[] raw = "A中文-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        ExecutionResult result = run("", """
                - script:
                    code: |
                      var text = "A中文-0123456789abcdef";
                      var bytes = hermit.utf8(text);
                      var sample = hermit.utf8("A");
                      try { hermit.hex = function () { return "no"; }; } catch (e) {}
                      try { hermit.extra = 1; } catch (e) {}
                      try { hermit = {}; } catch (e) {}
                      return {
                        back: hermit.fromUtf8(bytes),
                        hex: hermit.hex(bytes),
                        unhex: hermit.fromUtf8(hermit.unhex(hermit.hex(bytes))),
                        b64: hermit.base64(bytes),
                        unb64: hermit.fromUtf8(hermit.unbase64(hermit.base64(bytes))),
                        url: hermit.base64url(bytes),
                        unurl: hermit.fromUtf8(hermit.unbase64url(hermit.base64url(bytes))),
                        plain: hermit.fromUtf8(hermit.unbase64url("QQ")),
                        padded: hermit.fromUtf8(hermit.unbase64url("QQ==")),
                        still: hermit.hex(sample),
                        extra: hermit.extra == null,
                        frozen: Object.isFrozen(hermit)
                      };
                    into: value
                """);
        Map<String, Object> data = map(value(result));
        assertThat(data.get("back")).isEqualTo("A中文-0123456789abcdef");
        assertThat(data.get("hex")).isEqualTo(HexFormat.of().formatHex(raw));
        assertThat(data.get("unhex")).isEqualTo("A中文-0123456789abcdef");
        assertThat(data.get("b64")).isEqualTo(java.util.Base64.getEncoder().encodeToString(raw));
        assertThat(data.get("unb64")).isEqualTo("A中文-0123456789abcdef");
        assertThat(data.get("url")).isEqualTo(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
        assertThat(data.get("unurl")).isEqualTo("A中文-0123456789abcdef");
        assertThat(data.get("plain")).isEqualTo("A");
        assertThat(data.get("padded")).isEqualTo("A");
        assertThat(data.get("still")).isEqualTo("41");
        assertThat(data.get("extra")).isEqualTo(true);
        assertThat(data.get("frozen")).isEqualTo(true);
    }

    @Test
    void randomBytesAndNowUseTheEngine() {
        EngineTestSupport.MutableClock clock = new EngineTestSupport.MutableClock();
        ExecutionResult result = execute(conch("", """
                - script:
                    code: |
                      var a = hermit.randomBytes(4);
                      var b = hermit.randomBytes(1);
                      var c = hermit.randomBytes(1024);
                      return { n4: a.length, n1: b.length, n1024: c.length, b0: a[0], now: hermit.now() };
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """), "run", Map.of(), Origin.OFFICIAL, null, clock, KnownSecrets.empty(), "http://127.0.0.1");
        assertThat(result.success()).as(error(result)).isTrue();
        Map<String, Object> data = map(value(result));
        assertThat(data).containsEntry("n4", 4L).containsEntry("n1", 1L).containsEntry("n1024", 1024L);
        assertThat(((Number) data.get("b0")).intValue()).isBetween(0, 255);
        assertThat(data.get("now")).isEqualTo(clock.instant().toEpochMilli());
        for (String bad : List.of("0", "1025", "1.5", "\"4\"")) {
            ExecutionResult rejected = run("", """
                    - script:
                        code: |
                          hermit.randomBytes(%s);
                          return { ok: 1 };
                    """.formatted(bad));
            assertThat(rejected.success()).as(bad).isFalse();
            assertThat(rejected.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        }
    }

    @Test
    void typedArrayCannotBeReturned() {
        ExecutionResult result = run("", """
                - script:
                    code: |
                      return hermit.utf8("A");
                """);
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo(ErrorCode.RULE_MISMATCH);
        assertThat(result.error().message()).contains("脚本返回值类型无效");
    }

    @Test
    void hermitFunctionsDoNotOpenJava() {
        List<String> probes = List.of(
                "return { ok: hermit.utf8.constructor('return 1')() };",
                "return { ok: hermit.randomBytes.constructor('return 1')() };",
                "return { ok: hermit.now.constructor('return 1')() };",
                "var g = hermit.utf8.constructor('return this')(); return { ok: String(g.java) };");
        for (String code : probes) {
            ExecutionResult result = run("", """
                    - script:
                        code: |
                          %s
                    """.formatted(code));
            assertThat(result.success()).as(code).isFalse();
            assertThat(result.error().code()).as(code).isEqualTo(ErrorCode.RULE_MISMATCH);
            assertThat(result.error().message()).as(code).doesNotContain(EngineTestSupport.PASSWORD);
        }
    }

    @Test
    void libraryLinesShiftSyntaxErrors() {
        ExecutionResult result = run(libraries("lib", """
                function unused() {
                  return 1;
                }
                """), """
                - script:
                    use: [lib]
                    code: |
                      var ok = 1;
                      ???
                """);
        assertThat(result.success()).isFalse();
        assertThat(result.error().message()).isEqualTo("脚本 code 第 2 行第 3 列：syntax error");
    }

    @Test
    void missingAndOversizedLibrariesFailAtRuntime() {
        ScriptStep step = new ScriptStep(Clock.systemUTC());
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("code", "return { ok: 1 };");
        missing.put("use", List.of("missing"));
        assertThatThrownBy(() -> step.run(missing, new LinkedHashMap<>(), Long.MAX_VALUE, Map.of()))
                .isInstanceOf(RuleProblem.class)
                .hasMessage(ScriptLibraries.missing("missing"));

        Map<String, Object> huge = new LinkedHashMap<>();
        huge.put("code", "return { ok: 1 };");
        huge.put("use", List.of("lib"));
        String source = "a".repeat(ScriptLibraries.MAX_BYTES + 1);
        assertThatThrownBy(() -> step.run(huge, new LinkedHashMap<>(), Long.MAX_VALUE, Map.of("lib", source)))
                .isInstanceOf(RuleProblem.class)
                .hasMessage(ScriptLibraries.TOO_LARGE)
                .hasMessageNotContaining("aaaa");
    }

    @Test
    void rsa2048EncryptsWithinTheDefaultBudget() throws Exception {
        run("", """
                - script:
                    code: |
                      return 1;
                    into: value
                """);
        String js = resource("scripts/jsbn-rsa.js");
        String yaml = conch(libraries("jsbn_rsa", js), """
                - script:
                    use: [jsbn_rsa]
                    code: |
                      return 1;
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        ExecutionResult idle = execute(yaml, "run", Map.of(), Origin.OFFICIAL, null, Clock.systemUTC(),
                KnownSecrets.empty(), "http://127.0.0.1");
        assertThat(idle.success()).as(error(idle)).isTrue();

        String checked = conch(libraries("jsbn_rsa", js), """
                - script:
                    use: [jsbn_rsa]
                    code: |
                      try {
                        return modPowHex("2", "10", "3f1");
                      } catch (e) {
                        return "ERR " + e;
                      }
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """);
        ExecutionResult pow = execute(checked, "run", Map.of(), Origin.OFFICIAL, null, Clock.systemUTC(),
                KnownSecrets.empty(), "http://127.0.0.1");
        assertThat(pow.success()).as(error(pow)).isTrue();
        assertThat(value(pow)).isEqualTo("3c0");

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        int bytes = (publicKey.getModulus().bitLength() + 7) / 8;
        String modulus = publicKey.getModulus().toString(16);
        String encrypting = conch(libraries("jsbn_rsa", js), """
                - script:
                    use: [jsbn_rsa]
                    code: |
                      try {
                        return rsaEncrypt(vars.input.modulus, vars.input.message);
                      } catch (e) {
                        return "ERR " + e;
                      }
                    into: value
                """, """
                value: { type: string, description: 结果 }
                """, """
                modulus: { type: string, description: 模数 }
                message: { type: string, description: 明文 }
                """);
        ExecutionResult encrypted = execute(
                encrypting,
                "run",
                Map.of("modulus", modulus, "message", "hello-rsa"),
                Origin.OFFICIAL,
                null,
                Clock.systemUTC(),
                KnownSecrets.empty(),
                "http://127.0.0.1");
        assertThat(encrypted.success()).as(error(encrypted)).isTrue();
        String cipherHex = String.valueOf(value(encrypted));
        assertThat(cipherHex).doesNotStartWith("ERR");
        byte[] decoded = HexFormat.of().parseHex(cipherHex);
        assertThat(decoded).hasSize(bytes);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, (RSAPrivateKey) pair.getPrivate());
        byte[] plain = cipher.doFinal(decoded);
        assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo("hello-rsa");
    }

    private static void assertMasked(ExecutionResult result) {
        assertThat(result.success()).as(error(result)).isTrue();
        Map<String, Object> data = map(result.data());
        assertThat(data).containsEntry("blob", "***").containsEntry("spaced", "***");
        assertThat(data).containsEntry("plus", "***").containsEntry("pct", "***").containsEntry("tiny", TINY);
        assertSecretsAbsent(EngineTestSupport.flatten(result));
    }

    private static void assertSecretsAbsent(String text) {
        EngineTestSupport.secretsAbsent(text, EngineTestSupport.PASSWORD);
        EngineTestSupport.secretsAbsent(text, BLOB);
        EngineTestSupport.secretsAbsent(text, SPACED);
        assertThat(text).doesNotContain(PLUS).doesNotContain(PCT);
    }

    private static String maskedConch(boolean credential) {
        return conch("", """
                - script:
                    credential: %s
                    into: produced
                    code: |
                      return { blob: "%s", spaced: "%s", tiny: "%s" };
                - set:
                    blob: "{{produced.blob}}"
                    spaced: "{{produced.spaced}}"
                    tiny: "{{produced.tiny}}"
                - request:
                    method: GET
                    path: "/p/{{blob}}"
                """.formatted(credential, BLOB, SPACED, TINY), """
                blob: { type: string, description: 密文 }
                spaced: { type: string, description: 带空格 }
                plus: { type: string, description: 加号编码, value: "%s" }
                pct: { type: string, description: 百分号编码, value: "%s" }
                tiny: { type: string, description: 短值 }
                """.formatted(PLUS, PCT));
    }

    private static String loginConch() {
        return """
                conch: sample_conch
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials:
                  username: { type: text, required: true }
                  password: { type: secret, required: true }
                auth:
                  steps:
                    - script:
                        credential: true
                        into: spaced
                        code: |
                          return "%s";
                    - set:
                        body: nope
                    - extract:
                        got: { regex: "{{spaced}}", from: body }
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
                      - set:
                          marker: ok
                """.formatted(SPACED);
    }

    private static String libraries(String... namesAndSources) {
        StringBuilder yaml = new StringBuilder();
        for (int i = 0; i < namesAndSources.length; i += 2) {
            String source = namesAndSources[i + 1].stripIndent();
            if (!source.endsWith("\n")) {
                source = source + "\n";
            }
            yaml.append(namesAndSources[i]).append(": |\n").append(source.indent(2));
        }
        return yaml.toString();
    }

    private static ExecutionResult run(String scripts, String steps) {
        return run(scripts, steps, """
                value: { type: string, description: 结果 }
                """);
    }

    private static ExecutionResult run(String scripts, String steps, String output) {
        return execute(conch(scripts, steps, output), "run", Map.of(), Origin.OFFICIAL, null, Clock.systemUTC(),
                KnownSecrets.empty(), "http://127.0.0.1");
    }

    private static String conch(String scripts, String steps, String output) {
        return conch(scripts, steps, output, "");
    }

    private static String conch(String scripts, String steps, String output, String input) {
        String library = scripts == null || scripts.isBlank() ? "" : "scripts:\n" + scripts.stripIndent().indent(2);
        String indentedSteps = steps.stripIndent().indent(6).stripTrailing();
        String indentedOutput = output.stripIndent().indent(6).stripTrailing();
        String inputBlock = input == null || input.isBlank()
                ? ""
                : ("input:\n" + input.stripIndent().indent(2)).indent(4);
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
                %s
                    output:
                %s
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    related: [run]
                    steps:
                %s
                """.formatted(library, inputBlock, indentedOutput, indentedSteps);
    }

    private static ExecutionResult execute(
            String yaml,
            String action,
            Map<String, Object> input,
            Origin origin,
            DevTraceSink sink,
            Clock clock,
            KnownSecrets secrets,
            String baseUrl
    ) {
        try (SessionManager sessions = new SessionManager(new NopSessionPersistence(), EngineSettings.defaults(), clock)) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(yaml));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults(), secrets);
            if (sink != null) {
                engine.bindDevTraceSink(sink);
            }
            return engine.execute(EngineTestSupport.request(
                    origin, "sample_conch", action, "alice", input,
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, baseUrl)));
        }
    }

    private static ExecutionResult testLogin(String yaml, KnownSecrets secrets) {
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), EngineSettings.defaults(), Clock.systemUTC())) {
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(yaml));
            RuleEngine engine = new RuleEngine(registry, sessions, EngineSettings.defaults(), secrets);
            return engine.testLogin(EngineTestSupport.request(
                    Origin.OFFICIAL, "sample_conch", "run", "alice", Map.of(),
                    EngineTestSupport.account("alice", EngineTestSupport.PASSWORD, "http://127.0.0.1")));
        }
    }

    private static Object value(ExecutionResult result) {
        assertThat(result.success()).as(error(result)).isTrue();
        return map(result.data()).get("value");
    }

    private static String error(ExecutionResult result) {
        return result.error() == null ? "" : result.error().message();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = ScriptLibraryEngineTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 缺少 " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class MemoryTrace implements DevTraceSink {
        private String body;

        @Override
        public DevTraceDraft open(ExecutionRequest request) {
            return new DevTraceDraft(Instant.parse("2026-11-17T00:00:00Z"));
        }

        @Override
        public void save(ExecutionRequest request, Instant expiresAt, String body) {
            this.body = body;
        }
    }
}
