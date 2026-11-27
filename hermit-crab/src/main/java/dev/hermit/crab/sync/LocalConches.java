package dev.hermit.crab.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;

import javax.sql.DataSource;

import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.McpAuth;
import dev.hermit.common.model.McpUpstream;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchDestinations;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.DuplicateActions;
import dev.hermit.common.validate.DuplicateExports;
import dev.hermit.common.validate.DuplicateGroups;
import dev.hermit.common.validate.DuplicateScripts;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CredentialVault;
import dev.hermit.crab.store.Origin;
import dev.hermit.crab.upstream.McpOAuthToken;

/**
 * Unsigned conches stored as {@code local-conches/{conch}.yaml}. The bytes on disk are the
 * import, including a later export. One bad file does not stop the others.
 *
 * <p>A file that cannot be read, fails to parse, or names a different package is not left
 * callable and is not copied aside. When the file stem is a legal package name, that name stays
 * listed as broken until it is deleted, and can then be imported again. Removing the file from
 * the directory still unloads the package and drops the broken row.
 */
public final class LocalConches {
    /** Same ceiling as Reef {@code ConchPaths.MAX_UPLOAD_BYTES}. */
    public static final int MAX_BYTES = 1024 * 1024;
    /** 待确认覆盖的内存寿命。重启即丢，拒绝和到期不写审计。 */
    public static final Duration PENDING_TTL = Duration.ofMinutes(30);
    /**
     * {@code .history} 里每个包保留的份数。正在使用的 {@code {conch}.yaml} 不占这名额，
     * 所以这是「当前版本之外的最近 2 份」，连当前一共 3 份。写入在同一把锁里备份后修剪；
     * 启动和 reload 也修剪，旧安装里多出来的不会留到下一次写入。和当前内容相同的不进历史。
     */
    static final int HISTORY_KEEP = 2;
    /** 带 API Key 的请求不能创建或撤销自动确认，即使同时带了控制台头。 */
    public static final String GRANT_API_KEY = "API Key 不能授权或撤销自动确认。";

    private static final Logger LOG = Logger.getLogger(LocalConches.class.getName());
    private static final String SUFFIX = ".yaml";
    private static final DateTimeFormatter HISTORY_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    private static final Set<String> STEP_TYPES = Set.of(
            "request", "extract", "assert", "set", "script", "ask", "if", "repeat");

    private final CrabPaths paths;
    private final ConchRegistry registry;
    private final DataSource dataSource;
    private final BooleanSupplier debugEnabled;
    private final Clock clock;
    private final Object lock = new Object();
    /** Legal stems whose yaml failed to load. Rebuilt on startup and reload. Not callable. */
    private final LinkedHashMap<String, BrokenLocal> broken = new LinkedHashMap<>();
    /** 每个包最多一条。新的替换旧的。不落库。 */
    private final LinkedHashMap<String, PendingOverwrite> pending = new LinkedHashMap<>();
    /**
     * 控制台在本次开发者模式里勾选「不再询问」的包。按包名，没有全局授权。
     * 只在内存：关掉开发者模式、到期、撤销或进程重启都会丢掉。不写 schema。
     */
    private final LinkedHashMap<String, OverrideGrant> grants = new LinkedHashMap<>();
    private volatile Consumer<String> onWidened = name -> {
    };
    private volatile Consumer<String> onAuthSpecChanged = name -> {
    };
    private volatile java.util.function.BiConsumer<String, String> onDeleted = (name, actor) -> {
    };
    private volatile Predicate<String> boundToIdentity = conch -> false;
    private volatile Predicate<String> boundToOfficialAuth = conch -> false;
    private volatile Predicate<String> boundToAnyAuth = conch -> false;
    /** 第一次清会话返回之后、写文件之前。测试在这个窗口里插入登录。生产路径是空操作。 */
    private volatile Runnable widenedGap = () -> {
    };

    public LocalConches(CrabPaths paths, ConchRegistry registry, DataSource dataSource) {
        this(paths, registry, dataSource, () -> false, Clock.systemUTC());
    }

    /**
     * {@code debugEnabled} 为 true 时，空 actions 的 mcp 骨架可以导入（警告，不是错误）。
     * 正式发布校验不走这里。
     */
    public LocalConches(
            CrabPaths paths,
            ConchRegistry registry,
            DataSource dataSource,
            BooleanSupplier debugEnabled
    ) {
        this(paths, registry, dataSource, debugEnabled, Clock.systemUTC());
    }

    public LocalConches(
            CrabPaths paths,
            ConchRegistry registry,
            DataSource dataSource,
            BooleanSupplier debugEnabled,
            Clock clock
    ) {
        this.paths = paths;
        this.registry = registry;
        this.dataSource = dataSource;
        this.debugEnabled = debugEnabled == null ? () -> false : debugEnabled;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 地址范围扩大的覆盖调用两次，都在这把写入锁里：
     * {@code registry.put} 之前一次，成功之后再一次。
     * 用来清掉这个包全部账号的 HTTP 会话和登录快照。
     * 两次之间新建立的会话由第二次清掉。
     */
    public void onWidened(Consumer<String> listener) {
        this.onWidened = listener == null ? name -> {
        } : listener;
    }

    /** 同边界覆盖但包的 {@code auth} 段变化时调用：刷新登录/换票快照，不解除授权器绑定。 */
    public void onAuthSpecChanged(Consumer<String> listener) {
        this.onAuthSpecChanged = listener == null ? name -> {
        } : listener;
    }

    /** 本地包被使用者删掉之后调用，参数是包名和操作者。放开本地包的锁之后才调用。 */
    public void onDeleted(java.util.function.BiConsumer<String, String> listener) {
        this.onDeleted = listener == null ? (name, actor) -> {
        } : listener;
    }

    /** 检查本地包是否有任意账号绑到了共享身份。已绑共享身份的包覆盖前必须确认，确认后自动移出共享身份。 */
    public void onBoundCheck(Predicate<String> check) {
        this.boundToIdentity = check == null ? conch -> false : check;
    }

    /**
     * 检查本地包与授权器的绑定状态：
     * {@code officialAuthCheck} 为绑定到正式来源授权器（同边界首次覆盖需确认，确认后保留绑定，勾选免确认后同边界免询问）；
     * {@code anyAuthCheck} 为存在任意授权器绑定（仅当边界扩大时拒绝免确认并在确认后解绑）。
     */
    public void onBoundAuthChecks(Predicate<String> officialAuthCheck, Predicate<String> anyAuthCheck) {
        this.boundToOfficialAuth = officialAuthCheck == null ? conch -> false : officialAuthCheck;
        this.boundToAnyAuth = anyAuthCheck == null ? conch -> false : anyAuthCheck;
    }

    /** 测试用：第一次清会话返回之后、写文件之前。 */
    void widenedGap(Runnable hook) {
        this.widenedGap = hook == null ? () -> {
        } : hook;
    }

    public ImportResult importBytes(byte[] raw, String actor) {
        return importBytes(raw, actor, false);
    }

    /**
     * @param replace overwrite an existing local package or yaml file. Without it, an existing
     *     target fails with {@link LocalConchException#replaceRequired(String)} and the file is
     *     left untouched.
     */
    public ImportResult importBytes(byte[] raw, String actor, boolean replace) {
        return importBytes(raw, actor, replace, ReplaceOptions.none());
    }

    /**
     * {@code options.expectedBaseSha()} 非空时，磁盘文件的 SHA-256 必须还是这个值，否则 409 且不写。
     * 空字符串表示当时没有文件。
     */
    public ImportResult importBytes(byte[] raw, String actor, boolean replace, ReplaceOptions options) {
        String checked = actor(actor);
        requireRaw(raw);
        ReplaceOptions opts = options == null ? ReplaceOptions.none() : options;
        synchronized (lock) {
            return writeUnlocked(raw, checked, replace, opts);
        }
    }

    /**
     * 已有本地包且目的地没有扩大、未绑共享身份、也未绑正式授权器时直接覆盖。否则抛 {@link LocalConchException#replaceRequired(String)}，
     * 不写文件。判断和写入在同一把锁里。
     */
    public ImportResult replaceIfSameBoundary(byte[] raw, String actor, String keyName) {
        String checked = actor(actor);
        requireRaw(raw);
        synchronized (lock) {
            Parsed parsed = parse(raw);
            String conch = parsed.doc().conch();
            rejectClash(conch);
            if (!localPackageExists(conch)) {
                return writeUnlocked(raw, checked, false, ReplaceOptions.none());
            }
            ConchDoc before = readableDoc(conch).orElse(null);
            if (boundToIdentity.test(conch)
                    || boundToOfficialAuth.test(conch)
                    || !ConchDestinations.between(before, parsed.doc()).sameBoundary()) {
                throw LocalConchException.replaceRequired(conch);
            }
            return writeUnlocked(raw, checked, true, new ReplaceOptions(null, keyName, null));
        }
    }

    /**
     * 地址扩大、已绑共享身份或已绑正式授权器、客户端不能当场确认时记下一条待确认覆盖。已经是同一边界且无需确认，或包已经不在了，返回 empty，不写文件。
     */
    public Optional<PendingOverwrite> stagePending(byte[] raw, String actor, String submittedBy) {
        String checked = actor(actor);
        requireRaw(raw);
        synchronized (lock) {
            Parsed parsed = parse(raw);
            String conch = parsed.doc().conch();
            rejectClash(conch);
            if (!localPackageExists(conch)) {
                return Optional.empty();
            }
            ConchDoc before = readableDoc(conch).orElse(null);
            ConchDestinations.Boundary boundary = ConchDestinations.between(before, parsed.doc());
            if (boundary.sameBoundary() && !boundToIdentity.test(conch) && !boundToOfficialAuth.test(conch)) {
                return Optional.empty();
            }
            byte[] disk = readIfRegular(conch);
            String baseSha = disk == null ? "" : sha256(disk);
            PendingOverwrite row = new PendingOverwrite(
                    conch,
                    raw,
                    sha256(raw),
                    baseSha,
                    parsed.doc().version().toString(),
                    before == null ? null : before.version().toString(),
                    boundary.addedDestinations(),
                    boundary.undetermined(),
                    blankToNull(submittedBy),
                    clock.instant());
            pending.put(conch, row);
            auditDetail(checked, AuditKind.LOCAL_CONCH_REPLACE_PENDING, detail(
                    conch,
                    row.version(),
                    row.previousVersion(),
                    row.sha256(),
                    baseSha,
                    boundary,
                    null,
                    row.submittedBy()));
            return Optional.of(row);
        }
    }

    /** 未过期的待确认覆盖，按包名排序。过期的顺手丢掉，不写审计。 */
    public List<PendingOverwrite> pending() {
        synchronized (lock) {
            ArrayList<PendingOverwrite> rows = new ArrayList<>();
            for (String conch : List.copyOf(pending.keySet())) {
                PendingOverwrite row = fresh(conch);
                if (row != null) {
                    rows.add(row);
                }
            }
            rows.sort(Comparator.comparing(PendingOverwrite::conch));
            return List.copyOf(rows);
        }
    }

    /**
     * 控制台确认一条待确认覆盖。{@code sha256} 必须是这条 YAML 的摘要。
     * 磁盘已不是提交时的 {@code baseSha} 时 409，并丢掉这条待确认。
     */
    public ImportResult confirmPending(String conch, String sha256, String actor) {
        return confirmPending(conch, sha256, actor, false);
    }

    /**
     * {@code remember} 为 true 时，成功写入之后给这个包记下自动确认。
     * 只有控制台 actor 可以记下；其它 actor 是 403，不写文件，也不留下授权。
     * 开发者模式关着时这次确认仍然写入，但不留下授权。
     * 判断、写入和记授权都在同一把锁里。
     */
    public ImportResult confirmPending(String conch, String sha256, String actor, boolean remember) {
        String checked = actor(actor);
        if (remember && !Actors.CONSOLE.equals(checked)) {
            throw LocalConchException.forbidden(LocalConchException.GRANT_CONSOLE_ONLY);
        }
        synchronized (lock) {
            PendingOverwrite row = fresh(conch);
            if (row == null) {
                throw LocalConchException.pendingMissing();
            }
            if (sha256 == null || !sha256.equals(row.sha256())) {
                throw LocalConchException.pendingChanged();
            }
            try {
                ImportResult result = writeUnlocked(
                        row.yaml(),
                        checked,
                        true,
                        new ReplaceOptions(row.baseSha(), row.submittedBy(), AuditKind.LOCAL_CONCH_REPLACED));
                if (remember && debugOn()) {
                    grants.put(result.conch(), new OverrideGrant(result.conch(), clock.instant(), checked));
                }
                return result;
            } catch (LocalConchException ex) {
                if (LocalConchException.STALE.equals(ex.getMessage())) {
                    pending.remove(conch);
                }
                throw ex;
            }
        }
    }

    /**
     * 这个包在本次开发者模式里已被控制台授权时，在同一把锁里走与确认相同的校验、大小、摘要和路径检查，
     * 并写入历史。没有授权、开发者模式已经关掉，或包已绑定共享身份时返回 empty，不写文件。
     * 校验失败抛出，授权还在，文件不动。
     */
    public Optional<ImportResult> applyIfGranted(byte[] raw, String actor, String keyName) {
        String checked = actor(actor);
        requireRaw(raw);
        synchronized (lock) {
            if (!debugOn()) {
                grants.clear();
                return Optional.empty();
            }
            Parsed parsed = parse(raw);
            String conch = parsed.doc().conch();
            OverrideGrant grant = grants.get(conch);
            if (grant == null || boundToIdentity.test(conch)) {
                return Optional.empty();
            }
            ConchDoc before = readableDoc(conch).orElse(null);
            if (!ConchDestinations.between(before, parsed.doc()).sameBoundary() && boundToAnyAuth.test(conch)) {
                return Optional.empty();
            }
            return Optional.of(writeUnlocked(
                    raw,
                    checked,
                    true,
                    ReplaceOptions.autoApproved(keyName, grant.createdBy())));
        }
    }

    /** 还在本次开发者模式里的自动确认，按包名排序。开发者模式关着时先清空。 */
    public List<OverrideGrant> grants() {
        synchronized (lock) {
            if (!debugOn()) {
                grants.clear();
                return List.of();
            }
            ArrayList<OverrideGrant> rows = new ArrayList<>(grants.values());
            rows.sort(Comparator.comparing(OverrideGrant::conch));
            return List.copyOf(rows);
        }
    }

    /** 没有这项授权时返回 false。 */
    public boolean revokeGrant(String conch) {
        if (conch == null || conch.isBlank()) {
            return false;
        }
        synchronized (lock) {
            return grants.remove(conch) != null;
        }
    }

    /** 关掉开发者模式、到期或重启时丢掉全部自动确认。 */
    public void clearGrants() {
        synchronized (lock) {
            grants.clear();
        }
    }

    /** 拒绝不写审计。没有这条待确认时 404。 */
    public void rejectPending(String conch) {
        synchronized (lock) {
            if (fresh(conch) == null) {
                throw LocalConchException.pendingMissing();
            }
            pending.remove(conch);
        }
    }

    /** 磁盘上这份本地包的 SHA-256。没有普通文件时是空字符串。 */
    public String diskSha(String conch) {
        synchronized (lock) {
            byte[] raw = readIfRegular(conch);
            return raw == null ? "" : sha256(raw);
        }
    }

    /** 最近的在前。包已经删掉时，只要历史还在就能列出来。 */
    public List<HistoryEntry> history(String conch) {
        synchronized (lock) {
            Path dir = historyDir(conch);
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            ArrayList<Path> files = new ArrayList<>();
            try (var children = Files.list(dir)) {
                for (Path child : children.toList()) {
                    if (Files.isSymbolicLink(child)) {
                        continue;
                    }
                    String name = child.getFileName().toString();
                    if (name.endsWith(SUFFIX) && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        files.add(child);
                    }
                }
            } catch (IOException ex) {
                throw new IllegalStateException("local conch history was not listed");
            }
            files.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
            ArrayList<HistoryEntry> rows = new ArrayList<>();
            for (Path file : files.subList(0, Math.min(files.size(), HISTORY_KEEP))) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - SUFFIX.length());
                rows.add(new HistoryEntry(id, historyVersion(file), historySavedAt(id)));
            }
            return List.copyOf(rows);
        }
    }

    /**
     * 用一份历史替换当前文件。当前文件会先备份。包已经不在时，这是一次带回退审计的导入。
     */
    public ImportResult restore(String conch, String id, String actor) {
        String checked = actor(actor);
        synchronized (lock) {
            byte[] raw = readHistory(conch, id);
            Parsed parsed = parse(raw);
            if (!conch.equals(parsed.doc().conch())) {
                throw LocalConchException.invalid("历史文件里的包名不一致。");
            }
            boolean exists = localPackageExists(conch);
            return writeUnlocked(
                    raw, checked, exists, new ReplaceOptions(null, null, AuditKind.LOCAL_CONCH_RESTORED));
        }
    }

    public List<FileResult> reload(String actor) {
        String checked = actor(actor);
        ArrayList<String> deleted = new ArrayList<>();
        List<FileResult> results;
        synchronized (lock) {
            results = scan(checked, true, deleted);
        }
        for (String conch : deleted) {
            onDeleted.accept(conch, checked);
        }
        return results;
    }

    /** Startup load. A bad file is logged and skipped. Nothing is written to the audit log. */
    public void loadStartup() {
        synchronized (lock) {
            try {
                scan(null, false, null);
            } catch (RuntimeException ex) {
                LOG.warning("local conch directory was not loaded: " + ex.getClass().getName());
            }
        }
    }

    public void delete(String conch, String actor) {
        String checked = actor(actor);
        synchronized (lock) {
            if (registry.origin(conch) == Origin.OFFICIAL) {
                throw LocalConchException.notFound();
            }
            boolean loaded = registry.origin(conch) == Origin.LOCAL && registry.find(conch).isPresent();
            BrokenLocal failed = broken.get(conch);
            if (!loaded && failed == null) {
                throw LocalConchException.notFound();
            }
            ConchDoc doc = loaded ? registry.find(conch).orElse(null) : null;
            Path file = yamlPath(conch);
            synchronized (ConchFileLocks.of(conch)) {
                if (Files.isSymbolicLink(file)) {
                    throw LocalConchException.invalid("local conch path is a symlink");
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ex) {
                    throw new IllegalStateException("local conch file was not deleted");
                }
                if (loaded) {
                    registry.remove(conch);
                }
                broken.remove(conch);
                audit(checked, AuditKind.LOCAL_CONCH_DELETED, conch, doc == null ? "" : doc.version().toString());
            }
        }
        onDeleted.accept(conch, checked);
    }

    /** Loaded local packages and broken files whose stem is a legal package name, sorted by name. */
    public List<LocalPackage> packages() {
        synchronized (lock) {
            TreeMap<String, LocalPackage> rows = new TreeMap<>();
            for (Map.Entry<String, ConchDoc> entry : registry.snapshot().entrySet()) {
                if (registry.origin(entry.getKey()) != Origin.LOCAL) {
                    continue;
                }
                String name = entry.getKey();
                rows.put(name, new LocalPackage(
                        name, name + SUFFIX, entry.getValue().version().toString(), true, false, null, false));
            }
            for (BrokenLocal failed : broken.values()) {
                if (rows.containsKey(failed.conch())) {
                    continue;
                }
                rows.put(failed.conch(), new LocalPackage(
                        failed.conch(), failed.file(), null, false, true, failed.reason(), failed.ruleDisabled()));
            }
            return List.copyOf(rows.values());
        }
    }

    /** True when the name is a loaded local package or a broken local file. It is not callable when only broken. */
    public boolean occupies(String conch) {
        if (conch == null) {
            return false;
        }
        synchronized (lock) {
            return registry.origin(conch) == Origin.LOCAL || broken.containsKey(conch);
        }
    }

    /** 磁盘上是否还存在这个包对应的本地 yaml 文件（含损坏或符号链接文件）。 */
    public boolean hasYamlFile(String conch) {
        if (conch == null) {
            return false;
        }
        try {
            return Files.exists(yamlPath(conch), LinkOption.NOFOLLOW_LINKS);
        } catch (LocalConchException ex) {
            return false;
        }
    }

    public Export export(String conch) {
        synchronized (lock) {
            if (registry.origin(conch) == Origin.LOCAL) {
                ConchDoc doc = registry.find(conch).orElse(null);
                if (doc == null || !ConchFiles.safeSegment(doc.version().toString())) {
                    throw LocalConchException.notFound();
                }
                return exportFile(conch, doc.version().toString());
            }
            BrokenLocal failed = broken.get(conch);
            if (failed != null && failed.ruleDisabled()) {
                String version = "0.0.0";
                ConchDoc doc = readStored(conch).orElse(null);
                if (doc != null && doc.version() != null && ConchFiles.safeSegment(doc.version().toString())) {
                    version = doc.version().toString();
                }
                return exportFile(conch, version);
            }
            throw LocalConchException.notFound();
        }
    }

    /**
     * Parsed document for a local file, including one the validator will not load.
     * Empty when the file is missing, unreadable, or names a different package.
     */
    public Optional<ConchDoc> readableDoc(String conch) {
        synchronized (lock) {
            if (registry.origin(conch) == Origin.LOCAL) {
                return registry.find(conch);
            }
            if (conch == null || !broken.containsKey(conch)) {
                return Optional.empty();
            }
            return readStored(conch).filter(doc -> conch.equals(doc.conch()));
        }
    }

    /**
     * 覆盖确认文案。目的地没变时沿用短句。扩大时先列出新增地址。
     * 上游主机或已声明的令牌端点主机会变时，写明旧主机、新主机，并说明确认后会清掉已保存的上游令牌。
     */
    public static String overwriteConfirm(String conch, ConchDoc before, ConchDoc after) {
        String lead = "将覆盖已有的本地规则包 " + conch + "。";
        ConchDestinations.Boundary boundary = ConchDestinations.between(before, after);
        StringBuilder text = new StringBuilder(lead);
        if (!boundary.sameBoundary()) {
            if (!boundary.addedDestinations().isEmpty() || boundary.undetermined()) {
                text.append("新增地址：").append(ConchDestinations.describe(boundary)).append('。');
            } else if (boundary.authChanged()) {
                text.append("认证方式有变化。");
            }
        }
        UpstreamIdentity left = identity(before);
        UpstreamIdentity right = identity(after);
        boolean urlHostChanged = !Objects.equals(left.host, right.host);
        boolean tokenHostChanged = !Objects.equals(left.tokenHost, right.tokenHost);
        if (!urlHostChanged && !tokenHostChanged) {
            if (boundary.sameBoundary()) {
                return lead + "确认后才会写入。";
            }
            text.append("确认后才会写入。");
            return text.toString();
        }
        boolean tokenFollowsUrl = urlHostChanged
                && Objects.equals(left.tokenHost, left.host)
                && Objects.equals(right.tokenHost, right.host);
        if (urlHostChanged) {
            text.append("上游主机将从 ").append(hostText(left.host)).append(" 改为 ")
                    .append(hostText(right.host)).append('，');
        }
        if (tokenHostChanged && !tokenFollowsUrl) {
            text.append("令牌端点主机将从 ").append(hostText(left.tokenHost)).append(" 改为 ")
                    .append(hostText(right.tokenHost)).append('，');
        }
        text.append("确认后会清掉已保存的上游令牌。确认后才会写入。");
        return text.toString();
    }

    private Export exportFile(String conch, String version) {
        Path file = yamlPath(conch);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw LocalConchException.notFound();
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw LocalConchException.invalid("yaml exceeds 1 MiB");
            }
            return new Export(conch, version, Files.readAllBytes(file));
        } catch (LocalConchException ex) {
            throw ex;
        } catch (IOException ex) {
            throw LocalConchException.notFound();
        }
    }

    private Optional<ConchDoc> readStored(String conch) {
        Path file = yamlPath(conch);
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            if (Files.size(file) > MAX_BYTES) {
                return Optional.empty();
            }
            return Optional.of(ConchParser.parse(Files.readString(file)));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Parse and validate the same way as import. Does not check name clashes and does not write.
     * A failure that is not a validation issue (empty body, bad UTF-8, over 1 MiB) is thrown.
     */
    public CheckResult check(byte[] raw) {
        if (raw == null) {
            throw LocalConchException.invalid("yaml is required");
        }
        if (raw.length > MAX_BYTES) {
            throw LocalConchException.invalid("yaml exceeds 1 MiB");
        }
        try {
            Parsed parsed = parse(raw);
            return new CheckResult(
                    parsed.doc().conch(),
                    parsed.doc().version().toString(),
                    List.of(),
                    parsed.warnings());
        } catch (LocalConchException ex) {
            if (!ex.hasIssues()) {
                throw ex;
            }
            return new CheckResult(ex.parsedConch(), null, ex.errors(), ex.warnings());
        }
    }

    public static byte[] readLimited(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw LocalConchException.invalid("yaml exceeds 1 MiB");
        }
        return bytes;
    }

    private List<FileResult> scan(String actor, boolean auditChanges, List<String> deletedOut) {
        Path root = paths.localConches().toAbsolutePath().normalize();
        pruneAllHistory();
        Map<String, ConchDoc> beforeDocs = localDocs();
        Map<String, String> beforeVersions = localVersions();
        Set<String> beforeBroken = Set.copyOf(broken.keySet());
        ArrayList<FileResult> results = new ArrayList<>();
        LinkedHashMap<String, ConchDoc> loaded = new LinkedHashMap<>();
        LinkedHashMap<String, BrokenLocal> nextBroken = new LinkedHashMap<>();
        try (var children = Files.list(root)) {
            for (Path child : children.filter(path -> {
                String name = path.getFileName().toString();
                return !name.startsWith(".") && name.endsWith(SUFFIX);
            }).sorted().toList()) {
                results.add(loadFile(root, child, beforeDocs, beforeBroken, loaded, nextBroken));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("local conch directory was not listed");
        }
        for (FileResult result : results) {
            if (!result.ok()) {
                LOG.warning("local conch skipped file=" + result.file()
                        + " codes=" + codes(result.issues())
                        + " lines=" + RuleDisabled.lines(result.issues()));
            }
        }
        List<String> removed = dropMissing(loaded.keySet());
        broken.clear();
        broken.putAll(nextBroken);
        if (deletedOut != null && actor != null) {
            java.util.LinkedHashSet<String> gone = new java.util.LinkedHashSet<>();
            for (String conch : removed) {
                if (!broken.containsKey(conch)) {
                    gone.add(conch);
                }
            }
            for (String conch : beforeBroken) {
                if (!loaded.containsKey(conch) && !broken.containsKey(conch)) {
                    gone.add(conch);
                }
            }
            deletedOut.addAll(gone);
        }
        if (auditChanges && actor != null) {
            Map<String, String> afterVersions = localVersions();
            for (Map.Entry<String, String> entry : afterVersions.entrySet()) {
                if (!entry.getValue().equals(beforeVersions.get(entry.getKey()))) {
                    audit(actor, AuditKind.LOCAL_CONCH_IMPORTED, entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, String> entry : beforeVersions.entrySet()) {
                if (!afterVersions.containsKey(entry.getKey()) && !broken.containsKey(entry.getKey())) {
                    audit(actor, AuditKind.LOCAL_CONCH_DELETED, entry.getKey(), entry.getValue());
                }
            }
        }
        return List.copyOf(results);
    }

    private FileResult loadFile(
            Path root,
            Path child,
            Map<String, ConchDoc> beforeDocs,
            Set<String> beforeBroken,
            Map<String, ConchDoc> loaded,
            Map<String, BrokenLocal> nextBroken
    ) {
        String fileName = child.getFileName().toString();
        Path file = child.toAbsolutePath().normalize();
        if (!file.startsWith(root) || !root.equals(file.getParent())) {
            return failed(fileName, null, null, issue("PATH", fileName, sentence("文件 " + fileName + " 不在本地规则包目录里")));
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return failed(fileName, stemName(stemOf(fileName)), null,
                    issue("SYMLINK", fileName, sentence("文件 " + fileName + " 不是普通文件")));
        }
        String stem = stemOf(fileName);
        byte[] raw;
        try {
            if (Files.size(file) > MAX_BYTES) {
                String reason = readReason(fileName, stemName(stem), "超过 1 MiB");
                return reject(fileName, stem, stemName(stem), null, "TOO_LARGE", reason, List.of(), nextBroken);
            }
            raw = Files.readAllBytes(file);
        } catch (IOException ex) {
            String reason = readReason(fileName, stemName(stem), "没有读出来");
            return reject(fileName, stem, stemName(stem), null, "IO", reason, List.of(), nextBroken);
        }
        Parsed parsed;
        try {
            parsed = parse(raw);
        } catch (LocalConchException ex) {
            boolean disabled = ruleDisabled(ex);
            String name = ex.parsedConch() != null ? ex.parsedConch() : stemName(stem);
            String reason = disabled
                    ? readReason(fileName, name, RuleDisabled.explain(ex.errors()))
                    : loadFailureReason(fileName, stemName(stem), ex);
            String code = ex.errors().isEmpty() ? "INVALID_INPUT" : ex.errors().get(0).code();
            return reject(fileName, stem, ex.parsedConch(), null, code, reason, ex.errors(), disabled, nextBroken);
        }
        if (!stem.equals(parsed.doc().conch())) {
            String reason = sentence("文件 " + fileName + " 里的规则包是 " + parsed.doc().conch() + "，与文件名不一致");
            return reject(
                    fileName,
                    stem,
                    parsed.doc().conch(),
                    parsed.doc().version().toString(),
                    "NAME",
                    reason,
                    List.of(),
                    nextBroken);
        }
        String conch = parsed.doc().conch();
        ConchDoc before = beforeDocs.get(conch);
        boolean widened = (before != null && !ConchDestinations.between(before, parsed.doc()).sameBoundary())
                || (before == null && beforeBroken.contains(conch));
        synchronized (ConchFileLocks.of(conch)) {
            try {
                rejectClash(conch);
            } catch (LocalConchException ex) {
                String reason = readReason(fileName, conch, ex.getMessage());
                if (registry.origin(conch) != Origin.OFFICIAL) {
                    rememberBroken(stem, fileName, reason, nextBroken);
                }
                return failed(fileName, conch, parsed.doc().version().toString(),
                        issue("CONFLICT", conch, reason));
            }
            if (widened) {
                onWidened.accept(conch);
                widenedGap.run();
            }
            try {
                registry.put(parsed.doc(), Origin.LOCAL, allowEmptyMcpActions());
            } catch (ConchRejectedException ex) {
                List<ValidationIssue> located = ImportIssueLines.locate(new String(raw, StandardCharsets.UTF_8), ex.issues());
                String reason = readReason(fileName, conch, RuleDisabled.explain(located));
                rememberBroken(stem, fileName, reason, true, nextBroken);
                return new FileResult(
                        fileName,
                        shownConch(stem, conch),
                        parsed.doc().version().toString(),
                        false,
                        failureIssues("INVALID_INPUT", fileName, reason, located));
            }
            if (widened) {
                onWidened.accept(conch);
            }
        }
        nextBroken.remove(conch);
        loaded.put(conch, parsed.doc());
        List<ValidationIssue> warnings = parsed.warnings();
        return new FileResult(fileName, conch, parsed.doc().version().toString(), true, warnings);
    }

    private FileResult reject(
            String fileName,
            String stem,
            String reportedConch,
            String version,
            String code,
            String reason,
            List<ValidationIssue> details,
            Map<String, BrokenLocal> nextBroken
    ) {
        return reject(fileName, stem, reportedConch, version, code, reason, details, false, nextBroken);
    }

    private FileResult reject(
            String fileName,
            String stem,
            String reportedConch,
            String version,
            String code,
            String reason,
            List<ValidationIssue> details,
            boolean ruleDisabled,
            Map<String, BrokenLocal> nextBroken
    ) {
        rememberBroken(stem, fileName, reason, ruleDisabled, nextBroken);
        return new FileResult(fileName, shownConch(stem, reportedConch), version, false,
                failureIssues(code, fileName, reason, details));
    }

    private static boolean ruleDisabled(LocalConchException ex) {
        if (ex.errors().isEmpty()) {
            return false;
        }
        for (ValidationIssue issue : ex.errors()) {
            String code = issue.code();
            if (!"PARSE".equals(code) && !"CONCH_NAME".equals(code) && !"VERSION_INVALID".equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops a previously loaded local package with this stem and records it as broken.
     * An official package of the same name is left alone. An illegal stem is only reported on the file row.
     */
    private void rememberBroken(String stem, String file, String reason, Map<String, BrokenLocal> nextBroken) {
        rememberBroken(stem, file, reason, false, nextBroken);
    }

    private void rememberBroken(
            String stem,
            String file,
            String reason,
            boolean ruleDisabled,
            Map<String, BrokenLocal> nextBroken
    ) {
        String name = stemName(stem);
        if (name == null || registry.origin(name) == Origin.OFFICIAL) {
            return;
        }
        dropStem(name);
        nextBroken.put(name, new BrokenLocal(name, file, reason, ruleDisabled));
    }

    private boolean localPackageExists(String conch) {
        if (registry.origin(conch) == Origin.LOCAL || broken.containsKey(conch)) {
            return true;
        }
        return Files.exists(yamlPath(conch), LinkOption.NOFOLLOW_LINKS);
    }

    private List<String> dropMissing(java.util.Set<String> kept) {
        ArrayList<String> stale = new ArrayList<>();
        for (var entry : registry.snapshot().entrySet()) {
            if (registry.origin(entry.getKey()) == Origin.LOCAL && !kept.contains(entry.getKey())) {
                stale.add(entry.getKey());
            }
        }
        for (String conch : stale) {
            registry.remove(conch);
        }
        return stale;
    }

    private Map<String, ConchDoc> localDocs() {
        LinkedHashMap<String, ConchDoc> docs = new LinkedHashMap<>();
        for (Map.Entry<String, ConchDoc> entry : registry.snapshot().entrySet()) {
            if (registry.origin(entry.getKey()) == Origin.LOCAL) {
                docs.put(entry.getKey(), entry.getValue());
            }
        }
        return docs;
    }

    private Map<String, String> localVersions() {
        LinkedHashMap<String, String> versions = new LinkedHashMap<>();
        for (Map.Entry<String, ConchDoc> entry : registry.snapshot().entrySet()) {
            if (registry.origin(entry.getKey()) == Origin.LOCAL) {
                versions.put(entry.getKey(), entry.getValue().version().toString());
            }
        }
        return versions;
    }

    private void dropStem(String stem) {
        if (stem != null && registry.origin(stem) == Origin.LOCAL) {
            registry.remove(stem);
        }
    }

    private ImportResult writeUnlocked(byte[] raw, String actor, boolean replace, ReplaceOptions options) {
        Parsed parsed = parse(raw);
        rejectClash(parsed.doc().conch());
        boolean exists = localPackageExists(parsed.doc().conch());
        if (exists && !replace) {
            throw LocalConchException.replaceRequired(parsed.doc().conch());
        }
        WriteOutcome outcome = store(parsed.doc(), raw, options);
        broken.remove(parsed.doc().conch());
        AuditKind kind = options.auditKind();
        if (kind == null) {
            kind = outcome.replaced() ? AuditKind.LOCAL_CONCH_REPLACED : AuditKind.LOCAL_CONCH_IMPORTED;
        }
        if (kind == AuditKind.LOCAL_CONCH_IMPORTED) {
            audit(actor, kind, parsed.doc());
        } else {
            auditDetail(actor, kind, detail(
                    parsed.doc().conch(),
                    parsed.doc().version().toString(),
                    outcome.previousVersion(),
                    outcome.sha256(),
                    outcome.previousSha256(),
                    outcome.boundary(),
                    outcome.backupId(),
                    options.keyName(),
                    options.autoApproved(),
                    options.grantedBy()));
        }
        return new ImportResult(
                parsed.doc().conch(),
                parsed.doc().version().toString(),
                outcome.replaced(),
                parsed.warnings(),
                outcome.replaced() ? outcome.previousVersion() : null,
                outcome.replaced() ? outcome.backupId() : null);
    }

    private WriteOutcome store(ConchDoc doc, byte[] raw, ReplaceOptions options) {
        synchronized (ConchFileLocks.of(doc.conch())) {
            rejectClash(doc.conch());
            Path file = yamlPath(doc.conch());
            if (Files.isSymbolicLink(file)) {
                throw LocalConchException.invalid("local conch path is a symlink");
            }
            boolean replaced = localPackageExists(doc.conch());
            byte[] previous = null;
            boolean existed = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
            if (existed) {
                try {
                    if (Files.size(file) > MAX_BYTES) {
                        throw LocalConchException.invalid("上一版超过 1 MiB，没有覆盖。");
                    }
                    previous = Files.readAllBytes(file);
                } catch (LocalConchException ex) {
                    throw ex;
                } catch (IOException ex) {
                    throw new IllegalStateException("local conch file was not read");
                }
            }
            String currentSha = previous == null ? "" : sha256(previous);
            if (options.expectedBaseSha() != null && !options.expectedBaseSha().equals(currentSha)) {
                throw LocalConchException.stale();
            }
            ConchDoc before = baseline(doc.conch(), previous);
            ConchDestinations.Boundary boundary = ConchDestinations.between(before, doc);
            String backupId = null;
            if (previous != null && !Arrays.equals(previous, raw)) {
                String version = before != null && ConchFiles.safeSegment(before.version().toString())
                        ? before.version().toString()
                        : "unknown";
                backupId = backup(doc.conch(), version, previous, raw);
            }
            boolean widened = replaced && !boundary.sameBoundary();
            boolean clearSessions = replaced && (widened || boundToIdentity.test(doc.conch()));
            boolean authSpecChanged = replaced && !clearSessions && before != null
                    && !Objects.equals(before.auth(), doc.auth());
            if (clearSessions) {
                onWidened.accept(doc.conch());
                widenedGap.run();
            }
            try {
                atomicWrite(file, raw);
                registry.put(doc, Origin.LOCAL, allowEmptyMcpActions());
            } catch (ConchRejectedException ex) {
                restore(file, previous, existed);
                throw LocalConchException.invalid("规则包校验失败", ex.issues(), List.of(), doc.conch());
            } catch (RuntimeException ex) {
                restore(file, previous, existed);
                throw ex;
            }
            if (clearSessions) {
                onWidened.accept(doc.conch());
            } else if (authSpecChanged) {
                onAuthSpecChanged.accept(doc.conch());
            }
            if (dropUpstreamSecrets(before, doc, existed)) {
                deleteUpstreamSecrets(doc.conch());
            }
            pending.remove(doc.conch());
            dropHistoryEqualTo(doc.conch(), raw);
            return new WriteOutcome(
                    replaced,
                    before == null ? null : before.version().toString(),
                    currentSha,
                    sha256(raw),
                    backupId,
                    boundary);
        }
    }

    /** 注册表里已载入的文档优先；否则解析覆盖前的文件。解析失败返回 null。 */
    private ConchDoc baseline(String conch, byte[] previous) {
        if (registry.origin(conch) == Origin.LOCAL) {
            ConchDoc loaded = registry.find(conch).orElse(null);
            if (loaded != null) {
                return loaded;
            }
        }
        if (previous == null || previous.length == 0) {
            return null;
        }
        try {
            ConchDoc parsed = ConchParser.parse(new String(previous, StandardCharsets.UTF_8));
            return conch.equals(parsed.conch()) ? parsed : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 上游 URL 的 scheme、host、port，认证类型，或已声明的令牌端点主机变了就清令牌。
     * 读不出旧文档时也清，避免旧 Bearer 打到对不上的上游。
     */
    private static boolean dropUpstreamSecrets(ConchDoc before, ConchDoc after, boolean fileExisted) {
        if (before == null) {
            return fileExisted;
        }
        return !identity(before).equals(identity(after));
    }

    private void deleteUpstreamSecrets(String conch) {
        // deleteFields 只按字段名删行，不读写明文。构造凭证库仍要一把密钥，这把不会用来加密。
        CredentialVault vault = new CredentialVault(dataSource, new CredentialCipher(new byte[32]));
        vault.deleteFields(
                Origin.LOCAL,
                conch,
                Set.of(McpOAuthToken.FIELD, McpOAuthToken.CLIENT_SECRET));
    }

    private static UpstreamIdentity identity(ConchDoc doc) {
        if (doc == null || doc.upstream() == null) {
            return new UpstreamIdentity(null, null, -1, null, null);
        }
        McpUpstream upstream = doc.upstream();
        String scheme = null;
        String host = null;
        int port = -1;
        if (upstream.url() != null && !upstream.url().isBlank()) {
            McpAddresses.ParseResult parsed = McpAddresses.parse(upstream.url());
            if (parsed.ok()) {
                scheme = parsed.url().scheme();
                host = parsed.url().host();
                port = parsed.url().port();
            } else {
                scheme = "";
                host = upstream.url();
                port = -1;
            }
        }
        String authType = upstream.auth() == null ? null : upstream.auth().type();
        return new UpstreamIdentity(scheme, host, port, authType, tokenHost(upstream.auth()));
    }

    private static String tokenHost(McpAuth auth) {
        if (auth == null || auth.tokenEndpoint() == null || auth.tokenEndpoint().isBlank()) {
            return null;
        }
        McpAddresses.ParseResult parsed = McpAddresses.parse(auth.tokenEndpoint());
        return parsed.ok() ? parsed.url().host() : auth.tokenEndpoint();
    }

    private static String hostText(String host) {
        return host == null || host.isBlank() ? "（无）" : host;
    }

    private void rejectClash(String conch) {
        // 本地包可以和正式包同名：两份都留在本机，同一时刻只有一份生效。
    }

    private Parsed parse(byte[] raw) {
        String yaml = utf8(raw);
        ConchDoc doc;
        try {
            doc = ConchParser.parse(yaml);
        } catch (ConchParseException ex) {
            throw LocalConchException.invalid(
                    "规则包校验失败",
                    ImportIssueLines.locate(yaml, List.of(issue("PARSE", ex.path(), clip(ex.getMessage())))),
                    List.of());
        }
        if (!ConchValidator.isConchName(doc.conch()) || !ConchFiles.safeSegment(doc.conch())) {
            throw LocalConchException.invalid(
                    "规则包校验失败",
                    ImportIssueLines.locate(yaml, List.of(issue(
                            "CONCH_NAME", "conch", "conch 不符合 ^[a-z][a-z0-9_]{1,63}$"))),
                    List.of());
        }
        if (!ConchFiles.safeSegment(doc.version().toString())) {
            throw LocalConchException.invalid(
                    "规则包校验失败",
                    ImportIssueLines.locate(yaml, List.of(issue(
                            "VERSION_INVALID", "version", "version is not a safe path segment"))),
                    List.of(),
                    doc.conch());
        }
        boolean allowEmpty = allowEmptyMcpActions();
        ValidationResult result = ConchValidator.validate(doc, validationContext(allowEmpty));
        ArrayList<ValidationIssue> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(LiteralCredentialWarnings.scan(doc));
        java.util.Set<String> errorPaths = new java.util.HashSet<>();
        for (ValidationIssue error : result.errors()) {
            if (error.path() != null) {
                errorPaths.add(error.path());
            }
        }
        warnings.removeIf(issue -> LiteralCredentialWarnings.CODE.equals(issue.code())
                && issue.path() != null
                && errorPaths.contains(issue.path()));
        List<ValidationIssue> locatedWarnings = ImportIssueLines.locate(yaml, warnings);
        List<ValidationIssue> structural = registry.inspect(doc, allowEmpty);
        // 解析时同名能力、脚本库或分组只留下一个，要回到 YAML 节点上找。Reef 上传用同一条规则，本地不能先放过。
        ArrayList<ValidationIssue> duplicates = new ArrayList<>();
        duplicates.addAll(DuplicateActions.find(yaml));
        duplicates.addAll(DuplicateScripts.find(yaml));
        duplicates.addAll(DuplicateGroups.find(yaml));
        duplicates.addAll(DuplicateExports.find(yaml));
        if (!structural.isEmpty() || !duplicates.isEmpty()) {
            ArrayList<ValidationIssue> errors = new ArrayList<>(duplicates);
            errors.addAll(ImportIssueLines.locate(yaml, structural));
            throw LocalConchException.invalid(
                    "规则包校验失败",
                    errors,
                    locatedWarnings,
                    doc.conch());
        }
        return new Parsed(doc, locatedWarnings);
    }

    private boolean allowEmptyMcpActions() {
        return debugOn();
    }

    /** 开发者模式读取失败时当作关着，避免误放自动确认。不在这里改授权表。 */
    private boolean debugOn() {
        try {
            return debugEnabled.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static ConchValidator.Context validationContext(boolean allowEmptyMcpActions) {
        if (allowEmptyMcpActions) {
            return ConchValidator.Context.allowingEmptyMcpActions(CrabVersion.CURRENT, STEP_TYPES);
        }
        return ConchValidator.Context.of(CrabVersion.CURRENT, STEP_TYPES);
    }

    private Path yamlPath(String conch) {
        if (!ConchValidator.isConchName(conch) || !ConchFiles.safeSegment(conch)) {
            throw LocalConchException.invalid("conch name is not safe");
        }
        Path root = paths.localConches().toAbsolutePath().normalize();
        Path file = root.resolve(conch + SUFFIX).normalize();
        if (!file.startsWith(root) || !root.equals(file.getParent())) {
            throw LocalConchException.invalid("conch path escapes local-conches");
        }
        return file;
    }

    private static void requireRaw(byte[] raw) {
        if (raw == null) {
            throw LocalConchException.invalid("yaml is required");
        }
        if (raw.length > MAX_BYTES) {
            throw LocalConchException.invalid("yaml exceeds 1 MiB");
        }
    }

    private PendingOverwrite fresh(String conch) {
        PendingOverwrite row = pending.get(conch);
        if (row == null) {
            return null;
        }
        if (!row.submittedAt().plus(PENDING_TTL).isAfter(clock.instant())) {
            pending.remove(conch);
            return null;
        }
        return row;
    }

    private byte[] readIfRegular(String conch) {
        Path file = yamlPath(conch);
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (Files.size(file) > MAX_BYTES) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalStateException("local conch file was not read");
        }
    }

    private String backup(String conch, String version, byte[] previous, byte[] incoming) {
        if (!ConchFiles.safeSegment(version)) {
            version = "unknown";
        }
        Path dir = historyDir(conch);
        Path historyRoot = dir.getParent();
        if (historyRoot != null && Files.isSymbolicLink(historyRoot) || Files.isSymbolicLink(dir)) {
            throw LocalConchException.invalid("local conch history path is a symlink");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new IllegalStateException("local conch history was not written");
        }
        String stamp = HISTORY_STAMP.format(clock.instant());
        String stem = stamp + "-" + version;
        Path target = dir.resolve(stem + SUFFIX).normalize();
        int suffix = 2;
        while (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            target = dir.resolve(stem + "-" + suffix + SUFFIX).normalize();
            suffix++;
            if (suffix > 100) {
                throw new IllegalStateException("local conch history was not written");
            }
        }
        if (!target.startsWith(dir)) {
            throw LocalConchException.invalid("history path escapes local-conches");
        }
        atomicWrite(target, previous);
        pruneHistory(dir, incoming);
        String name = target.getFileName().toString();
        return name.substring(0, name.length() - SUFFIX.length());
    }

    /** 同一把锁、同一次写入里丢掉最旧的备份，只留 {@link #HISTORY_KEEP} 份。当前 yaml 不在这个目录里。 */
    /** 读不出来的备份当作不相同，照常参加修剪，不挡住这次写入。 */
    private static boolean sameBytes(Path file, byte[] expected) {
        try {
            return Files.size(file) == expected.length && Arrays.equals(Files.readAllBytes(file), expected);
        } catch (IOException ex) {
            return false;
        }
    }

    private static void pruneHistory(Path dir) {
        pruneHistory(dir, null);
    }

    /**
     * 和 {@code incoming} 内容相同的备份不占名额也不在这里删：回退成功后它由
     * {@link #dropHistoryEqualTo} 去掉，其余两份都留得住；写入失败时它还在。
     */
    private static void pruneHistory(Path dir, byte[] incoming) {
        try (var children = Files.list(dir)) {
            ArrayList<Path> files = new ArrayList<>();
            for (Path child : children.toList()) {
                if (Files.isSymbolicLink(child)) {
                    Files.deleteIfExists(child);
                    continue;
                }
                String name = child.getFileName().toString();
                if (name.endsWith(SUFFIX) && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (incoming != null && sameBytes(child, incoming)) {
                        continue;
                    }
                    files.add(child);
                }
            }
            files.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
            for (int i = HISTORY_KEEP; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("local conch history was not pruned");
        }
    }

    /** 回退之后历史里那一份和当前内容相同，不再占名额。删不掉只记日志，写入已经完成。 */
    private void dropHistoryEqualTo(String conch, byte[] current) {
        try {
            Path dir = historyDir(conch);
            Path historyRoot = dir.getParent();
            if (historyRoot == null
                    || Files.isSymbolicLink(historyRoot)
                    || Files.isSymbolicLink(dir)
                    || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try (var children = Files.list(dir)) {
                for (Path child : children.toList()) {
                    if (Files.isSymbolicLink(child)
                            || !child.getFileName().toString().endsWith(SUFFIX)
                            || !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (sameBytes(child, current)) {
                        Files.deleteIfExists(child);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOG.warning("local conch history was not deduplicated: " + ex.getClass().getName());
        }
    }

    /** 启动和 reload 时把每个包的历史修剪到 {@link #HISTORY_KEEP} 份。失败只记日志，不挡加载。 */
    private void pruneAllHistory() {
        Path history = paths.localConches().toAbsolutePath().normalize().resolve(".history");
        if (Files.isSymbolicLink(history) || !Files.isDirectory(history, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var children = Files.list(history)) {
            for (Path dir : children.toList()) {
                if (!Files.isSymbolicLink(dir) && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                    dropHistoryEqualToCurrent(history.getParent(), dir.getFileName().toString());
                    pruneHistory(dir);
                }
            }
        } catch (IOException | RuntimeException ex) {
            LOG.warning("local conch history was not pruned: " + ex.getClass().getName());
        }
    }

    /** 旧版本留下的、和当前内容相同的备份先去掉，不让它挤掉一份真正能回退的版本。 */
    private void dropHistoryEqualToCurrent(Path root, String conch) {
        if (!ConchValidator.isConchName(conch) || !ConchFiles.safeSegment(conch)) {
            return;
        }
        Path current = root.resolve(conch + SUFFIX);
        if (Files.isSymbolicLink(current) || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.size(current) > MAX_BYTES) {
                return;
            }
            dropHistoryEqualTo(conch, Files.readAllBytes(current));
        } catch (IOException ex) {
            LOG.warning("local conch history was not deduplicated: " + ex.getClass().getName());
        }
    }

    private Path historyDir(String conch) {
        if (!ConchValidator.isConchName(conch) || !ConchFiles.safeSegment(conch)) {
            throw LocalConchException.invalid("conch name is not safe");
        }
        Path root = paths.localConches().toAbsolutePath().normalize();
        Path history = root.resolve(".history").normalize();
        Path dir = history.resolve(conch).normalize();
        if (!dir.startsWith(history) || !history.equals(dir.getParent())) {
            throw LocalConchException.invalid("conch path escapes local-conches");
        }
        return dir;
    }

    private byte[] readHistory(String conch, String id) {
        if (id == null || id.length() > 180 || id.contains("..") || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0
                || !id.matches("[0-9A-Za-z._-]+") || !ConchFiles.safeSegment(id)) {
            throw LocalConchException.invalid("历史编号无效。");
        }
        Path dir = historyDir(conch);
        Path file = dir.resolve(id + SUFFIX).normalize();
        if (!file.startsWith(dir) || !dir.equals(file.getParent())) {
            throw LocalConchException.invalid("历史编号无效。");
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw LocalConchException.historyMissing();
        }
        try {
            if (Files.size(file) > MAX_BYTES) {
                throw LocalConchException.invalid("yaml exceeds 1 MiB");
            }
            return Files.readAllBytes(file);
        } catch (LocalConchException ex) {
            throw ex;
        } catch (IOException ex) {
            throw LocalConchException.historyMissing();
        }
    }

    private static String historyVersion(Path file) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                return "unknown";
            }
            ConchDoc doc = ConchParser.parse(Files.readString(file));
            return doc.version() == null ? "unknown" : doc.version().toString();
        } catch (IOException | RuntimeException ex) {
            return "unknown";
        }
    }

    private static Instant historySavedAt(String id) {
        if (id.length() < 17) {
            return null;
        }
        String stamp = id.substring(0, 17);
        for (int i = 0; i < stamp.length(); i++) {
            if (!Character.isDigit(stamp.charAt(i))) {
                return null;
            }
        }
        try {
            return HISTORY_STAMP.parse(stamp, Instant::from);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, Object> detail(
            String conch,
            String version,
            String previousVersion,
            String sha256,
            String previousSha256,
            ConchDestinations.Boundary boundary,
            String backupId,
            String keyName
    ) {
        return detail(
                conch, version, previousVersion, sha256, previousSha256, boundary, backupId, keyName, false, null);
    }

    private static Map<String, Object> detail(
            String conch,
            String version,
            String previousVersion,
            String sha256,
            String previousSha256,
            ConchDestinations.Boundary boundary,
            String backupId,
            String keyName,
            boolean autoApproved,
            String grantedBy
    ) {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("conch", conch);
        body.put("version", version);
        body.put("previousVersion", previousVersion);
        body.put("sha256", sha256);
        body.put("previousSha256", previousSha256 == null ? "" : previousSha256);
        body.put("boundary", boundary != null && boundary.sameBoundary() ? "same" : "widened");
        body.put("addedDestinations", boundary == null ? List.of() : boundary.addedDestinations());
        body.put("backupId", backupId);
        if (keyName != null && !keyName.isBlank()) {
            body.put("key", keyName);
        }
        if (autoApproved) {
            body.put("autoApproved", true);
            body.put("grantedBy", grantedBy == null ? "" : grantedBy);
        }
        return body;
    }

    private void auditDetail(String actor, AuditKind kind, Map<String, Object> body) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor) && !Actors.MCP.equals(actor)) {
            return;
        }
        AuditLog.record(
                dataSource, clock.instant(), actor, kind, Origin.LOCAL.name() + "/" + body.get("conch"), ApiJson.write(body));
    }

    private void audit(String actor, AuditKind kind, ConchDoc doc) {
        audit(actor, kind, doc.conch(), doc.version().toString());
    }

    private void audit(String actor, AuditKind kind, String conch, String version) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor) && !Actors.MCP.equals(actor)) {
            return;
        }
        AuditLog.record(
                dataSource,
                clock.instant(),
                actor,
                kind,
                Origin.LOCAL.name() + "/" + conch,
                "{\"conch\":\"" + conch + "\",\"version\":\"" + version + "\"}");
    }

    private static String actor(String actor) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor) && !Actors.MCP.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli, console, or mcp");
        }
        return actor;
    }

    private static void atomicWrite(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString() + "-", ".tmp");
            try {
                Files.write(tmp, bytes);
                moveWithWindowsLockRetry(tmp, target);
            } catch (IOException ex) {
                Files.deleteIfExists(tmp);
                throw ex;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("local conch file was not written");
        }
    }

    private static void moveWithWindowsLockRetry(Path tmp, Path target) throws IOException {
        long[] backoffMs = {50L, 100L};
        for (int attempt = 0; ; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.AccessDeniedException ex) {
                if (attempt >= backoffMs.length) {
                    throw ex;
                }
                try {
                    Thread.sleep(backoffMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private static void restore(Path file, byte[] previous, boolean existed) {
        try {
            if (previous != null) {
                atomicWrite(file, previous);
            } else if (!existed) {
                Files.deleteIfExists(file);
            }
        } catch (IOException | RuntimeException ex) {
            LOG.warning("local conch file was not restored: " + ex.getClass().getName());
        }
    }

    private static String utf8(byte[] raw) {
        if (raw.length == 0) {
            throw LocalConchException.invalid("yaml is required");
        }
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException ex) {
            throw LocalConchException.invalid("yaml is not valid UTF-8");
        }
    }

    private static FileResult failed(String file, String conch, String version, ValidationIssue issue) {
        return new FileResult(file, conch, version, false, List.of(issue));
    }

    private static ValidationIssue issue(String code, String path, String message) {
        return new ValidationIssue(code, path == null ? "" : path, clip(message));
    }

    private static String stemOf(String fileName) {
        return fileName.substring(0, fileName.length() - SUFFIX.length());
    }

    private static String stemName(String stem) {
        return ConchValidator.isConchName(stem) && ConchFiles.safeSegment(stem) ? stem : null;
    }

    /** The deletable package is the file stem. An illegal stem is not a package row; the reason still names the YAML. */
    private static String shownConch(String stem, String reported) {
        String slot = stemName(stem);
        if (slot != null) {
            return slot;
        }
        return reported != null && stemName(reported) != null && reported.equals(stem) ? reported : null;
    }

    private static String loadFailureReason(String fileName, String slot, LocalConchException ex) {
        String inner = ex.parsedConch();
        boolean parse = ex.errors().stream().anyMatch(issue -> "PARSE".equals(issue.code()));
        if (parse || ex.errors().isEmpty()) {
            String detail = ex.errors().isEmpty() ? ex.getMessage() : ex.errors().get(0).message();
            String name = slot != null ? slot : inner;
            if ("yaml is not valid UTF-8".equals(detail)) {
                return readReason(fileName, name, "不是合法的 UTF-8");
            }
            if ("yaml is required".equals(detail)) {
                return readReason(fileName, name, "是空的");
            }
            if (name == null) {
                return sentence("文件 " + fileName + " 无法解析：" + detail);
            }
            return sentence("文件 " + fileName + " 的规则包 " + name + " 无法解析：" + detail);
        }
        if (inner != null && slot != null && !slot.equals(inner)) {
            return sentence("文件 " + fileName + " 里的规则包是 " + inner + "，与文件名不一致，而且未通过校验");
        }
        String name = inner != null ? inner : slot;
        if (name == null) {
            return sentence("文件 " + fileName + " 未通过校验");
        }
        return sentence("文件 " + fileName + " 的规则包 " + name + " 未通过校验");
    }

    private static String readReason(String fileName, String conch, String what) {
        if (conch == null) {
            return sentence("文件 " + fileName + " " + what);
        }
        return sentence("文件 " + fileName + " 的规则包 " + conch + " " + what);
    }

    private static String sentence(String text) {
        String cleaned = clip(text).trim();
        if (cleaned.isEmpty()) {
            return "";
        }
        char last = cleaned.charAt(cleaned.length() - 1);
        if (last == '。' || last == '.' || last == '！' || last == '?' || last == '？') {
            return cleaned;
        }
        return cleaned + "。";
    }

    private static List<ValidationIssue> failureIssues(
            String code,
            String fileName,
            String reason,
            List<ValidationIssue> details
    ) {
        ArrayList<ValidationIssue> issues = new ArrayList<>();
        issues.add(issue(code, fileName, reason));
        if (details != null) {
            for (ValidationIssue detail : details) {
                if ("PARSE".equals(detail.code()) || reason.equals(detail.message())) {
                    continue;
                }
                issues.add(detail);
            }
        }
        return List.copyOf(issues);
    }

    private static String codes(List<ValidationIssue> issues) {
        StringBuilder text = new StringBuilder();
        for (ValidationIssue issue : issues) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(issue.code());
        }
        return text.toString();
    }

    private static String clip(String message) {
        if (message == null) {
            return "";
        }
        String cleaned = message.replace('\r', ' ').replace('\n', ' ');
        return cleaned.length() <= 200 ? cleaned : cleaned.substring(0, 200);
    }

    private record Parsed(ConchDoc doc, List<ValidationIssue> warnings) {
    }

    /**
     * Validation outcome shared by import and {@code hermit_dev__validate}. {@code version} is null when
     * the YAML did not validate. Empty {@code errors} means the YAML itself is acceptable.
     */
    public record CheckResult(
            String conch,
            String version,
            List<ValidationIssue> errors,
            List<ValidationIssue> warnings
    ) {
        public CheckResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public record ImportResult(
            String conch,
            String version,
            boolean replaced,
            List<ValidationIssue> warnings,
            String previousVersion,
            String backupId
    ) {
        public ImportResult(String conch, String version, boolean replaced, List<ValidationIssue> warnings) {
            this(conch, version, replaced, warnings, null, null);
        }

        public ImportResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * 一次覆盖相对提交时磁盘的约束。{@code expectedBaseSha} 为 null 时不核对。
     * {@code auditKind} 为 null 时，新建记 IMPORTED，覆盖记 REPLACED。
     * {@code autoApproved} 为 true 时审计种类是 {@link AuditKind#LOCAL_CONCH_AUTO_APPROVED}，
     * 详情带 {@code grantedBy}。
     */
    public record ReplaceOptions(
            String expectedBaseSha,
            String keyName,
            AuditKind auditKind,
            boolean autoApproved,
            String grantedBy
    ) {
        public ReplaceOptions(String expectedBaseSha, String keyName, AuditKind auditKind) {
            this(expectedBaseSha, keyName, auditKind, false, null);
        }

        public static ReplaceOptions none() {
            return new ReplaceOptions(null, null, null);
        }

        public static ReplaceOptions autoApproved(String keyName, String grantedBy) {
            return new ReplaceOptions(null, keyName, AuditKind.LOCAL_CONCH_AUTO_APPROVED, true, grantedBy);
        }
    }

    /** 内存里的待确认覆盖。{@code yaml} 每次返回都是副本。 */
    public record PendingOverwrite(
            String conch,
            byte[] yaml,
            String sha256,
            String baseSha,
            String version,
            String previousVersion,
            List<String> addedDestinations,
            boolean undetermined,
            String submittedBy,
            Instant submittedAt
    ) {
        public PendingOverwrite {
            yaml = yaml == null ? new byte[0] : yaml.clone();
            addedDestinations = addedDestinations == null ? List.of() : List.copyOf(addedDestinations);
            Objects.requireNonNull(submittedAt, "submittedAt");
        }

        @Override
        public byte[] yaml() {
            return yaml.clone();
        }
    }

    /** {@code id} 是 {@code .history} 里的文件名去掉 {@code .yaml}。 */
    public record HistoryEntry(String id, String version, Instant savedAt) {
    }

    /**
     * 控制台在本次开发者模式里授权的自动确认。{@code createdBy} 是记下授权的 actor，目前只有 {@code console}。
     */
    public record OverrideGrant(String conch, Instant since, String createdBy) {
        public OverrideGrant {
            Objects.requireNonNull(conch, "conch");
            Objects.requireNonNull(since, "since");
            Objects.requireNonNull(createdBy, "createdBy");
        }
    }

    public record FileResult(String file, String conch, String version, boolean ok, List<ValidationIssue> issues) {
        public FileResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    /**
     * One local package. {@code broken} packages are not in the registry and cannot be called.
     * {@code version} and {@code reason} are null when they do not apply.
     */
    public record LocalPackage(
            String conch,
            String file,
            String version,
            boolean loaded,
            boolean broken,
            String reason,
            boolean ruleDisabled
    ) {
    }

    private record BrokenLocal(String conch, String file, String reason, boolean ruleDisabled) {
    }

    private record WriteOutcome(
            boolean replaced,
            String previousVersion,
            String previousSha256,
            String sha256,
            String backupId,
            ConchDestinations.Boundary boundary
    ) {
    }

    /** 上游身份。端口 -1 表示没有可比较的 URL。令牌端点只比主机。 */
    private record UpstreamIdentity(String scheme, String host, int port, String authType, String tokenHost) {
    }

    public record Export(String conch, String version, byte[] bytes) {
        public Export {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
