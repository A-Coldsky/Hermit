package dev.hermit.reef.conch;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.reef.config.ReefProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SigningKeyStore {

    static final String PRIVATE_FILE = "signing.key";
    static final String PUBLIC_FILE = "signing.pub";
    static final Set<PosixFilePermission> PRIVATE_PERMS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    static final Set<PosixFilePermission> PUBLIC_PERMS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    /** 崩溃残留的公钥被删掉时的日志。不含密钥字节。 */
    static final String LEFTOVER_PUBLIC_KEY = "removing leftover signing.pub because signing.key is absent";

    private static final Logger log = LoggerFactory.getLogger(SigningKeyStore.class);
    private static final int RACE_ATTEMPTS = 100;
    private static final long RACE_WAIT_MILLIS = 10L;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String publicPem;

    public SigningKeyStore(ReefProperties properties) {
        Loaded loaded = loadOrCreate(properties.dataDirectory().resolve("keys"));
        this.privateKey = loaded.privateKey;
        this.publicKey = loaded.publicKey;
        this.publicPem = loaded.publicPem;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public String publicPem() {
        return publicPem;
    }

    PrivateKey privateKey() {
        return privateKey;
    }

    /**
     * 两个 PEM 都先写临时文件。先硬链接 {@code signing.pub}，再硬链接 {@code signing.key}。
     * {@code signing.key} 存在即表示一对完整；链接目标已存在则读取，绝不覆盖。
     * 只有公钥时先按竞态等待私钥，仍没有则删掉这份崩溃残留并重新生成。
     */
    static Loaded loadOrCreate(Path directory) {
        try {
            Files.createDirectories(directory);
            requirePosix(directory);
            Path privateFile = directory.resolve(PRIVATE_FILE);
            Path publicFile = directory.resolve(PUBLIC_FILE);
            rejectSymlink(privateFile);
            rejectSymlink(publicFile);
            if (Files.exists(privateFile)) {
                return readPair(privateFile, publicFile);
            }
            if (Files.exists(publicFile)) {
                Loaded raced = waitForCompletePair(privateFile, publicFile);
                if (raced != null) {
                    return raced;
                }
                if (Files.isRegularFile(privateFile)) {
                    return readPair(privateFile, publicFile);
                }
                if (!Files.isRegularFile(publicFile)) {
                    throw new IllegalStateException("signing key files are incomplete");
                }
                log.warn(LEFTOVER_PUBLIC_KEY);
                Files.deleteIfExists(publicFile);
                if (Files.exists(privateFile)) {
                    return readPair(privateFile, publicFile);
                }
            }
            return publishNewPair(directory, privateFile, publicFile);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not initialize signing key", ex);
        }
    }

    private static Loaded publishNewPair(Path directory, Path privateFile, Path publicFile)
            throws IOException, InterruptedException {
        KeyPair generated = Ed25519Keys.generate();
        String privatePem = Ed25519Keys.writePrivatePem(generated.getPrivate());
        String publicPem = Ed25519Keys.writePublicPem(generated.getPublic());
        Path privateTemp = Files.createTempFile(
                directory, ".signing-", ".key.tmp", PosixFilePermissions.asFileAttribute(PRIVATE_PERMS));
        Path publicTemp = Files.createTempFile(
                directory, ".signing-", ".pub.tmp", PosixFilePermissions.asFileAttribute(PUBLIC_PERMS));
        try {
            Files.writeString(privateTemp, privatePem, StandardCharsets.UTF_8);
            Files.writeString(publicTemp, publicPem, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(privateTemp, PRIVATE_PERMS);
            Files.setPosixFilePermissions(publicTemp, PUBLIC_PERMS);
            try {
                Files.createLink(publicFile, publicTemp);
            } catch (FileAlreadyExistsException ex) {
                return readPairAfterRace(privateFile, publicFile);
            }
            Files.setPosixFilePermissions(publicFile, PUBLIC_PERMS);
            try {
                Files.createLink(privateFile, privateTemp);
            } catch (FileAlreadyExistsException ex) {
                return readPair(privateFile, publicFile);
            }
            Files.setPosixFilePermissions(privateFile, PRIVATE_PERMS);
            return readPair(privateFile, publicFile);
        } finally {
            Files.deleteIfExists(privateTemp);
            Files.deleteIfExists(publicTemp);
        }
    }

    private static Loaded readPairAfterRace(Path privateFile, Path publicFile) throws IOException, InterruptedException {
        Loaded loaded = waitForCompletePair(privateFile, publicFile);
        if (loaded == null) {
            throw new IllegalStateException("signing key files are incomplete");
        }
        return loaded;
    }

    /** 另一进程可能刚链上公钥、还没链上私钥。等到一对都在，或尝试次数用尽。 */
    private static Loaded waitForCompletePair(Path privateFile, Path publicFile)
            throws IOException, InterruptedException {
        for (int attempt = 0; attempt < RACE_ATTEMPTS; attempt++) {
            if (Files.isRegularFile(privateFile) && Files.isRegularFile(publicFile)) {
                return readPair(privateFile, publicFile);
            }
            Thread.sleep(RACE_WAIT_MILLIS);
        }
        if (Files.isRegularFile(privateFile) && Files.isRegularFile(publicFile)) {
            return readPair(privateFile, publicFile);
        }
        return null;
    }

    private static Loaded readPair(Path privateFile, Path publicFile) throws IOException {
        rejectSymlink(privateFile);
        rejectSymlink(publicFile);
        if (!Files.isRegularFile(privateFile) || !Files.isRegularFile(publicFile)) {
            throw new IllegalStateException("signing key files are incomplete");
        }
        Files.setPosixFilePermissions(privateFile, PRIVATE_PERMS);
        String privatePem = Files.readString(privateFile, StandardCharsets.UTF_8);
        String publicPem = Files.readString(publicFile, StandardCharsets.UTF_8);
        PrivateKey privateKey;
        PublicKey publicKey;
        try {
            privateKey = Ed25519Keys.readPrivatePem(privatePem);
            publicKey = Ed25519Keys.readPublicPem(publicPem);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new IllegalStateException("signing key files are not a usable Ed25519 pair");
        }
        requireMatch(privateKey, publicKey);
        return new Loaded(privateKey, publicKey, publicPem);
    }

    private static void requireMatch(PrivateKey privateKey, PublicKey publicKey) {
        try {
            byte[] message = new byte[] {1, 2, 3, 4};
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(message);
            byte[] signed = signature.sign();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(message);
            if (!verifier.verify(signed)) {
                throw new IllegalStateException("signing.pub does not match signing.key");
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("signing.pub does not match signing.key");
        }
    }

    private static void requirePosix(Path directory) throws IOException {
        if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new IOException("POSIX permissions are required for signing.key");
        }
    }

    private static void rejectSymlink(Path file) {
        if (Files.isSymbolicLink(file)) {
            throw new IllegalStateException("signing key path must not be a symlink");
        }
    }

    record Loaded(PrivateKey privateKey, PublicKey publicKey, String publicPem) {
    }
}
