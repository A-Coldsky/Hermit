package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.parse.ShellParser;
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
import dev.hermit.crab.sync.DisabledShells;

class DisabledShellInvokeTest {

    private static final String SHELL = "audit_probe";
    private static final String ACTION = "inspect";
    private static final String MARKER = "old_read";

    @TempDir
    Path temp;

    @Test
    void aDisabledShellIsRejectedBeforeTheLoadedSnapshotRuns() {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp));
                SessionManager sessions = new SessionManager()) {
            CredentialCipher cipher = new CredentialCipher(new byte[32]);
            AccountStore accounts = new AccountStore(database.dataSource());
            ShellRegistry registry = new ShellRegistry();
            registry.put(ShellParser.parse(shellYaml()));
            accounts.create(Origin.OFFICIAL, SHELL, "alice", Actors.CLI);
            DisabledShells disabled = DisabledShells.none();
            disabled.mark(SHELL, "1.0.0", Instant.EPOCH);
            Invoker invoker = new Invoker(
                    registry,
                    accounts,
                    new CredentialVault(database.dataSource(), cipher),
                    new ShellConfigStore(database.dataSource()),
                    new RuleEngine(registry, sessions),
                    new CallLogStore(database.dataSource(), Clock.systemUTC()),
                    new IdempotencyStore(database.dataSource(), cipher, Clock.systemUTC()),
                    (shell, action) -> false,
                    disabled);
            ApiKeyPrincipal principal = new ApiKeyPrincipal("key", "key", new ApiKeyScope(Set.of(), Set.of(), true));

            InvokeOutcome blocked = invoker.invoke(
                    principal, SHELL, ACTION, Map.of(), Optional.empty(), Optional.empty(), "disabled");
            String blockedBody = new String(blocked.body(), StandardCharsets.UTF_8);
            assertThat(blocked.status()).isEqualTo(409);
            assertThat(blockedBody).contains("SHELL_DISABLED").doesNotContain(MARKER);
            assertThat(registry.find(SHELL)).isPresent();

            disabled.clear(SHELL);
            InvokeOutcome open = invoker.invoke(
                    principal, SHELL, ACTION, Map.of(), Optional.empty(), Optional.empty(), "enabled");
            assertThat(open.status()).isEqualTo(200);
            assertThat(new String(open.body(), StandardCharsets.UTF_8)).contains(MARKER);
        }
    }

    private static String shellYaml() {
        return """
                shell: %s
                version: 1.0.0
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
                    method: GET
                    mcp: {read_only: true}
                    steps: []
                    examples: [{input: {}, output: {value: example}}]
                    notes: Synthetic fixture only
                    output:
                      value: {type: string, description: Result, value: '%s'}
                """.formatted(SHELL, MARKER);
    }
}
