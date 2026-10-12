package dev.hermit.crab.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CrabPathsTest {
    @TempDir
    Path temp;

    @Test
    void defaultRootIsTheHermitDirectoryInTheUserHome() {
        assertThat(new CrabPaths().root()).isEqualTo(Path.of(System.getProperty("user.home"), ".hermit"));
    }

    @Test
    void constructorOverridesTheRootAndDirectoriesAppearOnFirstUse() {
        CrabPaths paths = new CrabPaths(temp);
        assertThat(paths.root()).isEqualTo(temp.toAbsolutePath().normalize());
        assertThat(temp.resolve("shells")).doesNotExist();
        assertThat(temp.resolve("local-shells")).doesNotExist();
        assertThat(temp.resolve("data")).doesNotExist();
        assertThat(temp.resolve("logs")).doesNotExist();

        assertThat(paths.shells()).isEqualTo(temp.resolve("shells").toAbsolutePath().normalize());
        assertThat(temp.resolve("shells")).isDirectory();
        assertThat(temp.resolve("data")).doesNotExist();

        paths.localShells();
        paths.data();
        paths.logs();
        assertThat(temp.resolve("local-shells")).isDirectory();
        assertThat(temp.resolve("data")).isDirectory();
        assertThat(temp.resolve("logs")).isDirectory();
        assertThat(paths.masterKeyFile()).isEqualTo(temp.resolve("data").resolve("master.key").toAbsolutePath().normalize());
        assertThat(paths.databaseFile()).isEqualTo(temp.resolve("data").resolve("crab").toAbsolutePath().normalize());
    }
}
