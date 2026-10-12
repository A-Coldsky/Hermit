package dev.hermit.crab.secure;

import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class MasterKeyProviderTest {
    @TempDir
    Path temp;

    @Test
    void environmentOverridesKeyFileAndDefault() throws IOException {
        byte[] fromEnv = key((byte) 1);
        byte[] fromFile = key((byte) 2);
        byte[] fromDefault = key((byte) 3);
        Path flag = temp.resolve("flag.key");
        Files.writeString(flag, encode(fromFile));
        Path fallback = new CrabPaths(temp).masterKeyFile();
        Files.writeString(fallback, encode(fromDefault));

        byte[] loaded = MasterKeyProvider.load(new CrabPaths(temp), Optional.of(flag), name -> encode(fromEnv));

        sameKey(loaded, fromEnv);
    }

    @Test
    void keyFileOverridesDefaultWhenEnvironmentIsUnset() throws IOException {
        byte[] fromFile = key((byte) 4);
        byte[] fromDefault = key((byte) 5);
        Path flag = temp.resolve("flag.key");
        Files.writeString(flag, encode(fromFile));
        Path fallback = new CrabPaths(temp).masterKeyFile();
        Files.writeString(fallback, encode(fromDefault));

        byte[] loaded = MasterKeyProvider.load(new CrabPaths(temp), Optional.of(flag), name -> null);

        sameKey(loaded, fromFile);
    }

    @Test
    void existingDefaultIsUsedWhenNoHigherSourceIsSet() throws IOException {
        byte[] fromDefault = key((byte) 6);
        Path fallback = new CrabPaths(temp).masterKeyFile();
        Files.writeString(fallback, "\n" + encode(fromDefault) + "\n");

        byte[] loaded = MasterKeyProvider.load(new CrabPaths(temp), Optional.empty(), name -> null);

        sameKey(loaded, fromDefault);
    }

    @Test
    void missingDefaultIsGeneratedOwnerReadWriteAndReloaded() throws IOException {
        CrabPaths paths = new CrabPaths(temp);
        byte[] generated = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
        assertThat(generated).hasSize(32);
        Path file = paths.masterKeyFile();
        assertThat(Files.readString(file)).isEqualTo(encode(generated));
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        sameKey(MasterKeyProvider.load(paths, Optional.empty(), name -> null), generated);
    }

    @Test
    void defaultOverloadAgreesWithTheProcessEnvironment() {
        CrabPaths paths = new CrabPaths(temp);
        byte[] first = MasterKeyProvider.load(paths, Optional.empty());
        byte[] second = MasterKeyProvider.load(paths, Optional.empty(), System::getenv);
        sameKey(first, second);
        assertThat(first).hasSize(32);
    }

    @Test
    void invalidEnvironmentFailsWithoutEchoingTheValueOrFallingThrough() throws IOException {
        String sentinel = "HermitInvalidKeyMaterial-ZZZ-should-not-appear";
        Path flag = temp.resolve("flag.key");
        Files.writeString(flag, encode(key((byte) 9)));

        Throwable thrown = catchThrowable(() ->
                MasterKeyProvider.load(new CrabPaths(temp), Optional.of(flag), name -> sentinel));

        assertThat(thrown).isInstanceOf(MasterKeyException.class);
        SecretChecks.absent(thrown, sentinel);
        assertThat(temp.resolve("data").resolve("master.key")).doesNotExist();
        assertThat(Files.readString(flag)).isEqualTo(encode(key((byte) 9)));
    }

    @Test
    void invalidKeyFileFailsWithoutEchoingTheValueOrReplacingIt() throws IOException {
        String sentinel = "HermitInvalidFileKey-QQQ-should-not-appear";
        Path flag = temp.resolve("flag.key");
        Files.writeString(flag, sentinel);
        Path fallback = new CrabPaths(temp).masterKeyFile();
        Files.writeString(fallback, encode(key((byte) 8)));

        Throwable thrown = catchThrowable(() ->
                MasterKeyProvider.load(new CrabPaths(temp), Optional.of(flag), name -> null));

        assertThat(thrown).isInstanceOf(MasterKeyException.class);
        SecretChecks.absent(thrown, sentinel);
        assertThat(Files.readString(flag)).isEqualTo(sentinel);
        assertThat(Files.readString(fallback)).isEqualTo(encode(key((byte) 8)));
    }

    @Test
    void wrongLengthAndUrlSafeEncodingFailWithoutEchoingTheMaterial() {
        String shortKey = encode(new byte[16]);
        Throwable shortFailure = catchThrowable(() ->
                MasterKeyProvider.load(new CrabPaths(temp), Optional.empty(), name -> shortKey));
        assertThat(shortFailure).isInstanceOf(MasterKeyException.class);
        SecretChecks.absent(shortFailure, shortKey);

        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) 0xfb);
        String urlSafe = Base64.getUrlEncoder().encodeToString(raw);
        Throwable urlFailure = catchThrowable(() ->
                MasterKeyProvider.load(new CrabPaths(temp), Optional.empty(), name -> urlSafe));
        assertThat(urlFailure).isInstanceOf(MasterKeyException.class);
        SecretChecks.absent(urlFailure, urlSafe);
    }

    @Test
    void invalidDefaultFileIsNotReplaced() throws IOException {
        Path fallback = new CrabPaths(temp).masterKeyFile();
        assertDefaultFileStaysUntouched(fallback, "HermitBrokenDefaultKey-not-base64".getBytes(StandardCharsets.US_ASCII));
        assertDefaultFileStaysUntouched(fallback, Base64.getEncoder().encode(new byte[16]));
    }

    @Test
    void failedDefaultWriteLeavesNoMasterKeyAndTheNextLoadGeneratesOne() throws IOException {
        CrabPaths paths = new CrabPaths(temp);
        String sentinel = "HermitKeyWriteFailed-do-not-keep";
        Throwable thrown = catchThrowable(() -> MasterKeyProvider.load(
                paths,
                Optional.empty(),
                name -> null,
                (tempFile, key) -> {
                    Files.writeString(tempFile, "partial");
                    throw new IOException(sentinel);
                }));

        assertThat(thrown).isInstanceOf(MasterKeyException.class);
        assertThat(thrown.getCause()).isNull();
        SecretChecks.absent(thrown, sentinel);
        assertThat(paths.masterKeyFile()).doesNotExist();
        assertThat(dataFiles(paths)).isEmpty();

        byte[] generated = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
        assertThat(generated).hasSize(32);
        assertThat(Files.readString(paths.masterKeyFile())).isEqualTo(encode(generated));
        sameKey(MasterKeyProvider.load(paths, Optional.empty(), name -> null), generated);
        assertThat(dataFiles(paths)).containsExactly("master.key");
    }

    @Test
    void defaultFileThatAppearsBeforePublishIsReadAndNotReplaced() throws IOException {
        CrabPaths paths = new CrabPaths(temp);
        byte[] existing = key((byte) 11);
        byte[] before = encode(existing).getBytes(StandardCharsets.US_ASCII);

        byte[] loaded = MasterKeyProvider.load(paths, Optional.empty(), name -> null, (tempFile, key) -> {
            Files.write(tempFile.resolveSibling("master.key"), before);
            Files.writeString(tempFile, encode(key));
        });

        sameKey(loaded, existing);
        assertFileBytes(paths.masterKeyFile(), before);
        assertThat(dataFiles(paths)).containsExactly("master.key");
    }

    @Test
    void missingExplicitKeyFileFails() {
        Path missing = temp.resolve("missing.key");
        Throwable thrown = catchThrowable(() ->
                MasterKeyProvider.load(new CrabPaths(temp), Optional.of(missing), name -> null));
        assertThat(thrown).isInstanceOf(MasterKeyException.class);
        assertThat(thrown.getMessage()).contains(missing.toString());
    }

    private void assertDefaultFileStaysUntouched(Path fallback, byte[] contents) throws IOException {
        Files.write(fallback, contents);
        byte[] before = Files.readAllBytes(fallback);
        String visible = new String(contents, StandardCharsets.US_ASCII);

        Throwable first = catchThrowable(() ->
                MasterKeyProvider.load(new CrabPaths(temp), Optional.empty(), name -> null));
        assertThat(first).isInstanceOf(MasterKeyException.class);
        assertThat(first.getCause()).isNull();
        assertThat(first.getMessage()).contains("default master key file");
        SecretChecks.absent(first, visible);
        assertFileBytes(fallback, before);

        Throwable second = catchThrowable(() ->
                MasterKeyProvider.load(new CrabPaths(temp), Optional.empty(), name -> null));
        assertThat(second).isInstanceOf(MasterKeyException.class);
        assertThat(second.getCause()).isNull();
        SecretChecks.absent(second, visible);
        assertFileBytes(fallback, before);
    }

    private static List<String> dataFiles(CrabPaths paths) throws IOException {
        try (Stream<Path> files = Files.list(paths.data())) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertFileBytes(Path file, byte[] expected) throws IOException {
        if (!Arrays.equals(expected, Files.readAllBytes(file))) {
            throw new AssertionError("invalid default master key file was replaced");
        }
    }

    private static byte[] key(byte fill) {
        byte[] value = new byte[32];
        Arrays.fill(value, fill);
        return value;
    }

    private static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static void sameKey(byte[] actual, byte[] expected) {
        if (!Arrays.equals(actual, expected)) {
            throw new AssertionError("master key did not come from the expected source");
        }
    }
}
