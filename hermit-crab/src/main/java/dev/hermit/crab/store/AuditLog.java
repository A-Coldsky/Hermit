package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Config audit. {@link #record} appends a row. {@link #page} reads it back as who/what/when:
 * actor, action, origin, shell, account, time, and field names. Secret values and the stored
 * detail document are not part of the result.
 */
public final class AuditLog {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

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
     * Newest first ({@code at}, then {@code id}). Blank shell or account means no filter.
     * {@code page} is 1-based. {@code limit} is from 1 through {@link #MAX_LIMIT}.
     */
    public static Page page(DataSource dataSource, String shell, String account, int page, int limit) {
        Objects.requireNonNull(dataSource, "dataSource");
        String shellFilter = shellFilter(shell);
        String accountFilter = accountFilter(account);
        if (page < 1) {
            throw new IllegalArgumentException("page is out of range");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit is out of range");
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, limit);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("page is out of range");
        }
        return Db.transaction(
                dataSource,
                connection -> ConfigAudit.page(connection, shellFilter, accountFilter, page, limit, offset));
    }

    private static String shellFilter(String shell) {
        if (shell == null || shell.isBlank()) {
            return null;
        }
        if (shell.length() > 256 || shell.indexOf('/') >= 0 || shell.indexOf('\\') >= 0 || hasControl(shell)) {
            throw new IllegalArgumentException("shell is invalid");
        }
        return shell;
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
            String shell,
            String account,
            List<String> fields
    ) {
    }

    public record Page(List<Entry> entries, int page, int limit, long total) {
    }
}
