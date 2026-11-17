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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

import dev.hermit.common.mcp.McpAddresses;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.McpAuth;
import dev.hermit.common.model.McpUpstream;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.DuplicateActions;
import dev.hermit.common.validate.DuplicateScripts;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.engine.CrabVersion;
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

    private static final Logger LOG = Logger.getLogger(LocalConches.class.getName());
    private static final String SUFFIX = ".yaml";

    private static final Set<String> STEP_TYPES = Set.of("request", "extract", "assert", "set", "script");

    private final CrabPaths paths;
    private final ConchRegistry registry;
    private final DataSource dataSource;
    private final BooleanSupplier debugEnabled;
    private final Object lock = new Object();
    /** Legal stems whose yaml failed to load. Rebuilt on startup and reload. Not callable. */
    private final LinkedHashMap<String, BrokenLocal> broken = new LinkedHashMap<>();

    public LocalConches(CrabPaths paths, ConchRegistry registry, DataSource dataSource) {
        this(paths, registry, dataSource, () -> false);
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
        this.paths = paths;
        this.registry = registry;
        this.dataSource = dataSource;
        this.debugEnabled = debugEnabled == null ? () -> false : debugEnabled;
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
        String checked = actor(actor);
        if (raw == null) {
            throw LocalConchException.invalid("yaml is required");
        }
        if (raw.length > MAX_BYTES) {
            throw LocalConchException.invalid("yaml exceeds 1 MiB");
        }
        Parsed parsed = parse(raw);
        synchronized (lock) {
            rejectClash(parsed.doc().conch());
            boolean exists = localPackageExists(parsed.doc().conch());
            if (exists && !replace) {
                throw LocalConchException.replaceRequired(parsed.doc().conch());
            }
            store(parsed.doc(), raw);
            broken.remove(parsed.doc().conch());
            audit(checked, AuditKind.LOCAL_CONCH_IMPORTED, parsed.doc());
            return new ImportResult(
                    parsed.doc().conch(),
                    parsed.doc().version().toString(),
                    exists,
                    parsed.warnings());
        }
    }

    public List<FileResult> reload(String actor) {
        String checked = actor(actor);
        synchronized (lock) {
            return scan(checked, true);
        }
    }

    /** Startup load. A bad file is logged and skipped. Nothing is written to the audit log. */
    public void loadStartup() {
        synchronized (lock) {
            try {
                scan(null, false);
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
     * 覆盖确认文案。上游地址的主机和已声明的令牌端点主机都没变时，沿用短句。
     * 主机会变时写明旧主机、新主机，并说明确认后会清掉已保存的上游令牌。
     */
    public static String overwriteConfirm(String conch, ConchDoc before, ConchDoc after) {
        String unchanged = "将覆盖已有的本地规则包 " + conch + "。确认后才会写入。";
        UpstreamIdentity left = identity(before);
        UpstreamIdentity right = identity(after);
        boolean urlHostChanged = !Objects.equals(left.host, right.host);
        boolean tokenHostChanged = !Objects.equals(left.tokenHost, right.tokenHost);
        if (!urlHostChanged && !tokenHostChanged) {
            return unchanged;
        }
        StringBuilder text = new StringBuilder("将覆盖已有的本地规则包 ").append(conch).append('。');
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

    private List<FileResult> scan(String actor, boolean auditChanges) {
        Path root = paths.localConches().toAbsolutePath().normalize();
        Map<String, String> beforeVersions = localVersions();
        ArrayList<FileResult> results = new ArrayList<>();
        LinkedHashMap<String, ConchDoc> loaded = new LinkedHashMap<>();
        LinkedHashMap<String, BrokenLocal> nextBroken = new LinkedHashMap<>();
        try (var children = Files.list(root)) {
            for (Path child : children.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                results.add(loadFile(root, child, loaded, nextBroken));
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
        dropMissing(loaded.keySet());
        broken.clear();
        broken.putAll(nextBroken);
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
        synchronized (ConchFileLocks.of(parsed.doc().conch())) {
            try {
                rejectClash(parsed.doc().conch());
            } catch (LocalConchException ex) {
                String reason = readReason(fileName, parsed.doc().conch(), ex.getMessage());
                if (registry.origin(parsed.doc().conch()) != Origin.OFFICIAL) {
                    rememberBroken(stem, fileName, reason, nextBroken);
                }
                return failed(fileName, parsed.doc().conch(), parsed.doc().version().toString(),
                        issue("CONFLICT", parsed.doc().conch(), reason));
            }
            try {
                registry.put(parsed.doc(), Origin.LOCAL, allowEmptyMcpActions());
            } catch (ConchRejectedException ex) {
                List<ValidationIssue> located = ImportIssueLines.locate(new String(raw, StandardCharsets.UTF_8), ex.issues());
                String reason = readReason(fileName, parsed.doc().conch(), RuleDisabled.explain(located));
                rememberBroken(stem, fileName, reason, true, nextBroken);
                return new FileResult(
                        fileName,
                        shownConch(stem, parsed.doc().conch()),
                        parsed.doc().version().toString(),
                        false,
                        failureIssues("INVALID_INPUT", fileName, reason, located));
            }
        }
        nextBroken.remove(parsed.doc().conch());
        loaded.put(parsed.doc().conch(), parsed.doc());
        List<ValidationIssue> warnings = parsed.warnings();
        return new FileResult(fileName, parsed.doc().conch(), parsed.doc().version().toString(), true, warnings);
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

    private void dropMissing(java.util.Set<String> kept) {
        ArrayList<String> stale = new ArrayList<>();
        for (var entry : registry.snapshot().entrySet()) {
            if (registry.origin(entry.getKey()) == Origin.LOCAL && !kept.contains(entry.getKey())) {
                stale.add(entry.getKey());
            }
        }
        for (String conch : stale) {
            registry.remove(conch);
        }
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

    private void store(ConchDoc doc, byte[] raw) {
        synchronized (ConchFileLocks.of(doc.conch())) {
            rejectClash(doc.conch());
            Path file = yamlPath(doc.conch());
            if (Files.isSymbolicLink(file)) {
                throw LocalConchException.invalid("local conch path is a symlink");
            }
            byte[] previous = null;
            boolean existed = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
            if (existed) {
                try {
                    previous = Files.readAllBytes(file);
                } catch (IOException ex) {
                    throw new IllegalStateException("local conch file was not read");
                }
            }
            ConchDoc before = baseline(doc.conch(), previous);
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
            if (dropUpstreamSecrets(before, doc, existed)) {
                deleteUpstreamSecrets(doc.conch());
            }
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
        if (registry.origin(conch) == Origin.OFFICIAL) {
            throw LocalConchException.conflict("与已安装的正式规则包重名");
        }
        if (new Subscriptions(dataSource).contains(conch)) {
            throw LocalConchException.conflict("与已订阅的规则包重名");
        }
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
        // 解析时同名能力或脚本库只留下一个，要回到 YAML 节点上找。Reef 上传用同一条规则，本地不能先放过。
        ArrayList<ValidationIssue> duplicates = new ArrayList<>();
        duplicates.addAll(DuplicateActions.find(yaml));
        duplicates.addAll(DuplicateScripts.find(yaml));
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

    private void audit(String actor, AuditKind kind, ConchDoc doc) {
        audit(actor, kind, doc.conch(), doc.version().toString());
    }

    private void audit(String actor, AuditKind kind, String conch, String version) {
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor) && !Actors.MCP.equals(actor)) {
            return;
        }
        AuditLog.record(
                dataSource,
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
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                Files.deleteIfExists(tmp);
                throw ex;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("local conch file was not written");
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
     * Validation outcome shared by import and {@code conch_validate}. {@code version} is null when
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

    public record ImportResult(String conch, String version, boolean replaced, List<ValidationIssue> warnings) {
        public ImportResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
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
