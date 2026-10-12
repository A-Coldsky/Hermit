package dev.hermit.crab.store;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialVaultTest {
    private static final String PASSWORD = "HermitVaultPassword-aa11";
    private static final String TOKEN = "HermitVaultToken-bb22";

    @TempDir
    Path temp;

    private StoreFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = StoreFixture.open(temp);
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", "cli");
        fixture.accounts.create(Origin.OFFICIAL, "oa", "bob", "cli");
        fixture.accounts.create(Origin.LOCAL, "oa", "alice", "cli");
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void writeOverwriteAndEmptyStringDeleteHideValuesExceptOnDecrypt() {
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("password", PASSWORD, "token", TOKEN), "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("password", PASSWORD + "-new"), "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("token", ""), "cli");

        assertThat(fixture.vault.fieldNames(Origin.OFFICIAL, "oa", "alice")).containsExactly("password");
        SecretChecks.absent(fixture.vault.fieldNames(Origin.OFFICIAL, "oa", "alice").toString(), PASSWORD);
        SecretChecks.sameMap(
                fixture.vault.decryptForExecution(Origin.OFFICIAL, "oa", "alice"),
                Map.of("password", PASSWORD + "-new"));
        SecretChecks.missing(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), TOKEN);
        SecretChecks.absent(fixture.vault.toString(), PASSWORD);
        SecretChecks.absent(auditText(), PASSWORD);
        SecretChecks.absent(auditText(), TOKEN);
        assertThat(auditText()).contains("password").contains("token").contains("CREDENTIALS_WRITTEN");
    }

    @Test
    void secretsStayInsideOneOriginAndAccount() {
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("password", PASSWORD), "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "bob", Map.of("password", TOKEN), "cli");
        fixture.vault.write(Origin.LOCAL, "oa", "alice", Map.of("password", "HermitLocalOnly-cc33"), "cli");

        SecretChecks.sameMap(
                fixture.vault.decryptForExecution(Origin.OFFICIAL, "oa", "bob"),
                Map.of("password", TOKEN));
        SecretChecks.contains(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), PASSWORD);
        SecretChecks.contains(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), TOKEN);
        SecretChecks.missing(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), "HermitLocalOnly-cc33");
        SecretChecks.contains(fixture.vault.allSecretValues(Origin.LOCAL, "oa"), "HermitLocalOnly-cc33");
    }

    @Test
    void ciphertextCopiedOntoAnotherFieldOrAccountDoesNotDecrypt() throws Exception {
        fixture.vault.write(
                Origin.OFFICIAL,
                "oa",
                "alice",
                Map.of("password", PASSWORD, "token", PASSWORD),
                "cli");
        byte[] password = readCiphertext("alice", "password");
        byte[] token = readCiphertext("alice", "token");
        assertThat(password).isNotEqualTo(token);
        assertThat(SecretChecks.indexOf(password, PASSWORD.getBytes(StandardCharsets.UTF_8))).isNegative();

        writeCiphertext("alice", "password", token);
        assertThatThrownBy(() -> fixture.vault.decryptForExecution(Origin.OFFICIAL, "oa", "alice"))
                .isInstanceOf(dev.hermit.crab.secure.CredentialDecryptException.class);

        writeCiphertext("alice", "password", password);
        writeCiphertext("bob", "password", password);
        fixture.vault.write(Origin.OFFICIAL, "oa", "bob", Map.of("token", "placeholder-value"), "cli");
        assertThatThrownBy(() -> fixture.vault.decryptForExecution(Origin.OFFICIAL, "oa", "bob"))
                .isInstanceOf(dev.hermit.crab.secure.CredentialDecryptException.class);
    }

    @Test
    void missingAccountIsRejectedAndAnEmptyAccountDecryptsToNothing() {
        assertThatThrownBy(() -> fixture.vault.write(
                Origin.OFFICIAL, "oa", "missing", Map.of("password", PASSWORD), "cli"))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> fixture.vault.fieldNames(Origin.OFFICIAL, "oa", "missing"))
                .isInstanceOf(AccountNotFoundException.class);
        fixture.accounts.create(Origin.OFFICIAL, "oa", "carol", "cli");
        SecretChecks.sameMap(fixture.vault.decryptForExecution(Origin.OFFICIAL, "oa", "carol"), Map.of());
        assertThat(fixture.vault.fieldNames(Origin.OFFICIAL, "oa", "carol")).isEmpty();
    }

    @Test
    void deleteFieldsRemovesTheDeclarationForEveryOfficialAccount() throws Exception {
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("password", PASSWORD, "username", "alice"), "cli");
        fixture.vault.write(Origin.OFFICIAL, "oa", "bob", Map.of("password", TOKEN), "cli");
        fixture.vault.write(Origin.LOCAL, "oa", "alice", Map.of("password", "HermitLocalOnly-cc33"), "cli");

        fixture.vault.deleteFields(Origin.OFFICIAL, "oa", Set.of("password"));
        fixture.vault.deleteFields(Origin.OFFICIAL, "oa", Set.of());

        assertThat(fixture.vault.fieldNames(Origin.OFFICIAL, "oa", "alice")).containsExactly("username");
        assertThat(fixture.vault.fieldNames(Origin.OFFICIAL, "oa", "bob")).isEmpty();
        SecretChecks.contains(fixture.vault.allSecretValues(Origin.LOCAL, "oa"), "HermitLocalOnly-cc33");
        SecretChecks.missing(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), PASSWORD);
        SecretChecks.missing(fixture.vault.allSecretValues(Origin.OFFICIAL, "oa"), TOKEN);
        TextColumnScanner.assertAbsent(fixture.dataSource, PASSWORD, TOKEN);
        assertThatThrownBy(() -> fixture.vault.deleteFields(Origin.OFFICIAL, "oa", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] readCiphertext(String account, String field) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT ciphertext FROM credentials
                     WHERE origin = ? AND shell = ? AND account = ? AND field = ?
                     """)) {
            statement.setString(1, "OFFICIAL");
            statement.setString(2, "oa");
            statement.setString(3, account);
            statement.setString(4, field);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getBytes(1);
            }
        }
    }

    private void writeCiphertext(String account, String field, byte[] ciphertext) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE credentials SET ciphertext = ?
                     WHERE origin = ? AND shell = ? AND account = ? AND field = ?
                     """)) {
            update.setBytes(1, ciphertext);
            update.setString(2, "OFFICIAL");
            update.setString(3, "oa");
            update.setString(4, account);
            update.setString(5, field);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO credentials (origin, shell, account, field, ciphertext, updated_at)
                     VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                     """)) {
            insert.setString(1, "OFFICIAL");
            insert.setString(2, "oa");
            insert.setString(3, account);
            insert.setString(4, field);
            insert.setBytes(5, ciphertext);
            insert.executeUpdate();
        }
    }

    private String auditText() {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT detail, kind FROM config_audit");
             ResultSet resultSet = statement.executeQuery()) {
            StringBuilder text = new StringBuilder();
            while (resultSet.next()) {
                text.append(resultSet.getString(1)).append('\n').append(resultSet.getString(2)).append('\n');
            }
            return text.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read audit", ex);
        }
    }
}
