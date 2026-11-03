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
import java.util.List;
import java.util.Set;
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
        for (VersionRow row : currentRows()) {
            VerifiedConch verified = verifiedPackage(row);
            entries.add(new ManifestEntry(
                    verified.manifest().conch(),
                    verified.manifest().version(),
                    verified.manifest().sha256(),
                    verified.manifest().minCrabVersion(),
                    verified.conchDoc().title(),
                    verified.conchDoc().description(),
                    verified.conchDoc().actions().size(),
                    requiredCredentialNames(verified.conchDoc())));
        }
        entries.sort(Comparator.comparing(ManifestEntry::conch));
        byte[] canonical;
        try {
            canonical = objectMapper.writeValueAsBytes(entries);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        // ETag 只覆盖清单数组。信封里的 traceId 每次都会变，不能拿去算标签。
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
        List<VersionRow> currents = repository.listAll().stream().filter(VersionRow::current).toList();
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
        return repository.listAll().stream()
                .filter(row -> row.conch().equals(conch) && row.version().equals(parsed.toString()))
                .filter(row -> "PUBLISHED".equals(row.status()))
                .findFirst()
                .orElseThrow(ConchDistributionService::notFound);
    }

    private VerifiedConch verifiedPackage(VersionRow row) {
        try {
            return verifier.verify(storage.readZip(row.conch(), row.version()), keys.publicKey());
        } catch (PackageVerificationException ex) {
            log.error("Published conch failed verification reason={}", ex.reason());
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }

    public record ManifestNegotiation(boolean notModified, List<ManifestEntry> entries, String etag) {
    }
}
