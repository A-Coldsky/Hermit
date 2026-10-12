package dev.hermit.crab.store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AccountStore {
    private final DataSource dataSource;

    public AccountStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Account create(Origin origin, String shell, String account, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedAccount = Checks.account(account);
        String checkedActor = Checks.actor(actor);
        synchronized (ShellLocks.of(dataSource, checkedOrigin, checkedShell)) {
            return Db.transaction(dataSource, connection -> {
                if (find(connection, checkedOrigin, checkedShell, checkedAccount) != null) {
                    throw new IllegalStateException("account already exists");
                }
                boolean first = count(connection, checkedOrigin, checkedShell) == 0;
                Instant now = Instant.now();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO accounts (origin, shell, account, is_default, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, checkedOrigin.name());
                    statement.setString(2, checkedShell);
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
                        checkedActor,
                        AuditKind.ACCOUNT_CREATED,
                        ConfigAudit.target(checkedOrigin, checkedShell, checkedAccount),
                        Json.write(detail));
                return new Account(checkedOrigin, checkedShell, checkedAccount, first, now);
            });
        }
    }

    public void setDefault(Origin origin, String shell, String account, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedAccount = Checks.account(account);
        String checkedActor = Checks.actor(actor);
        synchronized (ShellLocks.of(dataSource, checkedOrigin, checkedShell)) {
            Db.transaction(dataSource, connection -> {
                if (find(connection, checkedOrigin, checkedShell, checkedAccount) == null) {
                    throw new AccountNotFoundException(checkedOrigin, checkedShell, checkedAccount);
                }
                try (PreparedStatement clear = connection.prepareStatement("""
                        UPDATE accounts SET is_default = FALSE
                        WHERE origin = ? AND shell = ? AND is_default = TRUE
                        """)) {
                    clear.setString(1, checkedOrigin.name());
                    clear.setString(2, checkedShell);
                    clear.executeUpdate();
                }
                try (PreparedStatement set = connection.prepareStatement("""
                        UPDATE accounts SET is_default = TRUE
                        WHERE origin = ? AND shell = ? AND account = ?
                        """)) {
                    set.setString(1, checkedOrigin.name());
                    set.setString(2, checkedShell);
                    set.setString(3, checkedAccount);
                    if (set.executeUpdate() != 1) {
                        throw new AccountNotFoundException(checkedOrigin, checkedShell, checkedAccount);
                    }
                }
                ConfigAudit.append(
                        connection,
                        checkedActor,
                        AuditKind.DEFAULT_CHANGED,
                        ConfigAudit.target(checkedOrigin, checkedShell, checkedAccount),
                        Json.write(Map.of("account", checkedAccount)));
                return null;
            });
        }
    }

    public void delete(Origin origin, String shell, String account, String actor) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        String checkedAccount = Checks.account(account);
        String checkedActor = Checks.actor(actor);
        synchronized (ShellLocks.of(dataSource, checkedOrigin, checkedShell)) {
            Db.transaction(dataSource, connection -> {
                Account existing = find(connection, checkedOrigin, checkedShell, checkedAccount);
                if (existing == null) {
                    throw new AccountNotFoundException(checkedOrigin, checkedShell, checkedAccount);
                }
                int others = count(connection, checkedOrigin, checkedShell) - 1;
                if (existing.defaultAccount() && others > 0) {
                    throw new IllegalStateException(
                            "cannot delete the default account while other accounts exist; set a new default first");
                }
                try (PreparedStatement credentials = connection.prepareStatement("""
                        DELETE FROM credentials WHERE origin = ? AND shell = ? AND account = ?
                        """)) {
                    credentials.setString(1, checkedOrigin.name());
                    credentials.setString(2, checkedShell);
                    credentials.setString(3, checkedAccount);
                    credentials.executeUpdate();
                }
                try (PreparedStatement accounts = connection.prepareStatement("""
                        DELETE FROM accounts WHERE origin = ? AND shell = ? AND account = ?
                        """)) {
                    accounts.setString(1, checkedOrigin.name());
                    accounts.setString(2, checkedShell);
                    accounts.setString(3, checkedAccount);
                    accounts.executeUpdate();
                }
                ConfigAudit.append(
                        connection,
                        checkedActor,
                        AuditKind.ACCOUNT_DELETED,
                        ConfigAudit.target(checkedOrigin, checkedShell, checkedAccount),
                        Json.write(Map.of("account", checkedAccount)));
                return null;
            });
        }
    }

    public Account resolve(Origin origin, String shell, Optional<String> account) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        Objects.requireNonNull(account, "account");
        if (account.isPresent()) {
            String name = Checks.account(account.get());
            Account found = Db.transaction(dataSource, connection -> find(connection, checkedOrigin, checkedShell, name));
            if (found == null) {
                throw new AccountNotFoundException(checkedOrigin, checkedShell, name);
            }
            return found;
        }
        List<Account> defaults = Db.transaction(
                dataSource, connection -> listDefaults(connection, checkedOrigin, checkedShell));
        if (defaults.isEmpty()) {
            throw AccountNotFoundException.noDefault(checkedOrigin, checkedShell);
        }
        if (defaults.size() > 1) {
            throw new IllegalStateException("multiple default accounts for " + checkedOrigin + "/" + checkedShell);
        }
        return defaults.get(0);
    }

    public List<Account> list(Origin origin, String shell) {
        Origin checkedOrigin = Checks.origin(origin);
        String checkedShell = Checks.shell(shell);
        return Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT origin, shell, account, is_default, created_at
                    FROM accounts
                    WHERE origin = ? AND shell = ?
                    ORDER BY created_at, account
                    """)) {
                statement.setString(1, checkedOrigin.name());
                statement.setString(2, checkedShell);
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

    private static Account find(Connection connection, Origin origin, String shell, String account) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT origin, shell, account, is_default, created_at
                FROM accounts
                WHERE origin = ? AND shell = ? AND account = ?
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
            statement.setString(3, account);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? read(resultSet) : null;
            }
        }
    }

    private static int count(Connection connection, Origin origin, String shell) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM accounts WHERE origin = ? AND shell = ?")) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static List<Account> listDefaults(Connection connection, Origin origin, String shell) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT origin, shell, account, is_default, created_at
                FROM accounts
                WHERE origin = ? AND shell = ? AND is_default = TRUE
                ORDER BY account
                """)) {
            statement.setString(1, origin.name());
            statement.setString(2, shell);
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
                resultSet.getString("shell"),
                resultSet.getString("account"),
                resultSet.getBoolean("is_default"),
                Db.instant(resultSet, "created_at"));
    }
}
