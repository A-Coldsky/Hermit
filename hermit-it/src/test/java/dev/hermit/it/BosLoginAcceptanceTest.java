package dev.hermit.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.crab.runtime.CrabServer;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * BOS 模拟登录。规则包用脚本库加密口令，{@code {{login.sid}}} 把 Cookie 里的会话带进查询串。
 * 开发追踪只记本地规则包，所以走本地导入，再经 {@link Invoker} 调用。不连真实邮箱。
 * 口令是测试夹具，不是真实凭证。步骤日志不含查询串，示例把同一个 sid 再放进路径，打码才能出现在步骤日志里。
 */
class BosLoginAcceptanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONCH = "bos_mail";
    private static final String ACTION = "list_messages";
    private static final String ALICE = "alice";
    private static final String BOB = "bobby";
    private static final String ALICE_PASSWORD = "p+ass/one";
    private static final String BOB_PASSWORD = "p+ass/two";
    private static final String ALICE_SUBJECT = "inbox-a";
    private static final String BOB_SUBJECT = "inbox-b";

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void scriptLibraryLoginReplaysAndMasks() throws Exception {
        String yaml = packageYaml();
        assertThat(yaml).contains("min_crab_version: 1.3.0");
        assertThat(yaml).contains("scripts:");
        assertThat(yaml).contains("use: [bos_rsa]");
        assertThat(yaml).contains("credential: true");
        assertThat(yaml).contains("hermit.randomBytes");
        assertThat(yaml).doesNotContain("Math.random");

        try (MockBosServer bos = new MockBosServer()) {
            bos.allow(ALICE, ALICE_PASSWORD, ALICE_SUBJECT);
            bos.allow(BOB, BOB_PASSWORD, BOB_SUBJECT);
            Path home = temp.resolve("crab");
            Files.createDirectories(home);
            Files.writeString(home.resolve("config.yml"), """
                    server:
                      host: 127.0.0.1
                      port: 0
                    report:
                      enabled: false
                    """);
            Function<String, String> env = name -> "HERMIT_HOME".equals(name) ? home.toString() : null;
            try (CrabServer crab = CrabServer.start(env)) {
                crab.runtime().localConches().importBytes(yaml.getBytes(StandardCharsets.UTF_8), Actors.CONSOLE);
                ConchDoc doc = crab.runtime().registry().find(CONCH).orElseThrow();
                crab.runtime().admin().putConfig(doc, "base_url", bos.baseUrl(), Actors.CONSOLE);
                crab.runtime().admin().createAccount(CONCH, ALICE, Actors.CONSOLE);
                crab.runtime().admin().createAccount(CONCH, BOB, Actors.CONSOLE);
                crab.runtime().admin().setDefault(CONCH, ALICE, Actors.CONSOLE);
                writeCredentials(crab, doc, ALICE, ALICE_PASSWORD);
                writeCredentials(crab, doc, BOB, BOB_PASSWORD);
                crab.runtime().debug().setEnabled(true, Actors.CONSOLE);

                Invoker invoker = crab.runtime().invoker();
                ApiKeyPrincipal principal = new ApiKeyPrincipal(
                        "bos-it", "bos-it", new ApiKeyScope(Set.of(), Set.of(), false));
                long started = System.currentTimeMillis();

                String aliceTrace = UUID.randomUUID().toString();
                JsonNode alice = json(invoker.invoke(
                        principal, CONCH, ACTION, Map.of(), Optional.of(ALICE), Optional.empty(), aliceTrace));
                assertThat(alice.path("success").asBoolean()).as(alice.toString()).isTrue();
                assertThat(alice.path("data").path("subject").asText()).isEqualTo(ALICE_SUBJECT);
                assertThat(bos.publicKeys()).hasSize(1);
                assertRecentRnd(bos.publicKeys().get(0).rnd(), started);
                assertThat(bos.logins()).hasSize(1);
                assertPassword(bos.logins().get(0), ALICE, ALICE_PASSWORD);
                MockBosServer.ListHit aliceList = bos.lists().get(0);
                assertSameSession(aliceList, ALICE, ALICE_SUBJECT, 200);
                String aliceSid = aliceList.cookieSid();

                String bobTrace = UUID.randomUUID().toString();
                JsonNode bob = json(invoker.invoke(
                        principal, CONCH, ACTION, Map.of(), Optional.of(BOB), Optional.empty(), bobTrace));
                assertThat(bob.path("success").asBoolean()).as(bob.toString()).isTrue();
                assertThat(bob.path("data").path("subject").asText()).isEqualTo(BOB_SUBJECT);
                assertThat(bos.logins()).hasSize(2);
                assertPassword(bos.logins().get(1), BOB, BOB_PASSWORD);
                MockBosServer.ListHit bobList = bos.lists().get(1);
                assertSameSession(bobList, BOB, BOB_SUBJECT, 200);
                assertThat(bobList.cookieSid()).isNotEqualTo(aliceSid);

                bos.invalidate(aliceSid);
                String replayTrace = UUID.randomUUID().toString();
                JsonNode replayed = json(invoker.invoke(
                        principal, CONCH, ACTION, Map.of(), Optional.of(ALICE), Optional.empty(), replayTrace));
                assertThat(replayed.path("success").asBoolean()).as(replayed.toString()).isTrue();
                assertThat(replayed.path("data").path("subject").asText()).isEqualTo(ALICE_SUBJECT);
                assertThat(bos.logins()).hasSize(3);
                assertPassword(bos.logins().get(2), ALICE, ALICE_PASSWORD);
                assertThat(bos.lists()).hasSize(4);
                MockBosServer.ListHit rejected = bos.lists().get(2);
                MockBosServer.ListHit renewed = bos.lists().get(3);
                assertThat(rejected.status()).isEqualTo(401);
                assertThat(rejected.cookieSid()).isEqualTo(aliceSid);
                assertThat(rejected.querySid()).isEqualTo(aliceSid);
                assertSameSession(renewed, ALICE, ALICE_SUBJECT, 200);
                assertThat(renewed.cookieSid()).isNotEqualTo(aliceSid);

                bos.failNextLists(2);
                String expiredTrace = UUID.randomUUID().toString();
                InvokeOutcome expiredOutcome = invoker.invoke(
                        principal, CONCH, ACTION, Map.of(), Optional.of(ALICE), Optional.empty(), expiredTrace);
                JsonNode expired = json(expiredOutcome);
                assertThat(expiredOutcome.status()).isEqualTo(422);
                assertThat(expired.path("success").asBoolean()).isFalse();
                assertThat(expired.path("error").path("code").asText()).isEqualTo("SESSION_EXPIRED");
                assertThat(expired.path("error").path("message").asText()).isEqualTo("会话已失效");
                assertThat(bos.logins()).hasSize(4);
                assertPassword(bos.logins().get(3), ALICE, ALICE_PASSWORD);
                assertThat(bos.lists()).hasSize(6);
                assertThat(bos.lists().get(4).status()).isEqualTo(401);
                assertThat(bos.lists().get(5).status()).isEqualTo(401);
                assertThat(bos.lists().get(5).cookieSid()).isNotEqualTo(bos.lists().get(4).cookieSid());

                List<String> secrets = secrets(bos);
                for (String traceId : List.of(aliceTrace, bobTrace, replayTrace, expiredTrace)) {
                    CallLogStore.CallDetail call = crab.runtime().callLogs().findByTraceId(traceId).orElseThrow();
                    String steps = call.steps().toString();
                    String error = call.call().errorMessage() == null ? "" : call.call().errorMessage();
                    assertMasked(steps, secrets);
                    assertMasked(error + steps, secrets);
                    String trace = crab.runtime().devTraces().find(traceId).orElseThrow().body();
                    assertMasked(trace, secrets);
                }
                assertAbsent(alice.toString() + bob.toString() + replayed.toString() + expired.toString(), secrets);
            }
        }
    }

    private static void writeCredentials(CrabServer crab, ConchDoc doc, String account, String password) {
        crab.runtime().admin().writeCredentials(
                doc,
                account,
                Map.of("username", account, "password", password),
                Actors.CONSOLE);
    }

    private static void assertPassword(MockBosServer.LoginHit hit, String username, String password) {
        assertThat(hit.accepted()).isTrue();
        assertThat(hit.username()).isEqualTo(username);
        if (!password.equals(hit.password())) {
            throw new AssertionError("server decrypted a different password");
        }
        assertThat(hit.sid()).isNotBlank();
        assertThat(hit.ciphertext()).isNotBlank();
        assertThat(hit.ciphertext()).doesNotContain(password);
    }

    private static void assertSameSession(MockBosServer.ListHit hit, String username, String subject, int status) {
        assertThat(hit.status()).isEqualTo(status);
        assertThat(hit.username()).isEqualTo(username);
        assertThat(hit.subject()).isEqualTo(subject);
        assertThat(hit.querySid()).isEqualTo(hit.cookieSid());
        assertThat(hit.pathSid()).isEqualTo(hit.cookieSid());
        assertThat(hit.cookieSid()).contains("+");
    }

    private static void assertRecentRnd(String rnd, long started) {
        assertThat(rnd).matches("\\d{12,13}");
        long value = Long.parseLong(rnd);
        assertThat(Math.abs(value - started)).isLessThan(120_000L);
    }

    private static List<String> secrets(MockBosServer bos) {
        ArrayList<String> secrets = new ArrayList<>();
        secrets.add(ALICE_PASSWORD);
        secrets.add(BOB_PASSWORD);
        secrets.add(ALICE);
        secrets.add(BOB);
        for (MockBosServer.LoginHit hit : bos.logins()) {
            secrets.add(hit.sid());
            secrets.add(hit.ciphertext());
        }
        for (MockBosServer.ListHit hit : bos.lists()) {
            secrets.add(hit.cookieSid());
            secrets.add(hit.querySid());
        }
        return secrets;
    }

    private static void assertMasked(String text, List<String> secrets) {
        assertThat(text).contains("***");
        assertAbsent(text, secrets);
    }

    private static void assertAbsent(String text, List<String> secrets) {
        for (String secret : secrets) {
            if (secret == null || secret.length() < 4) {
                continue;
            }
            SecretChecks.absent(text, secret);
            SecretChecks.absent(text, URLEncoder.encode(secret, StandardCharsets.UTF_8));
        }
    }

    private static JsonNode json(InvokeOutcome outcome) throws Exception {
        return JSON.readTree(outcome.body());
    }

    private static String packageYaml() throws Exception {
        String skeleton = resource("/bos/bos-mail.yaml");
        String library = resource("/bos/jsbn-rsa.js").stripIndent().stripTrailing();
        StringBuilder indented = new StringBuilder();
        for (String line : library.split("\n", -1)) {
            if (indented.length() > 0) {
                indented.append('\n');
            }
            if (!line.isEmpty()) {
                indented.append("    ");
            }
            indented.append(line);
        }
        String yaml = skeleton.replace("    __BOS_RSA__", indented.toString());
        if (yaml.contains("__BOS_RSA__")) {
            throw new AssertionError("script library was not spliced");
        }
        return yaml;
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = BosLoginAcceptanceTest.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new AssertionError("missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
