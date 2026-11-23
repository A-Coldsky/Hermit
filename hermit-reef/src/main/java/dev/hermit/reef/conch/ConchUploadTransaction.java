package dev.hermit.reef.conch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.common.model.ConchDoc;
import dev.hermit.common.parse.ConchParser;
import dev.hermit.common.http.AuthOrigins;
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
import java.util.LinkedHashSet;
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
        ConchDoc previous = currentDoc(existing);
        JsonNode diff = previous == null
                ? objectMapper.createObjectNode()
                : objectMapper.valueToTree(ConchDiff.between(previous, doc));
        List<ValidationIssue> warnings = withSameSite(withAddedAuthOrigins(prepared.warnings(), previous, doc), doc);
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
     * 这个版本写了当前发布版本没有的认证地址时加一条警告：凭证会提交到 {@code base_url} 之外的地址，
     * 发布的人要看得到。第一次上传的包，写了认证地址就提示。
     */
    private static List<ValidationIssue> withAddedAuthOrigins(
            List<ValidationIssue> warnings, ConchDoc previous, ConchDoc uploaded) {
        if (!AuthOrigins.used(uploaded)) {
            return warnings;
        }
        Set<String> before = previous == null || previous.auth() == null
                ? Set.of()
                : authEntries(previous);
        ArrayList<String> added = new ArrayList<>();
        for (String entry : authEntries(uploaded)) {
            if (!before.contains(entry)) {
                added.add(entry);
            }
        }
        if (added.isEmpty()) {
            return warnings;
        }
        ArrayList<ValidationIssue> all = new ArrayList<>(warnings);
        all.add(new ValidationIssue(
                ValidationCodes.AUTH_ORIGIN_ADDED,
                "auth.origins",
                "登录步骤除了 base_url 还会访问认证地址 " + String.join("、", added)
                        + (previous == null ? "" : "，当前发布的版本没有")
                        + "。账号密码会提交到那里，发布前确认它是这个系统的门户或认证中心。",
                null));
        return List.copyOf(all);
    }

    /** 认证地址逐项写成给人看的文字：字面量规范成 scheme://host:port，配置引用写出配置项名。 */
    private static Set<String> authEntries(ConchDoc doc) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String entry : doc.auth().origins()) {
            String name = AuthOrigins.configName(entry);
            if (name != null) {
                entries.add("配置项 " + name + " 填的地址");
                continue;
            }
            String site = SiteAddress.of(entry);
            if (site != null) {
                entries.add(site);
            }
        }
        return entries;
    }

    /**
     * 已发布的其它规则包里，包内地址和这次上传的包指向同一网站的，各加一条警告；
     * 不是同一网站、但经同一个认证地址登录的（或一方的站点就是另一方的认证地址），也各加一条。
     * 两个包可能出自互不知道对方的作者；同一台 Crab 同时启用它们时各自登录。
     * 读不出来的旧包跳过，不影响上传。
     */
    private List<ValidationIssue> withSameSite(List<ValidationIssue> warnings, ConchDoc uploaded) {
        Set<String> sites = SiteAddress.declared(uploaded);
        Set<String> auth = AuthOrigins.declared(uploaded);
        if (sites.isEmpty() && auth.isEmpty()) {
            return warnings;
        }
        ArrayList<ValidationIssue> all = new ArrayList<>(warnings);
        int siteHits = 0;
        int authHits = 0;
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
            Set<String> otherSites = SiteAddress.declared(other);
            String site = firstShared(otherSites, sites);
            if (site != null) {
                // 警告随版本存进一列 TEXT，条数要有上限。
                if (++siteHits <= SAME_SITE_LISTED) {
                    all.add(new ValidationIssue(
                            ValidationCodes.SAME_SITE, "base_url", sameSiteMessage(other, site), null));
                }
                continue;
            }
            Set<String> otherAuth = AuthOrigins.declared(other);
            String shared = firstShared(otherAuth, auth);
            if (shared == null) {
                shared = firstShared(otherSites, auth);
            }
            if (shared == null) {
                shared = firstShared(otherAuth, sites);
            }
            if (shared != null && ++authHits <= SAME_SITE_LISTED) {
                all.add(new ValidationIssue(
                        ValidationCodes.SAME_AUTH, "auth.origins", sameAuthMessage(other, shared), null));
            }
        }
        if (siteHits > SAME_SITE_LISTED) {
            all.add(new ValidationIssue(
                    ValidationCodes.SAME_SITE,
                    "base_url",
                    "另有 " + (siteHits - SAME_SITE_LISTED) + " 个已发布的规则包也访问同一网站，这里不逐个列出。",
                    null));
        }
        if (authHits > SAME_SITE_LISTED) {
            all.add(new ValidationIssue(
                    ValidationCodes.SAME_AUTH,
                    "auth.origins",
                    "另有 " + (authHits - SAME_SITE_LISTED) + " 个已发布的规则包也经同一个认证地址登录，这里不逐个列出。",
                    null));
        }
        return List.copyOf(all);
    }

    private static String firstShared(Set<String> candidates, Set<String> mine) {
        for (String candidate : candidates) {
            if (mine.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** 逐个写出的同网站规则包上限，按包名顺序取前几个；其余只报个数。 */
    static final int SAME_SITE_LISTED = 5;

    /** 标题和维护者没有长度上限，写进警告前截短。 */
    private static String clip(String text) {
        String trimmed = text.strip();
        int end = trimmed.offsetByCodePoints(0, Math.min(SAME_SITE_TEXT, trimmed.codePointCount(0, trimmed.length())));
        return end < trimmed.length() ? trimmed.substring(0, end) + "…" : trimmed;
    }

    static final int SAME_SITE_TEXT = 60;

    static String sameSiteMessage(ConchDoc other, String site) {
        return named(other).append("也访问 ").append(site)
                .append("。同一台 Crab 同时启用这两个包时各自登录；网站只允许一个会话时会互相顶掉。")
                .append("可以考虑把能力加进已有的包。")
                .toString();
    }

    static String sameAuthMessage(ConchDoc other, String origin) {
        return named(other).append("也经 ").append(origin)
                .append(" 登录。同一台 Crab 同时启用这两个包时各自在那里登录；它只允许一个会话时会互相顶掉。")
                .append("登录步骤先访问应用、出现登录页才提交账号密码，可以少登录几次。")
                .toString();
    }

    private static StringBuilder named(ConchDoc other) {
        StringBuilder text = new StringBuilder("已发布的规则包 ").append(other.conch());
        boolean titled = other.title() != null && !other.title().isBlank();
        boolean owned = other.owner() != null && !other.owner().isBlank();
        if (titled || owned) {
            text.append("（");
            if (titled) {
                text.append(clip(other.title()));
            }
            if (owned) {
                text.append(titled ? "，" : "").append("维护者 ").append(clip(other.owner()));
            }
            text.append("）");
        } else {
            text.append(' ');
        }
        return text;
    }

    /** 当前发布的版本；还没发布过为 null。 */
    private ConchDoc currentDoc(List<VersionRow> existing) {
        List<VersionRow> currents = existing.stream().filter(VersionRow::current).toList();
        if (currents.size() > 1) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        if (currents.isEmpty()) {
            return null;
        }
        VersionRow current = currents.get(0);
        return ConchParser.parse(new String(storage.readYaml(current.conch(), current.version()),
                StandardCharsets.UTF_8));
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
