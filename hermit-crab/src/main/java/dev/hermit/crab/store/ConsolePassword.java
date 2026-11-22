package dev.hermit.crab.store;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.sql.DataSource;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Optional console password. Only the PBKDF2 hash is stored. {@code config.yml} is not used.
 * An empty environment value means unset. A hash already in the database is left alone.
 */
public final class ConsolePassword {
    public static final String ENV = "HERMIT_CONSOLE_PASSWORD";
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 256;
    public static final int ITERATIONS = 210_000;
    public static final int SALT_BYTES = 16;
    public static final int HASH_BYTES = 32;
    public static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    public static final int MAX_FAILURES = 5;
    /** Wrong password and lockout share this message. */
    public static final String FAILURE_MESSAGE = "口令错误";
    public static final String LENGTH_MESSAGE = "口令至少 8 个字符，至多 256 个字符。";
    public static final String UNSET_MESSAGE = "尚未设置控制台口令。";
    public static final String ENV_LENGTH_MESSAGE = "HERMIT_CONSOLE_PASSWORD 长度必须是 8 到 256 个字符。";

    /**
     * Stored iteration counts above this are treated as a mismatch and are not run.
     * Rows written by this class always use {@link #ITERATIONS}.
     */
    private static final int MAX_RUN_ITERATIONS = 1_000_000;
    private static final int ROW_ID = 1;

    private final DataSource dataSource;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Object verifyLock = new Object();

    public ConsolePassword(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public enum Result {
        UNSET,
        ACCEPTED,
        REJECTED
    }

    public boolean isSet() {
        return Db.transaction(dataSource, connection -> select(connection) != null);
    }

    /**
     * Hashes {@code HERMIT_CONSOLE_PASSWORD} once when the database has no row.
     * Null and empty are unset. A value that is too short or too long refuses startup
     * and does not include the password in the message.
     */
    public void applyInitial(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (isSet()) {
            return;
        }
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(ENV_LENGTH_MESSAGE);
        }
        set(value);
    }

    public void set(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(LENGTH_MESSAGE);
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        char[] chars = password.toCharArray();
        byte[] hash;
        try {
            hash = pbkdf2(chars, salt, ITERATIONS);
        } finally {
            Arrays.fill(chars, '\0');
        }
        synchronized (verifyLock) {
            Db.transaction(dataSource, connection -> {
                upsert(connection, salt, hash, ITERATIONS, clock.instant());
                deleteFailures(connection);
                return null;
            });
        }
        Arrays.fill(hash, (byte) 0);
        Arrays.fill(salt, (byte) 0);
    }

    /**
     * Checks the password. Five failures inside {@link #LOCK_WINDOW} lock further attempts.
     * A locked attempt does not insert another failure row, so the window is not pushed forward.
     * The fifth failure itself is stored; the lock starts on the next attempt. Correct and
     * incorrect passwords are both rejected while locked.
     */
    public Result verify(String password) {
        String attempt = password == null ? "" : password;
        synchronized (verifyLock) {
            return Db.transaction(dataSource, connection -> {
                Stored stored = select(connection);
                if (stored == null) {
                    return Result.UNSET;
                }
                Instant now = clock.instant();
                deleteExpired(connection, now);
                boolean locked = countRecent(connection, now) >= MAX_FAILURES;
                boolean matches = matches(attempt, stored);
                if (!matches || locked) {
                    if (!locked) {
                        insertFailure(connection, now);
                    }
                    return Result.REJECTED;
                }
                deleteFailures(connection);
                return Result.ACCEPTED;
            });
        }
    }

    private static boolean matches(String password, Stored stored) {
        if (password.length() < 1 || password.length() > MAX_LENGTH) {
            return false;
        }
        if (stored.salt().length != SALT_BYTES || stored.hash().length != HASH_BYTES) {
            return false;
        }
        if (stored.iterations() < 1 || stored.iterations() > MAX_RUN_ITERATIONS) {
            return false;
        }
        char[] chars = password.toCharArray();
        byte[] computed = pbkdf2(chars, stored.salt(), stored.iterations());
        Arrays.fill(chars, '\0');
        try {
            return MessageDigest.isEqual(computed, stored.hash());
        } finally {
            Arrays.fill(computed, (byte) 0);
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("password hash failed");
        } finally {
            spec.clearPassword();
        }
    }

    private static Stored select(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT salt, hash, iterations FROM console_password WHERE id = ?")) {
            statement.setInt(1, ROW_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                byte[] salt = resultSet.getBytes("salt");
                byte[] hash = resultSet.getBytes("hash");
                return new Stored(
                        salt == null ? new byte[0] : salt,
                        hash == null ? new byte[0] : hash,
                        resultSet.getInt("iterations"));
            }
        }
    }

    private static void upsert(Connection connection, byte[] salt, byte[] hash, int iterations, Instant updatedAt)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE console_password SET salt = ?, hash = ?, iterations = ?, updated_at = ? WHERE id = ?
                """)) {
            update.setBytes(1, salt);
            update.setBytes(2, hash);
            update.setInt(3, iterations);
            Db.setInstant(update, 4, updatedAt);
            update.setInt(5, ROW_ID);
            if (update.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO console_password (id, salt, hash, iterations, updated_at) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setInt(1, ROW_ID);
            insert.setBytes(2, salt);
            insert.setBytes(3, hash);
            insert.setInt(4, iterations);
            Db.setInstant(insert, 5, updatedAt);
            insert.executeUpdate();
        }
    }

    private static int countRecent(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM console_login_failures WHERE failed_at > ?")) {
            Db.setInstant(statement, 1, now.minus(LOCK_WINDOW));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static void deleteExpired(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM console_login_failures WHERE failed_at <= ?")) {
            Db.setInstant(statement, 1, now.minus(LOCK_WINDOW));
            statement.executeUpdate();
        }
    }

    private static void insertFailure(Connection connection, Instant failedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO console_login_failures (failed_at) VALUES (?)")) {
            Db.setInstant(statement, 1, failedAt);
            statement.executeUpdate();
        }
    }

    private static void deleteFailures(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM console_login_failures")) {
            statement.executeUpdate();
        }
    }

    private record Stored(byte[] salt, byte[] hash, int iterations) {
    }
}
