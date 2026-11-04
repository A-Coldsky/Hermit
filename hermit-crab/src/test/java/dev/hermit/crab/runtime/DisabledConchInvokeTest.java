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

import dev.hermit.common.parse.ConchParser;
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
import dev.hermit.crab.sync.DisabledConches;

class DisabledConchInvokeTest {

    private static final String CONCH = "audit_probe";
    private static final String ACTION = "inspect";
    private static final String MARKER = "old_read";

    @TempDir
    Path temp;

    @Test
    void aLoadedDisabledConchStaysCallableUntilTheRegistryLosesIt() {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp));
                SessionManager sessions = new SessionManager()) {
            CredentialCipher cipher = new CredentialCipher(new byte[32]);
            AccountStore accounts = new AccountStore(database.dataSource());
            ConchRegistry registry = new ConchRegistry();
            registry.put(ConchParser.parse(conchYaml()));
            accounts.create(Origin.OFFICIAL, CONCH, "alice", Actors.CLI);
            DisabledConches disabled = DisabledConches.none();
            disabled.mark(CONCH, "1.0.0", Instant.EPOCH);
            boolean[] capabilityOff = {false};
            Invoker invoker = new Invoker(
                    registry,
                    accounts,
                    new CredentialVault(database.dataSource(), cipher),
                    new ConchConfigStore(database.dataSource()),
                    new RuleEngine(registry, sessions),
                    new CallLogStore(database.dataSource(), Clock.systemUTC()),
                    new IdempotencyStore(database.dataSource(), cipher, Clock.systemUTC()),
                    (conch, action) -> capabilityOff[0],
                    disabled);
            ApiKeyPrincipal principal = new ApiKeyPrincipal("key", "key", new ApiKeyScope(Set.of(), Set.of(), true));

            InvokeOutcome open = invoker.invoke(
                    principal, CONCH, ACTION, Map.of(), Optional.empty(), Optional.empty(), "still-loaded");
            assertThat(open.status()).isEqualTo(200);
            assertThat(new String(open.body(), StandardCharsets.UTF_8)).contains(MARKER);
            assertThat(registry.find(CONCH)).isPresent();

            capabilityOff[0] = true;
            InvokeOutcome capability = invoker.invoke(
                    principal, CONCH, ACTION, Map.of(), Optional.empty(), Optional.empty(), "capability-off");
            assertThat(capability.status()).isEqualTo(404);
            assertThat(new String(capability.body(), StandardCharsets.UTF_8))
                    .contains("CAPABILITY_DISABLED")
                    .contains(Invoker.CAPABILITY_DISABLED_MESSAGE)
                    .doesNotContain("NOT_FOUND")
                    .doesNotContain("not found")
                    .doesNotContain("CONCH_DISABLED")
                    .doesNotContain(MARKER);
            InvokeOutcome absent = invoker.invoke(
                    principal, CONCH, "no_such", Map.of(), Optional.empty(), Optional.empty(), "missing-action");
            assertThat(absent.status()).isEqualTo(404);
            assertThat(new String(absent.body(), StandardCharsets.UTF_8))
                    .contains("NOT_FOUND")
                    .contains("not found")
                    .doesNotContain("CAPABILITY_DISABLED");
            capabilityOff[0] = false;

            ApiKeyPrincipal narrow = new ApiKeyPrincipal(
                    "narrow", "narrow", new ApiKeyScope(Set.of("other_pack"), Set.of(), false));
            InvokeOutcome forbidden = invoker.invoke(
                    narrow, CONCH, ACTION, Map.of(), Optional.empty(), Optional.empty(), "narrow");
            assertThat(forbidden.status()).isEqualTo(403);
            assertThat(new String(forbidden.body(), StandardCharsets.UTF_8))
                    .contains("FORBIDDEN")
                    .doesNotContain("CONCH_DISABLED");

            registry.remove(CONCH);
            InvokeOutcome blocked = invoker.invoke(
                    principal, CONCH, ACTION, Map.of(), Optional.empty(), Optional.empty(), "unloaded");
            assertThat(blocked.status()).isEqualTo(409);
            assertThat(new String(blocked.body(), StandardCharsets.UTF_8))
                    .contains("CONCH_DISABLED")
                    .contains(DisabledConches.CALL_MESSAGE)
                    .doesNotContain("conch disabled")
                    .doesNotContain(MARKER);
        }
    }

    private static String conchYaml() {
        return """
                conch: %s
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
                """.formatted(CONCH, MARKER);
    }
}
