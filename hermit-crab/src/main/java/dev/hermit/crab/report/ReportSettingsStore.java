package dev.hermit.crab.report;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import dev.hermit.crab.store.Db;

/**
 * The schema-13 row that overrides {@code report.enabled} and {@code report.interval_seconds}.
 * No row means config.yml still applies.
 */
public final class ReportSettingsStore {
    public record Saved(boolean enabled, int intervalSeconds, Instant updatedAt) {
    }

    private final DataSource dataSource;

    public ReportSettingsStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Saved> read() {
        return Db.transaction(dataSource, this::read);
    }

    public void write(boolean enabled, int intervalSeconds, Instant updatedAt) {
        if (intervalSeconds < 60) {
            throw new IllegalArgumentException("report.interval_seconds must be at least 60");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt is required");
        }
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE report_settings SET enabled = ?, interval_seconds = ?, updated_at = ? WHERE id = 1
                    """)) {
                update.setBoolean(1, enabled);
                update.setInt(2, intervalSeconds);
                Db.setInstant(update, 3, updatedAt);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO report_settings (id, enabled, interval_seconds, updated_at) VALUES (1, ?, ?, ?)
                    """)) {
                insert.setBoolean(1, enabled);
                insert.setInt(2, intervalSeconds);
                Db.setInstant(insert, 3, updatedAt);
                insert.executeUpdate();
            }
            return null;
        });
    }

    private Optional<Saved> read(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT enabled, interval_seconds, updated_at FROM report_settings WHERE id = 1
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            return Optional.of(new Saved(
                    resultSet.getBoolean("enabled"),
                    resultSet.getInt("interval_seconds"),
                    Db.instant(resultSet, "updated_at")));
        }
    }
}
