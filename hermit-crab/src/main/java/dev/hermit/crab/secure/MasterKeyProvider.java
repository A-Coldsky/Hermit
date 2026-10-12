package dev.hermit.crab.secure;

import dev.hermit.crab.store.CrabPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
 * encrypted credentials are not orphaned by generating a new key over it.
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
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(keyFileFlag, "keyFileFlag");
        Objects.requireNonNull(environment, "environment");
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
            writeNewFile(file, generated);
        } catch (IOException ex) {
            Arrays.fill(generated, (byte) 0);
            throw new MasterKeyException("cannot create default master key file: " + file);
        }
        return generated;
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

    private static void writeNewFile(Path file, byte[] key) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(key);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
        } catch (UnsupportedOperationException ex) {
            Files.createFile(file);
        }
        Files.writeString(
                file,
                encoded,
                StandardCharsets.US_ASCII,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(file, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ex) {
            // Non-POSIX file systems have no owner/group/other bits to set.
        }
    }
}
