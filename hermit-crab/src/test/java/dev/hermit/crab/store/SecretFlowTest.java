package dev.hermit.crab.store;

import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SecretFlowTest {
    private static final String SECRET = "HermitKnownSecret-b7e1c04a9d2f48e3";
    private static final String CLEARED = "HermitClearedSecret-c19aa0e7";

    @TempDir
    Path temp;

    @Test
    void knownSecretSurvivesTheStoreFlowOnlyInsideCiphertext() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp)) {
            fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
            fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");
            fixture.vault.write(
                    Origin.OFFICIAL,
                    "oa",
                    "alice",
                    Map.of("password", SECRET, "token", CLEARED),
                    "cli");
            fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("token", ""), "cli");
            fixture.accounts.setDefault(Origin.OFFICIAL, "oa", "bob", "cli");
            fixture.config.put(Origin.OFFICIAL, "oa", "base_url", "http://legacy.example", "cli");
            CreatedApiKey created = fixture.apiKeys.create(
                    "ci", new ApiKeyScope(Set.of("oa"), Set.of(), true), "cli");
            assertThat(fixture.apiKeys.authenticate(created.plaintext())).isPresent();
            CreatedApiKey revoked = fixture.apiKeys.create(
                    "old", new ApiKeyScope(Set.of(), Set.of(), false), "cli");
            fixture.apiKeys.revoke(revoked.id(), "cli");

            String redacted = Redactor.of(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"))
                    .redact("user " + SECRET + " done");
            if (!"user *** done".equals(redacted)) {
                throw new AssertionError("redactor left a secret in text of length "
                        + (redacted == null ? -1 : redacted.length()));
            }
            Map<String, Object> fields = Redactor.of(Set.of()).redactFields(Map.of(
                    "password", SECRET,
                    "user", "alice"));
            SecretChecks.absent(String.valueOf(fields), SECRET);
            SecretChecks.sameMap(
                    fixture.vault.decryptForExecution(Origin.OFFICIAL, "oa", "alice"),
                    Map.of("password", SECRET));

            TextColumnScanner.assertAbsent(fixture.dataSource, SECRET, CLEARED, created.plaintext(), revoked.plaintext());
            assertCiphertextHides(fixture, SECRET);
            assertCiphertextHides(fixture, CLEARED);
            assertFilesHide(fixture.paths.data(), SECRET);
            assertFilesHide(fixture.paths.data(), CLEARED);
            assertFilesHide(fixture.paths.data(), created.plaintext());
            assertFilesHide(fixture.paths.data(), revoked.plaintext());
            assertThat(auditKinds(fixture)).contains(
                    "ACCOUNT_CREATED",
                    "DEFAULT_CHANGED",
                    "CREDENTIALS_WRITTEN",
                    "CONFIG_PUT",
                    "API_KEY_CREATED",
                    "API_KEY_REVOKED");
        }
    }

    private static void assertCiphertextHides(StoreFixture fixture, String secret) throws Exception {
        byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT ciphertext FROM credentials");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                byte[] ciphertext = resultSet.getBytes(1);
                if (SecretChecks.indexOf(ciphertext, needle) >= 0) {
                    throw new AssertionError("plaintext bytes found in ciphertext");
                }
            }
        }
    }

    private static void assertFilesHide(Path directory, String secret) throws Exception {
        byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                byte[] bytes = Files.readAllBytes(file);
                if (SecretChecks.indexOf(bytes, needle) >= 0) {
                    throw new AssertionError("secret bytes found in " + file.getFileName());
                }
            }
        }
    }

    private static Set<String> auditKinds(StoreFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT kind FROM config_audit");
             ResultSet resultSet = statement.executeQuery()) {
            Set<String> kinds = new java.util.HashSet<>();
            while (resultSet.next()) {
                kinds.add(resultSet.getString(1));
            }
            return kinds;
        }
    }
}
