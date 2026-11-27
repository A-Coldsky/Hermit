package dev.hermit.reef.conch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.validate.ConchDiff;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConchSubmissionTransaction {

    static final int MAX_PENDING_PER_USER = 20;
    static final int MAX_TITLE_LENGTH = 256;
    static final String TOO_MANY_PENDING = "待审批的提交已达上限（20 条），请等待管理员处理或先撤回旧提交";
    static final String ONLY_PENDING_CAN_APPROVE = "只能批准待审批的提交";
    static final String ONLY_PENDING_CAN_REJECT = "只能驳回待审批的提交";
    static final String ONLY_PENDING_CAN_WITHDRAW = "只能撤回待审批的提交";
    static final String ONLY_OWNER_CAN_WITHDRAW = "只能撤回自己提交的规则包";

    private final SubmissionRepository submissions;
    private final SubmissionStorage submissionStorage;
    private final ConchRepository conchRepository;
    private final ConchAvailabilityRepository availability;
    private final ConchStorage conchStorage;
    private final ConchPublishService publishService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuthDependencies authDependencies;

    ConchSubmissionTransaction(
            SubmissionRepository submissions,
            SubmissionStorage submissionStorage,
            ConchRepository conchRepository,
            ConchAvailabilityRepository availability,
            ConchStorage conchStorage,
            ConchPublishService publishService,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock,
            AuthDependencies authDependencies) {
        this.submissions = submissions;
        this.submissionStorage = submissionStorage;
        this.conchRepository = conchRepository;
        this.availability = availability;
        this.conchStorage = conchStorage;
        this.publishService = publishService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.authDependencies = authDependencies;
    }

    @Transactional
    public SubmissionDetail create(
            String actor,
            ConchUploadService.PreparedUpload prepared,
            List<ValidationIssue> sameSiteWarnings,
            String submitNote) {
        if (submissions.countPending(actor) >= MAX_PENDING_PER_USER) {
            throw conflict(TOO_MANY_PENDING);
        }
        ConchDoc doc = prepared.doc();
        String conch = doc.conch();
        String version = doc.version().toString();
        // 统一按 hermit_conch_versions -> hermit_conch_availability 的顺序加锁，避免与发布/停用事务死锁。
        List<VersionRow> existing = conchRepository.lockConch(conch);
        AvailabilityRow avail = availability.lock(conch);
        if (avail != null && avail.disabled()) {
            throw conflict(ConchAvailabilityService.DISABLED);
        }
        VersionRow currentRow = currentPublishedRow(existing);
        if (currentRow != null) {
            SemVer currentVer = SemVer.parse(currentRow.version());
            if (doc.version().compareTo(currentVer) <= 0) {
                throw conflict("提交版本必须高于当前已发布版本（" + currentRow.version() + "）");
            }
        }
        List<ValidationIssue> dependencyProblems = authDependencies.uploadProblems(doc);
        ValidationIssue pendingAuthWarning = null;
        if (!dependencyProblems.isEmpty()) {
            PendingAuthResolution resolved = resolvePendingAuthenticator(doc, dependencyProblems);
            if (resolved.satisfied()) {
                dependencyProblems = List.of();
                pendingAuthWarning = resolved.warning();
            } else {
                throw ConchUploadService.dependencyFailed(prepared, dependencyProblems);
            }
        }
        ConchDoc previous = currentRow == null ? null : readConchDoc(currentRow);
        JsonNode diff = previous == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(ConchDiff.between(previous, doc));
        List<ValidationIssue> warnings = append(
                ConchUploadTransaction.withAddedAuthOrigins(prepared.warnings(), previous, doc),
                sameSiteWarnings);
        if (pendingAuthWarning != null) {
            warnings = append(warnings, List.of(pendingAuthWarning));
        }
        String validationJson = json(warnings);
        String diffJson = json(diff);
        String sha256 = ConchHashes.sha256(prepared.yaml());
        String driver = (doc.driver() == null || doc.driver().isBlank()) ? "http" : doc.driver();
        String title = safeTitle(doc.title(), conch);
        Instant now = clock.instant();

        long id = 0;
        boolean wroteFile = false;
        try {
            id = submissions.insertPending(new SubmissionRow(
                    0,
                    conch,
                    version,
                    sha256,
                    doc.minCrabVersion().toString(),
                    driver,
                    title,
                    "",
                    validationJson,
                    diffJson,
                    submitNote,
                    SubmissionStatus.PENDING,
                    actor,
                    now,
                    null,
                    null,
                    null));
            String relativePath = submissionStorage.writeYaml(id, prepared.yaml());
            wroteFile = true;
            submissions.updateYamlPath(id, relativePath);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("submissionId", id);
            if (submitNote != null) {
                detail.put("note", submitNote);
            }
            auditService.record(actor, AuditAction.SUBMISSION_CREATED, conch, version, json(detail));

            boolean conflictNow = hasVersionConflict(existing, doc.version());
            return buildDetail(
                    id,
                    conch,
                    version,
                    sha256,
                    doc.minCrabVersion().toString(),
                    driver,
                    title,
                    SubmissionStatus.PENDING,
                    submitNote,
                    actor,
                    now,
                    null,
                    null,
                    null,
                    warnings,
                    diff,
                    currentRow == null ? null : currentRow.version(),
                    false,
                    conflictNow,
                    doc);
        } catch (RuntimeException ex) {
            if (wroteFile && id > 0) {
                submissionStorage.deleteYaml(id);
            }
            throw ex;
        }
    }

    private record PendingAuthResolution(boolean satisfied, ValidationIssue warning) {
    }

    private PendingAuthResolution resolvePendingAuthenticator(
            ConchDoc consumer,
            List<ValidationIssue> publishedProblems
    ) {
        if (!dev.hermit.common.validate.AuthenticatorChecks.uses(consumer)) {
            return new PendingAuthResolution(false, null);
        }
        if (!authDependencies.brokenConsumers(consumer.conch(), consumer).isEmpty()) {
            return new PendingAuthResolution(false, null);
        }
        String authName = consumer.auth().use();
        AvailabilityRow authAvail = availability.lock(authName);
        if (authAvail != null && authAvail.disabled()) {
            return new PendingAuthResolution(false, null);
        }
        for (SubmissionRow pending : submissions.findPendingByConch(authName)) {
            if (pending.yamlPath() == null || pending.yamlPath().isBlank()) {
                continue;
            }
            try {
                byte[] yaml = submissionStorage.readYaml(pending.id());
                ConchDoc pendingAuthDoc = ConchParser.parse(new String(yaml, StandardCharsets.UTF_8));
                if (dev.hermit.common.validate.AuthenticatorChecks.isAuthenticator(pendingAuthDoc)
                        && dev.hermit.common.validate.AuthenticatorChecks.link(consumer, pendingAuthDoc).isEmpty()) {
                    ValidationIssue note = new ValidationIssue(
                            dev.hermit.common.validate.ValidationCodes.AUTHENTICATOR,
                            "auth.use",
                            "引用的授权器 " + authName + "（v" + pendingAuthDoc.version()
                                    + "）当前处于待审批状态，审批时须先批准该授权器再批准本规则包");
                    return new PendingAuthResolution(true, note);
                }
            } catch (RuntimeException ignored) {
                // 跳过不可读的候选，继续检查其余待审批记录。
            }
        }
        return new PendingAuthResolution(false, null);
    }

    public List<SubmissionSummary> list(String actor, boolean admin, SubmissionStatus statusFilter) {
        List<SubmissionRow> rows = submissions.list(admin ? null : actor, statusFilter);
        List<SubmissionSummary> result = new ArrayList<>(rows.size());
        for (SubmissionRow row : rows) {
            result.add(toSummary(row));
        }
        return List.copyOf(result);
    }

    public long pendingCount(String actor, boolean admin) {
        return submissions.countPending(admin ? null : actor);
    }

    public SubmissionDetail detail(long id, String actor, boolean admin) {
        SubmissionRow row = requireReadable(id, actor, admin);
        return toDetail(row);
    }

    public byte[] yaml(long id, String actor, boolean admin) {
        SubmissionRow row = requireReadable(id, actor, admin);
        return submissionStorage.readYaml(row.id());
    }

    public SubmissionRow requireRowForApprovalPrep(long id) {
        return submissions.findById(id).orElseThrow(ConchSubmissionTransaction::notFound);
    }

    @Transactional
    public SubmissionDetail withdraw(long id, String actor) {
        SubmissionRow row = submissions.lockById(id).orElseThrow(ConchSubmissionTransaction::notFound);
        if (!row.submittedBy().equals(actor)) {
            throw new ApiException(403, ReefErrorCode.FORBIDDEN, ONLY_OWNER_CAN_WITHDRAW);
        }
        if (row.status() != SubmissionStatus.PENDING) {
            throw conflict(ONLY_PENDING_CAN_WITHDRAW);
        }
        Instant now = clock.instant();
        if (submissions.markReviewed(id, SubmissionStatus.WITHDRAWN, actor, now, null) != 1) {
            throw conflict(ONLY_PENDING_CAN_WITHDRAW);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("submissionId", id);
        auditService.record(actor, AuditAction.SUBMISSION_WITHDRAWN, row.conch(), row.version(), json(detail));
        return toDetail(submissions.findById(id).orElseThrow(ConchSubmissionTransaction::notFound));
    }

    @Transactional
    public SubmissionDetail reject(long id, String actor, String comment) {
        SubmissionRow row = submissions.lockById(id).orElseThrow(ConchSubmissionTransaction::notFound);
        if (row.status() != SubmissionStatus.PENDING) {
            throw conflict(ONLY_PENDING_CAN_REJECT);
        }
        Instant now = clock.instant();
        if (submissions.markReviewed(id, SubmissionStatus.REJECTED, actor, now, comment) != 1) {
            throw conflict(ONLY_PENDING_CAN_REJECT);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("submissionId", id);
        detail.put("submittedBy", row.submittedBy());
        detail.put("comment", comment);
        auditService.record(actor, AuditAction.SUBMISSION_REJECTED, row.conch(), row.version(), json(detail));
        return toDetail(submissions.findById(id).orElseThrow(ConchSubmissionTransaction::notFound));
    }

    @Transactional
    public SubmissionDetail approve(
            long id,
            String actor,
            String comment,
            List<ValidationIssue> freshSameSiteWarnings) {
        SubmissionRow row = submissions.lockById(id).orElseThrow(ConchSubmissionTransaction::notFound);
        if (row.status() != SubmissionStatus.PENDING) {
            throw conflict(ONLY_PENDING_CAN_APPROVE);
        }
        byte[] yamlBytes = submissionStorage.readYaml(row.id());
        if (!ConchHashes.sha256(yamlBytes).equals(row.sha256())) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        ConchUploadService.PreparedUpload prepared = ConchUploadService.prepare(yamlBytes);
        ConchDoc doc = prepared.doc();
        String conch = doc.conch();
        String version = doc.version().toString();
        if (!conch.equals(row.conch()) || !version.equals(row.version())) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }

        // 统一按 hermit_conch_versions -> hermit_conch_availability 的顺序加锁，避免与发布/停用事务死锁。
        List<VersionRow> existing = conchRepository.lockConch(conch);
        AvailabilityRow avail = availability.lock(conch);
        if (avail != null && avail.disabled()) {
            throw conflict(ConchAvailabilityService.DISABLED);
        }
        if (existing.stream().anyMatch(v -> v.version().equals(version))) {
            throw conflict("该规则包已存在版本 " + version + "，请先处理已有版本后再审批或驳回此提交");
        }
        SemVer highest = highestVersion(existing);
        if (highest != null && doc.version().compareTo(highest) <= 0) {
            throw conflict("审批发布失败：提交版本 " + version + " 不高于该规则包已有最高版本 " + highest);
        }

        VersionRow currentRow = currentPublishedRow(existing);
        List<ValidationIssue> dependencyProblems = authDependencies.uploadProblems(doc);
        if (!dependencyProblems.isEmpty()) {
            throw conflict(dependencyProblems.get(0).message());
        }
        ConchDoc previous = currentRow == null ? null : readConchDoc(currentRow);
        JsonNode diff = previous == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(ConchDiff.between(previous, doc));
        List<ValidationIssue> warnings = append(
                ConchUploadTransaction.withAddedAuthOrigins(prepared.warnings(), previous, doc),
                freshSameSiteWarnings);
        String validationJson = json(warnings);
        String diffJson = json(diff);

        boolean wroteConchFiles = false;
        try {
            conchStorage.writeYaml(conch, version, prepared.yaml());
            wroteConchFiles = true;
            conchRepository.insertUploaded(
                    new VersionRow(
                            0,
                            conch,
                            version,
                            row.sha256(),
                            doc.minCrabVersion().toString(),
                            "UPLOADED",
                            row.submittedBy(),
                            row.submittedAt(),
                            null,
                            null,
                            false,
                            validationJson,
                            diffJson,
                            0,
                            AuthDependencies.authUse(doc)),
                    "conches/" + conch + "/" + version + "/conch.yaml",
                    validationJson,
                    diffJson);
            publishService.publish(actor, conch, version);
            Instant now = clock.instant();
            if (submissions.markReviewed(id, SubmissionStatus.APPROVED, actor, now, comment) != 1) {
                throw conflict(ONLY_PENDING_CAN_APPROVE);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("submissionId", id);
            detail.put("submittedBy", row.submittedBy());
            if (comment != null) {
                detail.put("comment", comment);
            }
            auditService.record(actor, AuditAction.SUBMISSION_APPROVED, conch, version, json(detail));
            return buildDetail(
                    id,
                    conch,
                    version,
                    row.sha256(),
                    row.minCrabVersion(),
                    row.driver(),
                    row.title(),
                    SubmissionStatus.APPROVED,
                    row.submitNote(),
                    row.submittedBy(),
                    row.submittedAt(),
                    actor,
                    now,
                    comment,
                    warnings,
                    diff,
                    version,
                    false,
                    false,
                    doc);
        } catch (RuntimeException ex) {
            if (wroteConchFiles) {
                conchStorage.deleteUploadedFiles(conch, version);
            }
            if (ex instanceof DuplicateKeyException) {
                throw conflict("该规则包已存在版本 " + version + "，请先处理已有版本后再审批或驳回此提交");
            }
            throw ex;
        }
    }

    private SubmissionRow requireReadable(long id, String actor, boolean admin) {
        SubmissionRow row = submissions.findById(id).orElseThrow(ConchSubmissionTransaction::notFound);
        if (!admin && !row.submittedBy().equals(actor)) {
            throw new ApiException(403, ReefErrorCode.FORBIDDEN, ReefErrorCode.FORBIDDEN.message());
        }
        return row;
    }

    private SubmissionSummary toSummary(SubmissionRow row) {
        return new SubmissionSummary(
                row.id(),
                row.conch(),
                row.version(),
                row.sha256(),
                row.minCrabVersion(),
                row.driver(),
                row.title(),
                row.status(),
                row.submitNote(),
                row.submittedBy(),
                row.submittedAt(),
                row.reviewedBy(),
                row.reviewedAt(),
                row.reviewComment(),
                parseWarnings(row.validationJson()).size());
    }

    private SubmissionDetail toDetail(SubmissionRow row) {
        List<VersionRow> versions = conchRepository.listByConch(row.conch());
        VersionRow currentRow = currentPublishedRow(versions);
        boolean disabled = availability.isDisabled(row.conch());
        List<ValidationIssue> warnings = parseWarnings(row.validationJson());
        JsonNode diff = parseDiff(row.diffJson());
        boolean versionConflict = false;
        ConchDoc submittedDoc = readSubmittedDocQuietly(row.id());
        if (row.status() == SubmissionStatus.PENDING) {
            SemVer submittedVer = SemVer.parse(row.version());
            versionConflict = hasVersionConflict(versions, submittedVer);
            if (currentRow != null && submittedDoc != null) {
                try {
                    ConchDoc liveCurrent = readConchDoc(currentRow);
                    diff = objectMapper.valueToTree(ConchDiff.between(liveCurrent, submittedDoc));
                } catch (RuntimeException ignored) {
                    // 读不到时退回提交时快照。
                }
            } else if (currentRow == null) {
                diff = objectMapper.createObjectNode();
            }
        }
        return buildDetail(
                row.id(),
                row.conch(),
                row.version(),
                row.sha256(),
                row.minCrabVersion(),
                row.driver(),
                row.title(),
                row.status(),
                row.submitNote(),
                row.submittedBy(),
                row.submittedAt(),
                row.reviewedBy(),
                row.reviewedAt(),
                row.reviewComment(),
                warnings,
                diff,
                currentRow == null ? null : currentRow.version(),
                disabled,
                versionConflict,
                submittedDoc);
    }

    private ConchDoc readSubmittedDocQuietly(long id) {
        try {
            return ConchParser.parse(new String(submissionStorage.readYaml(id), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static SubmissionDetail buildDetail(
            long id,
            String conch,
            String version,
            String sha256,
            String minCrabVersion,
            String driver,
            String title,
            SubmissionStatus status,
            String submitNote,
            String submittedBy,
            Instant submittedAt,
            String reviewedBy,
            Instant reviewedAt,
            String reviewComment,
            List<ValidationIssue> warnings,
            JsonNode diff,
            String currentPublishedVersion,
            boolean conchDisabled,
            boolean versionConflict,
            ConchDoc doc
    ) {
        return new SubmissionDetail(
                id,
                conch,
                version,
                sha256,
                minCrabVersion,
                driver,
                title,
                status,
                submitNote,
                submittedBy,
                submittedAt,
                reviewedBy,
                reviewedAt,
                reviewComment,
                warnings,
                diff,
                currentPublishedVersion,
                conchDisabled,
                versionConflict,
                doc == null ? null : AuthDependencies.authUse(doc),
                doc == null || doc.auth() == null ? null : doc.auth().minVersion(),
                doc == null || doc.auth() == null ? List.of() : List.copyOf(doc.auth().exports().keySet()),
                doc == null || doc.auth() == null ? List.of() : doc.auth().allowHosts());
    }

    private static VersionRow currentPublishedRow(List<VersionRow> rows) {
        for (VersionRow row : rows) {
            if (row.current()) {
                return row;
            }
        }
        return null;
    }

    private static SemVer highestVersion(List<VersionRow> rows) {
        SemVer highest = null;
        for (VersionRow row : rows) {
            SemVer v = SemVer.parse(row.version());
            if (highest == null || v.compareTo(highest) > 0) {
                highest = v;
            }
        }
        return highest;
    }

    private static boolean hasVersionConflict(List<VersionRow> existing, SemVer candidate) {
        SemVer highest = highestVersion(existing);
        return highest != null && candidate.compareTo(highest) <= 0;
    }

    private ConchDoc readConchDoc(VersionRow row) {
        return ConchParser.parse(new String(conchStorage.readYaml(row.conch(), row.version()), StandardCharsets.UTF_8));
    }

    private List<ValidationIssue> parseWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ValidationIssue> list = objectMapper.readValue(json, new TypeReference<List<ValidationIssue>>() {
            });
            return list == null ? List.of() : List.copyOf(list);
        } catch (IOException ex) {
            return List.of();
        }
    }

    private JsonNode parseDiff(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null || node.isNull() ? objectMapper.createObjectNode() : node;
        } catch (IOException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static List<ValidationIssue> append(List<ValidationIssue> base, List<ValidationIssue> extra) {
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        ArrayList<ValidationIssue> all = new ArrayList<>(base.size() + extra.size());
        all.addAll(base);
        all.addAll(extra);
        return List.copyOf(all);
    }

    static String safeTitle(String rawTitle, String fallbackConch) {
        String source = (rawTitle == null || rawTitle.isBlank()) ? fallbackConch : rawTitle.trim();
        if (source.length() <= MAX_TITLE_LENGTH) {
            return source;
        }
        int count = source.codePointCount(0, source.length());
        int cut = source.offsetByCodePoints(0, Math.min(count, MAX_TITLE_LENGTH));
        while (cut > MAX_TITLE_LENGTH) {
            cut = Character.offsetByCodePoints(source, cut, -1);
        }
        return source.substring(0, cut);
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, ReefErrorCode.CONFLICT, message);
    }
}
