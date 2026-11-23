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
 * Stores the client envelope for a POST idempotency key, isolated by {@code api_key_id}
 * (schema 22). The blob is ciphertext; the AAD field is {@code idempotency}. {@code body_sha256}
 * is the lowercase hex SHA-256 of the raw request body and is not encrypted. A row older than
 * 24 hours (or a caller-supplied shorter window), or one that no longer decrypts, is deleted
 * and treated as a miss. A null digest is a row written before schema 10.
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

    public Optional<Cached> findFresh(Origin origin, String conch, String account, String action, String key) {
        return findFresh(origin, conch, account, action, "", key, TTL);
    }

    public Optional<Cached> findFresh(
            Origin origin,
            String conch,
            String account,
            String action,
            String apiKeyId,
            String key
    ) {
        return findFresh(origin, conch, account, action, apiKeyId, key, TTL);
    }

    public Optional<Cached> findFresh(
            Origin origin,
            String conch,
            String account,
            String action,
            String apiKeyId,
            String key,
            Duration maxAge
    ) {
        String normalizedKeyId = normalizeApiKeyId(apiKeyId);
        Duration window = effectiveWindow(maxAge);
        return Db.transaction(dataSource, connection -> {
            Row row = select(connection, origin, conch, account, action, normalizedKeyId, key);
            if (row == null) {
                return Optional.empty();
            }
            if (!row.createdAt().isAfter(clock.instant().minus(window))) {
                delete(connection, origin, conch, account, action, normalizedKeyId, key);
                return Optional.empty();
            }
            try {
                String envelope = cipher.decrypt(origin, conch, account, FIELD, row.ciphertext());
                return Optional.of(new Cached(envelope, row.bodySha256()));
            } catch (CredentialDecryptException ex) {
                delete(connection, origin, conch, account, action, normalizedKeyId, key);
                return Optional.empty();
            }
        });
    }

    /**
     * {@code bodySha256} is the lowercase hex digest of the raw body, or null when the caller has
     * no body bytes (in-process callers). HTTP always passes a digest.
     */
    public void save(
            Origin origin,
            String conch,
            String account,
            String action,
            String key,
            String envelopeJson,
            String bodySha256
    ) {
        save(origin, conch, account, action, "", key, envelopeJson, bodySha256);
    }

    public void save(
            Origin origin,
            String conch,
            String account,
            String action,
            String apiKeyId,
            String key,
            String envelopeJson,
            String bodySha256
    ) {
        Objects.requireNonNull(envelopeJson, "envelopeJson");
        String normalizedKeyId = normalizeApiKeyId(apiKeyId);
        byte[] ciphertext = cipher.encrypt(origin, conch, account, FIELD, envelopeJson);
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO idempotency (
                        origin, conch, account, action, api_key_id, "key", created_at, response_json, body_sha256
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, origin.name());
                statement.setString(2, conch);
                statement.setString(3, account);
                statement.setString(4, action);
                statement.setString(5, normalizedKeyId);
                statement.setString(6, key);
                Db.setInstant(statement, 7, clock.instant());
                statement.setBytes(8, ciphertext);
                statement.setString(9, bodySha256);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public void delete(
            Origin origin,
            String conch,
            String account,
            String action,
            String apiKeyId,
            String key
    ) {
        String normalizedKeyId = normalizeApiKeyId(apiKeyId);
        Db.transaction(dataSource, connection -> {
            delete(connection, origin, conch, account, action, normalizedKeyId, key);
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

    private static String normalizeApiKeyId(String apiKeyId) {
        return apiKeyId == null ? "" : apiKeyId;
    }

    private static Duration effectiveWindow(Duration maxAge) {
        if (maxAge == null || maxAge.compareTo(TTL) > 0) {
            return TTL;
        }
        return maxAge.isNegative() ? Duration.ZERO : maxAge;
    }

    private static Row select(
            Connection connection,
            Origin origin,
            String conch,
            String account,
            String action,
            String apiKeyId,
            String key
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT created_at, response_json, body_sha256 FROM idempotency
                WHERE origin = ? AND conch = ? AND account = ? AND action = ? AND api_key_id = ? AND "key" = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            statement.setString(3, account);
            statement.setString(4, action);
            statement.setString(5, apiKeyId);
            statement.setString(6, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new Row(
                        Db.instant(resultSet, "created_at"),
                        resultSet.getBytes("response_json"),
                        resultSet.getString("body_sha256"));
            }
        }
    }

    private static void delete(
            Connection connection,
            Origin origin,
            String conch,
            String account,
            String action,
            String apiKeyId,
            String key
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM idempotency
                WHERE origin = ? AND conch = ? AND account = ? AND action = ? AND api_key_id = ? AND "key" = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            statement.setString(3, account);
            statement.setString(4, action);
            statement.setString(5, apiKeyId);
            statement.setString(6, key);
            statement.executeUpdate();
        }
    }

    private record Row(Instant createdAt, byte[] ciphertext, String bodySha256) {
    }

    /** Decrypted envelope plus the stored body digest. The digest is null for pre-schema-10 rows. */
    public record Cached(String envelope, String bodySha256) {
    }
}
