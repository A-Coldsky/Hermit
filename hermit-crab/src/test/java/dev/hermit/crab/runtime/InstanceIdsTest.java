package dev.hermit.crab.runtime;

import java.nio.file.Files;
import java.util.UUID;

import dev.hermit.crab.store.CrabPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceIdsTest {
    @TempDir
    java.nio.file.Path temp;

    @Test
    void createsAUuidAndRejectsAnythingElse() throws Exception {
        CrabPaths paths = new CrabPaths(temp);
        String created = InstanceIds.loadOrCreate(paths);
        assertThat(UUID.fromString(created).toString()).isEqualTo(created);
        assertThat(InstanceIds.loadOrCreate(paths)).isEqualTo(created);

        CrabPaths broken = new CrabPaths(temp.resolve("broken"));
        Files.writeString(broken.instanceIdFile(), "not-a-uuid-secret\n");
        assertThatThrownBy(() -> InstanceIds.loadOrCreate(broken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("instance id is not a UUID");
    }
}
