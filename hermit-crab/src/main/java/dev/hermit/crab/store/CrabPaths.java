package dev.hermit.crab.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Local Crab directories. The root defaults to {@code ~/.hermit}; tests pass a temporary root.
 * {@code shells}, {@code local-shells}, {@code data}, and {@code logs} are created on first use.
 */
public final class CrabPaths {
    private final Path root;

    public CrabPaths() {
        this(Path.of(System.getProperty("user.home"), ".hermit"));
    }

    /** {@code HERMIT_HOME} when it is non-blank; otherwise {@link #CrabPaths()}. */
    public static CrabPaths fromEnvironment(Function<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String home = environment.apply("HERMIT_HOME");
        if (home == null || home.isBlank()) {
            return new CrabPaths();
        }
        return new CrabPaths(Path.of(home));
    }

    public Path configFile() {
        return root.resolve("config.yml");
    }

    public Path instanceIdFile() {
        return data().resolve("instance-id");
    }

    public CrabPaths(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path shells() {
        return directory("shells");
    }

    public Path localShells() {
        return directory("local-shells");
    }

    public Path data() {
        return directory("data");
    }

    public Path logs() {
        return directory("logs");
    }

    /** H2 file database path without the {@code .mv.db} suffix. */
    public Path databaseFile() {
        return data().resolve("crab");
    }

    public Path masterKeyFile() {
        return data().resolve("master.key");
    }

    private Path directory(String name) {
        Path path = root.resolve(name);
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot create directory " + path, ex);
        }
        return path;
    }
}
