package dev.hermit.crab.store;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.crab.engine.LoginSnapshotStore;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.CredentialDecryptException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 登录快照用 AAD 字段 {@code login} 加密，和 Cookie 的 {@code session} 分开。
 * 解密或 JSON 失败只删 {@code session_login} 这一行，不动 {@code sessions}。
 */
public final class EncryptedLoginSnapshotStore implements LoginSnapshotStore {
    static final String FIELD = "login";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> SNAPSHOT = new TypeReference<>() {
    };

    private final DataSource dataSource;
    private final CredentialCipher cipher;

    public EncryptedLoginSnapshotStore(DataSource dataSource, CredentialCipher cipher) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public void save(SessionKey key, Map<String, String> snapshot) {
        Objects.requireNonNull(key, "key");
        if (snapshot == null || snapshot.isEmpty()) {
            delete(key);
            return;
        }
        String json;
        try {
            json = JSON.writeValueAsString(snapshot);
        } catch (JacksonException ex) {
            throw new IllegalStateException("login snapshot encoding failed");
        }
        byte[] ciphertext = cipher.encrypt(key.origin(), key.conch(), key.account(), FIELD, json);
        Db.transaction(dataSource, connection -> {
            upsert(connection, key, ciphertext, Instant.now());
            return null;
        });
    }

    @Override
    public Map<String, String> load(SessionKey key) {
        Objects.requireNonNull(key, "key");
        byte[] ciphertext = Db.transaction(dataSource, connection -> select(connection, key));
        if (ciphertext == null) {
            return Map.of();
        }
        try {
            String json = cipher.decrypt(key.origin(), key.conch(), key.account(), FIELD, ciphertext);
            Map<String, String> snapshot = JSON.readValue(json, SNAPSHOT);
            if (snapshot == null || snapshot.isEmpty()) {
                return Map.of();
            }
            return Map.copyOf(snapshot);
        } catch (CredentialDecryptException | JacksonException | ClassCastException ex) {
            delete(key);
            return Map.of();
        }
    }

    @Override
    public void delete(SessionKey key) {
        Objects.requireNonNull(key, "key");
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM session_login WHERE origin = ? AND conch = ? AND account = ?
                    """)) {
                bind(statement, key);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static byte[] select(Connection connection, SessionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ciphertext FROM session_login WHERE origin = ? AND conch = ? AND account = ?
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
                UPDATE session_login SET ciphertext = ?, updated_at = ?
                WHERE origin = ? AND conch = ? AND account = ?
                """)) {
            update.setBytes(1, ciphertext);
            Db.setInstant(update, 2, updatedAt);
            update.setString(3, key.origin().name());
            update.setString(4, key.conch());
            update.setString(5, key.account());
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO session_login (origin, conch, account, ciphertext, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, key.origin().name());
            insert.setString(2, key.conch());
            insert.setString(3, key.account());
            insert.setBytes(4, ciphertext);
            Db.setInstant(insert, 5, updatedAt);
            insert.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, SessionKey key) throws SQLException {
        statement.setString(1, key.origin().name());
        statement.setString(2, key.conch());
        statement.setString(3, key.account());
    }
}
