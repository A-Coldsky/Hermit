package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.crab.store.Origin;

class ScriptCryptoEngineTest {

    private final ScriptStep scripts = new ScriptStep(Clock.systemUTC());

    private Map<String, Object> eval(String code, Map<String, Object> vars) {
        Map<String, Object> working = new LinkedHashMap<>(vars);
        scripts.run(
                Map.of("code", code, "credential", true),
                working,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(5),
                Map.of(),
                Set.of());
        return working;
    }

    @Test
    void gbkDigestAndHmacMatchStandardVectorsAndRemainFrozen() {
        Map<String, Object> out = eval("""
                var gbkBytes = hermit.gbk("中文OA");
                var gbkHex = hermit.hex(gbkBytes);
                var gbkRound = hermit.fromGbk(gbkBytes);
                var md5Abc = hermit.hex(hermit.md5("abc"));
                var md5Gbk = hermit.hex(hermit.md5(gbkBytes));
                var sha1Abc = hermit.hex(hermit.sha1("abc"));
                var sha256Abc = hermit.hex(hermit.sha256("abc"));
                var sha512Abc = hermit.hex(hermit.sha512("abc"));
                var hmac1 = hermit.hex(hermit.hmacSha1("key", "The quick brown fox jumps over the lazy dog"));
                var hmac256 = hermit.hex(hermit.hmacSha256("key", "The quick brown fox jumps over the lazy dog"));
                var hmacEmptyKey = hermit.hex(hermit.hmacSha256("", "abc"));
                return {
                  gbkHex: gbkHex,
                  gbkRound: gbkRound,
                  md5Abc: md5Abc,
                  md5Gbk: md5Gbk,
                  sha1Abc: sha1Abc,
                  sha256Abc: sha256Abc,
                  sha512Abc: sha512Abc,
                  hmac1: hmac1,
                  hmac256: hmac256,
                  hmacEmptyKey: hmacEmptyKey,
                  frozen: Object.isFrozen(hermit) && Object.isFrozen(hermit.md5) && Object.isFrozen(hermit.rsaEncrypt)
                };
                """, Map.of());

        assertThat(out.get("gbkHex")).isEqualTo("d6d0cec44f41");
        assertThat(out.get("gbkRound")).isEqualTo("中文OA");
        assertThat(out.get("md5Abc")).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
        assertThat(out.get("sha1Abc")).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d");
        assertThat(out.get("sha256Abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(out.get("sha512Abc")).isEqualTo(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                        + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");
        assertThat(out.get("hmac1")).isEqualTo("de7c9b85b8b78aa6bc8a7a36f70a90701c9db4d9");
        assertThat(out.get("hmac256")).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
        assertThat((String) out.get("hmacEmptyKey")).hasSize(64);
        assertThat(out.get("frozen")).isEqualTo(true);

        // 无法用 GBK 表示的 emoji 报错而不是悄悄变成问号。
        assertThatThrownBy(() -> eval("return { x: hermit.hex(hermit.gbk('\\uD83E\\uDD80')) };", Map.of()))
                .isInstanceOf(RuleProblem.class);
    }

    @Test
    void aesCbcEcbAndGcmRoundTripAndRejectTamperedTag() {
        Map<String, Object> out = eval("""
                var key = "0123456789abcdef";
                var iv16 = "fedcba9876543210";
                var iv12 = "fedcba987654";
                var cbc = hermit.aesEncrypt("CBC", key, iv16, "legacy-oa-secret");
                var cbcPlain = hermit.fromUtf8(hermit.aesDecrypt("AES/CBC/PKCS5Padding", key, iv16, cbc));
                var ecb = hermit.aesEncrypt("ECB", key, null, "ecb-payload");
                var ecbPlain = hermit.fromUtf8(hermit.aesDecrypt("ECB", key, null, ecb));
                var gcm = hermit.aesEncrypt("GCM", key, iv12, "gcm-authenticated");
                var gcmPlain = hermit.fromUtf8(hermit.aesDecrypt("GCM", key, iv12, gcm));
                return { cbcPlain: cbcPlain, ecbPlain: ecbPlain, gcmPlain: gcmPlain, cbcB64: hermit.base64(cbc) };
                """, Map.of());

        assertThat(out.get("cbcPlain")).isEqualTo("legacy-oa-secret");
        assertThat(out.get("ecbPlain")).isEqualTo("ecb-payload");
        assertThat(out.get("gcmPlain")).isEqualTo("gcm-authenticated");
        assertThat((String) out.get("cbcB64")).isNotBlank();

        assertThatThrownBy(() -> eval("""
                var key = "0123456789abcdef";
                var iv12 = "fedcba987654";
                var gcm = hermit.aesEncrypt("GCM", key, iv12, "secret");
                gcm[gcm.length - 1] ^= 1;
                return { x: hermit.fromUtf8(hermit.aesDecrypt("GCM", key, iv12, gcm)) };
                """, Map.of()))
                .isInstanceOf(RuleProblem.class);
    }

    @Test
    void rsaEncryptSupportsSpkiPemPkcs1PemAndModulusExponentAcrossPaddings() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair pair = kpg.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        RSAPrivateKey priv = (RSAPrivateKey) pair.getPrivate();

        String spkiPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pub.getEncoded())
                + "\n-----END PUBLIC KEY-----";
        String modHex = pub.getModulus().toString(16);
        String expHex = pub.getPublicExponent().toString(16);

        // 从 SPKI 提取内层 PKCS#1 BIT STRING 负载构造 BEGIN RSA PUBLIC KEY。
        byte[] spki = pub.getEncoded();
        // 2048-bit RSA SPKI 头部固定 24 字节 (SEQUENCE + AlgId + BIT STRING 0x03 0x82 ... 0x00)
        int bitStringIdx = findPkcs1PayloadOffset(spki);
        byte[] pkcs1Der = new byte[spki.length - bitStringIdx];
        System.arraycopy(spki, bitStringIdx, pkcs1Der, 0, pkcs1Der.length);
        String pkcs1Pem = "-----BEGIN RSA PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pkcs1Der)
                + "\n-----END RSA PUBLIC KEY-----";

        Map<String, Object> out = eval("""
                return {
                  spkiPkcs1: hermit.hex(hermit.rsaEncrypt(vars.spkiPem, "pwd-spki")),
                  pkcs1PemOut: hermit.hex(hermit.rsaEncrypt(vars.pkcs1Pem, "pwd-pkcs1", "PKCS1")),
                  modExpOaep256: hermit.hex(hermit.rsaEncrypt(
                      { modulus: vars.modHex, exponent: vars.expHex }, "pwd-oaep256", "OAEP-SHA256")),
                  oaepSha1: hermit.hex(hermit.rsaEncrypt(vars.spkiPem, "pwd-oaep1", "OAEP-SHA1"))
                };
                """, Map.of(
                "spkiPem", spkiPem,
                "pkcs1Pem", pkcs1Pem,
                "modHex", modHex,
                "expHex", expHex));

        Cipher decPkcs1 = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        decPkcs1.init(Cipher.DECRYPT_MODE, priv);
        assertThat(new String(decPkcs1.doFinal(HexFormat.of().parseHex((String) out.get("spkiPkcs1"))), StandardCharsets.UTF_8))
                .isEqualTo("pwd-spki");
        assertThat(new String(decPkcs1.doFinal(HexFormat.of().parseHex((String) out.get("pkcs1PemOut"))), StandardCharsets.UTF_8))
                .isEqualTo("pwd-pkcs1");

        Cipher decOaep256 = Cipher.getInstance("RSA/ECB/OAEPPadding");
        decOaep256.init(
                Cipher.DECRYPT_MODE,
                priv,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        assertThat(new String(decOaep256.doFinal(HexFormat.of().parseHex((String) out.get("modExpOaep256"))), StandardCharsets.UTF_8))
                .isEqualTo("pwd-oaep256");

        Cipher decOaep1 = Cipher.getInstance("RSA/ECB/OAEPPadding");
        decOaep1.init(
                Cipher.DECRYPT_MODE,
                priv,
                new OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
        assertThat(new String(decOaep1.doFinal(HexFormat.of().parseHex((String) out.get("oaepSha1"))), StandardCharsets.UTF_8))
                .isEqualTo("pwd-oaep1");
    }

    @Test
    void credentialScriptReadsVarsLoginTriggersColdLoginTaintsDerivedAndRedactsOutput() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/login", ex -> {
            logins.incrementAndGet();
            ex.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=xsrf-secret-val; Path=/");
            byte[] body = "{\"csrf\":\"csrf-secret-9876\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/api/sign", ex -> {
            String sig = ex.getRequestHeaders().getFirst("X-Signature");
            byte[] body = ("{\"echoSig\":\"" + sig + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (SessionManager sessions = new SessionManager()) {
            String yaml = """
                    conch: signed_oa
                    version: 1.0.0
                    driver: http
                    min_crab_version: 1.14.0
                    base_url: %s
                    title: 签名 OA
                    description: 验证 credential 脚本读取 vars.login 计算签名并维持污点与打码
                    credentials:
                      username: { type: text, label: 用户名 }
                      password: { type: secret, label: 密码 }
                    auth:
                      cookies:
                        xsrf: XSRF-TOKEN
                      steps:
                        - request:
                            method: POST
                            path: /login
                            form: { u: "{{credential.username}}", p: "{{credential.password}}" }
                        - extract: { csrf: { jsonpath: "$.csrf" } }
                    actions:
                      call_signed:
                        title: 签名调用
                        description: 计算请求头签名
                        method: GET
                        steps:
                          - script:
                              credential: true
                              into: sig
                              code: |
                                return hermit.hex(hermit.hmacSha256(vars.login.csrf, vars.login.xsrf + ":payload"));
                          - script:
                              into: plain_seen
                              code: |
                                return [typeof vars.login, typeof vars.sig].join("|");
                          - request:
                              method: GET
                              path: /api/sign
                              headers: { X-Signature: "{{sig}}" }
                          - extract: { echoed: { jsonpath: "$.echoSig" } }
                        output:
                          plainSeen: { type: string, description: 普通脚本可见性, value: "{{plain_seen}}" }
                          echoed: { type: string, description: 回显签名, value: "{{echoed}}" }
                        examples:
                          - input: {}
                            output: { plainSeen: "undefined|undefined", echoed: "***" }
                    """.formatted(baseUrl);
            ConchDoc doc = ConchParser.parse(yaml);
            assertThat(ConchValidator.validate(doc, ConchValidator.Context.of(CrabVersion.CURRENT)).errors()).isEmpty();

            ConchRegistry registry = new ConchRegistry();
            registry.put(doc, Origin.LOCAL);
            RuleEngine engine = new RuleEngine(registry, sessions);
            ExecutionResult res = engine.execute(EngineTestSupport.request(
                    Origin.LOCAL,
                    "signed_oa",
                    "call_signed",
                    "main",
                    Map.of(),
                    new EngineTestSupport.MapAccount(
                            Map.of("username", "alice", "password", "pass-123456"),
                            Map.of())));

            assertThat(res.success()).as(String.valueOf(res.error())).isTrue();
            assertThat(logins.get()).as("仅通过 script 访问 vars.login 也会在冷启动时自动触发登录").isEqualTo(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) res.data();
            assertThat(data.get("plainSeen")).isEqualTo("undefined|undefined");
            // 派生出的 64 字符 HMAC 签名属于 credential: true 产物，回显在输出中必须自动打码。
            assertThat(data.get("echoed")).isEqualTo("***");
            assertThat(EngineTestSupport.flatten(res)).doesNotContain("csrf-secret-9876").doesNotContain("xsrf-secret-val");
        } finally {
            server.stop(0);
        }
    }

    private static int findPkcs1PayloadOffset(byte[] spki) {
        for (int i = 0; i < spki.length - 4; i++) {
            if (spki[i] == 0x03 && (spki[i + 1] & 0xff) == 0x82 && spki[i + 4] == 0x00) {
                return i + 5;
            }
        }
        throw new IllegalStateException("PKCS#1 BIT STRING not found in SPKI");
    }
}
