package dev.hermit.crab.store;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SessionPersistence;
import dev.hermit.crab.engine.StoredCookie;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.CredentialDecryptException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Cookie jar encrypted with AAD field {@code session}. Decrypt or JSON failure deletes the row
 * and reports an empty session. This class does not call {@code SessionManager}.
 */
public final class EncryptedSessionPersistence implements SessionPersistence {
    static final String FIELD = "session";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<StoredCookie>> COOKIES = new TypeReference<>() {
    };

    private final DataSource dataSource;
    private final CredentialCipher cipher;

    public EncryptedSessionPersistence(DataSource dataSource, CredentialCipher cipher) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public void save(SessionKey key, List<StoredCookie> cookies) {
        Objects.requireNonNull(key, "key");
        if (cookies == null || cookies.isEmpty()) {
            delete(key);
            return;
        }
        String json;
        try {
            json = JSON.writeValueAsString(cookies);
        } catch (JacksonException ex) {
            throw new IllegalStateException("session encoding failed");
        }
        byte[] ciphertext = cipher.encrypt(key.origin(), key.shell(), key.account(), FIELD, json);
        Db.transaction(dataSource, connection -> {
            upsert(connection, key, ciphertext, Instant.now());
            return null;
        });
    }

    @Override
    public List<StoredCookie> load(SessionKey key) {
        Objects.requireNonNull(key, "key");
        byte[] ciphertext = Db.transaction(dataSource, connection -> select(connection, key));
        if (ciphertext == null) {
            return List.of();
        }
        try {
            String json = cipher.decrypt(key.origin(), key.shell(), key.account(), FIELD, ciphertext);
            List<StoredCookie> cookies = JSON.readValue(json, COOKIES);
            return cookies == null ? List.of() : List.copyOf(cookies);
        } catch (CredentialDecryptException | JacksonException ex) {
            delete(key);
            return List.of();
        }
    }

    @Override
    public void delete(SessionKey key) {
        Objects.requireNonNull(key, "key");
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM sessions WHERE origin = ? AND shell = ? AND account = ?
                    """)) {
                bind(statement, key);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static byte[] select(Connection connection, SessionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ciphertext FROM sessions WHERE origin = ? AND shell = ? AND account = ?
                """)) {
            bind(statement, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getBytes("ciphertext") : null;
            }
        }
    }

    private static void upsert(Connection connection, SessionKey key, byte[] ciphertext, Instant updatedAt)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE sessions SET ciphertext = ?, updated_at = ?
                WHERE origin = ? AND shell = ? AND account = ?
                """)) {
            update.setBytes(1, ciphertext);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, key.origin().name());
            update.setString(4, key.shell());
            update.setString(5, key.account());
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO sessions (origin, shell, account, ciphertext, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, key.origin().name());
            insert.setString(2, key.shell());
            insert.setString(3, key.account());
            insert.setBytes(4, ciphertext);
            Db.setInstant(insert, 5, updatedAt);
            insert.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, SessionKey key) throws SQLException {
        statement.setString(1, key.origin().name());
        statement.setString(2, key.shell());
        statement.setString(3, key.account());
    }
}
