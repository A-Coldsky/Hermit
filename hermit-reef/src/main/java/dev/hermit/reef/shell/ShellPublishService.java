package dev.hermit.reef.shell;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ShellPackage;
import dev.hermit.common.pkg.VerifiedShell;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShellPublishService {

    static final String PUBLISH_NOT_UPLOADED = "only an uploaded version can be published";
    static final String ROLLBACK_REJECTED = "rollback target must be a published version that is not current";

    private static final Logger log = LoggerFactory.getLogger(ShellPublishService.class);

    private final ShellRepository repository;
    private final ShellStorage storage;
    private final SigningKeyStore keys;
    private final PackageVerifier verifier;
    private final ShellAvailabilityRepository availability;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ShellPublishService(ShellRepository repository, ShellStorage storage, SigningKeyStore keys,
            PackageVerifier verifier, ShellAvailabilityRepository availability, AuditService auditService,
            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.keys = keys;
        this.verifier = verifier;
        this.availability = availability;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public PublishResponse publish(String actor, String shell, String version) {
        requireActor(actor);
        VersionRow target = requireRow(shell, version);
        rejectIfDisabled(target.shell());
        if (!"UPLOADED".equals(target.status())) {
            throw conflict(PUBLISH_NOT_UPLOADED);
        }
        byte[] yaml = storage.readYaml(target.shell(), target.version());
        byte[] zip = ShellPackage.write(yaml, keys.privateKey());
        storage.writeZip(target.shell(), target.version(), zip);
        Instant publishedAt = clock.instant();
        if (repository.markPublished(target.shell(), target.version(), actor, publishedAt) != 1) {
            storage.deleteZip(target.shell(), target.version());
            throw conflict(PUBLISH_NOT_UPLOADED);
        }
        try {
            byte[] stored = storage.readZip(target.shell(), target.version());
            VerifiedShell verified = verifier.verify(stored, keys.publicKey());
            if (!verified.manifest().sha256().equals(target.sha256())
                    || !verified.manifest().shell().equals(target.shell())
                    || !verified.manifest().version().equals(target.version())) {
                throw new PackageVerificationException(
                        dev.hermit.common.pkg.PackageReason.MANIFEST_MISMATCH, "published package does not match the row");
            }
        } catch (PackageVerificationException ex) {
            storage.deleteZip(target.shell(), target.version());
            log.error("Shell package self-check failed reason={}", ex.reason());
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        auditService.record(actor, AuditAction.PUBLISH, target.shell(), target.version(), null);
        return new PublishResponse(target.shell(), target.version());
    }

    @Transactional
    public RollbackResponse rollback(String actor, String shell, String version) {
        requireActor(actor);
        if (version == null || version.isBlank() || version.length() > 64) {
            throw ApiException.invalidInput();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw ApiException.invalidInput();
        }
        String canonical = parsed.toString();
        List<VersionRow> rows = repository.lockShell(shell);
        VersionRow target = rows.stream().filter(row -> row.version().equals(canonical)).findFirst().orElse(null);
        if (target == null) {
            throw notFound();
        }
        rejectIfDisabled(target.shell());
        if (!"PUBLISHED".equals(target.status()) || target.current()) {
            throw conflict(ROLLBACK_REJECTED);
        }
        String from = rows.stream().filter(VersionRow::current).map(VersionRow::version).findFirst().orElse(null);
        if (repository.switchCurrent(target.shell(), target.version()) != 1) {
            throw conflict(ROLLBACK_REJECTED);
        }
        auditService.record(actor, AuditAction.ROLLBACK, target.shell(), target.version(), detail(from, target.version()));
        return new RollbackResponse(target.shell(), from, target.version());
    }

    private VersionRow requireRow(String shell, String version) {
        if (!ShellPaths.singleSegment(shell) || !ShellPaths.singleSegment(version)) {
            throw notFound();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw notFound();
        }
        List<VersionRow> rows = repository.lockShell(shell);
        return rows.stream()
                .filter(row -> row.version().equals(parsed.toString()))
                .findFirst()
                .orElseThrow(ShellPublishService::notFound);
    }

    private void rejectIfDisabled(String shell) {
        AvailabilityRow row = availability.lock(shell);
        if (row != null && row.disabled()) {
            throw conflict(ShellAvailabilityService.DISABLED);
        }
    }

    private String detail(String from, String to) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", to);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, ReefErrorCode.CONFLICT, message);
    }
}
