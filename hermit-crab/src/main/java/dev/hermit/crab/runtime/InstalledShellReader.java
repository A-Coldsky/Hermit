package dev.hermit.crab.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.store.CrabPaths;

/**
 * Reads {@code shells/{shell}/current} and {@code {version}/shell.yaml}. Signature checks belong
 * to slice 2d; this reader only parses the installed text.
 */
public final class InstalledShellReader {
    private final Path root;

    public InstalledShellReader(CrabPaths paths) {
        this.root = paths.shells();
    }

    public List<ShellDoc> loadAll() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            List<Path> dirs = children.filter(Files::isDirectory).sorted().toList();
            ArrayList<ShellDoc> docs = new ArrayList<>();
            for (Path dir : dirs) {
                if (Files.isRegularFile(dir.resolve("current"))) {
                    docs.add(read(dir));
                }
            }
            return List.copyOf(docs);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot list installed shells", ex);
        }
    }

    public ShellDoc load(String shell) {
        if (!singleSegment(shell)) {
            throw new IllegalArgumentException("shell is not installed");
        }
        Path dir = root.resolve(shell);
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("current"))) {
            throw new IllegalArgumentException("shell is not installed");
        }
        return read(dir);
    }

    private ShellDoc read(Path dir) {
        String version = version(dir.resolve("current"));
        Path yaml = dir.resolve(version).resolve("shell.yaml");
        if (!Files.isRegularFile(yaml)) {
            throw new IllegalArgumentException("installed shell.yaml is missing");
        }
        String text;
        try {
            text = Files.readString(yaml, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read installed shell", ex);
        }
        ShellDoc doc = ShellParser.parse(text);
        if (!doc.shell().equals(dir.getFileName().toString())) {
            throw new IllegalArgumentException("installed shell name does not match its directory");
        }
        return doc;
    }

    private static String version(Path current) {
        String version;
        try {
            version = Files.readString(current, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read installed shell version", ex);
        }
        if (!singleSegment(version)) {
            throw new IllegalArgumentException("installed shell version is invalid");
        }
        return version;
    }

    private static boolean singleSegment(String value) {
        return value != null
                && !value.isBlank()
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && !value.contains("..")
                && value.indexOf('\0') < 0
                && value.indexOf('\n') < 0
                && value.indexOf('\r') < 0;
    }
}
