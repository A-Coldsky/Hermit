package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 整个规则包的本机开关，按包名保存，不分来源。
 * 与 Reef 停用墓碑（{@code disabled_conches}）和按能力的开关（{@code capability_switches}）无关。
 */
public final class ConchSwitchStore {
    private final DataSource dataSource;

    public ConchSwitchStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** {@code origin} 只写进审计目标，用来把记录归到当前载入的那份包。 */
    public void setDisabled(Origin origin, String conch, boolean disabled, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM conch_switches WHERE conch = ?")) {
                delete.setString(1, checkedConch);
                delete.executeUpdate();
            }
            if (disabled) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO conch_switches (conch, disabled, updated_at) VALUES (?, TRUE, ?)")) {
                    insert.setString(1, checkedConch);
                    Db.setInstant(insert, 2, Instant.now());
                    insert.executeUpdate();
                }
            }
            ConfigAudit.append(
                    connection,
                    checkedActor,
                    disabled ? AuditKind.CONCH_SWITCHED_OFF : AuditKind.CONCH_SWITCHED_ON,
                    checkedOrigin.name() + "/" + checkedConch,
                    Json.write(java.util.Map.of("disabled", disabled)));
            return null;
        });
    }

    public Set<String> disabledNames() {
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT conch FROM conch_switches WHERE disabled = TRUE ORDER BY conch");
                 ResultSet resultSet = statement.executeQuery()) {
                Set<String> names = new LinkedHashSet<>();
                while (resultSet.next()) {
                    names.add(resultSet.getString("conch"));
                }
                return Set.copyOf(names);
            }
        });
    }
}
