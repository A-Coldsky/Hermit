package dev.hermit.reef.conch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.http.SiteAddress;
import dev.hermit.common.validate.ConchDiff;
import dev.hermit.common.validate.ValidationCodes;
import dev.hermit.common.validate.ValidationIssue;
import dev.hermit.common.version.SemVer;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ConchUploadTransaction {

    private final ConchRepository repository;
    private final ConchStorage storage;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ConchUploadTransaction(ConchRepository repository, ConchStorage storage, AuditService auditService,
            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public UploadResponse store(String actor, ConchUploadService.PreparedUpload prepared) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        ConchDoc doc = prepared.doc();
        String conch = doc.conch();
        String version = doc.version().toString();
        List<VersionRow> existing = repository.lockConch(conch);
        if (existing.stream().anyMatch(row -> row.version().equals(version))) {
            throw conflict(ConchUploadService.VERSION_EXISTS);
        }
        SemVer highest = null;
        for (VersionRow row : existing) {
            SemVer current = SemVer.parse(row.version());
            if (highest == null || current.compareTo(highest) > 0) {
                highest = current;
            }
        }
        if (highest != null && doc.version().compareTo(highest) <= 0) {
            throw conflict(ConchUploadService.VERSION_NOT_GREATER);
        }
        JsonNode diff = diffAgainstCurrent(existing, doc);
        List<ValidationIssue> warnings = withSameSite(prepared.warnings(), doc);
        String validationJson = json(warnings);
        String diffJson = json(diff);
        boolean wrote = false;
        try {
            storage.writeYaml(conch, version, prepared.yaml());
            wrote = true;
            Instant uploadedAt = clock.instant();
            repository.insertUploaded(
                    new VersionRow(
                            0,
                            conch,
                            version,
                            ConchHashes.sha256(prepared.yaml()),
                            doc.minCrabVersion().toString(),
                            "UPLOADED",
                            actor,
                            uploadedAt,
                            null,
                            null,
                            false,
                            validationJson,
                            diffJson,
                            0),
                    "conches/" + conch + "/" + version + "/conch.yaml",
                    validationJson,
                    diffJson);
            auditService.record(actor, AuditAction.UPLOAD, conch, version, null);
            return new UploadResponse(conch, version, warnings, diff);
        } catch (RuntimeException ex) {
            if (wrote) {
                storage.deleteYaml(conch, version);
            }
            if (ex instanceof DataIntegrityViolationException) {
                throw conflict(ConchUploadService.VERSION_EXISTS);
            }
            throw ex;
        }
    }

    /**
     * 已发布的其它规则包里，包内地址和这次上传的包指向同一网站的，各加一条警告。
     * 两个包可能出自互不知道对方的作者；同一台 Crab 同时启用它们时各自登录。
     * 读不出来的旧包跳过，不影响上传。
     */
    private List<ValidationIssue> withSameSite(List<ValidationIssue> warnings, ConchDoc uploaded) {
        Set<String> sites = SiteAddress.declared(uploaded);
        if (sites.isEmpty()) {
            return warnings;
        }
        ArrayList<ValidationIssue> all = new ArrayList<>(warnings);
        List<VersionRow> published = new ArrayList<>(repository.listCurrent());
        published.sort(Comparator.comparing(VersionRow::conch));
        for (VersionRow row : published) {
            if (row.conch().equals(uploaded.conch())) {
                continue;
            }
            ConchDoc other;
            try {
                other = ConchParser.parse(new String(storage.readYaml(row.conch(), row.version()),
                        StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                continue;
            }
            for (String site : SiteAddress.declared(other)) {
                if (sites.contains(site)) {
                    all.add(new ValidationIssue(ValidationCodes.SAME_SITE, "base_url", sameSiteMessage(other, site), null));
                    break;
                }
            }
        }
        return List.copyOf(all);
    }

    static String sameSiteMessage(ConchDoc other, String site) {
        StringBuilder text = new StringBuilder("已发布的规则包 ").append(other.conch());
        boolean titled = other.title() != null && !other.title().isBlank();
        boolean owned = other.owner() != null && !other.owner().isBlank();
        if (titled || owned) {
            text.append("（");
            if (titled) {
                text.append(other.title());
            }
            if (owned) {
                text.append(titled ? "，" : "").append("维护者 ").append(other.owner());
            }
            text.append("）");
        } else {
            text.append(' ');
        }
        return text.append("也访问 ").append(site)
                .append("。同一台 Crab 同时启用这两个包时各自登录；网站只允许一个会话时会互相顶掉。")
                .append("可以考虑把能力加进已有的包。")
                .toString();
    }

    private JsonNode diffAgainstCurrent(List<VersionRow> existing, ConchDoc uploaded) {
        List<VersionRow> currents = existing.stream().filter(VersionRow::current).toList();
        if (currents.size() > 1) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        if (currents.isEmpty()) {
            return objectMapper.createObjectNode();
        }
        VersionRow current = currents.get(0);
        ConchDoc previous = ConchParser.parse(new String(storage.readYaml(current.conch(), current.version()),
                StandardCharsets.UTF_8));
        return objectMapper.valueToTree(ConchDiff.between(previous, uploaded));
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
