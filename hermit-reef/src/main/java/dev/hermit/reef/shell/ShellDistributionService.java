package dev.hermit.reef.shell;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.VerifiedShell;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ShellDistributionService {

    private static final Logger log = LoggerFactory.getLogger(ShellDistributionService.class);

    private final ShellRepository repository;
    private final ShellStorage storage;
    private final SigningKeyStore keys;
    private final PackageVerifier verifier;
    private final ObjectMapper objectMapper;

    public ShellDistributionService(ShellRepository repository, ShellStorage storage, SigningKeyStore keys,
            PackageVerifier verifier, ObjectMapper objectMapper) {
        this.repository = repository;
        this.storage = storage;
        this.keys = keys;
        this.verifier = verifier;
        this.objectMapper = objectMapper;
    }

    public ManifestNegotiation manifest(String ifNoneMatch) {
        List<ManifestEntry> entries = new ArrayList<>();
        for (VersionRow row : currentRows()) {
            VerifiedShell verified = verifiedPackage(row);
            entries.add(new ManifestEntry(
                    verified.manifest().shell(),
                    verified.manifest().version(),
                    verified.manifest().sha256(),
                    verified.manifest().minCrabVersion(),
                    verified.shellDoc().title()));
        }
        entries.sort(Comparator.comparing(ManifestEntry::shell));
        byte[] canonical;
        try {
            canonical = objectMapper.writeValueAsBytes(entries);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        // ETag 只覆盖清单数组。信封里的 traceId 每次都会变，不能拿去算标签。
        String etag = "\"" + ShellHashes.sha256(canonical) + "\"";
        if (noneMatch(ifNoneMatch, etag)) {
            return new ManifestNegotiation(true, List.of(), etag);
        }
        return new ManifestNegotiation(false, entries, etag);
    }

    public byte[] publishedZip(String shell, String version) {
        VersionRow row = publishedRow(shell, version);
        byte[] zip = storage.readZip(row.shell(), row.version());
        try {
            verifier.verify(zip, keys.publicKey());
        } catch (PackageVerificationException ex) {
            log.error("Published shell failed verification reason={}", ex.reason());
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
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

    private List<VersionRow> currentRows() {
        List<VersionRow> currents = repository.listAll().stream().filter(VersionRow::current).toList();
        long shells = currents.stream().map(VersionRow::shell).distinct().count();
        if (shells != currents.size()) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        return currents;
    }

    private VersionRow publishedRow(String shell, String version) {
        if (shell == null || version == null || !ShellPaths.singleSegment(shell) || !ShellPaths.singleSegment(version)) {
            throw notFound();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw notFound();
        }
        return repository.listAll().stream()
                .filter(row -> row.shell().equals(shell) && row.version().equals(parsed.toString()))
                .filter(row -> "PUBLISHED".equals(row.status()))
                .findFirst()
                .orElseThrow(ShellDistributionService::notFound);
    }

    private VerifiedShell verifiedPackage(VersionRow row) {
        try {
            return verifier.verify(storage.readZip(row.shell(), row.version()), keys.publicKey());
        } catch (PackageVerificationException ex) {
            log.error("Published shell failed verification reason={}", ex.reason());
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }

    public record ManifestNegotiation(boolean notModified, List<ManifestEntry> entries, String etag) {
    }
}
