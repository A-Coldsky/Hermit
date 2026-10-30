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
import java.util.logging.Logger;

import javax.sql.DataSource;

import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParseException;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.validate.ValidationResult;
import dev.hermit.crab.engine.ConchRegistry;
import dev.hermit.crab.engine.ConchRejectedException;
import dev.hermit.crab.engine.CrabVersion;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.AuditKind;
import dev.hermit.crab.store.AuditLog;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.Origin;

/**
 * Unsigned conches stored as {@code local-conches/{conch}.yaml}. The bytes on disk are the
 * import, including a later export. One bad file does not stop the others.
 */
public final class LocalConches {
    /** Same ceiling as Reef {@code ConchPaths.MAX_UPLOAD_BYTES}. */
    public static final int MAX_BYTES = 1024 * 1024;

    private static final Logger LOG = Logger.getLogger(LocalConches.class.getName());
    private static final String SUFFIX = ".yaml";

    private final CrabPaths paths;
    private final ConchRegistry registry;
    private final DataSource dataSource;
    private final Object lock = new Object();

    public LocalConches(CrabPaths paths, ConchRegistry registry, DataSource dataSource) {
        this.paths = paths;
        this.registry = registry;
        this.dataSource = dataSource;
    }

    public ImportResult importBytes(byte[] raw, String actor) {
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
            boolean replaced = registry.origin(parsed.doc().conch()).orElse(null) == Origin.LOCAL
                    || Files.exists(yamlPath(parsed.doc().conch()), LinkOption.NOFOLLOW_LINKS);
            store(parsed.doc(), raw);
            audit(checked, AuditKind.LOCAL_CONCH_IMPORTED, parsed.doc());
            return new ImportResult(
                    parsed.doc().conch(),
                    parsed.doc().version().toString(),
                    replaced,
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
            if (registry.origin(conch).orElse(null) != Origin.LOCAL) {
                throw LocalConchException.notFound();
            }
            ConchDoc doc = registry.find(conch).orElse(null);
            if (doc == null) {
                throw LocalConchException.notFound();
            }
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
                registry.remove(conch);
                audit(checked, AuditKind.LOCAL_CONCH_DELETED, doc);
            }
        }
    }

    public Export export(String conch) {
        synchronized (lock) {
            if (registry.origin(conch).orElse(null) != Origin.LOCAL) {
                throw LocalConchException.notFound();
            }
            ConchDoc doc = registry.find(conch).orElse(null);
            if (doc == null || !ConchFiles.safeSegment(doc.version().toString())) {
                throw LocalConchException.notFound();
            }
            Path file = yamlPath(conch);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw LocalConchException.notFound();
            }
            try {
                if (Files.size(file) > MAX_BYTES) {
                    throw LocalConchException.invalid("yaml exceeds 1 MiB");
                }
                return new Export(doc.conch(), doc.version().toString(), Files.readAllBytes(file));
            } catch (LocalConchException ex) {
                throw ex;
            } catch (IOException ex) {
                throw LocalConchException.notFound();
            }
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
        try (var children = Files.list(root)) {
            for (Path child : children.filter(path -> path.getFileName().toString().endsWith(SUFFIX)).sorted().toList()) {
                results.add(loadFile(root, child, loaded));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("local conch directory was not listed");
        }
        for (FileResult result : results) {
            if (!result.ok()) {
                LOG.warning("local conch skipped file=" + result.file() + " codes=" + codes(result.issues()));
            }
        }
        dropMissing(loaded.keySet());
        if (auditChanges && actor != null) {
            Map<String, String> afterVersions = localVersions();
            for (Map.Entry<String, String> entry : afterVersions.entrySet()) {
                if (!entry.getValue().equals(beforeVersions.get(entry.getKey()))) {
                    audit(actor, AuditKind.LOCAL_CONCH_IMPORTED, entry.getKey(), entry.getValue());
                }
            }
            for (Map.Entry<String, String> entry : beforeVersions.entrySet()) {
                if (!afterVersions.containsKey(entry.getKey())) {
                    audit(actor, AuditKind.LOCAL_CONCH_DELETED, entry.getKey(), entry.getValue());
                }
            }
        }
        return List.copyOf(results);
    }

    private FileResult loadFile(Path root, Path child, Map<String, ConchDoc> loaded) {
        String fileName = child.getFileName().toString();
        Path file = child.toAbsolutePath().normalize();
        if (!file.startsWith(root) || !root.equals(file.getParent())) {
            return failed(fileName, null, null, issue("PATH", fileName, "path escapes local-conches"));
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return failed(fileName, null, null, issue("SYMLINK", fileName, "not a regular file"));
        }
        String stem = fileName.substring(0, fileName.length() - SUFFIX.length());
        byte[] raw;
        try {
            if (Files.size(file) > MAX_BYTES) {
                dropStem(stem);
                return failed(fileName, stemName(stem), null, issue("TOO_LARGE", fileName, "yaml exceeds 1 MiB"));
            }
            raw = Files.readAllBytes(file);
        } catch (IOException ex) {
            dropStem(stem);
            return failed(fileName, stemName(stem), null, issue("IO", fileName, "file was not read"));
        }
        Parsed parsed;
        try {
            parsed = parse(raw);
        } catch (LocalConchException ex) {
            dropStem(stem);
            String conch = ex.errors().isEmpty() ? stemName(stem) : null;
            return new FileResult(fileName, conch, null, false, ex.errors().isEmpty()
                    ? List.of(issue("INVALID_INPUT", fileName, ex.getMessage()))
                    : ex.errors());
        }
        if (!stem.equals(parsed.doc().conch())) {
            dropStem(stem);
            return failed(fileName, parsed.doc().conch(), parsed.doc().version().toString(),
                    issue("NAME", fileName, "file name does not match conch"));
        }
        synchronized (ConchFileLocks.of(parsed.doc().conch())) {
            try {
                rejectClash(parsed.doc().conch());
            } catch (LocalConchException ex) {
                return failed(fileName, parsed.doc().conch(), parsed.doc().version().toString(),
                        issue("CONFLICT", parsed.doc().conch(), ex.getMessage()));
            }
            try {
                registry.put(parsed.doc(), Origin.LOCAL);
            } catch (ConchRejectedException ex) {
                dropStem(stem);
                return new FileResult(fileName, parsed.doc().conch(), parsed.doc().version().toString(), false, ex.issues());
            }
        }
        loaded.put(parsed.doc().conch(), parsed.doc());
        List<ValidationIssue> warnings = parsed.warnings();
        return new FileResult(fileName, parsed.doc().conch(), parsed.doc().version().toString(), true, warnings);
    }

    private void dropMissing(java.util.Set<String> kept) {
        ArrayList<String> stale = new ArrayList<>();
        for (var entry : registry.snapshot().entrySet()) {
            if (registry.origin(entry.getKey()).orElse(null) == Origin.LOCAL && !kept.contains(entry.getKey())) {
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
            if (registry.origin(entry.getKey()).orElse(null) == Origin.LOCAL) {
                versions.put(entry.getKey(), entry.getValue().version().toString());
            }
        }
        return versions;
    }

    private void dropStem(String stem) {
        if (stem != null && registry.origin(stem).orElse(null) == Origin.LOCAL) {
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
            try {
                atomicWrite(file, raw);
                registry.put(doc, Origin.LOCAL);
            } catch (ConchRejectedException ex) {
                restore(file, previous, existed);
                throw LocalConchException.invalid("conch validation failed", ex.issues(), List.of());
            } catch (RuntimeException ex) {
                restore(file, previous, existed);
                throw ex;
            }
        }
    }

    private void rejectClash(String conch) {
        if (registry.origin(conch).orElse(null) == Origin.OFFICIAL) {
            throw LocalConchException.conflict("与已安装的正式规则包重名");
        }
        if (SubscriptionNames.contains(dataSource, conch)) {
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
                    "conch validation failed",
                    List.of(issue("PARSE", ex.path(), clip(ex.getMessage()))),
                    List.of());
        }
        if (!ConchValidator.isConchName(doc.conch()) || !ConchFiles.safeSegment(doc.conch())) {
            throw LocalConchException.invalid(
                    "conch validation failed",
                    List.of(issue("CONCH_NAME", "conch", "conch 不符合 ^[a-z][a-z0-9_]{1,63}$")),
                    List.of());
        }
        if (!ConchFiles.safeSegment(doc.version().toString())) {
            throw LocalConchException.invalid(
                    "conch validation failed",
                    List.of(issue("VERSION_INVALID", "version", "version is not a safe path segment")),
                    List.of());
        }
        ValidationResult result = ConchValidator.validate(
                doc, ConchValidator.Context.of(CrabVersion.CURRENT, java.util.Set.of(
                        "request", "extract", "assert", "set", "script")));
        List<ValidationIssue> structural = registry.inspect(doc);
        if (!structural.isEmpty()) {
            throw LocalConchException.invalid("conch validation failed", structural, result.warnings());
        }
        return new Parsed(doc, result.warnings());
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
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
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
        if (!Actors.CLI.equals(actor) && !Actors.CONSOLE.equals(actor)) {
            throw new IllegalArgumentException("actor must be cli or console");
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

    private static String stemName(String stem) {
        return ConchValidator.isConchName(stem) ? stem : null;
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
