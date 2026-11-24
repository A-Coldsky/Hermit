package dev.hermit.reef.download;

import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从 Reef 数据目录下的 {@code downloads/} 目录发现并流式提供独立可执行 Crab JAR 包。
 * 不提供网页上传入口，也不读取符号链接或目录外文件，响应里不暴露宿主机绝对路径。
 */
@Service
public class CrabDownloadService {

    public static final String DOWNLOAD_ENDPOINT = "/api/admin/crab/download";
    public static final String DOWNLOADS_SUBDIR = "downloads";
    static final String NO_LOCAL_JAR = "当前未放置本地 Crab 可执行 JAR 包";

    private static final Logger log = LoggerFactory.getLogger(CrabDownloadService.class);
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final int DIGEST_BUFFER_BYTES = 64 * 1024;

    private final ReefProperties properties;
    private volatile CachedDigest cachedDigest;

    public CrabDownloadService(ReefProperties properties) {
        this.properties = properties;
    }

    public CrabDownloadInfo info() {
        String latestVersion = properties.latestCrabVersionOrNull();
        String externalUrl = properties.crabDownloadUrlOrNull();
        Optional<ResolvedJar> local = resolveLocalJar();
        if (local.isPresent()) {
            ResolvedJar jar = local.get();
            String sha256 = sha256(jar);
            return new CrabDownloadInfo(
                    true,
                    CrabDownloadInfo.Source.LOCAL,
                    jar.fileName(),
                    jar.sizeBytes(),
                    sha256,
                    jar.updatedAt(),
                    latestVersion,
                    DOWNLOAD_ENDPOINT,
                    externalUrl);
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
                    externalUrl);
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
                null);
    }

    public ResolvedJar requireLocalJar() {
        return resolveLocalJar()
                .orElseThrow(() -> new ApiException(404, ReefErrorCode.NOT_FOUND, NO_LOCAL_JAR));
    }

    Optional<ResolvedJar> resolveLocalJar() {
        Path downloadsDir = properties.dataDirectory().toAbsolutePath().normalize().resolve(DOWNLOADS_SUBDIR);
        if (!Files.exists(downloadsDir, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(downloadsDir)
                || !Files.isDirectory(downloadsDir, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path realRoot;
        try {
            realRoot = downloadsDir.toRealPath();
        } catch (IOException ex) {
            log.warn("Failed to resolve Reef downloads directory");
            return Optional.empty();
        }
        String pinned = properties.crabJarFileOrNull();
        if (pinned != null) {
            if (!isSafeJarName(pinned)) {
                log.warn("Configured hermit.reef.crab-jar-file is not a valid jar filename");
                return Optional.empty();
            }
            return inspectCandidate(realRoot, realRoot.resolve(pinned));
        }
        try (Stream<Path> entries = Files.list(realRoot)) {
            return entries
                    .map(entry -> inspectCandidate(realRoot, entry))
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
        CachedDigest current = cachedDigest;
        if (current != null
                && current.fileName().equals(jar.fileName())
                && current.sizeBytes() == jar.sizeBytes()
                && current.mtimeMillis() == mtimeMillis) {
            return current.sha256();
        }
        String computed = computeSha256(jar.path());
        cachedDigest = new CachedDigest(jar.fileName(), jar.sizeBytes(), mtimeMillis, computed);
        return computed;
    }

    private static String computeSha256(Path file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
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

    public record ResolvedJar(Path path, String fileName, long sizeBytes, Instant updatedAt) {
    }

    private record CachedDigest(String fileName, long sizeBytes, long mtimeMillis, String sha256) {
    }
}
