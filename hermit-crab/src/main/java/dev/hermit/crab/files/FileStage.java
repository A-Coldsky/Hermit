package dev.hermit.crab.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 文件暂存区。规则和调用方之间只传 fileId，文件本身放在 Crab 数据目录下，到期删除。
 * 清单只在内存里：进程重启后目录被清空，旧的 fileId 和下载链接一起失效。
 *
 * <p>fileId 用来在调用里指代文件，只有创建它的 API Key 能用（控制台上传的所有 Key 都能用）。
 * 下载令牌是另一串随机数，只出现在下载链接里，拿到链接的人在有效期内可以反复下载。
 */
public final class FileStage implements AutoCloseable {

    /** 小文件可以直接用 data URI 传进来，解码后的上限。 */
    public static final int INLINE_LIMIT = 256 * 1024;
    /** 暂存区里最多同时放这么多个文件。 */
    public static final int MAX_FILES = 10_000;
    public static final String TOO_LARGE = "文件超过暂存区的单文件上限";
    public static final String FULL = "文件暂存区已满，等已有文件过期后再试，或在 Crab 控制台调大总容量";
    public static final String MISSING = "文件不存在、已过期，或不属于这个 API Key";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int NAME_LIMIT = 200;

    private final Path dir;
    private final Clock clock;
    private final Map<String, StagedFile> byId = new ConcurrentHashMap<>();
    private final Map<String, StagedFile> byToken = new ConcurrentHashMap<>();
    private final Object capacity = new Object();
    private volatile FileStageSettings settings;
    private long reserved;
    private int activeSpools;

    public FileStage(Path dir, Clock clock, FileStageSettings settings) {
        this.dir = dir;
        this.clock = clock;
        this.settings = settings;
        clearDirectory();
    }

    public FileStageSettings settings() {
        return settings;
    }

    public void updateSettings(FileStageSettings next) {
        this.settings = next;
    }

    /** {@code owner} 是创建者的 API Key 编号；{@code null} 表示控制台上传，所有 Key 可用。 */
    public StagedFile store(byte[] bytes, String name, String mime, String owner) {
        return store(new ByteArrayInputStream(bytes), name, mime, owner);
    }

    public StagedFile store(InputStream in, String name, String mime, String owner) {
        Spool spool = spool();
        try {
            byte[] buffer = new byte[64 * 1024];
            while (true) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                spool.write(buffer, 0, read);
            }
            return spool.commit(name, mime, owner);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } finally {
            spool.discard();
        }
    }

    /** 开始收一个文件。内容边到边写盘，{@link Spool#commit} 之前别人看不到它。 */
    public Spool spool() {
        sweep();
        // 空文件不占字节数，另按个数拦；正在收的文件也算名额，避免并发越过上限。
        synchronized (capacity) {
            if (byId.size() + activeSpools >= MAX_FILES) {
                throw new FileStageException(FULL);
            }
            activeSpools++;
        }
        try {
            return new Spool();
        } catch (Throwable ex) {
            synchronized (capacity) {
                activeSpools = Math.max(0, activeSpools - 1);
            }
            throw ex;
        }
    }

    /**
     * 一个还在收的文件。只给一个线程用。没有 {@code commit} 的必须 {@code discard}，
     * 否则占着的容量和磁盘上的半截文件要等到重启才清。
     */
    public final class Spool {
        private final FileStageSettings limits = settings;
        private final String fileId = "f_" + random(12);
        private final Path target = dir.resolve(fileId);
        private final MessageDigest digest = sha256();
        private OutputStream out;
        private long written;
        private boolean finished;

        private Spool() {
        }

        public void write(byte[] buffer, int offset, int length) {
            if (finished) {
                throw new IllegalStateException("spool is finished");
            }
            if (length <= 0) {
                return;
            }
            if (written + length > limits.maxFileBytes()) {
                throw new FileStageException(TOO_LARGE);
            }
            try {
                if (out == null) {
                    Files.createDirectories(dir);
                    out = Files.newOutputStream(target);
                }
                // 按块占用总容量，并发上传不会一起越过上限。
                synchronized (capacity) {
                    if (reserved + length > limits.maxTotalBytes()) {
                        throw new FileStageException(FULL);
                    }
                    reserved += length;
                }
                written += length;
                digest.update(buffer, offset, length);
                out.write(buffer, offset, length);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        public long written() {
            return written;
        }

        public StagedFile commit(String name, String mime, String owner) {
            if (finished) {
                throw new IllegalStateException("spool is finished");
            }
            try {
                if (out == null) {
                    Files.createDirectories(dir);
                    out = Files.newOutputStream(target);
                }
                out.close();
            } catch (IOException ex) {
                discard();
                throw new UncheckedIOException(ex);
            }
            Instant now = clock.instant();
            StagedFile file = new StagedFile(
                    fileId,
                    random(32),
                    cleanName(name),
                    cleanMime(mime),
                    written,
                    HexFormat.of().formatHex(digest.digest()),
                    now,
                    now.plus(Duration.ofSeconds(limits.ttlSeconds())),
                    owner,
                    target);
            synchronized (capacity) {
                byId.put(fileId, file);
                byToken.put(file.token(), file);
                activeSpools = Math.max(0, activeSpools - 1);
            }
            finished = true;
            return file;
        }

        /** 丢掉没有提交的内容。已经提交或已经丢掉的再调没有效果。 */
        public void discard() {
            if (finished) {
                return;
            }
            finished = true;
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 反正要删。
                }
            }
            synchronized (capacity) {
                reserved = Math.max(0, reserved - written);
                activeSpools = Math.max(0, activeSpools - 1);
            }
            deleteQuietly(target);
        }
    }

    /** 调用里按 fileId 取文件。别的 Key 创建的文件按不存在处理。 */
    public Optional<StagedFile> find(String fileId, String owner) {
        if (fileId == null) {
            return Optional.empty();
        }
        StagedFile file = live(byId.get(fileId));
        if (file == null || (file.owner() != null && !file.owner().equals(owner))) {
            return Optional.empty();
        }
        return Optional.of(file);
    }

    public Optional<StagedFile> byToken(String token) {
        return token == null ? Optional.empty() : Optional.ofNullable(live(byToken.get(token)));
    }

    public byte[] read(StagedFile file) {
        try {
            return Files.readAllBytes(file.path());
        } catch (IOException ex) {
            throw new FileStageException(MISSING);
        }
    }

    public InputStream open(StagedFile file) {
        try {
            return Files.newInputStream(file.path());
        } catch (IOException ex) {
            throw new FileStageException(MISSING);
        }
    }

    public List<StagedFile> list() {
        sweep();
        ArrayList<StagedFile> files = new ArrayList<>(byId.values());
        files.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return files;
    }

    public boolean delete(String fileId) {
        StagedFile file = byId.get(fileId);
        if (file == null) {
            return false;
        }
        drop(file);
        return true;
    }

    public Map<String, Object> usage() {
        sweep();
        LinkedHashMap<String, Object> usage = new LinkedHashMap<>();
        usage.put("files", byId.size());
        synchronized (capacity) {
            usage.put("bytes", reserved);
        }
        return usage;
    }

    /** 删掉到期的文件。每次存取前都会调，另有定时任务兜底。 */
    public void sweep() {
        Instant now = clock.instant();
        for (StagedFile file : new ArrayList<>(byId.values())) {
            if (!file.expiresAt().isAfter(now)) {
                drop(file);
            }
        }
    }

    @Override
    public void close() {
        for (StagedFile file : new ArrayList<>(byId.values())) {
            drop(file);
        }
    }

    /** 解析 {@code data:<类型>;name=<文件名>;base64,<内容>}。不是 data URI 时返回空。 */
    public static Optional<Inline> inline(String value) {
        if (value == null || !value.regionMatches(true, 0, "data:", 0, 5)) {
            return Optional.empty();
        }
        int comma = value.indexOf(',');
        if (comma < 0) {
            throw new FileStageException("data URI 缺少逗号后的内容");
        }
        String[] head = value.substring(5, comma).split(";", -1);
        String mime = head[0].isBlank() ? null : head[0].trim();
        String name = null;
        boolean base64 = false;
        for (int i = 1; i < head.length; i++) {
            String piece = head[i].trim();
            if ("base64".equalsIgnoreCase(piece)) {
                base64 = true;
            } else if (piece.regionMatches(true, 0, "name=", 0, 5)) {
                try {
                    name = java.net.URLDecoder.decode(piece.substring(5), java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException ex) {
                    throw new FileStageException("data URI 文件名编码无效");
                }
            }
        }
        if (!base64) {
            throw new FileStageException("data URI 只支持 base64");
        }
        String payload = value.substring(comma + 1).replaceAll("\\s", "");
        // 先按长度拦，不为超限的内容分配解码缓冲。
        if (payload.length() > (INLINE_LIMIT / 3 + 1) * 4L + 4) {
            throw new FileStageException("data URI 里的文件超过 256 KiB，请先上传到暂存区再传 fileId");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException ex) {
            throw new FileStageException("data URI 的 base64 无法解码");
        }
        if (bytes.length > INLINE_LIMIT) {
            throw new FileStageException("data URI 里的文件超过 256 KiB，请先上传到暂存区再传 fileId");
        }
        return Optional.of(new Inline(bytes, name, mime));
    }

    public record Inline(byte[] bytes, String name, String mime) {
    }

    private StagedFile live(StagedFile file) {
        if (file == null) {
            return null;
        }
        if (!file.expiresAt().isAfter(clock.instant())) {
            drop(file);
            return null;
        }
        return file;
    }

    private void drop(StagedFile file) {
        synchronized (capacity) {
            if (!byId.remove(file.fileId(), file)) {
                return;
            }
            byToken.remove(file.token(), file);
            reserved = Math.max(0, reserved - file.size());
        }
        deleteQuietly(file.path());
    }

    private void clearDirectory() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile).forEach(FileStage::deleteQuietly);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 删不掉的文件留给下次启动清空目录。
        }
    }

    /** 文件名只用于展示和 multipart 的 filename，不参与磁盘路径。 */
    static String cleanName(String name) {
        if (name == null) {
            return "file";
        }
        String cleaned = name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cleaned.length() && out.length() < NAME_LIMIT; i++) {
            char c = cleaned.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '"') {
                out.append(c);
            }
        }
        // 截断不能留下半个代理对，也去掉本来就不成对的代理项：这样的名字没有编码能发出去。
        StringBuilder whole = new StringBuilder();
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < out.length() && Character.isLowSurrogate(out.charAt(i + 1))) {
                whole.append(c).append(out.charAt(++i));
            } else if (!Character.isSurrogate(c)) {
                whole.append(c);
            }
        }
        String result = whole.toString().trim();
        return result.isEmpty() || ".".equals(result) || "..".equals(result) ? "file" : result;
    }

    static String cleanMime(String mime) {
        if (mime == null) {
            return "application/octet-stream";
        }
        String value = mime;
        int semi = value.indexOf(';');
        if (semi >= 0) {
            value = value.substring(0, semi);
        }
        value = value.trim().toLowerCase(java.util.Locale.ROOT);
        return value.matches("[a-z0-9!#$&^_.+-]{1,100}/[a-z0-9!#$&^_.+-]{1,100}")
                ? value
                : "application/octet-stream";
    }

    private static String random(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
