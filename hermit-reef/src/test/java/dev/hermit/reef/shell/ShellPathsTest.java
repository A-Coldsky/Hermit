package dev.hermit.reef.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellPathsTest {

    @TempDir
    Path dataDir;

    @Test
    void rejectsSegmentsThatCouldEscapeTheShellRoot() {
        assertThatThrownBy(() -> ShellPaths.versionDirectory(dataDir, "..", "1.0.0", true))
                .isInstanceOf(ShellPathException.class);
        assertThatThrownBy(() -> ShellPaths.versionDirectory(dataDir, "legacy_oa", "..", true))
                .isInstanceOf(ShellPathException.class);
        assertThatThrownBy(() -> ShellPaths.versionDirectory(dataDir, "a/b", "1.0.0", true))
                .isInstanceOf(ShellPathException.class);
        assertThatThrownBy(() -> ShellPaths.versionDirectory(dataDir, "legacy_oa", "1.0.0/../../etc", true))
                .isInstanceOf(ShellPathException.class);
        assertThat(Files.exists(dataDir.resolve("etc"))).isFalse();
    }

    @Test
    void doesNotFollowASymlinkOutOfTheShellRoot() throws Exception {
        Path outside = dataDir.resolve("outside");
        Files.createDirectories(outside);
        Path shells = dataDir.resolve("shells");
        Files.createDirectories(shells);
        Files.createSymbolicLink(shells.resolve("legacy_oa"), outside);

        assertThatThrownBy(() -> ShellPaths.versionDirectory(dataDir, "legacy_oa", "1.0.0", true))
                .isInstanceOf(ShellPathException.class);
        try (var children = Files.list(outside)) {
            assertThat(children.count()).isZero();
        }
    }

    @Test
    void placesANormalVersionDirectoryInsideShells() throws Exception {
        Path dir = ShellPaths.versionDirectory(dataDir, "legacy_oa", "1.0.0-rc.1", true);
        Path root = ShellPaths.shellsRoot(dataDir);
        assertThat(dir.startsWith(root)).isTrue();
        assertThat(dir.getFileName().toString()).isEqualTo("1.0.0-rc.1");
        Files.writeString(dir.resolve("shell.yaml"), "shell: legacy_oa\n");
        assertThat(Files.exists(dir.resolve("shell.yaml"))).isTrue();
    }
}
