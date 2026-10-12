package dev.hermit.crab.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import dev.hermit.crab.store.CrabPaths;

public final class InstanceIds {
    private InstanceIds() {
    }

    public static String loadOrCreate(CrabPaths paths) {
        Path file = paths.instanceIdFile();
        if (Files.isRegularFile(file)) {
            return read(file);
        }
        String created = UUID.randomUUID().toString();
        Path directory = file.getParent();
        Path temp;
        try {
            temp = Files.createTempFile(directory, ".instance-", ".tmp");
            Files.writeString(temp, created + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, file);
            } catch (FileAlreadyExistsException ex) {
                Files.deleteIfExists(temp);
                return read(file);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot write instance id");
        }
        return created;
    }

    private static String read(Path file) {
        String id;
        try {
            id = Files.readString(file, StandardCharsets.UTF_8).trim();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read instance id");
        }
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("instance id is not a UUID");
        }
    }
}
