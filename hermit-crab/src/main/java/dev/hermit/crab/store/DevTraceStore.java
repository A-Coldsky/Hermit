package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

import dev.hermit.crab.engine.DevTraceDraft;
import dev.hermit.crab.secure.Redactor;

/**
 * Redacted developer traces. Rows live only while the debug switch is on.
 * Turning the switch off, or letting it expire, deletes every row in the same
 * transaction as the flag change. {@link #recordJsonRpc} is called by MCP
 * forwarding. Several round trips with the same trace id accumulate in one body.
 * The HTTP engine does not call it.
 */
public final class DevTraceStore {
    /** Refresh is not a capability call. A forwarder may store a trace under this action and no call log. */
    public static final String MCP_REFRESH_ACTION = "mcp_refresh";

    private static final Logger LOG = Logger.getLogger(DevTraceStore.class.getName());

    private final DataSource dataSource;
    private final Clock clock;
    private final DebugStateStore debug;

    public DevTraceStore(DataSource dataSource, Clock clock, DebugStateStore debug) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.debug = Objects.requireNonNull(debug, "debug");
    }

    public void save(NewTrace trace) {
        Objects.requireNonNull(trace, "trace");
        if (!fits(trace.traceId(), trace.conch(), trace.account(), trace.action()) || trace.body() == null) {
            return;
        }
        Db.transaction(dataSource, connection -> {
            write(connection, trace, clock.instant());
            return null;
        });
    }

    /**
     * Stores one JSON-RPC exchange when the debug switch is on and the conch is local.
     * Later exchanges with the same trace id, conch, account, and action are appended.
     * Official packages and a switch that is off are ignored. This does not write {@code call_logs}.
     * {@code secrets} are used only to redact and are not stored. {@code Mcp-Session-Id} is dropped.
     */
    public void recordJsonRpc(JsonRpcTrace trace) {
        if (trace == null || trace.origin() != Origin.LOCAL) {
            return;
        }
        String action = trace.action() == null || trace.action().isBlank() ? MCP_REFRESH_ACTION : trace.action();
        String account = trace.account() == null ? "" : trace.account();
        String conch = trace.conch() == null ? "" : trace.conch();
        if (!fits(trace.traceId(), conch, account, action)) {
            return;
        }
        DebugStateStore.DebugSnapshot snapshot = debug.get();
        if (!snapshot.enabled()) {
            return;
        }
        try {
            LinkedHashSet<String> secrets = new LinkedHashSet<>();
            if (trace.secrets() != null) {
                for (String secret : trace.secrets()) {
                    if (secret != null) {
                        secrets.add(secret);
                    }
                }
            }
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("method", trace.method());
            fields.put("id", trace.id());
            fields.put("httpStatus", trace.httpStatus());
            fields.put("durationMs", trace.durationMs());
            fields.put("params", trace.params());
            fields.put("result", trace.result());
            fields.put("error", trace.error());
            if (trace.error() instanceof Map<?, ?> error && error.get("transport") instanceof Map<?, ?> transport) {
                fields.put("transport", transport);
            }
            String rendered = DevTraceDraft.renderJsonRpc(fields, Redactor.of(secrets));
            Db.transaction(dataSource, connection -> {
                String prior = compatibleBody(connection, trace.traceId(), conch, account, action);
                String body = prior == null ? rendered : DevTraceDraft.mergeJsonRpc(prior, rendered);
                write(connection, new NewTrace(
                        trace.traceId(), Origin.LOCAL, conch, account, action, snapshot.expiresAt(), body),
                        clock.instant());
                return null;
            });
        } catch (RuntimeException ex) {
            LOG.warning("jsonrpc trace failed: " + ex.getClass().getName());
        }
    }

    public Optional<Trace> find(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT trace_id, origin, conch, account, action, created_at, expires_at, body
                    FROM dev_traces WHERE trace_id = ?
                    """)) {
                statement.setString(1, traceId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(read(resultSet));
                }
            }
        });
    }

    public int count() {
        return Db.transaction(dataSource, connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM dev_traces")) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        });
    }

    public int deleteAll() {
        return Db.transaction(dataSource, DevTraceStore::deleteAll);
    }

    public int deleteExpired(Instant now) {
        Instant cutoff = now == null ? clock.instant() : now;
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM dev_traces WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
                Db.setInstant(statement, 1, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    static int deleteAll(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate("DELETE FROM dev_traces");
        }
    }

    static List<String> ids(Connection connection) throws SQLException {
        ArrayList<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT trace_id FROM dev_traces")) {
            while (resultSet.next()) {
                ids.add(resultSet.getString(1));
            }
        }
        return ids;
    }

    private static void write(Connection connection, NewTrace trace, Instant createdAt) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE dev_traces
                SET origin = ?, conch = ?, account = ?, action = ?, created_at = ?, expires_at = ?, body = ?
                WHERE trace_id = ?
                """)) {
            bind(update, trace, createdAt);
            update.setString(8, trace.traceId());
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO dev_traces (trace_id, origin, conch, account, action, created_at, expires_at, body)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, trace.traceId());
            insert.setString(2, trace.origin().name());
            insert.setString(3, trace.conch());
            insert.setString(4, trace.account());
            insert.setString(5, trace.action());
            Db.setInstant(insert, 6, createdAt);
            if (trace.expiresAt() == null) {
                insert.setObject(7, null);
            } else {
                Db.setInstant(insert, 7, trace.expiresAt());
            }
            insert.setString(8, trace.body());
            insert.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, NewTrace trace, Instant createdAt) throws SQLException {
        statement.setString(1, trace.origin().name());
        statement.setString(2, trace.conch());
        statement.setString(3, trace.account());
        statement.setString(4, trace.action());
        Db.setInstant(statement, 5, createdAt);
        if (trace.expiresAt() == null) {
            statement.setObject(6, null);
        } else {
            Db.setInstant(statement, 6, trace.expiresAt());
        }
        statement.setString(7, trace.body());
    }

    private static Trace read(ResultSet resultSet) throws SQLException {
        return new Trace(
                resultSet.getString("trace_id"),
                Origin.valueOf(resultSet.getString("origin")),
                resultSet.getString("conch"),
                resultSet.getString("account"),
                resultSet.getString("action"),
                Db.instant(resultSet, "created_at"),
                Db.instant(resultSet, "expires_at"),
                resultSet.getString("body"));
    }

    /** {@code null} when the row is absent. A mismatched row is not merged; the caller overwrites it. */
    private static String compatibleBody(
            Connection connection,
            String traceId,
            String conch,
            String account,
            String action
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT origin, conch, account, action, body FROM dev_traces WHERE trace_id = ?
                """)) {
            statement.setString(1, traceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String storedAccount = resultSet.getString("account");
                if (storedAccount == null) {
                    storedAccount = "";
                }
                boolean same = Origin.LOCAL.name().equals(resultSet.getString("origin"))
                        && conch.equals(resultSet.getString("conch"))
                        && account.equals(storedAccount)
                        && action.equals(resultSet.getString("action"));
                if (!same) {
                    return null;
                }
                return resultSet.getString("body");
            }
        }
    }

    private static boolean fits(String traceId, String conch, String account, String action) {
        return traceId != null && !traceId.isBlank() && traceId.length() <= 64
                && conch != null && !conch.isBlank() && conch.length() <= 256
                && account != null && account.length() <= 64
                && action != null && !action.isBlank() && action.length() <= 256;
    }

    public record NewTrace(
            String traceId,
            Origin origin,
            String conch,
            String account,
            String action,
            Instant expiresAt,
            String body
    ) {
    }

    public record Trace(
            String traceId,
            Origin origin,
            String conch,
            String account,
            String action,
            Instant createdAt,
            Instant expiresAt,
            String body
    ) {
    }

    /**
     * One upstream JSON-RPC exchange. {@code secrets} redact the body and are not written.
     * Leave {@code action} blank to store {@link #MCP_REFRESH_ACTION}.
     */
    public record JsonRpcTrace(
            String traceId,
            Origin origin,
            String conch,
            String account,
            String action,
            String method,
            Object id,
            Integer httpStatus,
            Long durationMs,
            Object params,
            Object result,
            Object error,
            List<String> secrets
    ) {
    }
}
