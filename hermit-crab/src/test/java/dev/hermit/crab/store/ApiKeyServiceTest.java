package dev.hermit.crab.store;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyServiceTest {
    @TempDir
    Path temp;

    private StoreFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = StoreFixture.open(temp);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void plaintextExistsOnlyOnTheCreateResult() throws Exception {
        for (var component : ApiKeySummary.class.getRecordComponents()) {
            assertThat(component.getName()).isNotIn("hash", "plaintext", "secret");
        }
        ApiKeyScope scope = new ApiKeyScope(Set.of(), Set.of(), false);
        CreatedApiKey created = fixture.apiKeys.create("ci", scope, "cli");
        String plaintext = created.plaintext();

        SecretChecks.absent(created.toString(), plaintext);
        assertThat(created.prefix()).isEqualTo(plaintext.substring(0, 8));
        assertThat(fixture.apiKeys.list()).singleElement().satisfies(summary -> {
            SecretChecks.absent(summary.toString(), plaintext);
            assertThat(summary.prefix()).isEqualTo(created.prefix());
            assertThat(summary.id()).isEqualTo(created.id());
            assertThat(summary.revokedAt()).isNull();
            assertThat(summary.lastUsedAt()).isNull();
            assertThat(summary.scope()).isEqualTo(scope);
        });
        assertThat(storedHash(created.id())).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        TextColumnScanner.assertAbsent(fixture.dataSource, plaintext);
        SecretChecks.absent(auditText(), plaintext);
        assertThat(auditText()).contains("API_KEY_CREATED").contains("ci");
    }

    @Test
    void revokedAndUnknownKeysFailAndALiveKeyRecordsLastUse() {
        CreatedApiKey live = fixture.apiKeys.create("live", new ApiKeyScope(Set.of(), Set.of(), false), "cli");
        CreatedApiKey revoked = fixture.apiKeys.create("old", new ApiKeyScope(Set.of(), Set.of(), false), "cli");
        fixture.apiKeys.revoke(revoked.id(), "cli");

        assertThat(fixture.apiKeys.authenticate(revoked.plaintext())).isEmpty();
        assertThat(fixture.apiKeys.authenticate("hk_not-a-real-key")).isEmpty();
        assertThat(fixture.apiKeys.authenticate(null)).isEmpty();
        Optional<ApiKeyPrincipal> principal = fixture.apiKeys.authenticate(live.plaintext());
        assertThat(principal).isPresent();
        SecretChecks.absent(principal.orElseThrow().toString(), live.plaintext());
        assertThat(fixture.apiKeys.list())
                .filteredOn(summary -> summary.id().equals(live.id()))
                .singleElement()
                .extracting(ApiKeySummary::lastUsedAt)
                .isNotNull();
        assertThat(fixture.apiKeys.list())
                .filteredOn(summary -> summary.id().equals(revoked.id()))
                .singleElement()
                .extracting(ApiKeySummary::lastUsedAt)
                .isNull();
        SecretChecks.absent(auditText(), live.plaintext());
        SecretChecks.absent(auditText(), revoked.plaintext());
        assertThatThrownBy(() -> fixture.apiKeys.revoke(revoked.id(), "cli"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.apiKeys.revoke("missing", "cli"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("api key not found");
    }

    @Test
    void lastUsedAtIsNotRewrittenUntilSixtySecondsHavePassed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-12T00:00:00Z"));
        ApiKeyService apiKeys = new ApiKeyService(fixture.dataSource, clock);
        CreatedApiKey created = apiKeys.create("metered", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);

        assertThat(apiKeys.authenticate(created.plaintext())).isPresent();
        Instant first = lastUsedAt(apiKeys, created.id());

        clock.advance(Duration.ofSeconds(59));
        assertThat(apiKeys.authenticate(created.plaintext())).isPresent();
        assertThat(lastUsedAt(apiKeys, created.id())).isEqualTo(first);

        clock.advance(Duration.ofSeconds(1));
        assertThat(apiKeys.authenticate(created.plaintext())).isPresent();
        assertThat(lastUsedAt(apiKeys, created.id())).isAfter(first);
    }

    @Test
    void shellActionAndReadOnlyScopesAllApply() {
        ApiKeyPrincipal shell = principal(new ApiKeyScope(Set.of("oa"), Set.of(), false));
        assertThat(shell.permits("oa", "delete", false)).isTrue();
        assertThat(shell.permits("erp", "list", true)).isFalse();

        ApiKeyPrincipal action = principal(new ApiKeyScope(Set.of(), Set.of("oa.list"), false));
        assertThat(action.permits("oa", "list", false)).isTrue();
        assertThat(action.permits("oa", "delete", false)).isFalse();
        assertThat(action.permits("erp", "list", false)).isFalse();

        ApiKeyPrincipal readOnly = principal(new ApiKeyScope(Set.of(), Set.of(), true));
        assertThat(readOnly.permits("oa", "list", true)).isTrue();
        assertThat(readOnly.permits("oa", "delete", false)).isFalse();

        ApiKeyPrincipal combined = principal(new ApiKeyScope(Set.of("oa"), Set.of("oa.list"), true));
        assertThat(combined.permits("oa", "list", true)).isTrue();
        assertThat(combined.permits("oa", "list", false)).isFalse();
        assertThat(combined.permits("oa", "delete", true)).isFalse();
        assertThat(combined.permits("erp", "list", true)).isFalse();
        assertThat(combined.permits(null, "list", true)).isFalse();
    }

    private static Instant lastUsedAt(ApiKeyService apiKeys, String id) {
        return apiKeys.list().stream()
                .filter(summary -> summary.id().equals(id))
                .findFirst()
                .orElseThrow()
                .lastUsedAt();
    }

    private ApiKeyPrincipal principal(ApiKeyScope scope) {
        CreatedApiKey created = fixture.apiKeys.create("scoped", scope, "cli");
        return fixture.apiKeys.authenticate(created.plaintext()).orElseThrow();
    }

    private String storedHash(String id) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT hash FROM api_keys WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private String auditText() {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT kind, target, detail FROM config_audit");
             ResultSet resultSet = statement.executeQuery()) {
            StringBuilder text = new StringBuilder();
            while (resultSet.next()) {
                text.append(resultSet.getString(1)).append('\n');
                text.append(resultSet.getString(2)).append('\n');
                text.append(resultSet.getString(3)).append('\n');
            }
            return text.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read audit", ex);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
