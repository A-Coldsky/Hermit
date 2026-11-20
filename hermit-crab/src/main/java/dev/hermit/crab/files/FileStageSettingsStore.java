package dev.hermit.crab.files;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import javax.sql.DataSource;

import dev.hermit.crab.store.Db;

/** {@code file_stage_settings} 只有一行。没有这一行时用默认值。 */
public final class FileStageSettingsStore {
    private final DataSource dataSource;

    public FileStageSettingsStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<FileStageSettings> read() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT ttl_seconds, max_file_bytes, max_total_bytes FROM file_stage_settings WHERE id = 1
                    """);
                    ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.<FileStageSettings>empty();
                }
                try {
                    return Optional.of(new FileStageSettings(
                            rows.getInt("ttl_seconds"),
                            rows.getLong("max_file_bytes"),
                            rows.getLong("max_total_bytes")));
                } catch (IllegalArgumentException ex) {
                    // 库里的值不在允许范围内时退回默认值，不让 Crab 起不来。
                    return Optional.<FileStageSettings>empty();
                }
            }
        });
    }

    public void write(FileStageSettings settings, Instant now) {
        Db.transaction(dataSource, connection -> {
            int updated;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE file_stage_settings
                    SET ttl_seconds = ?, max_file_bytes = ?, max_total_bytes = ?, updated_at = ?
                    WHERE id = 1
                    """)) {
                update.setInt(1, settings.ttlSeconds());
                update.setLong(2, settings.maxFileBytes());
                update.setLong(3, settings.maxTotalBytes());
                update.setTimestamp(4, Timestamp.from(now));
                updated = update.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO file_stage_settings (id, ttl_seconds, max_file_bytes, max_total_bytes, updated_at)
                        VALUES (1, ?, ?, ?, ?)
                        """)) {
                    insert.setInt(1, settings.ttlSeconds());
                    insert.setLong(2, settings.maxFileBytes());
                    insert.setLong(3, settings.maxTotalBytes());
                    insert.setTimestamp(4, Timestamp.from(now));
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }
}
