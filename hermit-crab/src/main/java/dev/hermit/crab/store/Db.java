package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class Db {
    private Db() {
    }

    @FunctionalInterface
    interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    static <T> T transaction(DataSource dataSource, Work<T> work) {
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

    static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static void setInstant(PreparedStatement statement, int index, Instant instant) throws SQLException {
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
