package dev.hermit.crab.engine;

import com.sun.net.httpserver.HttpServer;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.Invoker;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.store.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;

/** Audit observations, deliberately outside the normal test suite. Uses only temporary data. */
public final class CrabAuditProbe {
    public static void main(String[] args) throws Exception {
        cookieHistory();
        slowResponse();
        runtimeFindings();
    }

    private static void cookieHistory() throws Exception {
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.OFFICIAL, "audit_probe", "alice");
            if (!sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                throw new IllegalStateException("failed to acquire test lock");
            }
            try {
                BasicClientCookie cookie = new BasicClientCookie("sid", "audit-cookie-value");
                cookie.setDomain("127.0.0.1");
                cookie.setPath("/");
                sessions.cookies(key).addCookie(cookie);
                for (int i = 0; i < 1000; i++) sessions.persist(key);
                System.out.printf("COOKIE_HISTORY: cookies=%d, history=%d%n",
                        sessions.cookies(key).getCookies().size(), sessions.seenCookieValues(key).size());
            } finally {
                sessions.unlock(key);
            }
        }
    }

    private static void slowResponse() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                for (int i = 0; i < 25; i++) {
                    body.write('x');
                    body.flush();
                    try { Thread.sleep(80); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); break; }
                }
            }
        });
        upstream.start();
        try (SessionManager sessions = new SessionManager()) {
            ShellRegistry registry = new ShellRegistry();
            ShellDoc shell = ShellParser.parse(yaml("1.0.0", true, "ok")
                    .replace("http://127.0.0.1:9", "http://127.0.0.1:" + upstream.getAddress().getPort())
                    .replace("steps: []", "steps: [{request: {method: GET, path: /slow}}]"));
            registry.put(shell);
            EngineSettings settings = new EngineSettings(java.time.Duration.ofMillis(500),
                    java.time.Duration.ofMinutes(10), 3);
            RuleEngine engine = new RuleEngine(registry, sessions, settings);
            long start = System.nanoTime();
            ExecutionResult result = engine.execute(new ExecutionRequest("audit-slow", Origin.OFFICIAL,
                    shell.shell(), "inspect", Map.of(), "alice", new AccountContext() {
                        public Map<String, String> credentials() { return Map.of(); }
                        public Map<String, String> config() { return Map.of(); }
                    }));
            System.out.printf("TOTAL_TIMEOUT: budgetMs=500, elapsedMs=%d, success=%s, error=%s%n",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), result.success(),
                    result.error() == null ? "none" : result.error().code());
        } finally {
            upstream.stop(0);
        }
    }

    private static void runtimeFindings() throws Exception {
        var root = Files.createTempDirectory("hermit-audit-probe-");
        try (CrabRuntime runtime = CrabRuntime.open(new CrabPaths(root), CrabConfig.defaults(), name -> null);
             SessionManager sessions = new SessionManager()) {
            ShellDoc oldDoc = ShellParser.parse(yaml("1.0.0", true, "old_read"));
            ShellDoc newDoc = ShellParser.parse(yaml("1.1.0", false, "new_write"));
            runtime.registry().put(oldDoc);
            runtime.admin().createAccount(oldDoc.shell(), "alice", Actors.CLI);
            var ds = runtime.dataSource();
            var cipher = new CredentialCipher(new byte[32]); // Empty credential store; test-only key.
            Invoker invoker = new Invoker(runtime.registry(), new AccountStore(ds),
                    new CredentialVault(ds, cipher), new ShellConfigStore(ds),
                    new RuleEngine(runtime.registry(), sessions), new CallLogStore(ds, Clock.systemUTC()),
                    new IdempotencyStore(ds, cipher, Clock.systemUTC()), (shell, action) -> {
                        // Deterministic interleaving: sync replaces the registry after Invoker reads v1.
                        runtime.registry().put(newDoc);
                        return false;
                    });
            var readOnly = new ApiKeyPrincipal("audit-read", "audit", new ApiKeyScope(Set.of(), Set.of(), true));
            var outcome = invoker.invoke(readOnly, oldDoc.shell(), "inspect", Map.of(),
                    Optional.empty(), Optional.empty(), "audit-race");
            System.out.printf("SNAPSHOT_RACE: newActionPermitted=%s, status=%d, body=%s%n",
                    readOnly.permits(newDoc.shell(), "inspect", false), outcome.status(),
                    new String(outcome.body(), StandardCharsets.UTF_8));

            var writable = new ApiKeyPrincipal("audit-write", "audit", new ApiKeyScope(Set.of(), Set.of(), false));
            for (int i = 0; i < 100; i++) {
                var call = runtime.invoker().invoke(writable, newDoc.shell(), "inspect", Map.of(),
                        Optional.empty(), Optional.of("audit-key-" + i), "audit-idem-" + i);
                if (call.status() != 200) throw new IllegalStateException("probe call failed: " + call.status());
            }
            var lockField = Invoker.class.getDeclaredField("idempotencyLocks");
            lockField.setAccessible(true);
            int before = ((Map<?, ?>) lockField.get(runtime.invoker())).size();
            new IdempotencyStore(ds, cipher, Clock.systemUTC()).purgeBefore(java.time.Instant.now().plusSeconds(1));
            int after = ((Map<?, ?>) lockField.get(runtime.invoker())).size();
            System.out.printf("IDEMPOTENCY_LOCKS: beforePurge=%d, afterPurge=%d%n", before, after);

            ShellDoc secretDoc = ShellParser.parse(yaml("1.2.0", true, "{{config.access_value}}")
                    .replace("credentials: {}", "credentials: {}\nconfig:\n  access_value: {type: secret, label: Access value}"));
            runtime.registry().put(secretDoc);
            String marker = "audit-dummy-sensitive-value";
            runtime.admin().putConfig(secretDoc, "access_value", marker, Actors.CLI);
            boolean plaintext;
            try (var conn = ds.getConnection(); var stmt = conn.createStatement();
                 var rows = stmt.executeQuery("SELECT \"value\" FROM shell_config WHERE \"key\" = 'access_value'")) {
                plaintext = rows.next() && marker.equals(rows.getString(1));
            }
            var secretOutput = runtime.invoker().invoke(readOnly, secretDoc.shell(), "inspect", Map.of(),
                    Optional.empty(), Optional.empty(), "audit-config");
            System.out.printf("SECRET_CONFIG: storedAsPlaintext=%s, returnedUnmasked=%s%n", plaintext,
                    new String(secretOutput.body(), StandardCharsets.UTF_8).contains(marker));
        }
        // Retain synthetic data only, so a reviewer can inspect it. Never opens ~/.hermit.
        System.out.println("TEMP_DATA: " + root);
    }

    private static String yaml(String version, boolean readOnly, String value) {
        return """
                shell: audit_probe
                version: %s
                min_crab_version: 1.0.0
                driver: http
                base_url: http://127.0.0.1:9
                title: Audit probe
                description: Synthetic audit fixture
                credentials: {}
                actions:
                  inspect:
                    title: Inspect
                    description: Synthetic action
                    method: %s
                    mcp: {read_only: %s}
                    steps: []
                    examples: [{input: {}, output: {value: example}}]
                    notes: Synthetic fixture only
                    output:
                      value: {type: string, description: Result, value: '%s'}
                """.formatted(version, readOnly ? "GET" : "POST", readOnly, value);
    }
}
