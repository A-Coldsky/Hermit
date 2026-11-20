package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 本地包和正式包同名时，记住哪一份生效。只有两份都在时才有记录。 */
public final class ConchSourceStore {
    private final DataSource dataSource;

    public ConchSourceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Map<String, Origin> load() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT conch, origin FROM conch_active_origin ORDER BY conch");
                 ResultSet rows = statement.executeQuery()) {
                LinkedHashMap<String, Origin> found = new LinkedHashMap<>();
                while (rows.next()) {
                    String origin = rows.getString("origin");
                    if (Origin.LOCAL.name().equals(origin) || Origin.OFFICIAL.name().equals(origin)) {
                        found.put(rows.getString("conch"), Origin.valueOf(origin));
                    }
                }
                return found;
            }
        });
    }

    /** {@code origin} 为 null 时删掉记录。 */
    public void save(String conch, Origin origin) {
        String checked = Checks.conch(conch);
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM conch_active_origin WHERE conch = ?")) {
                delete.setString(1, checked);
                delete.executeUpdate();
            }
            if (origin != null) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO conch_active_origin (conch, origin, updated_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, checked);
                    insert.setString(2, origin.name());
                    Db.setInstant(insert, 3, Instant.now());
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }
}
