package dev.hermit.crab.store;

import dev.hermit.crab.secure.CredentialCipher;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Encrypted credentials for one account.
 *
 * <p>{@link #decryptForExecution} is the only method that returns plaintext fields, and it
 * exists only for the rule engine. {@link #allSecretValues} returns values for redaction,
 * not as a credential read API. No other method may expose plaintext, and {@link #toString()}
 * must not include it.
 */
public final class CredentialVault {
    private final DataSource dataSource;
    private final CredentialCipher cipher;

    public CredentialVault(DataSource dataSource, CredentialCipher cipher) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    public void write(Origin origin, String shell, String account, Map<String, String> fields, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedAccount = Checks.account(account);
        String checkedActor = Checks.actor(actor);
        if (fields == null) {
            throw new IllegalArgumentException("fields are required");
        }
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String field = Checks.field(entry.getKey());
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("credential field value must not be null");
            }
            snapshot.put(field, entry.getValue());
        }
        synchronized (ShellLocks.of(dataSource, checkedOrigin, checkedShell)) {
            Db.transaction(dataSource, connection -> {
                lockAccount(connection, checkedOrigin, checkedShell, checkedAccount);
                if (snapshot.isEmpty()) {
                    return null;
                }
                List<String> written = new ArrayList<>();
                List<String> cleared = new ArrayList<>();
                Instant now = Instant.now();
                for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                    if (entry.getValue().isEmpty()) {
                        deleteField(connection, checkedOrigin, checkedShell, checkedAccount, entry.getKey());
                        cleared.add(entry.getKey());
                    } else {
                        byte[] ciphertext = cipher.encrypt(
                                checkedOrigin, checkedShell, checkedAccount, entry.getKey(), entry.getValue());
                        upsert(connection, checkedOrigin, checkedShell, checkedAccount, entry.getKey(), ciphertext, now);
                        written.add(entry.getKey());
                    }
                }
                Collections.sort(written);
                Collections.sort(cleared);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("fields", written);
                detail.put("cleared", cleared);
                ConfigAudit.append(
                        connection,
                        checkedActor,
                        AuditKind.CREDENTIALS_WRITTEN,
                        ConfigAudit.target(checkedOrigin, checkedShell, checkedAccount),
                        Json.write(detail));
                return null;
            });
        }
    }

    public Set<String> fieldNames(Origin origin, String shell, String account) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedAccount = Checks.account(account);
        return Db.transaction(dataSource, connection -> {
            requireAccount(connection, checkedOrigin, checkedShell, checkedAccount);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT field FROM credentials
                    WHERE origin = ? AND shell = ? AND account = ?
                    ORDER BY field
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedShell);
                statement.setString(3, checkedAccount);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<String> names = new LinkedHashSet<>();
                    while (resultSet.next()) {
                        names.add(resultSet.getString("field"));
                    }
                    return Set.copyOf(names);
                }
            }
        });
    }

    public Map<String, String> decryptForExecution(Origin origin, String shell, String account) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedAccount = Checks.account(account);
        return Db.transaction(dataSource, connection -> {
            requireAccount(connection, checkedOrigin, checkedShell, checkedAccount);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT field, ciphertext FROM credentials
                    WHERE origin = ? AND shell = ? AND account = ?
                    ORDER BY field
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedShell);
                statement.setString(3, checkedAccount);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        String field = resultSet.getString("field");
                        byte[] ciphertext = resultSet.getBytes("ciphertext");
                        fields.put(field, cipher.decrypt(checkedOrigin, checkedShell, checkedAccount, field, ciphertext));
                    }
                    return Map.copyOf(fields);
                }
            }
        });
    }

    public Set<String> allSecretValues(Origin origin, String shell) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT account, field, ciphertext FROM credentials
                    WHERE origin = ? AND shell = ?
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedShell);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<String> values = new LinkedHashSet<>();
                    while (resultSet.next()) {
                        String account = resultSet.getString("account");
                        String field = resultSet.getString("field");
                        byte[] ciphertext = resultSet.getBytes("ciphertext");
                        values.add(cipher.decrypt(checkedOrigin, checkedShell, account, field, ciphertext));
                    }
                    return Set.copyOf(values);
                }
            }
        });
    }

    @Override
    public String toString() {
        return "CredentialVault";
    }

    private static void lockAccount(Connection connection, Origin origin, String shell, String account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account FROM accounts
                WHERE origin = ? AND shell = ? AND account = ?
                FOR UPDATE
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
            statement.setString(3, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AccountNotFoundException(origin, shell, account);
                }
            }
        }
    }

    private static void requireAccount(Connection connection, Origin origin, String shell, String account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account FROM accounts
                WHERE origin = ? AND shell = ? AND account = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
            statement.setString(3, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AccountNotFoundException(origin, shell, account);
                }
            }
        }
    }

    private static void deleteField(
            Connection connection,
            Origin origin,
            String shell,
            String account,
            String field
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM credentials
                WHERE origin = ? AND shell = ? AND account = ? AND field = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
            statement.setString(3, account);
            statement.setString(4, field);
            statement.executeUpdate();
        }
    }

    private static void upsert(
            Connection connection,
            Origin origin,
            String shell,
            String account,
            String field,
            byte[] ciphertext,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE credentials
                SET ciphertext = ?, updated_at = ?
                WHERE origin = ? AND shell = ? AND account = ? AND field = ?
                """)) {
            update.setBytes(1, ciphertext);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, origin.name());
            update.setString(4, shell);
            update.setString(5, account);
            update.setString(6, field);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO credentials (origin, shell, account, field, ciphertext, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, origin.name());
            insert.setString(2, shell);
            insert.setString(3, account);
            insert.setString(4, field);
            insert.setBytes(5, ciphertext);
            Db.setInstant(insert, 6, updatedAt);
            insert.executeUpdate();
        }
    }
}
