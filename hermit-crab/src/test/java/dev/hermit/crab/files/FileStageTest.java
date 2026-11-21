package dev.hermit.crab.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileStageTest {
    private static final Instant START = Instant.parse("2026-11-20T08:00:00Z");

    @TempDir
    Path temp;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @Test
    void filesBelongToTheKeyThatCreatedThemUnlessTheConsoleUploaded() {
        FileStage stage = new FileStage(temp.resolve("files"), clock, FileStageSettings.defaults());
        StagedFile mine = stage.store(bytes("mine"), "a.txt", "text/plain", "key-1");
        StagedFile shared = stage.store(bytes("shared"), "b.txt", "text/plain", null);

        assertThat(stage.find(mine.fileId(), "key-1")).isPresent();
        assertThat(stage.find(mine.fileId(), "key-2")).isEmpty();
        assertThat(stage.find(mine.fileId(), null)).isEmpty();
        assertThat(stage.find(shared.fileId(), "key-2")).isPresent();
        // 令牌和 fileId 是两个值，互相不能代替。
        assertThat(mine.token()).isNotEqualTo(mine.fileId());
        assertThat(stage.byToken(mine.fileId())).isEmpty();
        assertThat(stage.find(mine.token(), "key-1")).isEmpty();
        assertThat(stage.read(stage.byToken(mine.token()).orElseThrow())).isEqualTo(bytes("mine"));
        assertThat(mine.sha256()).hasSize(64);
    }

    @Test
    void anAbandonedPartialFileLeavesNothingBehind() throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), clock, new FileStageSettings(3600, 1024, 4096));
        FileStage.Pending dropped = stage.begin("key-1");
        dropped.write(bytes("12345"), 0, 5);
        dropped.abort();
        // 写到一半超过单文件上限：文件丢弃，占用的容量退回。
        FileStage.Pending tooLarge = stage.begin("key-1");
        tooLarge.write(new byte[1000], 0, 1000);
        assertThatThrownBy(() -> tooLarge.write(new byte[25], 0, 25))
                .isInstanceOf(FileStageException.class)
                .hasMessage(FileStage.TOO_LARGE);

        assertThat(stage.usage()).containsEntry("files", 0).containsEntry("bytes", 0L);
        try (java.util.stream.Stream<Path> left = java.nio.file.Files.list(temp.resolve("files"))) {
            assertThat(left).isEmpty();
        }

        FileStage.Pending kept = stage.begin("key-1");
        kept.write(bytes("abc"), 0, 3);
        StagedFile file = kept.commit("a.txt", "text/plain");
        kept.abort();
        assertThat(stage.read(stage.find(file.fileId(), "key-1").orElseThrow())).isEqualTo(bytes("abc"));
        assertThat(stage.usage()).containsEntry("files", 1).containsEntry("bytes", 3L);
    }

    @Test
    void filesExpireAndTheirBytesAreFreed() {
        FileStage stage = new FileStage(temp.resolve("files"), clock, new FileStageSettings(60, 1024, 2048));
        StagedFile file = stage.store(new byte[1024], "a.bin", null, "key-1");
        assertThat(file.expiresAt()).isEqualTo(START.plusSeconds(60));
        assertThat(stage.store(new byte[1024], "b.bin", null, "key-1")).isNotNull();
        assertThatThrownBy(() -> stage.store(new byte[1], "c.bin", null, "key-1"))
                .isInstanceOf(FileStageException.class).hasMessage(FileStage.FULL);
        assertThatThrownBy(() -> stage.store(new byte[1025], "d.bin", null, "key-1"))
                .isInstanceOf(FileStageException.class).hasMessage(FileStage.TOO_LARGE);
        // 失败的那两次不占容量。
        assertThat(stage.usage()).containsEntry("bytes", 2048L).containsEntry("files", 2);

        now.set(START.plus(Duration.ofSeconds(60)));
        assertThat(stage.find(file.fileId(), "key-1")).isEmpty();
        assertThat(stage.byToken(file.token())).isEmpty();
        assertThat(stage.usage()).containsEntry("bytes", 0L).containsEntry("files", 0);
        assertThat(Files.exists(file.path())).isFalse();
        assertThat(stage.store(new byte[1024], "e.bin", null, "key-1")).isNotNull();
    }

    @Test
    void emptyFilesAreLimitedByCount() {
        FileStage stage = new FileStage(temp.resolve("files"), clock, FileStageSettings.defaults());
        for (int i = 0; i < FileStage.MAX_FILES; i++) {
            stage.store(new byte[0], "empty", null, "key-1");
        }
        assertThatThrownBy(() -> stage.store(new byte[0], "empty", null, "key-1"))
                .isInstanceOf(FileStageException.class).hasMessage(FileStage.FULL);
    }

    @Test
    void aNewTtlOnlyAppliesToLaterFiles() {
        FileStage stage = new FileStage(temp.resolve("files"), clock, FileStageSettings.defaults());
        StagedFile before = stage.store(bytes("a"), "a", null, null);
        stage.updateSettings(new FileStageSettings(60, 1024, 2048));
        StagedFile after = stage.store(bytes("b"), "b", null, null);
        assertThat(before.expiresAt()).isEqualTo(START.plusSeconds(3600));
        assertThat(after.expiresAt()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    void restartClearsTheDirectory() throws Exception {
        Path dir = temp.resolve("files");
        FileStage first = new FileStage(dir, clock, FileStageSettings.defaults());
        StagedFile file = first.store(bytes("old"), "a.txt", null, null);
        assertThat(Files.exists(file.path())).isTrue();

        FileStage second = new FileStage(dir, clock, FileStageSettings.defaults());
        assertThat(Files.exists(file.path())).isFalse();
        assertThat(second.find(file.fileId(), null)).isEmpty();
        assertThat(second.list()).isEmpty();
    }

    @Test
    void uploadedNamesNeverBecomePaths() {
        FileStage stage = new FileStage(temp.resolve("files"), clock, FileStageSettings.defaults());
        StagedFile file = stage.store(bytes("x"), "../../etc/passwd\r\nX: y", "text/html\r\nX: y", null);
        assertThat(file.path().getParent()).isEqualTo(temp.resolve("files"));
        assertThat(file.path().getFileName().toString()).isEqualTo(file.fileId());
        assertThat(file.name()).doesNotContain("/", "\\", "\r", "\n");
        assertThat(file.mime()).doesNotContain("\r", "\n");
        assertThat(stage.store(bytes("x"), null, null, null).name()).isNotBlank();
        // 200 个字符处正好切在表情中间：不留半个代理对。
        String cut = stage.store(bytes("x"), "a".repeat(199) + "😀.txt", null, null).name();
        assertThat(cut).isEqualTo("a".repeat(199));
        assertThat(new String(cut.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(cut);
    }

    @Test
    void inlineDataUrisAreLimitedTo256KiB() {
        String small = "data:text/plain;name=a.txt;base64," + Base64.getEncoder().encodeToString(bytes("hello"));
        FileStage.Inline inline = FileStage.inline(small).orElseThrow();
        assertThat(inline.bytes()).isEqualTo(bytes("hello"));
        assertThat(inline.name()).isEqualTo("a.txt");
        assertThat(inline.mime()).isEqualTo("text/plain");
        assertThat(FileStage.inline("f_abc")).isEmpty();

        String large = "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(new byte[FileStage.INLINE_LIMIT + 1]);
        assertThatThrownBy(() -> FileStage.inline(large)).isInstanceOf(FileStageException.class);
        assertThatThrownBy(() -> FileStage.inline("data:text/plain;base64,***"))
                .isInstanceOf(FileStageException.class);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
