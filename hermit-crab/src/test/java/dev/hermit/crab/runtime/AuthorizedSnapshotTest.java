package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.engine.AccountContext;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.store.AccountStore;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CallLogStore;
import dev.hermit.crab.store.CrabDatabase;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.IdempotencyStore;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.store.ConchConfigStore;

class AuthorizedSnapshotTest {
    private static final String CONCH = "audit_probe";
    private static final String ACTION = "inspect";
    private static final String OLD_MARKER = "old_read";
    private static final String NEW_MARKER = "new_write";
    private static final AccountContext EMPTY_ACCOUNT = new AccountContext() {
        @Override
        public Map<String, String> credentials() {
            return Map.of();
        }

        @Override
        public Map<String, String> config() {
            return Map.of();
        }
    };

    @TempDir
    Path temp;

    @Test
    void readOnlyKeyDoesNotExecuteReplacedWriteCapability() {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp));
                SessionManager sessions = new SessionManager()) {
            CredentialCipher cipher = new CredentialCipher(new byte[32]);
            AccountStore accounts = new AccountStore(database.dataSource());
            ConchRegistry registry = new ConchRegistry();
            ConchDoc oldDoc = ConchParser.parse(conchYaml("1.0.0", true, OLD_MARKER));
            ConchDoc newDoc = ConchParser.parse(conchYaml("1.1.0", false, NEW_MARKER));
            registry.put(oldDoc);
            accounts.create(Origin.OFFICIAL, oldDoc.conch(), "alice", Actors.CLI);
            Invoker invoker = new Invoker(
                    registry,
                    accounts,
                    new CredentialVault(database.dataSource(), cipher),
                    new ConchConfigStore(database.dataSource()),
                    new RuleEngine(registry, sessions),
                    new CallLogStore(database.dataSource(), Clock.systemUTC()),
                    new IdempotencyStore(database.dataSource(), cipher, Clock.systemUTC()),
                    (conch, action) -> {
                        registry.put(newDoc);
                        return false;
                    });
            ApiKeyPrincipal readOnly = new ApiKeyPrincipal(
                    "read-only", "read-only", new ApiKeyScope(Set.of(), Set.of(), true));
            assertThat(readOnly.permits(newDoc.conch(), ACTION, false)).isFalse();

            InvokeOutcome outcome = invoker.invoke(
                    readOnly,
                    oldDoc.conch(),
                    ACTION,
                    Map.of(),
                    Optional.empty(),
                    Optional.empty(),
                    "snapshot-auth");

            String body = new String(outcome.body(), StandardCharsets.UTF_8);
            assertThat(registry.find(CONCH)).contains(newDoc);
            assertThat(body).doesNotContain(NEW_MARKER);
            if (outcome.status() == 200) {
                assertThat(body).contains(OLD_MARKER);
            }
        }
    }

    @Test
    void authorizedSnapshotIsNotReplacedFromTheRegistry() {
        ConchRegistry registry = new ConchRegistry();
        ConchDoc installed = ConchParser.parse(conchYaml("1.1.0", false, NEW_MARKER));
        ConchDoc snapshot = ConchParser.parse(conchYaml("1.0.0", true, OLD_MARKER));
        ConchDoc otherConch = ConchParser.parse(
                conchYaml("1.0.0", true, OLD_MARKER).replace("conch: " + CONCH, "conch: other_conch"));
        ConchDoc missingAction = ConchParser.parse(conchYaml("1.0.0", true, OLD_MARKER).replace(ACTION + ":", "look:"));
        registry.put(installed);
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = new RuleEngine(registry, sessions);
            ExecutionRequest request = request(CONCH, ACTION);

            ExecutionResult live = engine.execute(request);
            ExecutionResult explicitNull = engine.execute(request, null);
            assertThat(live.success()).isTrue();
            assertThat(explicitNull.success()).isTrue();
            assertThat(live.data()).isEqualTo(Map.of("value", NEW_MARKER));
            assertThat(explicitNull.data()).isEqualTo(Map.of("value", NEW_MARKER));

            ExecutionResult kept = engine.execute(request, snapshot);
            assertThat(kept.success()).isTrue();
            assertThat(kept.conchVersion()).isEqualTo("1.0.0");
            assertThat(kept.data()).isEqualTo(Map.of("value", OLD_MARKER));

            ExecutionResult absent = engine.execute(request, missingAction);
            assertThat(absent.success()).isFalse();
            assertThat(absent.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(absent.error().message()).contains("能力");
            assertThat(absent.conchVersion()).isEqualTo("1.0.0");
            assertThat(String.valueOf(absent.data())).doesNotContain(NEW_MARKER);

            ExecutionResult wrongConch = engine.execute(request, otherConch);
            assertThat(wrongConch.success()).isFalse();
            assertThat(wrongConch.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(wrongConch.error().message()).isEqualTo("规则包不存在");
            assertThat(wrongConch.conchVersion()).isNull();
            assertThat(String.valueOf(wrongConch.data())).doesNotContain(NEW_MARKER);
        }
    }

    @Test
    void invokerRejectsHttpMethodMismatchAndMcpSnapshotViolationsAtomically() {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp.resolve("invoker-atomic")));
                SessionManager sessions = new SessionManager()) {
            CredentialCipher cipher = new CredentialCipher(new byte[32]);
            AccountStore accounts = new AccountStore(database.dataSource());
            ConchRegistry registry = new ConchRegistry();
            ConchDoc postDoc = ConchParser.parse(conchYaml("1.1.0", false, NEW_MARKER));
            registry.put(postDoc, Origin.OFFICIAL);
            accounts.create(Origin.OFFICIAL, postDoc.conch(), "alice", Actors.CLI);
            Invoker invoker = new Invoker(
                    registry,
                    accounts,
                    new CredentialVault(database.dataSource(), cipher),
                    new ConchConfigStore(database.dataSource()),
                    new RuleEngine(registry, sessions),
                    new CallLogStore(database.dataSource(), Clock.systemUTC()),
                    new IdempotencyStore(database.dataSource(), cipher, Clock.systemUTC()),
                    (conch, action) -> false);
            ApiKeyPrincipal full = new ApiKeyPrincipal(
                    "full", "full", new ApiKeyScope(Set.of(), Set.of(), false, true));

            // Issue 3: HTTP method checked atomically inside Invoker against resolved capability.
            InvokeOutcome wrongMethod = invoker.invoke(
                    InvokeRequest.of(full, CONCH, ACTION, Map.of(), "http-method-race")
                            .httpMethod("GET"));
            assertThat(wrongMethod.status()).isEqualTo(405);
            assertThat(wrongMethod.allowMethod()).isEqualTo("POST");
            assertThat(wrongMethod.requestOnly()).isTrue();
            assertThat(new String(wrongMethod.body(), StandardCharsets.UTF_8))
                    .contains("INVALID_INPUT")
                    .contains("这个能力要用 POST 调用。")
                    .doesNotContain(NEW_MARKER);

            // Issue 5b (1): MCP call rejects unexposed capability with NOT_FOUND.
            ConchDoc hiddenDoc = ConchParser.parse(conchYaml("1.2.0", true, OLD_MARKER, false, false));
            registry.put(hiddenDoc, Origin.OFFICIAL);
            InvokeOutcome hidden = invoker.invoke(
                    InvokeRequest.of(full, CONCH, ACTION, Map.of(), "mcp-hidden")
                            .channel(dev.hermit.crab.store.CallChannel.MCP));
            assertThat(hidden.status()).isEqualTo(404);
            assertThat(new String(hidden.body(), StandardCharsets.UTF_8))
                    .contains("NOT_FOUND")
                    .doesNotContain(OLD_MARKER);

            // Issue 5b (2): MCP confirmation against LOCAL cannot confirm an identical OFFICIAL doc.
            ConchDoc destructiveDoc = ConchParser.parse(conchYaml("1.3.0", false, NEW_MARKER, true, true));
            registry.put(destructiveDoc, Origin.OFFICIAL);
            InvokeOutcome originMismatch = invoker.invoke(
                    InvokeRequest.of(full, CONCH, ACTION, Map.of(), "mcp-origin-mismatch")
                            .channel(dev.hermit.crab.store.CallChannel.MCP)
                            .confirmedAgainst(destructiveDoc, Origin.LOCAL));
            assertThat(originMismatch.status()).isEqualTo(409);
            assertThat(new String(originMismatch.body(), StandardCharsets.UTF_8))
                    .contains("CONFLICT")
                    .contains(Invoker.CHANGED_AFTER_CONFIRM)
                    .doesNotContain(NEW_MARKER);

            // Issue 5b (3): MCP call without elicitation rejects if resolved capability is now destructive.
            InvokeOutcome unconfirmedDestructive = invoker.invoke(
                    InvokeRequest.of(full, CONCH, ACTION, Map.of(), "mcp-unconfirmed-destructive")
                            .channel(dev.hermit.crab.store.CallChannel.MCP));
            assertThat(unconfirmedDestructive.status()).isEqualTo(409);
            assertThat(new String(unconfirmedDestructive.body(), StandardCharsets.UTF_8))
                    .contains("CONFLICT")
                    .contains(Invoker.CHANGED_AFTER_CONFIRM)
                    .doesNotContain(NEW_MARKER);

            // Matching snapshot + origin succeeds.
            InvokeOutcome confirmedOk = invoker.invoke(
                    InvokeRequest.of(full, CONCH, ACTION, Map.of(), "mcp-confirmed-ok")
                            .channel(dev.hermit.crab.store.CallChannel.MCP)
                            .confirmedAgainst(destructiveDoc, Origin.OFFICIAL));
            assertThat(confirmedOk.status()).isEqualTo(200);
            assertThat(new String(confirmedOk.body(), StandardCharsets.UTF_8)).contains(NEW_MARKER);
        }
    }

    @Test
    void localWriteCredentialsDoesNotAcceptFieldsOnlyDeclaredInOfficialStagedUpdate() {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp.resolve("local-staged")));
                SessionManager sessions = new SessionManager()) {
            CredentialCipher cipher = new CredentialCipher(new byte[32]);
            AccountStore accounts = new AccountStore(database.dataSource());
            CredentialVault vault = new CredentialVault(database.dataSource(), cipher);
            ConchConfigStore configStore = new ConchConfigStore(database.dataSource());
            dev.hermit.crab.store.ApiKeyService apiKeys = new dev.hermit.crab.store.ApiKeyService(database.dataSource());
            ConchRegistry registry = new ConchRegistry();
            ConchDoc localDoc = ConchParser.parse(conchYaml("1.0.0", true, OLD_MARKER));
            ConchDoc stagedOfficial = ConchParser.parse(conchYaml("2.0.0", true, OLD_MARKER)
                    .replace("credentials: {}", """
                            credentials:
                              staged_only:
                                type: secret
                                required: true
                                label: 暂存专用"""));
            registry.put(localDoc, Origin.LOCAL);
            accounts.create(Origin.LOCAL, CONCH, "alice", Actors.CONSOLE);
            ConchAdmin admin = new ConchAdmin(
                    database.dataSource(),
                    accounts,
                    vault,
                    configStore,
                    apiKeys,
                    sessions,
                    new RuleEngine(registry, sessions),
                    registry,
                    dev.hermit.crab.sync.DisabledConches.none());
            admin.bindStagedDeclarations(name -> CONCH.equals(name) ? Optional.of(stagedOfficial) : Optional.empty());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> admin.writeCredentials(
                            Origin.LOCAL, localDoc, "alice", Map.of("staged_only", "secret-val"), Actors.CONSOLE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unknown credential field");
            assertThat(admin.declaresCredential(Origin.LOCAL, localDoc, "staged_only")).isFalse();
            assertThat(admin.declaresCredential(Origin.OFFICIAL, localDoc, "staged_only")).isTrue();
        }
    }

    private static ExecutionRequest request(String conch, String action) {
        return new ExecutionRequest("snapshot-auth", Origin.OFFICIAL, conch, action, Map.of(), "alice", EMPTY_ACCOUNT);
    }

    private static String conchYaml(String version, boolean readOnly, String value) {
        return conchYaml(version, readOnly, value, true, false);
    }

    private static String conchYaml(String version, boolean readOnly, String value, boolean expose, boolean destructive) {
        return """
                conch: %s
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
                    mcp: {expose: %s, read_only: %s, destructive: %s}
                    steps: []
                    examples: [{input: {}, output: {value: example}}]
                    notes: Synthetic fixture only
                    output:
                      value: {type: string, description: Result, value: '%s'}
                """.formatted(CONCH, version, readOnly ? "GET" : "POST", expose, readOnly, destructive, value);
    }
}
