package dev.hermit.reef.notice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnouncementService {

    public static final int MAX_TITLE_LENGTH = 128;
    public static final int MAX_BODY_LENGTH = 8000;

    private static final RowMapper<AnnouncementView> ROWS = (rs, rowNum) -> new AnnouncementView(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("body"),
            AnnouncementLevel.valueOf(rs.getString("level")),
            rs.getBoolean("pinned"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("updated_by"),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnnouncementService(JdbcTemplate jdbc, AuditService auditService, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<AnnouncementView> list() {
        return jdbc.query(
                "SELECT id, title, body, level, pinned, created_by, created_at, updated_by, updated_at "
                        + "FROM hermit_announcements ORDER BY pinned DESC, updated_at DESC, id DESC",
                ROWS);
    }

    @Transactional
    public AnnouncementView create(String actor, String rawTitle, String rawBody, String rawLevel, boolean pinned) {
        requireActor(actor);
        String title = validateTitle(rawTitle);
        String body = validateBody(rawBody);
        AnnouncementLevel level = AnnouncementLevel.parse(rawLevel);
        Instant now = clock.instant();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO hermit_announcements "
                            + "(title, body, level, pinned, created_by, created_at, updated_by, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, title);
            ps.setString(2, body);
            ps.setString(3, level.name());
            ps.setBoolean(4, pinned);
            ps.setString(5, actor);
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setString(7, actor);
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
        long id = key.longValue();
        auditService.record(actor, AuditAction.ANNOUNCEMENT_CREATED, null, null,
                auditDetail(id, title, level, pinned));
        return new AnnouncementView(id, title, body, level, pinned, actor, now, actor, now);
    }

    @Transactional
    public AnnouncementView update(String actor, long id, String rawTitle, String rawBody, String rawLevel,
            boolean pinned) {
        requireActor(actor);
        AnnouncementView existing = findById(id);
        if (existing == null) {
            throw notFound();
        }
        String title = validateTitle(rawTitle);
        String body = validateBody(rawBody);
        AnnouncementLevel level = AnnouncementLevel.parse(rawLevel);
        Instant now = clock.instant();
        int updated = jdbc.update(
                "UPDATE hermit_announcements SET title = ?, body = ?, level = ?, pinned = ?, "
                        + "updated_by = ?, updated_at = ? WHERE id = ?",
                title,
                body,
                level.name(),
                pinned,
                actor,
                Timestamp.from(now),
                id);
        if (updated != 1) {
            throw notFound();
        }
        auditService.record(actor, AuditAction.ANNOUNCEMENT_UPDATED, null, null,
                auditDetail(id, title, level, pinned));
        return new AnnouncementView(
                id,
                title,
                body,
                level,
                pinned,
                existing.createdBy(),
                existing.createdAt(),
                actor,
                now);
    }

    @Transactional
    public void delete(String actor, long id) {
        requireActor(actor);
        AnnouncementView existing = findById(id);
        if (existing == null) {
            throw notFound();
        }
        int deleted = jdbc.update("DELETE FROM hermit_announcements WHERE id = ?", id);
        if (deleted != 1) {
            throw notFound();
        }
        auditService.record(actor, AuditAction.ANNOUNCEMENT_DELETED, null, null,
                auditDetail(id, existing.title(), existing.level(), existing.pinned()));
    }

    private AnnouncementView findById(long id) {
        List<AnnouncementView> rows = jdbc.query(
                "SELECT id, title, body, level, pinned, created_by, created_at, updated_by, updated_at "
                        + "FROM hermit_announcements WHERE id = ?",
                ROWS,
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String validateTitle(String raw) {
        if (raw == null) {
            throw invalid("公告标题不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH) {
            throw invalid("公告标题长度需在 1 到 " + MAX_TITLE_LENGTH + " 个字符之间");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw invalid("公告标题不能包含控制字符或换行");
            }
        }
        return trimmed;
    }

    private static String validateBody(String raw) {
        if (raw == null) {
            throw invalid("公告正文不能为空");
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty() || normalized.length() > MAX_BODY_LENGTH) {
            throw invalid("公告正文长度需在 1 到 " + MAX_BODY_LENGTH + " 个字符之间");
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t') || c == 0x7f) {
                throw invalid("公告正文包含非法控制字符");
            }
        }
        return normalized;
    }

    private String auditDetail(long id, String title, AnnouncementLevel level, boolean pinned) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("title", title);
        payload.put("level", level.name());
        payload.put("pinned", pinned);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, ReefErrorCode.INTERNAL, ReefErrorCode.INTERNAL.message());
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, ReefErrorCode.INVALID_INPUT, message);
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }
}
