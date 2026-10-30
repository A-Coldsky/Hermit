package dev.hermit.crab.store;

import dev.hermit.crab.engine.StepLog;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Call and step logs. Callers must pass an already redacted error message; this store truncates
 * it to 500 characters and does not accept input or output payloads.
 */
public final class CallLogStore {
    public static final int ERROR_MESSAGE_LIMIT = 500;
    public static final int LIST_LIMIT = 500;

    private final DataSource dataSource;
    private final Clock clock;

    public CallLogStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public long append(NewCall call, List<StepLog> steps) {
        Objects.requireNonNull(call, "call");
        List<StepLog> snapshot = steps == null ? List.of() : List.copyOf(steps);
        return Db.transaction(dataSource, connection -> {
            long id = insertCall(connection, call);
            insertSteps(connection, id, snapshot);
            return id;
        });
    }

    /**
     * {@code action} is either the stored action name or {@code conch.action}, split on the first
     * dot. When {@code conch} is also set and names a different package, this throws
     * {@link IllegalArgumentException} so the console can answer 400 rather than an empty page.
     * The message names the parameters and does not repeat the query values.
     */
    public List<CallSummary> list(String conch, String action, int limit) {
        int capped = Math.min(LIST_LIMIT, Math.max(1, limit));
        CallFilter filter = callFilter(blankToNull(conch), blankToNull(action));
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, trace_id, at, origin, conch, action, account, conch_version,
                           success, error_code, error_message, duration_ms, api_key_id, source
                    FROM call_logs
                    WHERE (? IS NULL OR conch = ?)
                      AND (? IS NULL OR action = ?)
                    ORDER BY at DESC, id DESC
                    LIMIT ?
                    """)) {
                statement.setString(1, filter.conch());
                statement.setString(2, filter.conch());
                statement.setString(3, filter.action());
                statement.setString(4, filter.action());
                statement.setInt(5, capped);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<CallSummary> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        rows.add(readSummary(resultSet));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    public Optional<CallDetail> findByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        return Db.transaction(dataSource, connection -> {
            CallSummary summary;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, trace_id, at, origin, conch, action, account, conch_version,
                           success, error_code, error_message, duration_ms, api_key_id, source
                    FROM call_logs
                    WHERE trace_id = ?
                    ORDER BY at DESC, id DESC
                    LIMIT 1
                    """)) {
                statement.setString(1, traceId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    summary = readSummary(resultSet);
                }
            }
            return Optional.of(new CallDetail(summary, steps(connection, summary.id())));
        });
    }

    public int purgeBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement steps = connection.prepareStatement("""
                    DELETE FROM step_logs WHERE call_id IN (SELECT id FROM call_logs WHERE at < ?)
                    """)) {
                Db.setInstant(steps, 1, cutoff);
                steps.executeUpdate();
            }
            try (PreparedStatement calls = connection.prepareStatement("DELETE FROM call_logs WHERE at < ?")) {
                Db.setInstant(calls, 1, cutoff);
                return calls.executeUpdate();
            }
        });
    }

    private long insertCall(Connection connection, NewCall call) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO call_logs (
                    trace_id, at, origin, conch, action, account, conch_version,
                    success, error_code, error_message, duration_ms, api_key_id, source
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, call.traceId());
            Db.setInstant(statement, 2, clock.instant());
            statement.setString(3, call.origin().name());
            statement.setString(4, call.conch());
            statement.setString(5, call.action());
            statement.setString(6, call.account());
            statement.setString(7, call.conchVersion());
            statement.setBoolean(8, call.success());
            statement.setString(9, call.errorCode());
            statement.setString(10, truncate(call.errorMessage()));
            statement.setLong(11, call.durationMs());
            statement.setString(12, call.apiKeyId());
            statement.setString(13, sourceOf(call.origin()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("call log id was not generated");
                }
                return keys.getLong(1);
            }
        }
    }

    private static void insertSteps(Connection connection, long callId, List<StepLog> steps) throws SQLException {
        if (steps.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO step_logs (call_id, idx, type, duration_ms, method, path, status, extract_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (StepLog step : steps) {
                statement.setLong(1, callId);
                statement.setInt(2, step.index());
                statement.setString(3, clip(step.type(), 32));
                statement.setLong(4, step.durationMillis());
                statement.setString(5, clip(step.method(), 16));
                statement.setString(6, clip(step.path(), 2048));
                if (step.status() == null) {
                    statement.setObject(7, null);
                } else {
                    statement.setInt(7, step.status());
                }
                if (step.extractCount() == null) {
                    statement.setObject(8, null);
                } else {
                    statement.setInt(8, step.extractCount());
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<StepRow> steps(Connection connection, long callId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT idx, type, duration_ms, method, path, status, extract_count
                FROM step_logs
                WHERE call_id = ?
                ORDER BY idx
                """)) {
            statement.setLong(1, callId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StepRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    int status = resultSet.getInt("status");
                    Integer statusValue = resultSet.wasNull() ? null : status;
                    int extracted = resultSet.getInt("extract_count");
                    Integer extractValue = resultSet.wasNull() ? null : extracted;
                    rows.add(new StepRow(
                            resultSet.getInt("idx"),
                            resultSet.getString("type"),
                            resultSet.getLong("duration_ms"),
                            resultSet.getString("method"),
                            resultSet.getString("path"),
                            statusValue,
                            extractValue));
                }
                return List.copyOf(rows);
            }
        }
    }

    private static CallSummary readSummary(ResultSet resultSet) throws SQLException {
        return new CallSummary(
                resultSet.getLong("id"),
                resultSet.getString("trace_id"),
                Db.instant(resultSet, "at"),
                resultSet.getString("origin"),
                resultSet.getString("conch"),
                resultSet.getString("action"),
                resultSet.getString("account"),
                resultSet.getString("conch_version"),
                resultSet.getBoolean("success"),
                resultSet.getString("error_code"),
                resultSet.getString("error_message"),
                resultSet.getLong("duration_ms"),
                resultSet.getString("api_key_id"),
                sourceOrReef(resultSet.getString("source")));
    }

    /** Console and Tide use {@code LOCAL} or {@code REEF}. Official calls are {@code REEF}. */
    static String sourceOf(Origin origin) {
        return origin == Origin.LOCAL ? "LOCAL" : "REEF";
    }

    private static String sourceOrReef(String source) {
        return "LOCAL".equals(source) ? "LOCAL" : "REEF";
    }

    static String truncate(String value) {
        return clip(value, ERROR_MESSAGE_LIMIT);
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    static CallFilter callFilter(String conch, String action) {
        if (action == null) {
            return new CallFilter(conch, null);
        }
        int dot = action.indexOf('.');
        if (dot < 0) {
            return new CallFilter(conch, action);
        }
        String qualifiedConch = action.substring(0, dot);
        String qualifiedAction = action.substring(dot + 1);
        if (qualifiedConch.isBlank() || qualifiedAction.isBlank()) {
            throw new IllegalArgumentException("action 须为能力名或 conch.action");
        }
        if (conch != null && !conch.equals(qualifiedConch)) {
            throw new IllegalArgumentException("action 与 conch 参数不一致");
        }
        return new CallFilter(qualifiedConch, qualifiedAction);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record CallFilter(String conch, String action) {
    }

    public record NewCall(
            String traceId,
            Origin origin,
            String conch,
            String action,
            String account,
            String conchVersion,
            boolean success,
            String errorCode,
            String errorMessage,
            long durationMs,
            String apiKeyId
    ) {
    }

    public record CallSummary(
            long id,
            String traceId,
            Instant at,
            String origin,
            String conch,
            String action,
            String account,
            String conchVersion,
            boolean success,
            String errorCode,
            String errorMessage,
            long durationMs,
            String apiKeyId,
            String source
    ) {
    }

    public record StepRow(
            int index,
            String type,
            long durationMs,
            String method,
            String path,
            Integer status,
            Integer extractCount
    ) {
    }

    public record CallDetail(CallSummary call, List<StepRow> steps) {
        public CallDetail {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
}
