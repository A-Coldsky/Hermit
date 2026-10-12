package dev.hermit.reef.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hermit.common.pkg.Ed25519Keys;
import dev.hermit.reef.testsupport.MemoryLogAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeyStoreTest {

    @TempDir
    Path directory;

    @Test
    void createsAPairOnceAndDoesNotOverwriteIt() throws Exception {
        SigningKeyStore.Loaded first = SigningKeyStore.loadOrCreate(directory);
        byte[] privateBytes = Files.readAllBytes(directory.resolve(SigningKeyStore.PRIVATE_FILE));
        byte[] publicBytes = Files.readAllBytes(directory.resolve(SigningKeyStore.PUBLIC_FILE));
        assertThat(Files.getPosixFilePermissions(directory.resolve(SigningKeyStore.PRIVATE_FILE)))
                .isEqualTo(SigningKeyStore.PRIVATE_PERMS);
        assertThat(Files.getPosixFilePermissions(directory.resolve(SigningKeyStore.PUBLIC_FILE)))
                .isEqualTo(SigningKeyStore.PUBLIC_PERMS);
        assertThat(new String(publicBytes, StandardCharsets.UTF_8)).isEqualTo(first.publicPem());
        assertThat(first.publicPem()).contains("BEGIN PUBLIC KEY");
        assertPrivateKeyStaysInTheFile(privateBytes, first.publicPem());

        SigningKeyStore.Loaded second = SigningKeyStore.loadOrCreate(directory);
        assertUnchanged(privateBytes, publicBytes);
        assertThat(second.publicPem()).isEqualTo(first.publicPem());
        assertSignature(second.privateKey(), Ed25519Keys.readPublicPem(second.publicPem()));
    }

    @Test
    void keepsAnExistingPairAndRejectsAPublicKeyThatDoesNotMatch() throws Exception {
        KeyPair pair = Ed25519Keys.generate();
        Path privateFile = directory.resolve(SigningKeyStore.PRIVATE_FILE);
        Path publicFile = directory.resolve(SigningKeyStore.PUBLIC_FILE);
        Files.writeString(privateFile, Ed25519Keys.writePrivatePem(pair.getPrivate()));
        Files.writeString(publicFile, Ed25519Keys.writePublicPem(pair.getPublic()));
        byte[] privateBytes = Files.readAllBytes(privateFile);
        byte[] publicBytes = Files.readAllBytes(publicFile);

        SigningKeyStore.Loaded loaded = SigningKeyStore.loadOrCreate(directory);
        assertUnchanged(privateBytes, publicBytes);
        assertThat(loaded.publicPem()).isEqualTo(new String(publicBytes, StandardCharsets.UTF_8));

        Files.delete(publicFile);
        assertThatThrownBy(() -> SigningKeyStore.loadOrCreate(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
        assertUnchanged(privateBytes, null);
        assertThat(Files.exists(publicFile)).isFalse();

        Files.writeString(publicFile, Ed25519Keys.writePublicPem(Ed25519Keys.generate().getPublic()));
        byte[] mismatched = Files.readAllBytes(publicFile);
        assertThatThrownBy(() -> SigningKeyStore.loadOrCreate(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        assertUnchanged(privateBytes, mismatched);
    }

    @Test
    void replacesALeftoverPublicKeyWithANewPair() throws Exception {
        Path publicFile = directory.resolve(SigningKeyStore.PUBLIC_FILE);
        String leftover = Ed25519Keys.writePublicPem(Ed25519Keys.generate().getPublic());
        Files.writeString(publicFile, leftover);
        int logStart = MemoryLogAppender.LINES.size();

        SigningKeyStore.Loaded loaded = SigningKeyStore.loadOrCreate(directory);

        Path privateFile = directory.resolve(SigningKeyStore.PRIVATE_FILE);
        assertThat(loaded.publicPem()).contains("BEGIN PUBLIC KEY");
        assertThat(loaded.publicPem()).isNotEqualTo(leftover);
        assertThat(Files.readString(publicFile)).isEqualTo(loaded.publicPem());
        assertThat(Files.getPosixFilePermissions(privateFile)).isEqualTo(SigningKeyStore.PRIVATE_PERMS);
        assertSignature(loaded.privateKey(), Ed25519Keys.readPublicPem(loaded.publicPem()));
        assertPrivateKeyStaysInTheFile(Files.readAllBytes(privateFile), loaded.publicPem());
        assertLeftoverWasLoggedWithoutKeyMaterial(logStart, leftover, Files.readString(privateFile));

        byte[] privateBytes = Files.readAllBytes(privateFile);
        byte[] publicBytes = Files.readAllBytes(publicFile);
        SigningKeyStore.Loaded again = SigningKeyStore.loadOrCreate(directory);
        assertUnchanged(privateBytes, publicBytes);
        assertThat(again.publicPem()).isEqualTo(loaded.publicPem());
    }

    @Test
    void rejectsADirectoryThatOnlyHasThePrivateKey() throws Exception {
        Path privateFile = directory.resolve(SigningKeyStore.PRIVATE_FILE);
        Files.writeString(privateFile, Ed25519Keys.writePrivatePem(Ed25519Keys.generate().getPrivate()));
        byte[] privateBytes = Files.readAllBytes(privateFile);
        assertThatThrownBy(() -> SigningKeyStore.loadOrCreate(directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incomplete");
        assertUnchanged(privateBytes, null);
        assertThat(Files.exists(directory.resolve(SigningKeyStore.PUBLIC_FILE))).isFalse();
    }

    @Test
    void concurrentCreatorsShareOnePair() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start timeout");
                    }
                    return SigningKeyStore.loadOrCreate(directory).publicPem();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String first = results.get(0).get(20, TimeUnit.SECONDS);
            for (Future<String> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(Files.getPosixFilePermissions(directory.resolve(SigningKeyStore.PRIVATE_FILE)))
                .isEqualTo(SigningKeyStore.PRIVATE_PERMS);
        assertThat(firstPublicLine()).doesNotContain("PRIVATE");
    }

    private void assertUnchanged(byte[] privateBytes, byte[] publicBytes) throws Exception {
        if (!java.util.Arrays.equals(Files.readAllBytes(directory.resolve(SigningKeyStore.PRIVATE_FILE)), privateBytes)) {
            throw new AssertionError("signing.key was overwritten");
        }
        if (publicBytes != null && !java.util.Arrays.equals(
                Files.readAllBytes(directory.resolve(SigningKeyStore.PUBLIC_FILE)), publicBytes)) {
            throw new AssertionError("signing.pub was overwritten");
        }
    }

    private static void assertLeftoverWasLoggedWithoutKeyMaterial(int logStart, String leftover, String privatePem) {
        String leftoverBody = pemBodyLine(leftover);
        String privateBody = pemBodyLine(privatePem);
        boolean warned = false;
        for (String line : MemoryLogAppender.LINES.subList(logStart, MemoryLogAppender.LINES.size())) {
            if (line.contains(SigningKeyStore.LEFTOVER_PUBLIC_KEY)) {
                warned = true;
            }
            if (line.contains("BEGIN PRIVATE KEY") || line.contains(privateBody) || line.contains(leftoverBody)) {
                throw new AssertionError("signing key material appeared in the log");
            }
        }
        if (!warned) {
            throw new AssertionError("leftover signing.pub was not logged");
        }
    }

    private static void assertPrivateKeyStaysInTheFile(byte[] privateBytes, String publicPem) {
        String privatePem = new String(privateBytes, StandardCharsets.UTF_8);
        if (publicPem.contains("BEGIN PRIVATE KEY") || publicPem.contains(privateBodyLine(privatePem))) {
            throw new AssertionError("public pem contains private key material");
        }
    }

    private static String privateBodyLine(String privatePem) {
        return pemBodyLine(privatePem);
    }

    private static String pemBodyLine(String pem) {
        for (String line : pem.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("-----") && trimmed.length() >= 16) {
                return trimmed;
            }
        }
        throw new AssertionError("private pem has no body");
    }

    private String firstPublicLine() throws Exception {
        return Files.readString(directory.resolve(SigningKeyStore.PUBLIC_FILE));
    }

    private static void assertSignature(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        byte[] message = new byte[] {9, 8, 7};
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(message);
        byte[] signed = signature.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(message);
        assertThat(verifier.verify(signed)).isTrue();
    }
}
