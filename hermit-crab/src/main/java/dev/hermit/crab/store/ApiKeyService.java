package dev.hermit.crab.store;

import dev.hermit.crab.secure.ApiKeyTokens;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ApiKeyService {
    private final DataSource dataSource;

    public ApiKeyService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public CreatedApiKey create(String name, ApiKeyScope scope, String actor) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("api key name is required");
        }
        Objects.requireNonNull(scope, "scope");
        String checkedActor = Checks.actor(actor);
        ApiKeyTokens.Issued issued = ApiKeyTokens.issue();
        String id = UUID.randomUUID().toString();
        Instant createdAt = Instant.now();
        String scopeJson = Json.write(scope);
        Db.transaction(dataSource, connection -> {
            // Persist the hash and prefix only. The plaintext stays in the return value.
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO api_keys (id, name, prefix, hash, scope_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, name);
                statement.setString(3, issued.prefix());
                statement.setString(4, issued.hashHex());
                statement.setString(5, scopeJson);
                Db.setInstant(statement, 6, createdAt);
                statement.executeUpdate();
            }
            ConfigAudit.append(
                    connection,
                    checkedActor,
                    AuditKind.API_KEY_CREATED,
                    "apikey/" + id,
                    Json.write(Map.of("id", id, "name", name)));
            return null;
        });
        return new CreatedApiKey(id, name, issued.prefix(), issued.plaintext(), scope, createdAt);
    }

    public List<ApiKeySummary> list() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, name, prefix, scope_json, created_at, revoked_at, last_used_at
                    FROM api_keys
                    ORDER BY created_at, id
                    """);
                 ResultSet resultSet = statement.executeQuery()) {
                List<ApiKeySummary> keys = new ArrayList<>();
                while (resultSet.next()) {
                    keys.add(new ApiKeySummary(
                            resultSet.getString("id"),
                            resultSet.getString("name"),
                            resultSet.getString("prefix"),
                            Json.read(resultSet.getString("scope_json"), ApiKeyScope.class),
                            Db.instant(resultSet, "created_at"),
                            Db.instant(resultSet, "revoked_at"),
                            Db.instant(resultSet, "last_used_at")));
                }
                return List.copyOf(keys);
            }
        });
    }

    public void revoke(String id, String actor) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("api key id is required");
        }
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            boolean revoked;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT revoked_at FROM api_keys WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("api key not found");
                    }
                    revoked = resultSet.getObject("revoked_at") != null;
                }
            }
            if (revoked) {
                throw new IllegalStateException("api key is already revoked");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE api_keys SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL
                    """)) {
                Db.setInstant(statement, 1, Instant.now());
                statement.setString(2, id);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("api key is already revoked");
                }
            }
            ConfigAudit.append(
                    connection,
                    checkedActor,
                    AuditKind.API_KEY_REVOKED,
                    "apikey/" + id,
                    Json.write(Map.of("id", id)));
            return null;
        });
    }

    public Optional<ApiKeyPrincipal> authenticate(String presented) {
        if (presented == null || presented.length() < ApiKeyTokens.PREFIX_LENGTH) {
            return Optional.empty();
        }
        String prefix = presented.substring(0, ApiKeyTokens.PREFIX_LENGTH);
        byte[] presentedHash = ApiKeyTokens.sha256(presented);
        return Db.transaction(dataSource, connection -> {
            List<StoredKey> rows = loadByPrefix(connection, prefix);
            StoredKey match = null;
            for (StoredKey row : rows) {
                if (ApiKeyTokens.constantTimeEquals(presentedHash, row.hashHex())) {
                    match = row;
                }
            }
            if (rows.isEmpty()) {
                // Compare once on a miss so a missing prefix does not skip the hash compare.
                ApiKeyTokens.constantTimeEquals(presentedHash, null);
            }
            if (match == null || match.revoked()) {
                return Optional.empty();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE api_keys SET last_used_at = ? WHERE id = ? AND revoked_at IS NULL")) {
                Db.setInstant(statement, 1, Instant.now());
                statement.setString(2, match.id());
                if (statement.executeUpdate() != 1) {
                    return Optional.empty();
                }
            }
            return Optional.of(new ApiKeyPrincipal(match.id(), match.name(), Json.read(match.scopeJson(), ApiKeyScope.class)));
        });
    }

    private static List<StoredKey> loadByPrefix(Connection connection, String prefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, hash, scope_json, revoked_at
                FROM api_keys
                WHERE prefix = ?
                """)) {
            statement.setString(1, prefix);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredKey> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new StoredKey(
                            resultSet.getString("id"),
                            resultSet.getString("name"),
                            resultSet.getString("hash"),
                            resultSet.getString("scope_json"),
                            resultSet.getObject("revoked_at") != null));
                }
                return rows;
            }
        }
    }

    private record StoredKey(String id, String name, String hashHex, String scopeJson, boolean revoked) {
        @Override
        public String toString() {
            return "StoredKey[id=" + id + "]";
        }
    }
}
