package dev.hermit.crab.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConchSwitchStoreTest {
    @TempDir
    Path temp;

    @Test
    void switchSurvivesReopenAndIsAudited() throws Exception {
        try (StoreFixture fixture = StoreFixture.open(temp)) {
            ConchSwitchStore store = new ConchSwitchStore(fixture.dataSource);
            assertThat(store.disabledNames()).isEmpty();
            store.setDisabled(Origin.OFFICIAL, "legacy_oa", true, "cli");
            store.setDisabled(Origin.OFFICIAL, "legacy_oa", true, "cli");
            store.setDisabled(Origin.LOCAL, "other", true, "cli");
            store.setDisabled(Origin.LOCAL, "other", false, "cli");
        }
        try (StoreFixture fixture = StoreFixture.open(temp)) {
            ConchSwitchStore store = new ConchSwitchStore(fixture.dataSource);
            assertThat(store.disabledNames()).containsExactly("legacy_oa");
            List<String> audit = new ArrayList<>();
            try (Connection connection = fixture.dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT kind, target FROM config_audit ORDER BY id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    audit.add(rows.getString("kind") + " " + rows.getString("target"));
                }
            }
            assertThat(audit).containsExactly(
                    "CONCH_SWITCHED_OFF OFFICIAL/legacy_oa",
                    "CONCH_SWITCHED_OFF OFFICIAL/legacy_oa",
                    "CONCH_SWITCHED_OFF LOCAL/other",
                    "CONCH_SWITCHED_ON LOCAL/other");
            store.setDisabled(Origin.OFFICIAL, "legacy_oa", false, "cli");
            assertThat(store.disabledNames()).isEmpty();
        }
    }
}
