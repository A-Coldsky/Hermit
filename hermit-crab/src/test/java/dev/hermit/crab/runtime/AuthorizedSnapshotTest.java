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
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.engine.AccountContext;
import dev.hermit.crab.engine.ExecutionRequest;
import dev.hermit.crab.engine.ExecutionResult;
import dev.hermit.crab.engine.RuleEngine;
import dev.hermit.crab.engine.SessionManager;
import dev.hermit.crab.engine.ShellRegistry;
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
import dev.hermit.crab.store.ShellConfigStore;

class AuthorizedSnapshotTest {
    private static final String SHELL = "audit_probe";
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
            ShellRegistry registry = new ShellRegistry();
            ShellDoc oldDoc = ShellParser.parse(shellYaml("1.0.0", true, OLD_MARKER));
            ShellDoc newDoc = ShellParser.parse(shellYaml("1.1.0", false, NEW_MARKER));
            registry.put(oldDoc);
            accounts.create(Origin.OFFICIAL, oldDoc.shell(), "alice", Actors.CLI);
            Invoker invoker = new Invoker(
                    registry,
                    accounts,
                    new CredentialVault(database.dataSource(), cipher),
                    new ShellConfigStore(database.dataSource()),
                    new RuleEngine(registry, sessions),
                    new CallLogStore(database.dataSource(), Clock.systemUTC()),
                    new IdempotencyStore(database.dataSource(), cipher, Clock.systemUTC()),
                    (shell, action) -> {
                        registry.put(newDoc);
                        return false;
                    });
            ApiKeyPrincipal readOnly = new ApiKeyPrincipal(
                    "read-only", "read-only", new ApiKeyScope(Set.of(), Set.of(), true));
            assertThat(readOnly.permits(newDoc.shell(), ACTION, false)).isFalse();

            InvokeOutcome outcome = invoker.invoke(
                    readOnly,
                    oldDoc.shell(),
                    ACTION,
                    Map.of(),
                    Optional.empty(),
                    Optional.empty(),
                    "snapshot-auth");

            String body = new String(outcome.body(), StandardCharsets.UTF_8);
            assertThat(registry.find(SHELL)).contains(newDoc);
            assertThat(body).doesNotContain(NEW_MARKER);
            if (outcome.status() == 200) {
                assertThat(body).contains(OLD_MARKER);
            }
        }
    }

    @Test
    void authorizedSnapshotIsNotReplacedFromTheRegistry() {
        ShellRegistry registry = new ShellRegistry();
        ShellDoc installed = ShellParser.parse(shellYaml("1.1.0", false, NEW_MARKER));
        ShellDoc snapshot = ShellParser.parse(shellYaml("1.0.0", true, OLD_MARKER));
        ShellDoc otherShell = ShellParser.parse(
                shellYaml("1.0.0", true, OLD_MARKER).replace("shell: " + SHELL, "shell: other_shell"));
        ShellDoc missingAction = ShellParser.parse(shellYaml("1.0.0", true, OLD_MARKER).replace(ACTION + ":", "look:"));
        registry.put(installed);
        try (SessionManager sessions = new SessionManager()) {
            RuleEngine engine = new RuleEngine(registry, sessions);
            ExecutionRequest request = request(SHELL, ACTION);

            ExecutionResult live = engine.execute(request);
            ExecutionResult explicitNull = engine.execute(request, null);
            assertThat(live.success()).isTrue();
            assertThat(explicitNull.success()).isTrue();
            assertThat(live.data()).isEqualTo(Map.of("value", NEW_MARKER));
            assertThat(explicitNull.data()).isEqualTo(Map.of("value", NEW_MARKER));

            ExecutionResult kept = engine.execute(request, snapshot);
            assertThat(kept.success()).isTrue();
            assertThat(kept.shellVersion()).isEqualTo("1.0.0");
            assertThat(kept.data()).isEqualTo(Map.of("value", OLD_MARKER));

            ExecutionResult absent = engine.execute(request, missingAction);
            assertThat(absent.success()).isFalse();
            assertThat(absent.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(absent.error().message()).contains("能力");
            assertThat(absent.shellVersion()).isEqualTo("1.0.0");
            assertThat(String.valueOf(absent.data())).doesNotContain(NEW_MARKER);

            ExecutionResult wrongShell = engine.execute(request, otherShell);
            assertThat(wrongShell.success()).isFalse();
            assertThat(wrongShell.error().code()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(wrongShell.error().message()).contains("shell");
            assertThat(wrongShell.shellVersion()).isNull();
            assertThat(String.valueOf(wrongShell.data())).doesNotContain(NEW_MARKER);
        }
    }

    private static ExecutionRequest request(String shell, String action) {
        return new ExecutionRequest("snapshot-auth", Origin.OFFICIAL, shell, action, Map.of(), "alice", EMPTY_ACCOUNT);
    }

    private static String shellYaml(String version, boolean readOnly, String value) {
        return """
                shell: %s
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
                """.formatted(SHELL, version, readOnly ? "GET" : "POST", readOnly, value);
    }
}
