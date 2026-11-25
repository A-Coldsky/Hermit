package dev.hermit.reef.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * 提供独立可执行 Crab JAR 包的发现、内置默认分发与管理员上传管理（仅保留当前与上一版本）。
 *
 * <p>分发优先级：
 * <ol>
 *   <li>运维通过 {@code hermit.reef.crab-jar-file} 显式固定的本地文件（{@code LOCAL}）；</li>
 *   <li>管理员通过管理台上传的覆盖版本，保存在 {@code data-dir/downloads/managed/}（{@code UPLOADED}，仅保留当前 + 上一版）；</li>
 *   <li>运维手动放入 {@code data-dir/downloads/} 的本地文件（{@code LOCAL}）；</li>
 *   <li>构建期打入 Reef JAR 的内置默认 Crab 客户端（{@code BUNDLED}）；</li>
 *   <li>外部下载地址 {@code hermit.reef.crab-download-url}（{@code EXTERNAL}）。</li>
 * </ol>
 *
 * <p>不读取符号链接或目录外文件，响应里不暴露宿主机绝对路径。
 */
@Service
public class CrabDownloadService {

    public static final String DOWNLOAD_ENDPOINT = "/api/admin/crab/download";
    public static final String DOWNLOADS_SUBDIR = "downloads";
    public static final String MANAGED_SUBDIR = "managed";
    public static final long MAX_UPLOAD_BYTES = 64L * 1024 * 1024;
    static final String CRAB_MAIN_CLASS = "dev.hermit.crab.CrabMain";
    static final String CRAB_MAIN_ENTRY = "dev/hermit/crab/CrabMain.class";
    static final String FAT_JAR_MARKER_ENTRY = "io/javalin/Javalin.class";
    static final String NO_DOWNLOADABLE_JAR = "当前没有可供下载的 Crab 可执行 JAR 包";

    private static final Logger log = LoggerFactory.getLogger(CrabDownloadService.class);
    private static final String BUNDLED_DIR = "bundled-crab/";
    private static final String BUNDLED_METADATA = BUNDLED_DIR + "metadata.properties";
    private static final String STATE_FILE = "state.json";
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final int DIGEST_BUFFER_BYTES = 64 * 1024;
    private static final long[] MOVE_RETRY_DELAYS_MS = {50L, 100L};

    private final ReefProperties properties;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Object manageLock = new Object();

    private volatile CachedDigest cachedLocalDigest;
    private volatile BundledSource bundledSource = ClasspathBundledSource.INSTANCE;
    private volatile BundledCache cachedBundled;

    public CrabDownloadService(
            ReefProperties properties,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public CrabDownloadInfo info() {
        String latestVersion = properties.latestCrabVersionOrNull();
        String externalUrl = properties.crabDownloadUrlOrNull();
        boolean pinnedByConfig = properties.crabJarFileOrNull() != null;
        Optional<BundledMeta> bundled = resolveBundled();
        ManagedStateSnapshot managed = readManagedSnapshot();

        CrabDownloadInfo.PreviousUpload previous = toPreviousView(managed.previous());
        boolean bundledAvailable = bundled.isPresent();
        String bundledFileName = bundled.map(BundledMeta::fileName).orElse(null);
        Long bundledSizeBytes = bundled.map(BundledMeta::sizeBytes).orElse(null);
        String bundledSha256 = bundled.map(BundledMeta::sha256).orElse(null);

        if (pinnedByConfig) {
            Optional<ResolvedJar> pinned = resolvePinnedLocalJar();
            if (pinned.isPresent()) {
                ResolvedJar jar = pinned.get();
                return new CrabDownloadInfo(
                        true,
                        CrabDownloadInfo.Source.LOCAL,
                        jar.fileName(),
                        jar.sizeBytes(),
                        sha256(jar),
                        jar.updatedAt(),
                        latestVersion,
                        DOWNLOAD_ENDPOINT,
                        externalUrl,
                        null,
                        bundledAvailable,
                        bundledFileName,
                        bundledSizeBytes,
                        bundledSha256,
                        previous,
                        true);
            }
            if (externalUrl != null) {
                return new CrabDownloadInfo(
                        true,
                        CrabDownloadInfo.Source.EXTERNAL,
                        null,
                        null,
                        null,
                        null,
                        latestVersion,
                        null,
                        externalUrl,
                        null,
                        bundledAvailable,
                        bundledFileName,
                        bundledSizeBytes,
                        bundledSha256,
                        previous,
                        true);
            }
            return new CrabDownloadInfo(
                    false,
                    CrabDownloadInfo.Source.NONE,
                    null,
                    null,
                    null,
                    null,
                    latestVersion,
                    null,
                    null,
                    null,
                    bundledAvailable,
                    bundledFileName,
                    bundledSizeBytes,
                    bundledSha256,
                    previous,
                    true);
        }

        if (managed.current() != null) {
            ManagedEntry current = managed.current();
            return new CrabDownloadInfo(
                    true,
                    CrabDownloadInfo.Source.UPLOADED,
                    current.fileName(),
                    current.sizeBytes(),
                    current.sha256(),
                    current.uploadedAt(),
                    latestVersion,
                    DOWNLOAD_ENDPOINT,
                    externalUrl,
                    current.uploadedBy(),
                    bundledAvailable,
                    bundledFileName,
                    bundledSizeBytes,
                    bundledSha256,
                    previous,
                    false);
        }

        Optional<ResolvedJar> manual = resolveManualLocalJar();
        if (manual.isPresent()) {
            ResolvedJar jar = manual.get();
            return new CrabDownloadInfo(
                    true,
                    CrabDownloadInfo.Source.LOCAL,
                    jar.fileName(),
                    jar.sizeBytes(),
                    sha256(jar),
                    jar.updatedAt(),
                    latestVersion,
                    DOWNLOAD_ENDPOINT,
                    externalUrl,
                    null,
                    bundledAvailable,
                    bundledFileName,
                    bundledSizeBytes,
                    bundledSha256,
                    previous,
                    false);
        }

        if (bundled.isPresent()) {
            BundledMeta meta = bundled.get();
            return new CrabDownloadInfo(
                    true,
                    CrabDownloadInfo.Source.BUNDLED,
                    meta.fileName(),
                    meta.sizeBytes(),
                    meta.sha256(),
                    meta.builtAt(),
                    latestVersion,
                    DOWNLOAD_ENDPOINT,
                    externalUrl,
                    null,
                    true,
                    meta.fileName(),
                    meta.sizeBytes(),
                    meta.sha256(),
                    previous,
                    false);
        }

        if (externalUrl != null) {
            return new CrabDownloadInfo(
                    true,
                    CrabDownloadInfo.Source.EXTERNAL,
                    null,
                    null,
                    null,
                    null,
                    latestVersion,
                    null,
                    externalUrl,
                    null,
                    false,
                    null,
                    null,
                    null,
                    previous,
                    false);
        }

        return new CrabDownloadInfo(
                false,
                CrabDownloadInfo.Source.NONE,
                null,
                null,
                null,
                null,
                latestVersion,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                previous,
                false);
    }

    public DownloadableJar requireDownloadable() {
        if (properties.crabJarFileOrNull() != null) {
            ResolvedJar pinned = resolvePinnedLocalJar()
                    .orElseThrow(() -> new ApiException(404, ReefErrorCode.NOT_FOUND, NO_DOWNLOADABLE_JAR));
            return new DownloadableJar(new FileSystemResource(pinned.path()), pinned.fileName(), pinned.sizeBytes());
        }
        ManagedStateSnapshot managed = readManagedSnapshot();
        if (managed.current() != null && managed.currentPath() != null) {
            ManagedEntry current = managed.current();
            return new DownloadableJar(
                    new FileSystemResource(managed.currentPath()),
                    current.fileName(),
                    current.sizeBytes());
        }
        Optional<ResolvedJar> manual = resolveManualLocalJar();
        if (manual.isPresent()) {
            ResolvedJar jar = manual.get();
            return new DownloadableJar(new FileSystemResource(jar.path()), jar.fileName(), jar.sizeBytes());
        }
        Optional<BundledMeta> bundled = resolveBundled();
        if (bundled.isPresent()) {
            BundledMeta meta = bundled.get();
            return new DownloadableJar(meta.resource(), meta.fileName(), meta.sizeBytes());
        }
        throw new ApiException(404, ReefErrorCode.NOT_FOUND, NO_DOWNLOADABLE_JAR);
    }

    /**
     * 管理员上传新版 Crab 可执行 JAR，立即生效并仅保留当前版本与上一个版本。
     */
    public CrabDownloadInfo upload(String rawFileName, long declaredLength, InputStream body, String actor) {
        if (properties.crabJarFileOrNull() != null) {
            throw new ApiException(409, ReefErrorCode.CONFLICT,
                    "当前已通过配置项 hermit.reef.crab-jar-file 固定指定客户端文件，请先取消该配置再通过页面上传");
        }
        String fileName = sanitizeFileName(rawFileName);
        if (!isSafeJarName(fileName)) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT,
                    "文件名不合法，仅允许字母、数字、点、下划线和连字符，且必须以 .jar 结尾");
        }
        if (declaredLength > MAX_UPLOAD_BYTES) {
            throw new ApiException(413, ReefErrorCode.INVALID_INPUT, "文件大小不能超过 64 MB");
        }

        Path managedDir = ensureManagedDirectory();
        Path tempFile = null;
        boolean promoted = false;
        try {
            tempFile = Files.createTempFile(managedDir, ".upload-", ".tmp");
            StreamResult streamed = streamAndDigest(body, tempFile);
            validateCrabFatJar(tempFile);

            synchronized (manageLock) {
                if (properties.crabJarFileOrNull() != null) {
                    throw new ApiException(409, ReefErrorCode.CONFLICT,
                            "当前已通过配置项 hermit.reef.crab-jar-file 固定指定客户端文件，请先取消该配置再通过页面上传");
                }
                ManagedState existing = loadManagedState(managedDir);
                ManagedEntry existingCurrent = existing == null
                        ? null
                        : verifyManagedEntry(managedDir, existing.current());
                if (existingCurrent != null && existingCurrent.sha256().equals(streamed.sha256())) {
                    throw new ApiException(409, ReefErrorCode.CONFLICT,
                            "上传的文件与当前生效版本内容完全一致，无需重复上传");
                }

                Instant now = clock.instant();
                String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                String storageFile = "crab-" + now.toEpochMilli() + "-" + shortId + ".jar";
                Path destination = managedDir.resolve(storageFile);
                moveReplacing(tempFile, destination);
                promoted = true;

                ManagedEntry newCurrent = new ManagedEntry(
                        storageFile,
                        fileName,
                        streamed.sizeBytes(),
                        streamed.sha256(),
                        now,
                        actor);
                ManagedState nextState = new ManagedState(newCurrent, existingCurrent);
                writeManagedState(managedDir, nextState);
                pruneManagedJars(managedDir, nextState);

                LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
                detail.put("fileName", fileName);
                detail.put("sizeBytes", streamed.sizeBytes());
                detail.put("sha256", streamed.sha256());
                if (existingCurrent != null) {
                    detail.put("previousFileName", existingCurrent.fileName());
                    detail.put("previousSha256", existingCurrent.sha256());
                }
                audit.record(actor, AuditAction.CRAB_JAR_UPLOADED, null, null, writeJson(detail));
            }
            return info();
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            log.warn("Failed to store uploaded Crab JAR", ex);
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        } finally {
            if (!promoted && tempFile != null) {
                deleteQuietly(tempFile);
            }
        }
    }

    /**
     * 在已保留的两个上传版本之间切换（将上一版本恢复为当前版本，原当前版本退回上一版本）。
     */
    public CrabDownloadInfo rollback(String actor) {
        if (properties.crabJarFileOrNull() != null) {
            throw new ApiException(409, ReefErrorCode.CONFLICT,
                    "当前已通过配置项 hermit.reef.crab-jar-file 固定指定客户端文件，请先取消该配置后再回退");
        }
        synchronized (manageLock) {
            Optional<Path> maybeDir = resolveManagedDirectoryIfPresent();
            if (maybeDir.isEmpty()) {
                throw new ApiException(409, ReefErrorCode.CONFLICT, "没有可回退的上一版本");
            }
            Path managedDir = maybeDir.get();
            ManagedState existing = loadManagedState(managedDir);
            if (existing == null) {
                throw new ApiException(409, ReefErrorCode.CONFLICT, "没有可回退的上一版本");
            }
            ManagedEntry current = verifyManagedEntry(managedDir, existing.current());
            ManagedEntry previous = verifyManagedEntry(managedDir, existing.previous());
            if (previous == null) {
                throw new ApiException(409, ReefErrorCode.CONFLICT, "没有可回退的上一版本");
            }
            ManagedState swapped = new ManagedState(previous, current);
            try {
                writeManagedState(managedDir, swapped);
            } catch (IOException ex) {
                log.warn("Failed to rollback Crab JAR", ex);
                throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
            }
            pruneManagedJars(managedDir, swapped);

            LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
            if (current != null) {
                detail.put("fromFileName", current.fileName());
                detail.put("fromSha256", current.sha256());
            }
            detail.put("toFileName", previous.fileName());
            detail.put("toSha256", previous.sha256());
            audit.record(actor, AuditAction.CRAB_JAR_ROLLED_BACK, null, null, writeJson(detail));
        }
        return info();
    }

    /**
     * 清除管理员上传的覆盖版本，恢复使用 Reef 内置默认版本（或服务器本地兜底文件）。
     */
    public CrabDownloadInfo resetToDefault(String actor) {
        synchronized (manageLock) {
            Optional<Path> maybeDir = resolveManagedDirectoryIfPresent();
            if (maybeDir.isEmpty()) {
                throw new ApiException(404, ReefErrorCode.NOT_FOUND, "当前没有管理员上传的 Crab 版本");
            }
            Path managedDir = maybeDir.get();
            ManagedState existing = loadManagedState(managedDir);
            ManagedEntry current = existing == null ? null : verifyManagedEntry(managedDir, existing.current());
            ManagedEntry previous = existing == null ? null : verifyManagedEntry(managedDir, existing.previous());
            if (current == null && previous == null) {
                throw new ApiException(404, ReefErrorCode.NOT_FOUND, "当前没有管理员上传的 Crab 版本");
            }
            deleteQuietly(managedDir.resolve(STATE_FILE));
            pruneManagedJars(managedDir, new ManagedState(null, null));

            LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
            if (current != null) {
                detail.put("clearedFileName", current.fileName());
                detail.put("clearedSha256", current.sha256());
            }
            if (previous != null) {
                detail.put("clearedPreviousFileName", previous.fileName());
            }
            audit.record(actor, AuditAction.CRAB_JAR_RESET, null, null, writeJson(detail));
        }
        return info();
    }

    void setBundledSourceForTest(BundledSource source) {
        this.bundledSource = source == null ? ClasspathBundledSource.INSTANCE : source;
        this.cachedBundled = null;
    }

    Optional<ResolvedJar> resolvePinnedLocalJar() {
        String pinned = properties.crabJarFileOrNull();
        if (pinned == null) {
            return Optional.empty();
        }
        if (!isSafeJarName(pinned)) {
            log.warn("Configured hermit.reef.crab-jar-file is not a valid jar filename");
            return Optional.empty();
        }
        Optional<Path> realRoot = resolveDownloadsRootIfPresent();
        if (realRoot.isEmpty()) {
            return Optional.empty();
        }
        return inspectCandidate(realRoot.get(), realRoot.get().resolve(pinned));
    }

    Optional<ResolvedJar> resolveManualLocalJar() {
        Optional<Path> realRoot = resolveDownloadsRootIfPresent();
        if (realRoot.isEmpty()) {
            return Optional.empty();
        }
        Path root = realRoot.get();
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .map(entry -> inspectCandidate(root, entry))
                    .flatMap(Optional::stream)
                    .max(Comparator
                            .comparing((ResolvedJar jar) -> jar.fileName().toLowerCase(Locale.ROOT)
                                    .startsWith("hermit-crab"))
                            .thenComparing(ResolvedJar::updatedAt)
                            .thenComparing(ResolvedJar::fileName));
        } catch (IOException ex) {
            log.warn("Failed to list Reef downloads directory");
            return Optional.empty();
        }
    }

    private Optional<Path> resolveDownloadsRootIfPresent() {
        Path downloadsDir = properties.dataDirectory().toAbsolutePath().normalize().resolve(DOWNLOADS_SUBDIR);
        if (!Files.exists(downloadsDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(downloadsDir)
                || !Files.isDirectory(downloadsDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(downloadsDir.toRealPath());
        } catch (IOException ex) {
            log.warn("Failed to resolve Reef downloads directory");
            return Optional.empty();
        }
    }

    private Optional<Path> resolveManagedDirectoryIfPresent() {
        Optional<Path> downloadsRoot = resolveDownloadsRootIfPresent();
        if (downloadsRoot.isEmpty()) {
            return Optional.empty();
        }
        Path managedDir = downloadsRoot.get().resolve(MANAGED_SUBDIR);
        if (!Files.exists(managedDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(managedDir)
                || !Files.isDirectory(managedDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Path realManaged = managedDir.toRealPath();
            if (!downloadsRoot.get().equals(realManaged.getParent())) {
                return Optional.empty();
            }
            return Optional.of(realManaged);
        } catch (IOException ex) {
            log.warn("Failed to resolve Reef managed downloads directory");
            return Optional.empty();
        }
    }

    private Path ensureManagedDirectory() {
        try {
            Path downloadsDir = properties.dataDirectory().toAbsolutePath().normalize().resolve(DOWNLOADS_SUBDIR);
            if (Files.isSymbolicLink(downloadsDir)) {
                throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
            }
            Files.createDirectories(downloadsDir);
            Path realDownloads = downloadsDir.toRealPath();
            Path managedDir = realDownloads.resolve(MANAGED_SUBDIR);
            if (Files.isSymbolicLink(managedDir)) {
                throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
            }
            Files.createDirectories(managedDir);
            Path realManaged = managedDir.toRealPath();
            if (!realDownloads.equals(realManaged.getParent())) {
                throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
            }
            return realManaged;
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            log.warn("Failed to prepare Reef managed downloads directory", ex);
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private ManagedStateSnapshot readManagedSnapshot() {
        synchronized (manageLock) {
            Optional<Path> maybeDir = resolveManagedDirectoryIfPresent();
            if (maybeDir.isEmpty()) {
                return ManagedStateSnapshot.EMPTY;
            }
            Path managedDir = maybeDir.get();
            ManagedState state = loadManagedState(managedDir);
            if (state == null) {
                return ManagedStateSnapshot.EMPTY;
            }
            ManagedEntry current = verifyManagedEntry(managedDir, state.current());
            Path currentPath = current == null ? null : resolveSafeManagedJar(managedDir, current.storageFile()).orElse(null);
            ManagedEntry previous = verifyManagedEntry(managedDir, state.previous());
            return new ManagedStateSnapshot(current, currentPath, previous);
        }
    }

    private ManagedState loadManagedState(Path managedDir) {
        Path statePath = managedDir.resolve(STATE_FILE);
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(statePath)) {
            return null;
        }
        try {
            return objectMapper.readValue(statePath.toFile(), ManagedState.class);
        } catch (IOException ex) {
            log.warn("Failed to read managed Crab state file", ex);
            return null;
        }
    }

    private void writeManagedState(Path managedDir, ManagedState state) throws IOException {
        Path target = managedDir.resolve(STATE_FILE);
        Path temp = Files.createTempFile(managedDir, ".state-", ".tmp");
        boolean moved = false;
        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
            Files.write(temp, json);
            moveReplacing(temp, target);
            moved = true;
        } finally {
            if (!moved) {
                deleteQuietly(temp);
            }
        }
    }

    private ManagedEntry verifyManagedEntry(Path managedDir, ManagedEntry entry) {
        if (entry == null || !isSafeJarName(entry.fileName()) || !isSafeJarName(entry.storageFile())) {
            return null;
        }
        if (resolveSafeManagedJar(managedDir, entry.storageFile()).isEmpty()) {
            return null;
        }
        return entry;
    }

    private Optional<Path> resolveSafeManagedJar(Path managedDir, String storageFile) {
        if (!isSafeJarName(storageFile)) {
            return Optional.empty();
        }
        Path candidate = managedDir.resolve(storageFile);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) {
            return Optional.empty();
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(candidate, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink() || !Files.isReadable(candidate)) {
                return Optional.empty();
            }
            Path realFile = candidate.toRealPath();
            if (!managedDir.equals(realFile.getParent()) || !storageFile.equals(realFile.getFileName().toString())) {
                return Optional.empty();
            }
            return Optional.of(realFile);
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private void pruneManagedJars(Path managedDir, ManagedState keep) {
        String keepCurrent = keep != null && keep.current() != null ? keep.current().storageFile() : null;
        String keepPrevious = keep != null && keep.previous() != null ? keep.previous().storageFile() : null;
        try (Stream<Path> entries = Files.list(managedDir)) {
            for (Path entry : entries.toList()) {
                Path namePath = entry.getFileName();
                if (namePath == null) {
                    continue;
                }
                String name = namePath.toString();
                if (STATE_FILE.equals(name) || name.equals(keepCurrent) || name.equals(keepPrevious)) {
                    continue;
                }
                if (name.endsWith(".jar")) {
                    deleteQuietly(entry);
                }
            }
        } catch (IOException ex) {
            log.warn("Failed to prune old managed Crab JARs", ex);
        }
    }

    private StreamResult streamAndDigest(InputStream body, Path tempFile) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(tempFile)) {
            int read;
            while ((read = body.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > MAX_UPLOAD_BYTES) {
                    throw new ApiException(413, ReefErrorCode.INVALID_INPUT, "文件大小不能超过 64 MB");
                }
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, "上传的文件为空");
        }
        return new StreamResult(total, HexFormat.of().formatHex(digest.digest()));
    }

    static void validateCrabFatJar(Path jarFile) {
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null || manifestEntry.isDirectory()) {
                throw new ApiException(400, ReefErrorCode.INVALID_INPUT,
                        "不是有效的 Hermit Crab 可执行 JAR（缺少 MANIFEST.MF），请上传 hermit-crab-*-all.jar");
            }
            Manifest manifest;
            try (InputStream in = zip.getInputStream(manifestEntry)) {
                manifest = new Manifest(in);
            }
            String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            if (!CRAB_MAIN_CLASS.equals(mainClass)) {
                throw new ApiException(400, ReefErrorCode.INVALID_INPUT,
                        "不是有效的 Hermit Crab 可执行 JAR（Main-Class 不匹配），请确认上传的是 hermit-crab-*-all.jar");
            }
            ZipEntry mainEntry = zip.getEntry(CRAB_MAIN_ENTRY);
            if (mainEntry == null || mainEntry.isDirectory()) {
                throw new ApiException(400, ReefErrorCode.INVALID_INPUT,
                        "不是完整的 Hermit Crab 可执行 JAR（缺少 CrabMain 启动类）");
            }
            ZipEntry fatMarker = zip.getEntry(FAT_JAR_MARKER_ENTRY);
            if (fatMarker == null || fatMarker.isDirectory()) {
                throw new ApiException(400, ReefErrorCode.INVALID_INPUT,
                        "上传的是普通轻量 JAR 而非独立可执行包，请上传带 -all 后缀的 hermit-crab-*-all.jar");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (ZipException ex) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, "文件不是有效的 JAR/ZIP 归档");
        } catch (IOException ex) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, "无法读取上传的 JAR 文件");
        }
    }

    private Optional<BundledMeta> resolveBundled() {
        BundledCache cached = cachedBundled;
        if (cached != null) {
            return cached.meta();
        }
        Optional<BundledMeta> resolved = loadBundled();
        cachedBundled = new BundledCache(resolved);
        return resolved;
    }

    private Optional<BundledMeta> loadBundled() {
        Optional<BundledDescriptor> descriptor = bundledSource.load();
        if (descriptor.isEmpty()) {
            return Optional.empty();
        }
        BundledDescriptor desc = descriptor.get();
        if (!isSafeJarName(desc.fileName()) || desc.resource() == null || !desc.resource().exists()) {
            return Optional.empty();
        }
        try (InputStream in = desc.resource().getInputStream()) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    total += read;
                    digest.update(buffer, 0, read);
                }
            }
            if (total <= 0) {
                return Optional.empty();
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            return Optional.of(new BundledMeta(
                    desc.resource(),
                    desc.fileName(),
                    total,
                    sha256,
                    desc.builtAt()));
        } catch (IOException ex) {
            log.warn("Failed to inspect bundled Crab JAR", ex);
            return Optional.empty();
        }
    }

    private Optional<ResolvedJar> inspectCandidate(Path realRoot, Path candidate) {
        Path namePath = candidate.getFileName();
        if (namePath == null) {
            return Optional.empty();
        }
        String fileName = namePath.toString();
        if (!isSafeJarName(fileName)) {
            return Optional.empty();
        }
        if (Files.isSymbolicLink(candidate)) {
            return Optional.empty();
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(candidate, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink() || !Files.isReadable(candidate)) {
                return Optional.empty();
            }
            Path realFile = candidate.toRealPath();
            if (!realRoot.equals(realFile.getParent()) || !fileName.equals(realFile.getFileName().toString())) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedJar(realFile, fileName, attrs.size(), attrs.lastModifiedTime().toInstant()));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static CrabDownloadInfo.PreviousUpload toPreviousView(ManagedEntry previous) {
        if (previous == null) {
            return null;
        }
        return new CrabDownloadInfo.PreviousUpload(
                previous.fileName(),
                previous.sizeBytes(),
                previous.sha256(),
                previous.uploadedAt(),
                previous.uploadedBy());
    }

    static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 ? trimmed.substring(slash + 1).trim() : trimmed;
    }

    static boolean isSafeJarName(String name) {
        if (name == null || !SAFE_FILENAME.matcher(name).matches()) {
            return false;
        }
        if (".".equals(name) || "..".equals(name) || name.contains("..")) {
            return false;
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private String sha256(ResolvedJar jar) {
        long mtimeMillis = jar.updatedAt().toEpochMilli();
        CachedDigest current = cachedLocalDigest;
        if (current != null
                && current.fileName().equals(jar.fileName())
                && current.sizeBytes() == jar.sizeBytes()
                && current.mtimeMillis() == mtimeMillis) {
            return current.sha256();
        }
        String computed = computeSha256(jar.path());
        cachedLocalDigest = new CachedDigest(jar.fileName(), jar.sizeBytes(), mtimeMillis, computed);
        return computed;
    }

    private static String computeSha256(Path file) {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[DIGEST_BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException ex) {
                if (attempt >= MOVE_RETRY_DELAYS_MS.length) {
                    throw ex;
                }
                try {
                    Thread.sleep(MOVE_RETRY_DELAYS_MS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (IOException ex) {
            return null;
        }
    }

    public record DownloadableJar(Resource resource, String fileName, long sizeBytes) {
    }

    public record ResolvedJar(Path path, String fileName, long sizeBytes, Instant updatedAt) {
    }

    private record CachedDigest(String fileName, long sizeBytes, long mtimeMillis, String sha256) {
    }

    private record StreamResult(long sizeBytes, String sha256) {
    }

    private record ManagedEntry(
            String storageFile,
            String fileName,
            long sizeBytes,
            String sha256,
            Instant uploadedAt,
            String uploadedBy) {
    }

    private record ManagedState(ManagedEntry current, ManagedEntry previous) {
    }

    private record ManagedStateSnapshot(ManagedEntry current, Path currentPath, ManagedEntry previous) {
        static final ManagedStateSnapshot EMPTY = new ManagedStateSnapshot(null, null, null);
    }

    interface BundledSource {
        Optional<BundledDescriptor> load();
    }

    record BundledDescriptor(Resource resource, String fileName, Instant builtAt) {
    }

    private record BundledMeta(Resource resource, String fileName, long sizeBytes, String sha256, Instant builtAt) {
    }

    private record BundledCache(Optional<BundledMeta> meta) {
    }

    private enum ClasspathBundledSource implements BundledSource {
        INSTANCE;

        @Override
        public Optional<BundledDescriptor> load() {
            ClassPathResource metaResource = new ClassPathResource(BUNDLED_METADATA);
            if (!metaResource.exists()) {
                return Optional.empty();
            }
            Properties props = new Properties();
            try (InputStream in = metaResource.getInputStream()) {
                props.load(in);
            } catch (IOException ex) {
                log.warn("Failed to read bundled Crab metadata", ex);
                return Optional.empty();
            }
            String fileName = props.getProperty("fileName", "").trim();
            if (!isSafeJarName(fileName)) {
                return Optional.empty();
            }
            ClassPathResource jarResource = new ClassPathResource(BUNDLED_DIR + fileName);
            if (!jarResource.exists()) {
                return Optional.empty();
            }
            Instant builtAt = null;
            String rawBuiltAt = props.getProperty("builtAt", "").trim();
            if (!rawBuiltAt.isEmpty()) {
                try {
                    builtAt = Instant.parse(rawBuiltAt);
                } catch (DateTimeParseException ignored) {
                }
            }
            return Optional.of(new BundledDescriptor(jarResource, fileName, builtAt));
        }
    }
}
