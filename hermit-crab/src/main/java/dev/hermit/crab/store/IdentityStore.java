package dev.hermit.crab.store;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.SharedSessions;
import dev.hermit.crab.engine.StoredCookie;
import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.CredentialDecryptException;

/**
 * 身份、成员和身份的 Cookie（schema 21）。成员关系另在内存里留一份，调用路径上不查库。
 * 写操作都记 {@code config_audit}。这个类不调用 {@code SessionManager}：改了成员之后由调用方通知它。
 */
public final class IdentityStore implements SharedSessions {
    /** 身份 Cookie 的加密关联数据。包名不能含 {@code ~}，不会和账号自己的会话撞上。 */
    private static final String CIPHER_CONCH = "~identity";
    private static final String FIELD = "identity_session";
    private static final int MAX_NAME = 64;
    public static final int MAX_IDENTITIES = 200;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<StoredCookie>> COOKIES = new TypeReference<>() {
    };
    private static final Comparator<SessionKey> ORDER = Comparator
            .comparing((SessionKey key) -> key.conch())
            .thenComparing(key -> key.origin().name())
            .thenComparing(SessionKey::account);

    private final DataSource dataSource;
    private final CredentialCipher cipher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<SessionKey, String> bound = new ConcurrentHashMap<>();
    /** 串行化身份及其成员、会话的写事务和内存映射更新，避免并发创建/改名/绑定/删除留下孤儿行或破坏上限。 */
    private final Object writeLock = new Object();

    public IdentityStore(DataSource dataSource, CredentialCipher cipher) {
        this(dataSource, cipher, Clock.systemUTC());
    }

    public IdentityStore(DataSource dataSource, CredentialCipher cipher, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        for (Identity identity : list()) {
            for (SessionKey member : identity.members()) {
                bound.put(member, identity.id());
            }
        }
    }

    @Override
    public String identityOf(SessionKey key) {
        return key == null ? null : bound.get(key);
    }

    @Override
    public List<SessionKey> members(String identity) {
        ArrayList<SessionKey> members = new ArrayList<>();
        if (identity == null) {
            return members;
        }
        for (Map.Entry<SessionKey, String> entry : bound.entrySet()) {
            if (identity.equals(entry.getValue())) {
                members.add(entry.getKey());
            }
        }
        members.sort(ORDER);
        return members;
    }

    /** 有没有账号绑了身份。不查库。 */
    public boolean anyBound() {
        return !bound.isEmpty();
    }

    public List<Identity> list() {
        return Db.transaction(dataSource, connection -> {
            LinkedHashMap<String, ArrayList<SessionKey>> members = new LinkedHashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT origin, conch, account, identity_id FROM identity_members");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Origin origin = origin(rows.getString("origin"));
                    if (origin == null) {
                        continue;
                    }
                    members.computeIfAbsent(rows.getString("identity_id"), id -> new ArrayList<>())
                            .add(new SessionKey(origin, rows.getString("conch"), rows.getString("account")));
                }
            }
            ArrayList<Identity> found = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, name, created_at FROM identities ORDER BY name, id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString("id");
                    ArrayList<SessionKey> own = members.getOrDefault(id, new ArrayList<>());
                    own.sort(ORDER);
                    found.add(new Identity(id, rows.getString("name"), Db.instant(rows, "created_at"), own));
                }
            }
            return found;
        });
    }

    public Identity find(String id) {
        if (id == null) {
            return null;
        }
        for (Identity identity : list()) {
            if (identity.id().equals(id)) {
                return identity;
            }
        }
        return null;
    }

    public Identity create(String name, String actor) {
        String checked = name(name);
        String checkedActor = Checks.actor(actor);
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        String id = HexFormat.of().formatHex(bytes);
        synchronized (writeLock) {
            Instant now = clock.instant();
            Db.transaction(dataSource, connection -> {
                try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM identities");
                     ResultSet rows = count.executeQuery()) {
                    if (rows.next() && rows.getInt(1) >= MAX_IDENTITIES) {
                        throw new IllegalArgumentException("身份最多 " + MAX_IDENTITIES + " 个");
                    }
                }
                requireFreeName(connection, checked, null);
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO identities (id, name, created_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, id);
                    insert.setString(2, checked);
                    Db.setInstant(insert, 3, now);
                    insert.executeUpdate();
                }
                ConfigAudit.append(connection, now, checkedActor, AuditKind.IDENTITY_CREATED, id,
                        Json.write(Map.of("name", checked)));
                return null;
            });
            return new Identity(id, checked, now, List.of());
        }
    }

    public void rename(String id, String name, String actor) {
        String checked = name(name);
        String checkedActor = Checks.actor(actor);
        synchronized (writeLock) {
            Instant now = clock.instant();
            Db.transaction(dataSource, connection -> {
                requireIdentity(connection, id);
                requireFreeName(connection, checked, id);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE identities SET name = ? WHERE id = ?")) {
                    update.setString(1, checked);
                    update.setString(2, id);
                    update.executeUpdate();
                }
                ConfigAudit.append(connection, now, checkedActor, AuditKind.IDENTITY_RENAMED, id,
                        Json.write(Map.of("name", checked)));
                return null;
            });
        }
    }

    /** 删掉身份、它的成员关系和 Cookie。返回原来的成员，调用方让它们回到各自的会话。 */
    public List<SessionKey> delete(String id, String actor) {
        String checkedActor = Checks.actor(actor);
        synchronized (writeLock) {
            Instant now = clock.instant();
            List<SessionKey> members = Db.transaction(dataSource, connection -> {
                requireIdentity(connection, id);
                String name = nameOf(connection, id);
                List<SessionKey> before = membersOf(connection, id);
                for (String table : List.of("identity_members", "identity_sessions")) {
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM " + table + " WHERE identity_id = ?")) {
                        delete.setString(1, id);
                        delete.executeUpdate();
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM identities WHERE id = ?")) {
                    delete.setString(1, id);
                    delete.executeUpdate();
                }
                ConfigAudit.append(connection, now, checkedActor, AuditKind.IDENTITY_DELETED, id,
                        Json.write(Map.of("name", name, "members", before.size())));
                return before;
            });
            for (SessionKey member : members) {
                bound.remove(member, id);
            }
            return members;
        }
    }

    /**
     * 把账号绑到身份。账号已经绑了别的身份、或这个身份下已经有同一个包的另一个账号时拒绝。
     * 账号是否存在、两个包能不能绑到一起由调用方先检查。
     */
    public void bind(String id, SessionKey key, String actor) {
        Objects.requireNonNull(key, "key");
        String checkedActor = Checks.actor(actor);
        Checks.conch(key.conch());
        Checks.account(key.account());
        synchronized (writeLock) {
            Instant now = clock.instant();
            Db.transaction(dataSource, connection -> {
                requireIdentity(connection, id);
                String current = identityOf(connection, key);
                if (id.equals(current)) {
                    return null;
                }
                if (current != null) {
                    if (exists(connection, current)) {
                        throw new IllegalArgumentException("这个账号已经绑到另一个身份，先解除再绑定");
                    }
                    // 清理历史孤儿行（对应身份已不存在），避免主键冲突挡住新的绑定。
                    deleteMember(connection, key);
                }
                for (SessionKey member : membersOf(connection, id)) {
                    if (member.origin() == key.origin() && member.conch().equals(key.conch())) {
                        throw new IllegalArgumentException(
                                "这个身份下已经有 " + key.conch() + " 的账号 " + member.account() + "，一个包只能绑一个账号");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO identity_members (origin, conch, account, identity_id, bound_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, key.origin().name());
                    insert.setString(2, key.conch());
                    insert.setString(3, key.account());
                    insert.setString(4, id);
                    Db.setInstant(insert, 5, now);
                    insert.executeUpdate();
                }
                ConfigAudit.append(connection, now, checkedActor, AuditKind.IDENTITY_BOUND, label(key),
                        Json.write(Map.of("identity", nameOf(connection, id), "identityId", id)));
                return null;
            });
            bound.put(key, id);
        }
    }

    /** 解除一个账号的绑定。返回它原来的身份，本来没绑为 null。{@code reason} 写进审计。 */
    public String unbind(SessionKey key, String reason, String actor) {
        Objects.requireNonNull(key, "key");
        String checkedActor = Checks.actor(actor);
        synchronized (writeLock) {
            Instant now = clock.instant();
            String id = Db.transaction(dataSource, connection -> {
                String current = identityOf(connection, key);
                if (current == null) {
                    return null;
                }
                deleteMember(connection, key);
                if (!exists(connection, current)) {
                    return null;
                }
                LinkedHashMap<String, Object> detail = new LinkedHashMap<>();
                detail.put("identity", nameOf(connection, current));
                detail.put("identityId", current);
                if (reason != null) {
                    detail.put("reason", reason);
                }
                ConfigAudit.append(
                        connection, now, checkedActor, AuditKind.IDENTITY_UNBOUND, label(key), Json.write(detail));
                return current;
            });
            bound.remove(key);
            return id;
        }
    }

    /** 这个包（某个来源）绑了身份的账号。 */
    public List<SessionKey> boundAccounts(Origin origin, String conch) {
        ArrayList<SessionKey> keys = new ArrayList<>();
        for (SessionKey key : bound.keySet()) {
            if (key.origin() == origin && key.conch().equals(conch)) {
                keys.add(key);
            }
        }
        keys.sort(ORDER);
        return keys;
    }

    @Override
    public List<StoredCookie> load(String identity) {
        Objects.requireNonNull(identity, "identity");
        byte[] ciphertext = Db.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ciphertext FROM identity_sessions WHERE identity_id = ?")) {
                statement.setString(1, identity);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getBytes("ciphertext") : null;
                }
            }
        });
        if (ciphertext == null) {
            return List.of();
        }
        try {
            String json = cipher.decrypt(Origin.LOCAL, CIPHER_CONCH, identity, FIELD, ciphertext);
            List<StoredCookie> cookies = JSON.readValue(json, COOKIES);
            return cookies == null ? List.of() : List.copyOf(cookies);
        } catch (CredentialDecryptException | JacksonException ex) {
            delete(identity);
            return List.of();
        }
    }

    @Override
    public void save(String identity, List<StoredCookie> cookies) {
        Objects.requireNonNull(identity, "identity");
        if (cookies == null || cookies.isEmpty()) {
            delete(identity);
            return;
        }
        String json;
        try {
            json = JSON.writeValueAsString(cookies);
        } catch (JacksonException ex) {
            throw new IllegalStateException("session encoding failed");
        }
        byte[] ciphertext = cipher.encrypt(Origin.LOCAL, CIPHER_CONCH, identity, FIELD, json);
        synchronized (writeLock) {
            Instant now = clock.instant();
            Db.transaction(dataSource, connection -> {
                // 身份已经删掉时不再写回：进行中的调用可能在删除之后才收尾。
                if (!exists(connection, identity)) {
                    return null;
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE identity_sessions SET ciphertext = ?, updated_at = ? WHERE identity_id = ?")) {
                    update.setBytes(1, ciphertext);
                    Db.setInstant(update, 2, now);
                    update.setString(3, identity);
                    if (update.executeUpdate() == 1) {
                        return null;
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO identity_sessions (identity_id, ciphertext, updated_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, identity);
                    insert.setBytes(2, ciphertext);
                    Db.setInstant(insert, 3, now);
                    insert.executeUpdate();
                }
                return null;
            });
        }
    }

    @Override
    public void delete(String identity) {
        Objects.requireNonNull(identity, "identity");
        synchronized (writeLock) {
            Db.transaction(dataSource, connection -> {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM identity_sessions WHERE identity_id = ?")) {
                    delete.setString(1, identity);
                    delete.executeUpdate();
                }
                return null;
            });
        }
    }

    private static void deleteMember(Connection connection, SessionKey key) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM identity_members WHERE origin = ? AND conch = ? AND account = ?")) {
            delete.setString(1, key.origin().name());
            delete.setString(2, key.conch());
            delete.setString(3, key.account());
            delete.executeUpdate();
        }
    }

    private static String name(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("身份名称要 1 到 " + MAX_NAME + " 个字符，不能有控制字符");
        }
        return trimmed;
    }

    private static String label(SessionKey key) {
        return key.origin().name() + "/" + key.conch() + "/" + key.account();
    }

    private static Origin origin(String value) {
        for (Origin origin : Origin.values()) {
            if (origin.name().equals(value)) {
                return origin;
            }
        }
        return null;
    }

    private static boolean exists(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM identities WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static void requireIdentity(Connection connection, String id) throws SQLException {
        if (id == null || !exists(connection, id)) {
            throw new IllegalArgumentException("身份不存在");
        }
    }

    private static String nameOf(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM identities WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("name") : "";
            }
        }
    }

    private static void requireFreeName(Connection connection, String name, String except) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM identities WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!rows.getString("id").equals(except)) {
                        throw new IllegalArgumentException("已经有同名的身份");
                    }
                }
            }
        }
    }

    private static String identityOf(Connection connection, SessionKey key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT identity_id FROM identity_members WHERE origin = ? AND conch = ? AND account = ?")) {
            statement.setString(1, key.origin().name());
            statement.setString(2, key.conch());
            statement.setString(3, key.account());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("identity_id") : null;
            }
        }
    }

    private static List<SessionKey> membersOf(Connection connection, String id) throws SQLException {
        ArrayList<SessionKey> members = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT origin, conch, account FROM identity_members WHERE identity_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Origin origin = origin(rows.getString("origin"));
                    if (origin != null) {
                        members.add(new SessionKey(origin, rows.getString("conch"), rows.getString("account")));
                    }
                }
            }
        }
        members.sort(ORDER);
        return members;
    }
}
