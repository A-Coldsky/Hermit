package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Config audit. {@link #record} appends a row. {@link #page} reads it back as who/what/when:
 * actor, action, origin, conch, account, time, and field names. Secret values and the stored
 * detail document are not part of the result.
 */
public final class AuditLog {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    /** {@code page} below 1, or too large to page. HTTP 400 {@code INVALID_INPUT}. */
    public static final String PAGE_OUT_OF_RANGE = "页码超出范围。";

    private AuditLog() {
    }

    public static void record(DataSource dataSource, String actor, AuditKind kind, String target, String detail) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(detail, "detail");
        String checkedActor = Checks.actor(actor);
        Db.transaction(dataSource, connection -> {
            ConfigAudit.append(connection, checkedActor, kind, target, detail);
            return null;
        });
    }

    /**
     * Newest first ({@code at}, then {@code id}). Blank conch or account means no filter.
     * {@code page} is 1-based. {@code limit} is from 1 through {@link #MAX_LIMIT}.
     */
    public static Page page(DataSource dataSource, String conch, String account, int page, int limit) {
        Objects.requireNonNull(dataSource, "dataSource");
        String conchFilter = conchFilter(conch);
        String accountFilter = accountFilter(account);
        if (page < 1) {
            throw new IllegalArgumentException(PAGE_OUT_OF_RANGE);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit is out of range");
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, limit);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(PAGE_OUT_OF_RANGE);
        }
        return Db.transaction(
                dataSource,
                connection -> ConfigAudit.page(connection, conchFilter, accountFilter, page, limit, offset));
    }

    private static String conchFilter(String conch) {
        if (conch == null || conch.isBlank()) {
            return null;
        }
        if (conch.length() > 256 || conch.indexOf('/') >= 0 || conch.indexOf('\\') >= 0 || hasControl(conch)) {
            throw new IllegalArgumentException("conch is invalid");
        }
        return conch;
    }

    private static String accountFilter(String account) {
        if (account == null || account.isBlank()) {
            return null;
        }
        return Checks.account(account);
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

    public record Entry(
            long id,
            Instant at,
            String actor,
            String action,
            String origin,
            String conch,
            String account,
            List<String> fields
    ) {
    }

    public record Page(List<Entry> entries, int page, int limit, long total) {
    }
}
