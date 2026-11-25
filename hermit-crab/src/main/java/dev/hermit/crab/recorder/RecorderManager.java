package dev.hermit.crab.recorder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Stream;

import dev.hermit.common.error.ErrorCode;

/**
 * Memory-only, developer-mode-only manager for browser recording sessions and confirmed redacted snapshots.
 * Never persists captures to H2 or uploads to Reef; turning off developer mode or closing Crab wipes
 * all sessions, terminates any launched browser process, and clears all snapshots.
 */
public final class RecorderManager implements AutoCloseable {
    public static final int MAX_SNAPSHOTS = 10;
    public static final int MAX_SESSION_ENTRIES = CdpNetworkCollector.DEFAULT_MAX_ENTRIES;
    private static final String DEV_MODE_REQUIRED = "先打开开发者模式。";

    public enum SessionState {
        IDLE,
        RECORDING,
        STOPPED
    }

    public record StartRequest(
            String mode,
            String startUrl,
            String browserPath,
            String debugEndpoint,
            boolean headless
    ) {
    }

    public record RecorderStatus(
            boolean devMode,
            SessionState state,
            String sessionId,
            String mode,
            String source,
            String startUrl,
            Instant startedAt,
            Instant stoppedAt,
            int totalEntries,
            int businessEntries,
            boolean browserConnected,
            String disconnectReason,
            List<BrowserDetector.DetectedBrowser> browsers,
            boolean defaultRemoteDebuggingDetected,
            List<CaptureSnapshot> snapshots
    ) {
    }

    private static final long BROWSER_CACHE_SECONDS = 30L;

    private final Path profileBaseDir;
    private final Clock clock;
    private final Function<String, String> environment;
    private final BooleanSupplier devModeEnabled;

    private final Object lock = new Object();
    private List<BrowserDetector.DetectedBrowser> cachedBrowsers;
    private Instant browsersCachedAt;
    private SessionState state = SessionState.IDLE;
    private String sessionId;
    private String mode;
    private String source;
    private String startUrl;
    private Instant startedAt;
    private Instant stoppedAt;
    private BrowserLauncher.LaunchedBrowser launchedBrowser;
    private CdpNetworkCollector collector;
    private List<RecordedEntry> stoppedEntries = List.of();
    private final LinkedHashMap<String, CaptureSnapshot> snapshots = new LinkedHashMap<>();

    public RecorderManager(
            Path profileBaseDir,
            Clock clock,
            Function<String, String> environment,
            BooleanSupplier devModeEnabled
    ) {
        this.profileBaseDir = profileBaseDir;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.environment = environment == null ? System::getenv : environment;
        this.devModeEnabled = devModeEnabled == null ? () -> false : devModeEnabled;
        sweepStaleProfiles();
    }

    public RecorderStatus status() {
        boolean enabled = devModeEnabled.getAsBoolean();
        if (!enabled) {
            clearAll();
        }
        List<BrowserDetector.DetectedBrowser> browsers = detectBrowsersCached();
        List<Path> defaultPorts = BrowserDetector.findDefaultDevToolsActivePorts(
                System.getProperty("os.name", ""),
                environment,
                System.getProperty("user.home", ""));
        boolean defaultPort = BrowserLauncher.hasLiveDefaultDevToolsPort(defaultPorts);
        synchronized (lock) {
            List<RecordedEntry> current = currentEntriesLocked();
            int business = countBusiness(current);
            boolean connected = state == SessionState.RECORDING && collector != null && collector.isConnected();
            String reason = state == SessionState.RECORDING && collector != null ? collector.disconnectReason() : null;
            return new RecorderStatus(
                    enabled,
                    state,
                    sessionId,
                    mode,
                    source,
                    startUrl,
                    startedAt,
                    stoppedAt,
                    current.size(),
                    business,
                    connected,
                    reason,
                    browsers,
                    defaultPort,
                    List.copyOf(snapshots.values()));
        }
    }

    private List<BrowserDetector.DetectedBrowser> detectBrowsersCached() {
        Instant now = clock.instant();
        synchronized (lock) {
            if (cachedBrowsers != null && browsersCachedAt != null
                    && (!cachedBrowsers.isEmpty() || state == SessionState.RECORDING)
                    && !now.isAfter(browsersCachedAt.plusSeconds(BROWSER_CACHE_SECONDS))) {
                return cachedBrowsers;
            }
        }
        List<BrowserDetector.DetectedBrowser> fresh = BrowserDetector.detect(
                System.getProperty("os.name", ""),
                environment,
                System.getProperty("user.home", ""));
        synchronized (lock) {
            cachedBrowsers = fresh;
            browsersCachedAt = now;
        }
        return fresh;
    }

    public RecorderStatus start(StartRequest request) {
        requireDevMode();
        String modeValue = request == null || request.mode() == null || request.mode().isBlank()
                ? "launch"
                : request.mode().trim().toLowerCase(Locale.ROOT);
        if (!"launch".equals(modeValue) && !"attach".equals(modeValue)) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "录制模式只支持 launch（启动独立浏览器）或 attach（连接本机调试浏览器）。");
        }

        String id = "rec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String normalizedUrl = request == null ? "about:blank" : BrowserLauncher.normalizeStartUrl(request.startUrl());

        synchronized (lock) {
            if (state == SessionState.RECORDING) {
                throw new RecorderException(409, ErrorCode.CONFLICT, "当前已有正在进行的录制会话，请先停止录制。");
            }
        }

        BrowserLauncher.LaunchedBrowser browser = null;
        CdpNetworkCollector newCollector = null;
        try {
            URI wsUri;
            String navigateUrl = null;
            if ("launch".equals(modeValue)) {
                Path executable = resolveLaunchExecutable(request == null ? null : request.browserPath());
                Path profileDir = profileBaseDir.resolve(id);
                // Launch on about:blank so CDP Network.enable finishes before navigating to startUrl,
                // guaranteeing the very first login/redirect hop is captured.
                browser = BrowserLauncher.launch(executable, profileDir, "about:blank", request != null && request.headless());
                wsUri = browser.wsUri();
                if (!"about:blank".equalsIgnoreCase(normalizedUrl)) {
                    navigateUrl = normalizedUrl;
                }
            } else {
                wsUri = BrowserLauncher.resolveAttachUri(request == null ? null : request.debugEndpoint());
                if (!"about:blank".equalsIgnoreCase(normalizedUrl)) {
                    navigateUrl = normalizedUrl;
                }
            }
            newCollector = CdpNetworkCollector.connect(wsUri, clock, MAX_SESSION_ENTRIES, navigateUrl);
        } catch (RuntimeException ex) {
            if (newCollector != null) {
                newCollector.close();
            }
            if (browser != null) {
                browser.close();
            }
            throw ex;
        }

        synchronized (lock) {
            if (!devModeEnabled.getAsBoolean()) {
                newCollector.close();
                if (browser != null) {
                    browser.close();
                }
                throw new RecorderException(409, ErrorCode.NOT_CONFIGURED, DEV_MODE_REQUIRED);
            }
            if (state == SessionState.RECORDING) {
                newCollector.close();
                if (browser != null) {
                    browser.close();
                }
                throw new RecorderException(409, ErrorCode.CONFLICT, "当前已有正在进行的录制会话，请先停止录制。");
            }
            this.state = SessionState.RECORDING;
            this.sessionId = id;
            this.mode = modeValue;
            this.source = "launch".equals(modeValue) ? "chrome_launch" : "chrome_attach";
            this.startUrl = normalizedUrl;
            this.startedAt = clock.instant();
            this.stoppedAt = null;
            this.launchedBrowser = browser;
            this.collector = newCollector;
            this.stoppedEntries = List.of();
        }
        return status();
    }

    public RecorderStatus stop() {
        requireDevMode();
        CdpNetworkCollector activeCollector;
        BrowserLauncher.LaunchedBrowser activeBrowser;
        synchronized (lock) {
            if (state != SessionState.RECORDING) {
                return status();
            }
            activeCollector = this.collector;
            activeBrowser = this.launchedBrowser;
            this.collector = null;
            this.launchedBrowser = null;
        }

        List<RecordedEntry> collected = List.of();
        boolean gracefulCloseRequested = false;
        try {
            if (activeCollector != null) {
                gracefulCloseRequested = activeBrowser != null;
                collected = activeCollector.finishAndCollect(gracefulCloseRequested);
            }
        } finally {
            if (activeBrowser != null) {
                activeBrowser.close(gracefulCloseRequested);
            }
        }

        synchronized (lock) {
            this.stoppedEntries = collected;
            this.state = SessionState.STOPPED;
            this.stoppedAt = clock.instant();
        }
        return status();
    }

    public RecorderStatus importHar(byte[] bytes, String fileName) {
        requireDevMode();
        synchronized (lock) {
            if (state == SessionState.RECORDING) {
                throw new RecorderException(409, ErrorCode.CONFLICT, "正在录制浏览器，请先停止录制再导入 HAR 文件。");
            }
        }
        List<RecordedEntry> parsed = HarImporter.parse(bytes, MAX_SESSION_ENTRIES);
        if (parsed.isEmpty()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "HAR 文件中没有可解析的 HTTP(S) 请求记录。");
        }
        String id = "har_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Instant now = clock.instant();
        synchronized (lock) {
            if (!devModeEnabled.getAsBoolean()) {
                throw new RecorderException(409, ErrorCode.NOT_CONFIGURED, DEV_MODE_REQUIRED);
            }
            if (state == SessionState.RECORDING) {
                throw new RecorderException(409, ErrorCode.CONFLICT, "正在录制浏览器，请先停止录制再导入 HAR 文件。");
            }
            this.state = SessionState.STOPPED;
            this.sessionId = id;
            this.mode = "import";
            this.source = "har_import";
            this.startUrl = fileName == null || fileName.isBlank() ? "imported.har" : fileName.trim();
            this.startedAt = now;
            this.stoppedAt = now;
            this.stoppedEntries = parsed;
        }
        return status();
    }

    public List<RecordedEntry> listSessionEntries(boolean includeStatic) {
        requireDevMode();
        List<RecordedEntry> current;
        synchronized (lock) {
            current = currentEntriesLocked();
        }
        ArrayList<RecordedEntry> out = new ArrayList<>();
        int index = 0;
        for (RecordedEntry raw : current) {
            if (!includeStatic && raw.staticAsset()) {
                continue;
            }
            index++;
            out.add(CaptureRedactor.redactEntry(raw, index, Set.of(), CaptureRedactor.DEFAULT_PREVIEW_BODY_CHARS));
        }
        return List.copyOf(out);
    }

    public Optional<RecordedEntry> getSessionEntry(String entryId) {
        requireDevMode();
        if (entryId == null || entryId.isBlank()) {
            return Optional.empty();
        }
        List<RecordedEntry> current;
        synchronized (lock) {
            current = currentEntriesLocked();
        }
        for (RecordedEntry raw : current) {
            if (entryId.equals(raw.id())) {
                return Optional.of(CaptureRedactor.redactEntry(
                        raw, raw.index(), Set.of(), CaptureRedactor.DEFAULT_PREVIEW_BODY_CHARS));
            }
        }
        return Optional.empty();
    }

    public RecorderStatus clearSession() {
        requireDevMode();
        CdpNetworkCollector activeCollector;
        BrowserLauncher.LaunchedBrowser activeBrowser;
        synchronized (lock) {
            activeCollector = this.collector;
            activeBrowser = this.launchedBrowser;
            this.collector = null;
            this.launchedBrowser = null;
            this.state = SessionState.IDLE;
            this.sessionId = null;
            this.mode = null;
            this.source = null;
            this.startUrl = null;
            this.startedAt = null;
            this.stoppedAt = null;
            this.stoppedEntries = List.of();
        }
        closeQuietly(activeCollector, activeBrowser);
        return status();
    }

    public CaptureSnapshot createSnapshot(
            String rawTitle,
            List<String> entryIds,
            Collection<String> extraSecrets
    ) {
        requireDevMode();
        if (entryIds == null || entryIds.isEmpty()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "请至少勾选一条请求再生成脱敏快照。");
        }
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String id : entryIds) {
            if (id != null && !id.isBlank()) {
                wanted.add(id.trim());
            }
        }
        if (wanted.isEmpty()) {
            throw new RecorderException(400, ErrorCode.INVALID_INPUT, "请至少勾选一条请求再生成脱敏快照。");
        }

        List<RecordedEntry> current;
        String currentSource;
        synchronized (lock) {
            current = currentEntriesLocked();
            currentSource = this.source == null ? "chrome_launch" : this.source;
        }
        ArrayList<RecordedEntry> selectedRaw = new ArrayList<>();
        for (RecordedEntry entry : current) {
            if (wanted.contains(entry.id())) {
                selectedRaw.add(entry);
            }
        }
        if (selectedRaw.isEmpty()) {
            throw new RecorderException(404, ErrorCode.NOT_FOUND, "勾选的请求记录不存在，请刷新列表后重试。");
        }

        ArrayList<RecordedEntry> redacted = new ArrayList<>(selectedRaw.size());
        boolean hasRedirects = false;
        boolean hasDownloads = false;
        int idx = 0;
        for (RecordedEntry raw : selectedRaw) {
            idx++;
            RecordedEntry clean = CaptureRedactor.redactEntry(
                    raw, idx, extraSecrets, CaptureRedactor.DEFAULT_SNAPSHOT_BODY_CHARS);
            if (clean.redirectTo() != null && !clean.redirectTo().isBlank()) {
                hasRedirects = true;
            }
            if (clean.binary()) {
                hasDownloads = true;
            }
            redacted.add(clean);
        }

        List<String> origins = CaptureRedactor.extractOrigins(redacted);
        List<String> cookieNames = CaptureRedactor.extractCookieNames(redacted);
        String title = (rawTitle == null || rawTitle.isBlank())
                ? defaultTitle(origins)
                : trimTitle(rawTitle);
        String snapshotId = "cap_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        CaptureSnapshot snapshot = new CaptureSnapshot(
                snapshotId,
                title,
                clock.instant(),
                currentSource,
                origins,
                cookieNames,
                hasRedirects,
                hasDownloads,
                redacted);

        synchronized (lock) {
            if (!devModeEnabled.getAsBoolean()) {
                throw new RecorderException(409, ErrorCode.NOT_CONFIGURED, DEV_MODE_REQUIRED);
            }
            snapshots.put(snapshotId, snapshot);
            while (snapshots.size() > MAX_SNAPSHOTS) {
                String oldestKey = snapshots.keySet().iterator().next();
                snapshots.remove(oldestKey);
            }
        }
        return snapshot;
    }

    public List<CaptureSnapshot> listSnapshots() {
        if (!devModeEnabled.getAsBoolean()) {
            clearAll();
            return List.of();
        }
        synchronized (lock) {
            return List.copyOf(snapshots.values());
        }
    }

    public Optional<CaptureSnapshot> getSnapshot(String snapshotId) {
        if (!devModeEnabled.getAsBoolean()) {
            clearAll();
            return Optional.empty();
        }
        if (snapshotId == null || snapshotId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            return Optional.ofNullable(snapshots.get(snapshotId.trim()));
        }
    }

    public byte[] exportHar(String snapshotId) {
        requireDevMode();
        CaptureSnapshot snapshot = getSnapshot(snapshotId).orElseThrow(() -> new RecorderException(
                404, ErrorCode.NOT_FOUND, "抓包快照不存在或已清除。"));
        return HarWriter.write(snapshot);
    }

    public boolean deleteSnapshot(String snapshotId) {
        requireDevMode();
        if (snapshotId == null || snapshotId.isBlank()) {
            return false;
        }
        synchronized (lock) {
            return snapshots.remove(snapshotId.trim()) != null;
        }
    }

    /**
     * Stops any active recording, terminates any launched Chrome process, removes temp profiles,
     * and wipes both the session buffer and all memory snapshots.
     */
    public void clearAll() {
        CdpNetworkCollector activeCollector;
        BrowserLauncher.LaunchedBrowser activeBrowser;
        synchronized (lock) {
            activeCollector = this.collector;
            activeBrowser = this.launchedBrowser;
            this.collector = null;
            this.launchedBrowser = null;
            this.state = SessionState.IDLE;
            this.sessionId = null;
            this.mode = null;
            this.source = null;
            this.startUrl = null;
            this.startedAt = null;
            this.stoppedAt = null;
            this.stoppedEntries = List.of();
            this.snapshots.clear();
        }
        closeQuietly(activeCollector, activeBrowser);
        sweepStaleProfiles();
    }

    @Override
    public void close() {
        clearAll();
    }

    private void requireDevMode() {
        if (!devModeEnabled.getAsBoolean()) {
            clearAll();
            throw new RecorderException(409, ErrorCode.NOT_CONFIGURED, DEV_MODE_REQUIRED);
        }
    }

    private Path resolveLaunchExecutable(String explicitPath) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            return BrowserDetector.validateExecutable(explicitPath);
        }
        List<BrowserDetector.DetectedBrowser> detected = BrowserDetector.detect(
                System.getProperty("os.name", ""),
                environment,
                System.getProperty("user.home", ""));
        if (detected.isEmpty()) {
            throw new RecorderException(409, ErrorCode.NOT_CONFIGURED,
                    "未自动检测到本机 Chrome / Edge / Chromium 浏览器。请手动填写浏览器可执行文件路径，或改用「连接本机调试浏览器」/「导入 HAR 文件」。");
        }
        return BrowserDetector.validateExecutable(detected.get(0).path());
    }

    private List<RecordedEntry> currentEntriesLocked() {
        if (state == SessionState.RECORDING && collector != null) {
            return collector.snapshotEntries();
        }
        return stoppedEntries;
    }

    private static int countBusiness(List<RecordedEntry> entries) {
        int count = 0;
        for (RecordedEntry entry : entries) {
            if (!entry.staticAsset()) {
                count++;
            }
        }
        return count;
    }

    private static String defaultTitle(List<String> origins) {
        if (origins != null && !origins.isEmpty()) {
            return trimTitle(origins.get(0));
        }
        return "浏览器抓包";
    }

    private static String trimTitle(String raw) {
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private static void closeQuietly(
            CdpNetworkCollector collector,
            BrowserLauncher.LaunchedBrowser browser
    ) {
        boolean gracefulCloseRequested = false;
        if (collector != null) {
            try {
                gracefulCloseRequested = browser != null;
                collector.close(gracefulCloseRequested);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
        if (browser != null) {
            try {
                browser.close(gracefulCloseRequested);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup.
            }
        }
    }

    private void sweepStaleProfiles() {
        if (profileBaseDir == null || !Files.isDirectory(profileBaseDir)) {
            return;
        }
        try (Stream<Path> children = Files.list(profileBaseDir)) {
            children.forEach(BrowserLauncher::deleteDirectoryQuietly);
        } catch (IOException ignored) {
            // Best-effort cleanup on startup/shutdown.
        }
    }
}
