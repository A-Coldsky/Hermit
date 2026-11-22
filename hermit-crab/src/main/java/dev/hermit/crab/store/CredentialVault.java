package dev.hermit.crab.store;

import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.CredentialDecryptException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
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
    private final Clock clock;

    public CredentialVault(DataSource dataSource, CredentialCipher cipher) {
        this(dataSource, cipher, Clock.systemUTC());
    }

    public CredentialVault(DataSource dataSource, CredentialCipher cipher, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** True when any credential ciphertext is stored. Used before generating a missing master key. */
    public static boolean hasCiphertext(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM credentials");
                 ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        });
    }

    /**
     * Whether every stored field for this account opens with the current key.
     * An account with no ciphertext is readable. Plaintext is not returned.
     */
    public boolean readable(Origin origin, String conch, String account) {
        try {
            decryptForExecution(origin, conch, account);
            return true;
        } catch (CredentialDecryptException ex) {
            return false;
        }
    }

    /** True when any stored credential blob fails to open. Plaintext is not returned. */
    public boolean anyUnreadable() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT origin, conch, account, field, ciphertext FROM credentials
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Origin rowOrigin;
                    try {
                        rowOrigin = Origin.valueOf(resultSet.getString("origin"));
                    } catch (IllegalArgumentException ex) {
                        return true;
                    }
                    try {
                        cipher.decrypt(
                                rowOrigin,
                                resultSet.getString("conch"),
                                resultSet.getString("account"),
                                resultSet.getString("field"),
                                resultSet.getBytes("ciphertext"));
                    } catch (CredentialDecryptException ex) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    public void write(Origin origin, String conch, String account, Map<String, String> fields, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
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
        synchronized (ConchLocks.of(dataSource, checkedOrigin, checkedConch)) {
            Db.transaction(dataSource, connection -> {
                lockAccount(connection, checkedOrigin, checkedConch, checkedAccount);
                if (snapshot.isEmpty()) {
                    return null;
                }
                List<String> written = new ArrayList<>();
                List<String> cleared = new ArrayList<>();
                Instant now = clock.instant();
                for (Map.Entry<String, String> entry : snapshot.entrySet()) {
                    if (entry.getValue().isEmpty()) {
                        deleteField(connection, checkedOrigin, checkedConch, checkedAccount, entry.getKey());
                        cleared.add(entry.getKey());
                    } else {
                        byte[] ciphertext = cipher.encrypt(
                                checkedOrigin, checkedConch, checkedAccount, entry.getKey(), entry.getValue());
                        upsert(connection, checkedOrigin, checkedConch, checkedAccount, entry.getKey(), ciphertext, now);
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
                        now,
                        checkedActor,
                        AuditKind.CREDENTIALS_WRITTEN,
                        ConfigAudit.target(checkedOrigin, checkedConch, checkedAccount),
                        Json.write(detail));
                return null;
            });
        }
    }

    public Set<String> fieldNames(Origin origin, String conch, String account) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedAccount = Checks.account(account);
        return Db.transaction(dataSource, connection -> {
            requireAccount(connection, checkedOrigin, checkedConch, checkedAccount);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT field FROM credentials
                    WHERE origin = ? AND conch = ? AND account = ?
                    ORDER BY field
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedConch);
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

    public Map<String, String> decryptForExecution(Origin origin, String conch, String account) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedAccount = Checks.account(account);
        return Db.transaction(dataSource, connection -> {
            requireAccount(connection, checkedOrigin, checkedConch, checkedAccount);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT field, ciphertext FROM credentials
                    WHERE origin = ? AND conch = ? AND account = ?
                    ORDER BY field
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedConch);
                statement.setString(3, checkedAccount);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        String field = resultSet.getString("field");
                        byte[] ciphertext = resultSet.getBytes("ciphertext");
                        fields.put(field, cipher.decrypt(checkedOrigin, checkedConch, checkedAccount, field, ciphertext));
                    }
                    return Map.copyOf(fields);
                }
            }
        });
    }

    public Set<String> allSecretValues(Origin origin, String conch) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT account, field, ciphertext FROM credentials
                    WHERE origin = ? AND conch = ?
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedConch);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Set<String> values = new LinkedHashSet<>();
                    while (resultSet.next()) {
                        String account = resultSet.getString("account");
                        String field = resultSet.getString("field");
                        byte[] ciphertext = resultSet.getBytes("ciphertext");
                        values.add(cipher.decrypt(checkedOrigin, checkedConch, account, field, ciphertext));
                    }
                    return Set.copyOf(values);
                }
            }
        });
    }

    /**
     * Deletes a declaration that no longer exists, for every account of this conch.
     * Plaintext is neither read nor written. No audit row is appended: the store only accepts
     * {@code cli} and {@code console} actors, and this call has no actor.
     */
    public void deleteFields(Origin origin, String conch, Set<String> fields) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        if (fields == null) {
            throw new IllegalArgumentException("fields are required");
        }
        Set<String> checked = new LinkedHashSet<>();
        for (String field : fields) {
            checked.add(Checks.field(field));
        }
        if (checked.isEmpty()) {
            return;
        }
        synchronized (ConchLocks.of(dataSource, checkedOrigin, checkedConch)) {
            Db.transaction(dataSource, connection -> {
                for (String field : checked) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            DELETE FROM credentials
                            WHERE origin = ? AND conch = ? AND field = ?
                            """)) {
                        statement.setString(1, checkedOrigin.name());
                        statement.setString(2, checkedConch);
                        statement.setString(3, field);
                        statement.executeUpdate();
                    }
                }
                return null;
            });
        }
    }

    @Override
    public String toString() {
        return "CredentialVault";
    }

    private static void lockAccount(Connection connection, Origin origin, String conch, String account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account FROM accounts
                WHERE origin = ? AND conch = ? AND account = ?
                FOR UPDATE
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            statement.setString(3, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AccountNotFoundException(origin, conch, account);
                }
            }
        }
    }

    private static void requireAccount(Connection connection, Origin origin, String conch, String account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account FROM accounts
                WHERE origin = ? AND conch = ? AND account = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            statement.setString(3, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new AccountNotFoundException(origin, conch, account);
                }
            }
        }
    }

    private static void deleteField(
            Connection connection,
            Origin origin,
            String conch,
            String account,
            String field
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM credentials
                WHERE origin = ? AND conch = ? AND account = ? AND field = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            statement.setString(3, account);
            statement.setString(4, field);
            statement.executeUpdate();
        }
    }

    private static void upsert(
            Connection connection,
            Origin origin,
            String conch,
            String account,
            String field,
            byte[] ciphertext,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE credentials
                SET ciphertext = ?, updated_at = ?
                WHERE origin = ? AND conch = ? AND account = ? AND field = ?
                """)) {
            update.setBytes(1, ciphertext);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, origin.name());
            update.setString(4, conch);
            update.setString(5, account);
            update.setString(6, field);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO credentials (origin, conch, account, field, ciphertext, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, origin.name());
            insert.setString(2, conch);
            insert.setString(3, account);
            insert.setString(4, field);
            insert.setBytes(5, ciphertext);
            Db.setInstant(insert, 6, updatedAt);
            insert.executeUpdate();
        }
    }
}
