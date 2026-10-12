package dev.hermit.crab.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.hermit.crab.store.CrabPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstalledShellReaderTest {
    @TempDir
    Path temp;

    @Test
    void readsCurrentVersionAndRejectsATraversalVersion() throws Exception {
        CrabPaths paths = new CrabPaths(temp);
        Path dir = paths.shells().resolve("legacy_oa");
        Files.createDirectories(dir.resolve("1.0.0"));
        String yaml;
        try (var in = InstalledShellReaderTest.class.getClassLoader().getResourceAsStream("samples/legacy_oa.yaml")) {
            yaml = new String(in.readAllBytes());
        }
        Files.writeString(dir.resolve("1.0.0").resolve("shell.yaml"), yaml);
        Files.writeString(dir.resolve("current"), "1.0.0\n");
        InstalledShellReader reader = new InstalledShellReader(paths);
        assertThat(reader.load("legacy_oa").shell()).isEqualTo("legacy_oa");
        assertThat(reader.loadAll()).hasSize(1);

        Files.writeString(dir.resolve("current"), "../secrets\n");
        assertThatThrownBy(() -> reader.load("legacy_oa")).isInstanceOf(IllegalArgumentException.class);
    }
}
