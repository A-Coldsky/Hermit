package dev.hermit.crab.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Append-only config audit. A read returns who, what, and when. {@code detail} is used only
 * to collect field names and is never returned.
 */
final class ConfigAudit {
    /**
     * Underscore is a LIKE wildcard, and conch names may contain it. Filters use equality and
     * {@code LEFT}/{@code RIGHT} so {@code oa} does not match {@code oa_extra}.
     */
    private static final String FILTER = """
            WHERE (? IS NULL
                OR (kind = 'CREDENTIAL_DECLARATION' AND target = ?)
                OR target = ('OFFICIAL/' || ?)
                OR target = ('LOCAL/' || ?)
                OR LEFT(target, LENGTH('OFFICIAL/' || ? || '/')) = ('OFFICIAL/' || ? || '/')
                OR LEFT(target, LENGTH('LOCAL/' || ? || '/')) = ('LOCAL/' || ? || '/'))
              AND (? IS NULL
                OR (kind IN (
                        'ACCOUNT_CREATED', 'ACCOUNT_DELETED', 'DEFAULT_CHANGED',
                        'CREDENTIALS_WRITTEN', 'TEST_LOGIN',
                        'IDENTITY_BOUND', 'IDENTITY_UNBOUND')
                    AND (LENGTH(target) - LENGTH(REPLACE(target, '/', ''))) = 2
                    AND RIGHT(target, LENGTH('/' || ?)) = ('/' || ?)
                    AND (LEFT(target, LENGTH('OFFICIAL/')) = 'OFFICIAL/'
                        OR LEFT(target, LENGTH('LOCAL/')) = 'LOCAL/')))
            """;

    private static final TargetView EMPTY = new TargetView(null, null, null, null);

    private ConfigAudit() {
    }

    static void append(
            Connection connection,
            String actor,
            AuditKind kind,
            String target,
            String detail
    ) throws SQLException {
        append(connection, Instant.now(), actor, kind, target, detail);
    }

    static void append(
            Connection connection,
            Instant at,
            String actor,
            AuditKind kind,
            String target,
            String detail
    ) throws SQLException {
        Instant timestamp = at == null ? Instant.now() : at;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO config_audit (at, actor, kind, target, detail)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            Db.setInstant(statement, 1, timestamp);
            statement.setString(2, actor);
            statement.setString(3, kind.name());
            statement.setString(4, target);
            statement.setString(5, detail);
            statement.executeUpdate();
        }
    }

    static String target(Origin origin, String conch, String account) {
        return origin.name() + "/" + conch + "/" + account;
    }

    static String target(Origin origin, String conch) {
        return origin.name() + "/" + conch;
    }

    static AuditLog.Page page(Connection connection, String conch, String account, int page, int limit, int offset)
            throws SQLException {
        long total = count(connection, conch, account);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, at, actor, kind, target, detail
                FROM config_audit
                """ + FILTER + """
                ORDER BY at DESC, id DESC
                LIMIT ? OFFSET ?
                """)) {
            int index = bindFilter(statement, conch, account);
            statement.setInt(index, limit);
            statement.setInt(index + 1, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AuditLog.Entry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(entry(resultSet));
                }
                return new AuditLog.Page(List.copyOf(entries), page, limit, total);
            }
        }
    }

    private static long count(Connection connection, String conch, String account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM config_audit " + FILTER)) {
            bindFilter(statement, conch, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static int bindFilter(PreparedStatement statement, String conch, String account) throws SQLException {
        int index = 1;
        for (int i = 0; i < 8; i++) {
            statement.setString(index++, conch);
        }
        for (int i = 0; i < 3; i++) {
            statement.setString(index++, account);
        }
        return index;
    }

    private static AuditLog.Entry entry(ResultSet resultSet) throws SQLException {
        String kind = resultSet.getString("kind");
        String target = resultSet.getString("target");
        TargetView view = view(kind, target);
        return new AuditLog.Entry(
                resultSet.getLong("id"),
                Db.instant(resultSet, "at"),
                resultSet.getString("actor"),
                kind,
                view.origin(),
                view.conch(),
                view.account(),
                fieldNames(resultSet.getString("detail"), view.capability(), kind != null && kind.startsWith("IDENTITY_")));
    }

    /**
     * Names only. Unknown JSON keys, non-strings, and the raw detail text are dropped, including
     * when detail is not JSON.
     */
    private static List<String> fieldNames(String detail, String capability, boolean identity) {
        TreeSet<String> names = new TreeSet<>();
        addName(names, capability);
        JsonNode object = Json.object(detail);
        if (object != null) {
            addNames(names, object.get("fields"));
            addNames(names, object.get("cleared"));
            addNames(names, object.get("addedRequired"));
            addNames(names, object.get("removed"));
            addText(names, object.get("from"));
            addText(names, object.get("to"));
            if (identity) {
                // 身份的名字，以及自动解除时的原因。
                addText(names, object.get("identity"));
                addText(names, object.get("name"));
                addText(names, object.get("reason"));
            }
            JsonNode key = object.get("key");
            if (key != null && key.isTextual()) {
                addName(names, key.textValue());
            }
        }
        return List.copyOf(names);
    }

    private static void addNames(Set<String> names, JsonNode node) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                addName(names, item.textValue());
            }
        }
    }

    private static void addText(Set<String> names, JsonNode node) {
        if (node != null && node.isTextual()) {
            addName(names, node.textValue());
        }
    }

    private static void addName(Set<String> names, String value) {
        if (value == null || value.isBlank() || value.length() > 256 || hasControl(value)) {
            return;
        }
        names.add(value);
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static TargetView view(String kind, String target) {
        if (kind == null || target == null || target.isBlank()) {
            return EMPTY;
        }
        if (AuditKind.CREDENTIAL_DECLARATION.name().equals(kind)) {
            return new TargetView(null, target, null, null);
        }
        if (AuditKind.API_KEY_CREATED.name().equals(kind)
                || AuditKind.API_KEY_REVOKED.name().equals(kind)
                || AuditKind.DEBUG_CHANGED.name().equals(kind)
                || AuditKind.SYNC_REQUESTED.name().equals(kind)
                || AuditKind.CONSOLE_LOGIN.name().equals(kind)) {
            return EMPTY;
        }
        int first = target.indexOf('/');
        if (first <= 0 || first == target.length() - 1) {
            return EMPTY;
        }
        String origin = target.substring(0, first);
        if (!Origin.OFFICIAL.name().equals(origin) && !Origin.LOCAL.name().equals(origin)) {
            return EMPTY;
        }
        String rest = target.substring(first + 1);
        int second = rest.indexOf('/');
        if (second < 0) {
            return new TargetView(origin, rest, null, null);
        }
        if (second == 0 || second == rest.length() - 1 || rest.indexOf('/', second + 1) >= 0) {
            return new TargetView(origin, second == 0 ? null : rest.substring(0, second), null, null);
        }
        String conch = rest.substring(0, second);
        String tail = rest.substring(second + 1);
        if (accountKind(kind)) {
            return new TargetView(origin, conch, tail, null);
        }
        if (capabilityKind(kind)) {
            return new TargetView(origin, conch, null, tail);
        }
        return new TargetView(origin, conch, null, null);
    }

    private static boolean accountKind(String kind) {
        return AuditKind.ACCOUNT_CREATED.name().equals(kind)
                || AuditKind.ACCOUNT_DELETED.name().equals(kind)
                || AuditKind.DEFAULT_CHANGED.name().equals(kind)
                || AuditKind.CREDENTIALS_WRITTEN.name().equals(kind)
                || AuditKind.TEST_LOGIN.name().equals(kind)
                || AuditKind.IDENTITY_BOUND.name().equals(kind)
                || AuditKind.IDENTITY_UNBOUND.name().equals(kind);
    }

    private static boolean capabilityKind(String kind) {
        return AuditKind.CAPABILITY_ENABLED.name().equals(kind)
                || AuditKind.CAPABILITY_DISABLED.name().equals(kind);
    }

    private record TargetView(String origin, String conch, String account, String capability) {
    }
}
