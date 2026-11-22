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
    void aSpoolIsInvisibleUntilCommittedAndLeavesNothingWhenDiscarded() throws Exception {
        Path dir = temp.resolve("files");
        FileStage stage = new FileStage(dir, clock, new FileStageSettings(60, 1024, 2048));
        FileStage.Spool spool = stage.spool();
        spool.write(new byte[600], 0, 600);
        spool.write(new byte[400], 0, 400);
        // 收的时候就占容量，别人还看不到。
        assertThat(stage.usage()).containsEntry("bytes", 1000L).containsEntry("files", 0);
        assertThatThrownBy(() -> spool.write(new byte[25], 0, 25))
                .isInstanceOf(FileStageException.class).hasMessage(FileStage.TOO_LARGE);
        StagedFile file = spool.commit("a.bin", "application/pdf", "key-1");
        assertThat(file.size()).isEqualTo(1000);
        assertThat(file.sha256()).isEqualTo(stage.store(new byte[1000], "same.bin", null, "key-1").sha256());
        assertThat(stage.read(stage.find(file.fileId(), "key-1").orElseThrow())).isEqualTo(new byte[1000]);
        // 提交之后再 discard 不删文件。
        spool.discard();
        assertThat(stage.find(file.fileId(), "key-1")).isPresent();
        assertThat(stage.usage()).containsEntry("bytes", 2000L).containsEntry("files", 2);

        FileStage.Spool full = stage.spool();
        full.write(new byte[48], 0, 48);
        assertThatThrownBy(() -> full.write(new byte[1], 0, 1))
                .isInstanceOf(FileStageException.class).hasMessage(FileStage.FULL);
        full.discard();
        full.discard();
        assertThat(stage.usage()).containsEntry("bytes", 2000L).containsEntry("files", 2);
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            assertThat(entries.count()).isEqualTo(2);
        }
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
