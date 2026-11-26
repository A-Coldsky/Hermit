package dev.hermit.reef.conch;

import dev.hermit.common.pkg.PackageVerificationException;
import dev.hermit.common.pkg.ConchPackage;
import dev.hermit.common.pkg.VerifiedConch;
import dev.hermit.common.validate.ConchValidator;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.time.Clock;
import java.time.Instant;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ValidationIssue;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConchPublishService {

    static final String PUBLISH_NOT_UPLOADED = "只能发布已上传、尚未发布的版本";
    static final String PUBLISH_NOT_HIGHER = "发布版本必须高于当前已发布版本";

    private static final Logger log = LoggerFactory.getLogger(ConchPublishService.class);

    private final ConchRepository repository;
    private final ConchStorage storage;
    private final SigningKeyStore keys;
    private final PackageVerifier verifier;
    private final ConchAvailabilityRepository availability;
    private final AuditService auditService;
    private final Clock clock;
    private final AuthDependencies authDependencies;

    public ConchPublishService(ConchRepository repository, ConchStorage storage, SigningKeyStore keys,
            PackageVerifier verifier, ConchAvailabilityRepository availability, AuditService auditService,
            Clock clock, AuthDependencies authDependencies) {
        this.repository = repository;
        this.storage = storage;
        this.keys = keys;
        this.verifier = verifier;
        this.availability = availability;
        this.auditService = auditService;
        this.clock = clock;
        this.authDependencies = authDependencies;
    }

    @Transactional
    public PublishResponse publish(String actor, String conch, String version) {
        requireActor(actor);
        VersionRow target = requireRow(conch, version);
        rejectIfDisabled(target.conch());
        if (!"UPLOADED".equals(target.status())) {
            throw conflict(PUBLISH_NOT_UPLOADED);
        }
        rejectIfNotHigher(target);
        byte[] yaml = storage.readYaml(target.conch(), target.version());
        rejectIfDependenciesBreak(yaml);
        byte[] zip = ConchPackage.write(yaml, keys.privateKey());
        storage.writeZip(target.conch(), target.version(), zip);
        Instant publishedAt = clock.instant();
        if (repository.markPublished(target.conch(), target.version(), actor, publishedAt) != 1) {
            storage.deleteZip(target.conch(), target.version());
            throw conflict(PUBLISH_NOT_UPLOADED);
        }
        try {
            byte[] stored = storage.readZip(target.conch(), target.version());
            VerifiedConch verified = verifier.verify(stored, keys.publicKey());
            if (!verified.manifest().sha256().equals(target.sha256())
                    || !verified.manifest().conch().equals(target.conch())
                    || !verified.manifest().version().equals(target.version())) {
                throw new PackageVerificationException(
                        dev.hermit.common.pkg.PackageReason.MANIFEST_MISMATCH, "published package does not match the row");
            }
        } catch (PackageVerificationException ex) {
            storage.deleteZip(target.conch(), target.version());
            log.error("Conch package self-check failed reason={}", ex.reason());
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        auditService.record(actor, AuditAction.PUBLISH, target.conch(), target.version(), null);
        return new PublishResponse(target.conch(), target.version());
    }

    /** 上传之后授权器或依赖它的包可能变了，发布时按当前状态再查一次。 */
    private void rejectIfDependenciesBreak(byte[] yaml) {
        ConchDoc doc = ConchParser.parse(new String(yaml, StandardCharsets.UTF_8));
        List<ValidationIssue> problems = authDependencies.uploadProblems(doc);
        if (!problems.isEmpty()) {
            throw conflict(problems.get(0).message());
        }
    }

    private void rejectIfNotHigher(VersionRow target) {
        VersionRow current = null;
        for (VersionRow row : repository.lockConch(target.conch())) {
            if (row.conch().equals(target.conch()) && row.current()) {
                current = row;
                break;
            }
        }
        if (current == null) {
            return;
        }
        SemVer currentVersion = SemVer.parse(current.version());
        SemVer targetVersion = SemVer.parse(target.version());
        if (targetVersion.compareTo(currentVersion) <= 0) {
            throw conflict(PUBLISH_NOT_HIGHER);
        }
    }

    private VersionRow requireRow(String conch, String version) {
        if (!ConchValidator.isConchName(conch) || !ConchPaths.singleSegment(version)) {
            throw notFound();
        }
        SemVer parsed;
        try {
            parsed = SemVer.parse(version);
        } catch (IllegalArgumentException ex) {
            throw notFound();
        }
        List<VersionRow> rows = repository.lockConch(conch);
        return rows.stream()
                .filter(row -> row.conch().equals(conch) && row.version().equals(parsed.toString()))
                .findFirst()
                .orElseThrow(ConchPublishService::notFound);
    }

    private void rejectIfDisabled(String conch) {
        AvailabilityRow row = availability.lock(conch);
        if (row != null && row.disabled()) {
            throw conflict(ConchAvailabilityService.DISABLED);
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
