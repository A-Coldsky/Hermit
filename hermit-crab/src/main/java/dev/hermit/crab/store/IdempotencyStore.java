package dev.hermit.crab.store;

import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.CredentialDecryptException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores the client envelope for a POST idempotency key. The blob is ciphertext; the AAD field
 * is {@code idempotency}. A row older than 24 hours, or one that no longer decrypts, is deleted
 * and treated as a miss.
 */
public final class IdempotencyStore {
    public static final Duration TTL = Duration.ofHours(24);
    static final String FIELD = "idempotency";

    private final DataSource dataSource;
    private final CredentialCipher cipher;
    private final Clock clock;

    public IdempotencyStore(DataSource dataSource, CredentialCipher cipher, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<String> findFresh(Origin origin, String shell, String account, String action, String key) {
        return Db.transaction(dataSource, connection -> {
            Row row = select(connection, origin, shell, account, action, key);
            if (row == null) {
                return Optional.empty();
            }
            if (!row.createdAt().isAfter(clock.instant().minus(TTL))) {
                delete(connection, origin, shell, account, action, key);
                return Optional.empty();
            }
            try {
                return Optional.of(cipher.decrypt(origin, shell, account, FIELD, row.ciphertext()));
            } catch (CredentialDecryptException ex) {
                delete(connection, origin, shell, account, action, key);
                return Optional.empty();
            }
        });
    }

    public void save(Origin origin, String shell, String account, String action, String key, String envelopeJson) {
        Objects.requireNonNull(envelopeJson, "envelopeJson");
        byte[] ciphertext = cipher.encrypt(origin, shell, account, FIELD, envelopeJson);
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO idempotency (origin, shell, account, action, "key", created_at, response_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, origin.name());
                statement.setString(2, shell);
                statement.setString(3, account);
                statement.setString(4, action);
                statement.setString(5, key);
                Db.setInstant(statement, 6, clock.instant());
                statement.setBytes(7, ciphertext);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public int purgeBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM idempotency WHERE created_at < ?")) {
                Db.setInstant(statement, 1, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    private static Row select(
            Connection connection,
            Origin origin,
            String shell,
            String account,
            String action,
            String key
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT created_at, response_json FROM idempotency
                WHERE origin = ? AND shell = ? AND account = ? AND action = ? AND "key" = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
            statement.setString(3, account);
            statement.setString(4, action);
            statement.setString(5, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new Row(Db.instant(resultSet, "created_at"), resultSet.getBytes("response_json"));
            }
        }
    }

    private static void delete(
            Connection connection,
            Origin origin,
            String shell,
            String account,
            String action,
            String key
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM idempotency
                WHERE origin = ? AND shell = ? AND account = ? AND action = ? AND "key" = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
            statement.setString(3, account);
            statement.setString(4, action);
            statement.setString(5, key);
            statement.executeUpdate();
        }
    }

    private record Row(Instant createdAt, byte[] ciphertext) {
    }
}
