package dev.hermit.crab.store;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConchConfigStoreTest {
    private static final String VALUE = "HermitConfigSentinel-91ab";

    @TempDir
    Path temp;

    private StoreFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = StoreFixture.open(temp);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void putGetDeleteAndAllAreScopedByOrigin() {
        fixture.config.put(Origin.OFFICIAL, "oa", "base_url", VALUE, "cli");
        fixture.config.put(Origin.OFFICIAL, "oa", "base_url", VALUE + "/v2", "cli");
        fixture.config.put(Origin.OFFICIAL, "oa", "timeout", "5", "cli");
        fixture.config.put(Origin.LOCAL, "oa", "base_url", "http://local.example", "cli");

        assertThat(fixture.config.get(Origin.OFFICIAL, "oa", "base_url")).isEqualTo(Optional.of(VALUE + "/v2"));
        assertThat(fixture.config.all(Origin.OFFICIAL, "oa")).containsEntry("base_url", VALUE + "/v2").containsEntry("timeout", "5");
        assertThat(fixture.config.all(Origin.LOCAL, "oa")).containsOnlyKeys("base_url");
        SecretChecks.absent(auditText(), VALUE);

        fixture.config.delete(Origin.OFFICIAL, "oa", "timeout", "cli");
        assertThat(fixture.config.get(Origin.OFFICIAL, "oa", "timeout")).isEmpty();
        int before = auditCount();
        fixture.config.delete(Origin.OFFICIAL, "oa", "timeout", "cli");
        assertThat(auditCount()).isEqualTo(before);
        assertThat(auditText()).contains("base_url").contains("CONFIG_PUT").contains("CONFIG_DELETED");
    }

    @Test
    void blankKeysAndNullValuesAreRejected() {
        assertThatThrownBy(() -> fixture.config.put(Origin.OFFICIAL, "oa", " ", "x", "cli"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.config.put(Origin.OFFICIAL, "oa", "base_url", null, "cli"))
                .isInstanceOf(NullPointerException.class);
        fixture.config.put(Origin.OFFICIAL, "oa", "empty", "", "cli");
        assertThat(fixture.config.get(Origin.OFFICIAL, "oa", "empty")).contains("");
    }

    private String auditText() {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT kind, detail FROM config_audit");
             ResultSet resultSet = statement.executeQuery()) {
            StringBuilder text = new StringBuilder();
            while (resultSet.next()) {
                text.append(resultSet.getString(1)).append('\n').append(resultSet.getString(2)).append('\n');
            }
            return text.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read audit", ex);
        }
    }

    private int auditCount() {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM config_audit");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot count audit", ex);
        }
    }
}
