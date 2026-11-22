package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AccountStore {
    private final DataSource dataSource;
    private final Clock clock;

    public AccountStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public AccountStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Account create(Origin origin, String conch, String account, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedAccount = Checks.account(account);
        String checkedActor = Checks.actor(actor);
        synchronized (ConchLocks.of(dataSource, checkedOrigin, checkedConch)) {
            return Db.transaction(dataSource, connection -> {
                if (find(connection, checkedOrigin, checkedConch, checkedAccount) != null) {
                    throw new IllegalStateException("account already exists");
                }
                boolean first = count(connection, checkedOrigin, checkedConch) == 0;
                Instant now = clock.instant();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO accounts (origin, conch, account, is_default, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, checkedOrigin.name());
                    statement.setString(2, checkedConch);
                    statement.setString(3, checkedAccount);
                    statement.setBoolean(4, first);
                    Db.setInstant(statement, 5, now);
                    statement.executeUpdate();
                }
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("account", checkedAccount);
                detail.put("default", first);
                ConfigAudit.append(
                        connection,
                        now,
                        checkedActor,
                        AuditKind.ACCOUNT_CREATED,
                        ConfigAudit.target(checkedOrigin, checkedConch, checkedAccount),
                        Json.write(detail));
                return new Account(checkedOrigin, checkedConch, checkedAccount, first, now);
            });
        }
    }

    public void setDefault(Origin origin, String conch, String account, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedAccount = Checks.account(account);
        String checkedActor = Checks.actor(actor);
        synchronized (ConchLocks.of(dataSource, checkedOrigin, checkedConch)) {
            Db.transaction(dataSource, connection -> {
                if (find(connection, checkedOrigin, checkedConch, checkedAccount) == null) {
                    throw new AccountNotFoundException(checkedOrigin, checkedConch, checkedAccount);
                }
                try (PreparedStatement clear = connection.prepareStatement("""
                        UPDATE accounts SET is_default = FALSE
                        WHERE origin = ? AND conch = ? AND is_default = TRUE
                        """)) {
                    clear.setString(1, checkedOrigin.name());
                    clear.setString(2, checkedConch);
                    clear.executeUpdate();
                }
                try (PreparedStatement set = connection.prepareStatement("""
                        UPDATE accounts SET is_default = TRUE
                        WHERE origin = ? AND conch = ? AND account = ?
                        """)) {
                    set.setString(1, checkedOrigin.name());
                    set.setString(2, checkedConch);
                    set.setString(3, checkedAccount);
                    if (set.executeUpdate() != 1) {
                        throw new AccountNotFoundException(checkedOrigin, checkedConch, checkedAccount);
                    }
                }
                ConfigAudit.append(
                        connection,
                        clock.instant(),
                        checkedActor,
                        AuditKind.DEFAULT_CHANGED,
                        ConfigAudit.target(checkedOrigin, checkedConch, checkedAccount),
                        Json.write(Map.of("account", checkedAccount)));
                return null;
            });
        }
    }

    public void delete(Origin origin, String conch, String account, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        String checkedAccount = Checks.account(account);
        String checkedActor = Checks.actor(actor);
        synchronized (ConchLocks.of(dataSource, checkedOrigin, checkedConch)) {
            Db.transaction(dataSource, connection -> {
                Account existing = find(connection, checkedOrigin, checkedConch, checkedAccount);
                if (existing == null) {
                    throw new AccountNotFoundException(checkedOrigin, checkedConch, checkedAccount);
                }
                int others = count(connection, checkedOrigin, checkedConch) - 1;
                if (existing.defaultAccount() && others > 0) {
                    throw new IllegalStateException(
                            "cannot delete the default account while other accounts exist; set a new default first");
                }
                try (PreparedStatement credentials = connection.prepareStatement("""
                        DELETE FROM credentials WHERE origin = ? AND conch = ? AND account = ?
                        """)) {
                    credentials.setString(1, checkedOrigin.name());
                    credentials.setString(2, checkedConch);
                    credentials.setString(3, checkedAccount);
                    credentials.executeUpdate();
                }
                try (PreparedStatement accounts = connection.prepareStatement("""
                        DELETE FROM accounts WHERE origin = ? AND conch = ? AND account = ?
                        """)) {
                    accounts.setString(1, checkedOrigin.name());
                    accounts.setString(2, checkedConch);
                    accounts.setString(3, checkedAccount);
                    accounts.executeUpdate();
                }
                ConfigAudit.append(
                        connection,
                        clock.instant(),
                        checkedActor,
                        AuditKind.ACCOUNT_DELETED,
                        ConfigAudit.target(checkedOrigin, checkedConch, checkedAccount),
                        Json.write(Map.of("account", checkedAccount)));
                return null;
            });
        }
    }

    public Account resolve(Origin origin, String conch, Optional<String> account) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        Objects.requireNonNull(account, "account");
        if (account.isPresent()) {
            String name = Checks.account(account.get());
            Account found = Db.transaction(dataSource, connection -> find(connection, checkedOrigin, checkedConch, name));
            if (found == null) {
                throw new AccountNotFoundException(checkedOrigin, checkedConch, name);
            }
            return found;
        }
        List<Account> defaults = Db.transaction(
                dataSource, connection -> listDefaults(connection, checkedOrigin, checkedConch));
        if (defaults.isEmpty()) {
            throw AccountNotFoundException.noDefault(checkedOrigin, checkedConch);
        }
        if (defaults.size() > 1) {
            throw new IllegalStateException("multiple default accounts for " + checkedOrigin + "/" + checkedConch);
        }
        return defaults.get(0);
    }

    public List<Account> list(Origin origin, String conch) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedConch = Checks.conch(conch);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT origin, conch, account, is_default, created_at
                    FROM accounts
                    WHERE origin = ? AND conch = ?
                    ORDER BY created_at, account
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedConch);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Account> accounts = new ArrayList<>();
                    while (resultSet.next()) {
                        accounts.add(read(resultSet));
                    }
                    return List.copyOf(accounts);
                }
            }
        });
    }

    private static Account find(Connection connection, Origin origin, String conch, String account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT origin, conch, account, is_default, created_at
                FROM accounts
                WHERE origin = ? AND conch = ? AND account = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            statement.setString(3, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? read(resultSet) : null;
            }
        }
    }

    private static int count(Connection connection, Origin origin, String conch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM accounts WHERE origin = ? AND conch = ?")) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static List<Account> listDefaults(Connection connection, Origin origin, String conch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT origin, conch, account, is_default, created_at
                FROM accounts
                WHERE origin = ? AND conch = ? AND is_default = TRUE
                ORDER BY account
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, conch);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Account> defaults = new ArrayList<>();
                while (resultSet.next()) {
                    defaults.add(read(resultSet));
                }
                return defaults;
            }
        }
    }

    private static Account read(ResultSet resultSet) throws SQLException {
        return new Account(
                Origin.valueOf(resultSet.getString("origin")),
                resultSet.getString("conch"),
                resultSet.getString("account"),
                resultSet.getBoolean("is_default"),
                Db.instant(resultSet, "created_at"));
    }
}
