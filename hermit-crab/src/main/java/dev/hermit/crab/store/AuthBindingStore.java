package dev.hermit.crab.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import dev.hermit.crab.engine.SessionKey;

/**
 * 授权器绑定（schema 23）。绑定另在内存里留一份，调用路径上不查库。
 * 写操作都记 {@code config_audit}。这个类不调用 {@code SessionManager}：改了绑定之后由调用方通知它。
 */
public final class AuthBindingStore {
    private static final Comparator<AuthBinding> ORDER = Comparator
            .comparing((AuthBinding binding) -> binding.account().conch())
            .thenComparing(binding -> binding.account().origin().name())
            .thenComparing(binding -> binding.account().account());

    private final DataSource dataSource;
    private final Clock clock;
    private final ConcurrentHashMap<SessionKey, AuthBinding> bound = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public AuthBindingStore(DataSource dataSource, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        Db.transaction(dataSource, connection -> {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT origin, conch, account, auth_origin, auth_conch, auth_account, bound_base_url, bound_at
                    FROM auth_bindings
                    """);
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    AuthBinding binding = new AuthBinding(
                            new SessionKey(Origin.valueOf(rows.getString(1)), rows.getString(2), rows.getString(3)),
                            new SessionKey(Origin.valueOf(rows.getString(4)), rows.getString(5), rows.getString(6)),
                            rows.getString(7),
                            Db.instant(rows, "bound_at"));
                    bound.put(binding.account(), binding);
                }
            }
            return null;
        });
    }

    /** 这个账号的绑定；没绑为 null。不查库。 */
    public AuthBinding find(SessionKey account) {
        return account == null ? null : bound.get(account);
    }

    public List<AuthBinding> list() {
        ArrayList<AuthBinding> all = new ArrayList<>(bound.values());
        all.sort(ORDER);
        return all;
    }

    /** 绑到这个授权器账号的账号。 */
    public List<AuthBinding> consumersOf(SessionKey authenticator) {
        ArrayList<AuthBinding> found = new ArrayList<>();
        for (AuthBinding binding : bound.values()) {
            if (binding.authenticator().equals(authenticator)) {
                found.add(binding);
            }
        }
        found.sort(ORDER);
        return found;
    }

    /** 绑到这个授权器（某个来源那一份）任意账号的账号。 */
    public List<AuthBinding> consumersOf(Origin origin, String conch) {
        ArrayList<AuthBinding> found = new ArrayList<>();
        for (AuthBinding binding : bound.values()) {
            if (binding.authenticator().origin() == origin && binding.authenticator().conch().equals(conch)) {
                found.add(binding);
            }
        }
        found.sort(ORDER);
        return found;
    }

    /** 这个包（某个来源那一份）绑了授权器的账号。 */
    public List<AuthBinding> bindingsOf(Origin origin, String conch) {
        ArrayList<AuthBinding> found = new ArrayList<>();
        for (AuthBinding binding : bound.values()) {
            if (binding.account().origin() == origin && binding.account().conch().equals(conch)) {
                found.add(binding);
            }
        }
        found.sort(ORDER);
        return found;
    }

    /** 绑定或改绑。返回原来的绑定，原来没绑为 null。 */
    public AuthBinding bind(SessionKey account, SessionKey authenticator, String boundBaseUrl, String actor) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(authenticator, "authenticator");
        Objects.requireNonNull(boundBaseUrl, "boundBaseUrl");
        String checkedActor = Checks.actor(actor);
        Checks.conch(account.conch());
        Checks.account(account.account());
        Checks.conch(authenticator.conch());
        Checks.account(authenticator.account());
        synchronized (writeLock) {
            Instant now = clock.instant();
            AuthBinding created = new AuthBinding(account, authenticator, boundBaseUrl, now);
            Db.transaction(dataSource, connection -> {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auth_bindings WHERE origin = ? AND conch = ? AND account = ?")) {
                    delete.setString(1, account.origin().name());
                    delete.setString(2, account.conch());
                    delete.setString(3, account.account());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO auth_bindings
                        (origin, conch, account, auth_origin, auth_conch, auth_account, bound_base_url, bound_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, account.origin().name());
                    insert.setString(2, account.conch());
                    insert.setString(3, account.account());
                    insert.setString(4, authenticator.origin().name());
                    insert.setString(5, authenticator.conch());
                    insert.setString(6, authenticator.account());
                    insert.setString(7, boundBaseUrl);
                    Db.setInstant(insert, 8, now);
                    insert.executeUpdate();
                }
                LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
                detail.put("authenticator", label(authenticator));
                detail.put("baseUrl", boundBaseUrl);
                ConfigAudit.append(connection, now, checkedActor, AuditKind.AUTH_BOUND, label(account),
                        Json.write(detail));
                return null;
            });
            return bound.put(account, created);
        }
    }

    /** 解除绑定。返回原来的绑定，本来没绑为 null。{@code reason} 写进审计。 */
    public AuthBinding unbind(SessionKey account, String reason, String actor) {
        Objects.requireNonNull(account, "account");
        String checkedActor = Checks.actor(actor);
        synchronized (writeLock) {
            AuthBinding current = bound.get(account);
            if (current == null) {
                return null;
            }
            Instant now = clock.instant();
            Db.transaction(dataSource, connection -> {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auth_bindings WHERE origin = ? AND conch = ? AND account = ?")) {
                    delete.setString(1, account.origin().name());
                    delete.setString(2, account.conch());
                    delete.setString(3, account.account());
                    delete.executeUpdate();
                }
                LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
                detail.put("authenticator", label(current.authenticator()));
                if (reason != null) {
                    detail.put("reason", reason);
                }
                ConfigAudit.append(connection, now, checkedActor, AuditKind.AUTH_UNBOUND, label(account),
                        Json.write(detail));
                return null;
            });
            bound.remove(account);
            return current;
        }
    }

    private static String label(SessionKey key) {
        return key.origin().name() + "/" + key.conch() + "/" + key.account();
    }
}
