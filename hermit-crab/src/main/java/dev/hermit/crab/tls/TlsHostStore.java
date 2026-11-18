package dev.hermit.crab.tls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import dev.hermit.crab.store.Db;

/**
 * schema 17 {@code tls_trusted_hosts}。每个 host:port 一行，最多 256 行。
 * 再次登记同一地址会替换指纹。没有回填。
 */
public final class TlsHostStore implements HostPins {

    public static final int MAX_HOSTS = 256;
    public static final String TOO_MANY = "已登记 256 个站点，不能再添加。";

    private final DataSource dataSource;

    public TlsHostStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Pin find(String host, int port) {
        if (host == null || host.isBlank() || port < 1) {
            return null;
        }
        String key = host.toLowerCase(Locale.ROOT);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT sha256, not_after FROM tls_trusted_hosts WHERE host = ? AND port = ?
                    """)) {
                statement.setString(1, key);
                statement.setInt(2, port);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    return new Pin(rows.getString(1), Db.instant(rows, "not_after"));
                }
            }
        });
    }

    @Override
    public Set<String> keys() {
        return Db.transaction(dataSource, connection -> {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT host, port FROM tls_trusted_hosts");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(rows.getString(1) + ":" + rows.getInt(2));
                }
            }
            return Set.copyOf(keys);
        });
    }

    public List<Row> list() {
        return Db.transaction(dataSource, TlsHostStore::readAll);
    }

    public Row save(Row row) {
        return Db.transaction(dataSource, connection -> {
            if (!exists(connection, row.host(), row.port()) && count(connection) >= MAX_HOSTS) {
                throw new IllegalArgumentException(TOO_MANY);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    MERGE INTO tls_trusted_hosts
                        (host, port, sha256, subject, issuer, not_after, created_at, created_by)
                    KEY (host, port)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, row.host());
                statement.setInt(2, row.port());
                statement.setString(3, row.sha256());
                statement.setString(4, row.subject());
                statement.setString(5, row.issuer());
                Db.setInstant(statement, 6, row.notAfter());
                Db.setInstant(statement, 7, row.createdAt());
                statement.setString(8, row.createdBy());
                statement.executeUpdate();
            }
            return row;
        });
    }

    public boolean delete(String host, int port) {
        String key = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM tls_trusted_hosts WHERE host = ? AND port = ?")) {
                statement.setString(1, key);
                statement.setInt(2, port);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private static List<Row> readAll(Connection connection) throws SQLException {
        ArrayList<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT host, port, sha256, subject, issuer, not_after, created_at, created_by
                FROM tls_trusted_hosts
                ORDER BY host, port
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(new Row(
                        result.getString("host"),
                        result.getInt("port"),
                        result.getString("sha256"),
                        result.getString("subject"),
                        result.getString("issuer"),
                        Db.instant(result, "not_after"),
                        Db.instant(result, "created_at"),
                        result.getString("created_by")));
            }
        }
        return List.copyOf(rows);
    }

    private static boolean exists(Connection connection, String host, int port) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM tls_trusted_hosts WHERE host = ? AND port = ?")) {
            statement.setString(1, host);
            statement.setInt(2, port);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM tls_trusted_hosts");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    public record Row(
            String host,
            int port,
            String sha256,
            String subject,
            String issuer,
            Instant notAfter,
            Instant createdAt,
            String createdBy
    ) {
    }
}
