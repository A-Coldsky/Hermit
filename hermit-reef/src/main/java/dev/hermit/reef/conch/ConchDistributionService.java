package dev.hermit.reef.conch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.model.CredentialField;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConchDistributionService {

    private static final Logger log = LoggerFactory.getLogger(ConchDistributionService.class);

    private final ConchRepository repository;
    private final ConchAvailabilityRepository availability;
    private final ConchStorage storage;
    private final SigningKeyStore keys;
    private final PackageVerifier verifier;
    private final ObjectMapper objectMapper;
    /**
     * 已验签包的清单条目，键是 zip 字节的 SHA-256。每次轮询仍然读文件、算摘要，
     * 磁盘上的包被改动后摘要对不上，会重新验签并失败；签名公钥在进程内不变。
     */
    private final ConcurrentHashMap<String, ManifestEntry> verifiedEntries = new ConcurrentHashMap<>();

    public ConchDistributionService(ConchRepository repository, ConchAvailabilityRepository availability,
            ConchStorage storage, SigningKeyStore keys, PackageVerifier verifier, ObjectMapper objectMapper) {
        this.repository = repository;
        this.availability = availability;
        this.storage = storage;
        this.keys = keys;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    public ManifestNegotiation manifest(String ifNoneMatch) {
        List<ManifestEntry> entries = new ArrayList<>();
        HashMap<String, ManifestEntry> seen = new HashMap<>();
        for (VersionRow row : currentRows()) {
            byte[] zip = storage.readZip(row.conch(), row.version());
            String digest = ConchHashes.sha256(zip);
            ManifestEntry entry = verifiedEntries.get(digest);
            if (entry == null) {
                entry = entry(verified(zip));
            }
            seen.put(digest, entry);
            entries.add(entry);
        }
        // 只留当前清单用到的条目：换版本、停用后旧条目随之丢弃。
        verifiedEntries.keySet().retainAll(seen.keySet());
        verifiedEntries.putAll(seen);
        entries.sort(Comparator.comparing(ManifestEntry::conch));
        byte[] canonical;
        try {
            canonical = objectMapper.writeValueAsBytes(entries);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        // ETag 只覆盖清单数组，含 driver 与 upstreamHost。信封里的 traceId 每次都会变，不能拿去算标签。
        String etag = "\"" + ConchHashes.sha256(canonical) + "\"";
        if (noneMatch(ifNoneMatch, etag)) {
            return new ManifestNegotiation(true, List.of(), etag);
        }
        return new ManifestNegotiation(false, entries, etag);
    }

    public byte[] publishedZip(String conch, String version) {
        if (conch != null && availability.isDisabled(conch)) {
            throw notFound();
        }
        VersionRow row = publishedRow(conch, version);
        byte[] zip = storage.readZip(row.conch(), row.version());
        try {
            verifier.verify(zip, keys.publicKey());
        } catch (PackageVerificationException ex) {
            log.error("Published conch failed verification reason={}", ex.reason());
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        if (repository.incrementPullCount(row.conch(), row.version()) != 1) {
            throw notFound();
        }
        return zip;
    }

    static boolean noneMatch(String header, String etag) {
        if (header == null || header.isBlank()) {
            return false;
        }
        for (String raw : header.split(",")) {
            String token = raw.trim();
            if ("*".equals(token) || etag.equals(token) || ("W/" + etag).equals(token)) {
                return true;
            }
        }
        return false;
    }

    static List<String> requiredCredentialNames(ConchDoc doc) {
        if (doc == null || doc.credentials() == null || doc.credentials().isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (var entry : doc.credentials().entrySet()) {
            CredentialField field = entry.getValue();
            if (entry.getKey() != null && field != null && field.required()) {
                names.add(entry.getKey());
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private List<VersionRow> currentRows() {
        List<VersionRow> currents = repository.listCurrent();
        long conches = currents.stream().map(VersionRow::conch).distinct().count();
        if (conches != currents.size()) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        Set<String> disabled = availability.disabledNames();
        if (disabled.isEmpty()) {
            return currents;
        }
        return currents.stream().filter(row -> !disabled.contains(row.conch())).toList();
    }

    private VersionRow publishedRow(String conch, String version) {
        if (conch == null || version == null || !ConchPaths.singleSegment(conch) || !ConchPaths.singleSegment(version)) {
            throw notFound();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw notFound();
        }
        return repository.find(conch, parsed.toString())
                .filter(row -> "PUBLISHED".equals(row.status()))
                .orElseThrow(ConchDistributionService::notFound);
    }

    private VerifiedConch verified(byte[] zip) {
        try {
            return verifier.verify(zip, keys.publicKey());
        } catch (PackageVerificationException ex) {
            log.error("Published conch failed verification reason={}", ex.reason());
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static ManifestEntry entry(VerifiedConch verified) {
        McpPackageView.Projection mcp = McpPackageView.of(verified.conchDoc());
        return new ManifestEntry(
                verified.manifest().conch(),
                verified.manifest().version(),
                verified.manifest().sha256(),
                verified.manifest().minCrabVersion(),
                verified.conchDoc().title(),
                verified.conchDoc().description(),
                verified.conchDoc().actions().size(),
                requiredCredentialNames(verified.conchDoc()),
                mcp.driver(),
                mcp.upstreamHost(),
                AuthDependencies.authUse(verified.conchDoc()));
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }

    public record ManifestNegotiation(boolean notModified, List<ManifestEntry> entries, String etag) {
    }
}
