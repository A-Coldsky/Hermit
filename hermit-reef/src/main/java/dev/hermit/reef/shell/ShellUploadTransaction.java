package dev.hermit.reef.shell;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.common.validate.ShellDiff;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShellUploadTransaction {

    private final ShellRepository repository;
    private final ShellStorage storage;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ShellUploadTransaction(ShellRepository repository, ShellStorage storage, AuditService auditService,
            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public UploadResponse store(String actor, ShellUploadService.PreparedUpload prepared) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        ShellDoc doc = prepared.doc();
        String shell = doc.shell();
        String version = doc.version().toString();
        List<VersionRow> existing = repository.lockShell(shell);
        if (existing.stream().anyMatch(row -> row.version().equals(version))) {
            throw conflict(ShellUploadService.VERSION_EXISTS);
        }
        SemVer highest = null;
        for (VersionRow row : existing) {
            SemVer current = SemVer.parse(row.version());
            if (highest == null || current.compareTo(highest) > 0) {
                highest = current;
            }
        }
        if (highest != null && doc.version().compareTo(highest) <= 0) {
            throw conflict(ShellUploadService.VERSION_NOT_GREATER);
        }
        JsonNode diff = diffAgainstCurrent(existing, doc);
        String validationJson = json(prepared.warnings());
        String diffJson = json(diff);
        boolean wrote = false;
        try {
            storage.writeYaml(shell, version, prepared.yaml());
            wrote = true;
            Instant uploadedAt = clock.instant();
            repository.insertUploaded(
                    new VersionRow(
                            0,
                            shell,
                            version,
                            ShellHashes.sha256(prepared.yaml()),
                            doc.minCrabVersion().toString(),
                            "UPLOADED",
                            actor,
                            uploadedAt,
                            null,
                            null,
                            false,
                            validationJson,
                            diffJson),
                    "shells/" + shell + "/" + version + "/shell.yaml",
                    validationJson,
                    diffJson);
            auditService.record(actor, AuditAction.UPLOAD, shell, version, null);
            return new UploadResponse(shell, version, prepared.warnings(), diff);
        } catch (RuntimeException ex) {
            if (wrote) {
                storage.deleteYaml(shell, version);
            }
            if (ex instanceof DataIntegrityViolationException) {
                throw conflict(ShellUploadService.VERSION_EXISTS);
            }
            throw ex;
        }
    }

    private JsonNode diffAgainstCurrent(List<VersionRow> existing, ShellDoc uploaded) {
        List<VersionRow> currents = existing.stream().filter(VersionRow::current).toList();
        if (currents.size() > 1) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        if (currents.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        VersionRow current = currents.get(0);
        ShellDoc previous = ShellParser.parse(new String(storage.readYaml(current.shell(), current.version()),
                StandardCharsets.UTF_8));
        return objectMapper.valueToTree(ShellDiff.between(previous, uploaded));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, ReefErrorCode.CONFLICT, message);
    }
}
