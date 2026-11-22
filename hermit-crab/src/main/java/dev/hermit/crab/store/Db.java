package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class Db {
    private Db() {
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private record Joined(DataSource dataSource, Connection connection) {
    }

    private static final ThreadLocal<Joined> JOINED = new ThreadLocal<>();

    /**
     * 把这段代码里对同一个库的所有 {@link #transaction} 并进一个事务：全部成功才提交，任何一步抛出就全部回滚。
     * 只在当前线程有效。
     */
    public static <T> T atomic(DataSource dataSource, java.util.function.Supplier<T> work) {
        Joined outer = JOINED.get();
        if (outer != null && outer.dataSource() == dataSource) {
            return work.get();
        }
        return transaction(dataSource, connection -> {
            JOINED.set(new Joined(dataSource, connection));
            try {
                return work.get();
            } finally {
                if (outer == null) {
                    JOINED.remove();
                } else {
                    JOINED.set(outer);
                }
            }
        });
    }

    public static <T> T transaction(DataSource dataSource, Work<T> work) {
        Joined joined = JOINED.get();
        if (joined != null && joined.dataSource() == dataSource) {
            try {
                return work.run(joined.connection());
            } catch (SQLException ex) {
                throw new IllegalStateException("database operation failed", ex);
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Throwable ex) {
                rollback(connection, ex);
                if (ex instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (ex instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("database operation failed", ex);
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("database operation failed", ex);
        }
    }

    public static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
        statement.setObject(index, OffsetDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException ex) {
            failure.addSuppressed(ex);
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ex) {
            // Returning the connection to the pool must not hide the original failure.
        }
    }
}
