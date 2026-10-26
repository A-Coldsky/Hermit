package dev.hermit.reef.conch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConchPathsTest {

    @TempDir
    Path dataDir;

    @Test
    void rejectsSegmentsThatCouldEscapeTheConchRoot() {
        assertThatThrownBy(() -> ConchPaths.versionDirectory(dataDir, "..", "1.0.0", true))
                .isInstanceOf(ConchPathException.class);
        assertThatThrownBy(() -> ConchPaths.versionDirectory(dataDir, "legacy_oa", "..", true))
                .isInstanceOf(ConchPathException.class);
        assertThatThrownBy(() -> ConchPaths.versionDirectory(dataDir, "a/b", "1.0.0", true))
                .isInstanceOf(ConchPathException.class);
        assertThatThrownBy(() -> ConchPaths.versionDirectory(dataDir, "legacy_oa", "1.0.0/../../etc", true))
                .isInstanceOf(ConchPathException.class);
        assertThat(Files.exists(dataDir.resolve("etc"))).isFalse();
    }

    @Test
    void doesNotFollowASymlinkOutOfTheConchRoot() throws Exception {
        Path outside = dataDir.resolve("outside");
        Files.createDirectories(outside);
        Path conches = dataDir.resolve("conches");
        Files.createDirectories(conches);
        Files.createSymbolicLink(conches.resolve("legacy_oa"), outside);

        assertThatThrownBy(() -> ConchPaths.versionDirectory(dataDir, "legacy_oa", "1.0.0", true))
                .isInstanceOf(ConchPathException.class);
        try (var children = Files.list(outside)) {
            assertThat(children.count()).isZero();
        }
    }

    @Test
    void placesANormalVersionDirectoryInsideConches() throws Exception {
        Path dir = ConchPaths.versionDirectory(dataDir, "legacy_oa", "1.0.0-rc.1", true);
        Path root = ConchPaths.conchesRoot(dataDir);
        assertThat(dir.startsWith(root)).isTrue();
        assertThat(dir.getFileName().toString()).isEqualTo("1.0.0-rc.1");
        Files.writeString(dir.resolve("conch.yaml"), "conch: legacy_oa\n");
        assertThat(Files.exists(dir.resolve("conch.yaml"))).isTrue();
    }
}
