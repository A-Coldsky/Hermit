package dev.hermit.crab.secure;

import dev.hermit.crab.store.CrabPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Loads the 32-byte AES key.
 *
 * <p>Priority is {@code HERMIT_MASTER_KEY}, then the key-file flag, then {@code data/master.key}.
 * An invalid value from either of the first two sources aborts; it is not replaced by a later
 * source. An existing default file that cannot be decoded is left untouched, so previously
 * encrypted credentials are not orphaned by generating a new key over it. A missing default
 * file is written to a temporary file in the same directory, then published with a hard link
 * so an existing destination fails atomically and is read instead. File systems without hard
 * links fall back to a move that does not replace the destination. A failed write deletes
 * the temporary file and does not leave {@code master.key} behind.
 */
public final class MasterKeyProvider {
    public static final String ENV = "HERMIT_MASTER_KEY";
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private MasterKeyProvider() {
    }

    public static byte[] load(CrabPaths paths, Optional<Path> keyFileFlag) {
        return load(paths, keyFileFlag, System::getenv);
    }

    public static byte[] load(CrabPaths paths, Optional<Path> keyFileFlag, Function<String, String> environment) {
        return load(paths, keyFileFlag, environment, MasterKeyProvider::writeEncodedKey);
    }

    static byte[] load(
            CrabPaths paths,
            Optional<Path> keyFileFlag,
            Function<String, String> environment,
            DefaultKeyWriter writer
    ) {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(keyFileFlag, "keyFileFlag");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(writer, "writer");
        String fromEnvironment = environment.apply(ENV);
        if (fromEnvironment != null) {
            return decode(fromEnvironment, "HERMIT_MASTER_KEY");
        }
        if (keyFileFlag.isPresent()) {
            return readFile(keyFileFlag.get(), "master key file");
        }
        Path file = paths.masterKeyFile();
        if (Files.exists(file)) {
            return readFile(file, "default master key file");
        }
        byte[] generated = new byte[KEY_BYTES];
        RANDOM.nextBytes(generated);
        try {
            byte[] installed = publish(file, generated, writer);
            if (installed != generated) {
                Arrays.fill(generated, (byte) 0);
            }
            return installed;
        } catch (IOException ex) {
            Arrays.fill(generated, (byte) 0);
            throw new MasterKeyException("cannot create default master key file: " + file);
        } catch (RuntimeException ex) {
            Arrays.fill(generated, (byte) 0);
            throw ex;
        }
    }

    private static byte[] readFile(Path file, String source) {
        if (!Files.isRegularFile(file)) {
            throw new MasterKeyException(source + " does not exist: " + file);
        }
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException ex) {
            throw new MasterKeyException("cannot read " + source + ": " + file);
        }
        return decode(raw, source);
    }

    private static byte[] decode(String raw, String source) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new MasterKeyException(source + " is not valid Base64");
        }
        if (decoded.length != KEY_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            throw new MasterKeyException(source + " must decode to exactly 32 bytes");
        }
        return decoded;
    }

    private static byte[] publish(Path file, byte[] key, DefaultKeyWriter writer) throws IOException {
        Path directory = file.getParent();
        if (directory == null) {
            throw new IOException("cannot create default master key file: " + file);
        }
        Path temp = Files.createTempFile(directory, ".master-", ".tmp");
        try {
            restrictToOwner(temp);
            writer.write(temp, key);
            restrictToOwner(temp);
            if (!linkNewFile(temp, file)) {
                return readFile(file, "default master key file");
            }
            restrictToOwner(file);
            return key;
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ex) {
                // The next start must still be able to create master.key. Do not hide the original failure.
            }
        }
    }

    /**
     * Publish {@code temp} as {@code file}. {@code link} fails with EEXIST when the name is
     * taken; {@code rename} would replace a key another process already used.
     *
     * @return {@code false} when {@code file} already exists
     */
    private static boolean linkNewFile(Path temp, Path file) throws IOException {
        try {
            Files.createLink(file, temp);
            return true;
        } catch (FileAlreadyExistsException ex) {
            return false;
        } catch (UnsupportedOperationException | FileSystemException ex) {
            try {
                Files.move(temp, file);
                return true;
            } catch (FileAlreadyExistsException already) {
                return false;
            }
        }
    }

    private static void writeEncodedKey(Path temp, byte[] key) throws IOException {
        Files.writeString(
                temp,
                Base64.getEncoder().encodeToString(key),
                StandardCharsets.US_ASCII,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void restrictToOwner(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ex) {
            // Non-POSIX file systems have no owner/group/other bits to set.
        }
    }

    @FunctionalInterface
    interface DefaultKeyWriter {
        void write(Path tempFile, byte[] key) throws IOException;
    }
}
