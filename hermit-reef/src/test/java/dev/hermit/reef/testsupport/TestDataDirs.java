package dev.hermit.reef.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TestDataDirs {

    private TestDataDirs() {
    }

    public static Path create(String name) {
        try {
            return Files.createTempDirectory("hermit-reef-" + name + "-");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
