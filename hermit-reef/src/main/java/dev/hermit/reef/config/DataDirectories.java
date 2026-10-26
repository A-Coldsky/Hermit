package dev.hermit.reef.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DataDirectories {

    private DataDirectories() {
    }

    public static Path create(Path root) throws IOException {
        Files.createDirectories(root.resolve("db"));
        Files.createDirectories(root.resolve("conches"));
        Files.createDirectories(root.resolve("keys"));
        return root;
    }
}
