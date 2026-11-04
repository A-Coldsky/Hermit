package dev.hermit.reef.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.security.AgentPrincipal;
import dev.hermit.reef.security.TokenDigests;
import dev.hermit.reef.security.TokenEquality;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTokenService {

    public static final String SECRET_PREFIX = "hat_";
    public static final int RANDOM_BYTES = 32;
    public static final int PREFIX_LENGTH = 12;
    public static final String CONFIG_NAME = "HERMIT_REEF_AGENT_TOKEN";
    static final int CRAB_VERSION_MAX = 32;

    private static final byte[] DUMMY_DIGEST = new byte[32];

    private final JdbcTemplate jdbc;
    private final AdminUsers users;
    private final ReefProperties properties;
    private final AuditService auditService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public AgentTokenService(JdbcTemplate jdbc, AdminUsers users, ReefProperties properties, AuditService auditService,
            Clock clock, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.users = users;
        this.properties = properties;
        this.auditService = auditService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 配置令牌和库内哈希都查。库内令牌未吊销且用户未禁用才算通过；两边都对得上时用库内身份。
     * 配置令牌归 id 最小的 ADMIN。该用户已禁用则配置令牌 401。还没有任何 ADMIN 时配置令牌仍可通过，
     * 但身份上没有 userId，调用不记实例、上报 owner 为空。
     */
    public Optional<AgentPrincipal> authenticate(String header) {
        String presented = header == null ? "" : header;
        String configured = properties.configuredAgentToken();
        boolean configMatch = configured != null
                && TokenEquality.constantTimeEquals(configured, presented);
        if (configured == null) {
            TokenEquality.constantTimeEquals("", presented);
        }
        byte[] digest = TokenDigests.sha256(presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StoredToken stored = findByHash(TokenDigests.sha256Hex(presented));
        byte[] expected = stored == null ? DUMMY_DIGEST : decodeHash(stored.tokenHash());
        boolean hashMatches = MessageDigest.isEqual(digest, expected);
        boolean dbMatch = stored != null && hashMatches && stored.revokedAt() == null && !stored.disabled();
        if (dbMatch) {
            return Optional.of(new AgentPrincipal(stored.userId(), stored.username(), stored.id()));
        }
        if (!configMatch) {
            return Optional.empty();
        }
        AdminAccount owner = users.initialAdmin();
        if (owner == null) {
            return Optional.of(new AgentPrincipal(null, null, null));
        }
        if (owner.disabled()) {
            return Optional.empty();
        }
        return Optional.of(new AgentPrincipal(owner.id(), owner.username(), null));
    }

    public List<AgentTokenView> list(String actor, boolean admin, boolean all) {
        AdminAccount self = require(actor);
        boolean everyone = admin && all;
        List<AgentTokenView> views = new ArrayList<>();
        AdminAccount initial = users.initialAdmin();
        if (properties.configuredAgentToken() != null && initial != null
                && (everyone || initial.id() == self.id())) {
            views.add(configView(initial.username()));
        }
        views.addAll(databaseTokens(everyone ? null : self.id()));
        return List.copyOf(views);
    }

    @Transactional
    public IssuedAgentToken create(String actor, String name, String publicKey) {
        AdminAccount user = require(actor);
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        String secret = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String prefix = secret.substring(0, PREFIX_LENGTH);
        String hash = TokenDigests.sha256Hex(secret);
        Instant now = clock.instant();
        jdbc.update(
                "INSERT INTO hermit_agent_tokens (user_id, name, token_prefix, token_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                user.id(),
                name,
                prefix,
                hash,
                Timestamp.from(now));
        Long id = jdbc.queryForObject(
                "SELECT id FROM hermit_agent_tokens WHERE token_hash = ?",
                Long.class,
                hash);
        if (id == null) {
            throw new IllegalStateException("agent token id missing");
        }
        auditService.record(actor, AuditAction.AGENT_TOKEN_CREATED, null, null, detail(Map.of(
                "id", id,
                "name", name,
                "prefix", prefix,
                "owner", user.username())));
        AgentTokenView view = new AgentTokenView(id, user.username(), name, prefix, now, null, null,
                TokenSource.DATABASE);
        return new IssuedAgentToken(view, secret, publicKey);
    }

    @Transactional
    public AgentTokenView revoke(String actor, boolean admin, long id) {
        TokenRow row = findById(id);
        if (row == null) {
            throw new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
        }
        if (!admin && !row.owner().equals(actor)) {
            throw new ApiException(403, ReefErrorCode.FORBIDDEN, ReefErrorCode.FORBIDDEN.message());
        }
        if (row.revokedAt() == null) {
            Instant now = clock.instant();
            int updated = jdbc.update(
                    "UPDATE hermit_agent_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
                    Timestamp.from(now),
                    id);
            if (updated == 1) {
                auditService.record(actor, AuditAction.AGENT_TOKEN_REVOKED, null, null, detail(Map.of(
                        "id", id,
                        "name", row.name(),
                        "prefix", row.prefix(),
                        "owner", row.owner())));
            }
            row = findById(id);
        }
        return row.view();
    }

    public List<CrabInstanceView> crabs(String actor, boolean admin) {
        AdminAccount self = require(actor);
        String sql = "SELECT u.username AS owner, c.instance_id, t.name AS token_name, c.first_seen_at, c.last_seen_at, "
                + "c.crab_version FROM hermit_crab_instances c "
                + "JOIN hermit_admin_users u ON u.id = c.user_id "
                + "LEFT JOIN hermit_agent_tokens t ON t.id = c.token_id ";
        String order = "ORDER BY c.last_seen_at DESC, c.instance_id";
        if (admin) {
            return jdbc.query(sql + order, this::crabView);
        }
        return jdbc.query(sql + "WHERE c.user_id = ? " + order, this::crabView, self.id());
    }

    public static String crabVersionForStore(String crabVersion) {
        if (crabVersion == null || crabVersion.isBlank()) {
            return null;
        }
        if (crabVersion.length() <= CRAB_VERSION_MAX) {
            return crabVersion;
        }
        return crabVersion.substring(0, CRAB_VERSION_MAX);
    }

    private List<AgentTokenView> databaseTokens(Long userId) {
        String sql = "SELECT t.id, u.username AS owner, t.name, t.token_prefix, t.created_at, t.last_used_at, "
                + "t.revoked_at FROM hermit_agent_tokens t JOIN hermit_admin_users u ON u.id = t.user_id ";
        String order = "ORDER BY t.created_at DESC, t.id DESC";
        if (userId == null) {
            return jdbc.query(sql + order, (rs, rowNum) -> tokenView(rs));
        }
        return jdbc.query(sql + "WHERE t.user_id = ? " + order, (rs, rowNum) -> tokenView(rs), userId);
    }

    private static AgentTokenView tokenView(ResultSet rs) throws SQLException {
        return new AgentTokenView(
                rs.getLong("id"),
                rs.getString("owner"),
                rs.getString("name"),
                rs.getString("token_prefix"),
                rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("revoked_at")),
                TokenSource.DATABASE);
    }

    private CrabInstanceView crabView(ResultSet rs, int rowNum) throws SQLException {
        return new CrabInstanceView(
                rs.getString("owner"),
                rs.getString("instance_id"),
                rs.getString("token_name"),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getString("crab_version"));
    }

    private StoredToken findByHash(String hash) {
        return jdbc.query(
                "SELECT t.id, t.user_id, t.token_hash, t.revoked_at, u.username, u.disabled "
                        + "FROM hermit_agent_tokens t JOIN hermit_admin_users u ON u.id = t.user_id "
                        + "WHERE t.token_hash = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    String storedHash = rs.getString("token_hash");
                    Timestamp revoked = rs.getTimestamp("revoked_at");
                    return new StoredToken(
                            rs.getLong("id"),
                            rs.getLong("user_id"),
                            rs.getString("username"),
                            storedHash == null ? "" : storedHash.trim(),
                            revoked == null ? null : revoked.toInstant(),
                            rs.getBoolean("disabled"));
                },
                hash);
    }

    private TokenRow findById(long id) {
        return jdbc.query(
                "SELECT t.id, u.username AS owner, t.name, t.token_prefix, t.created_at, t.last_used_at, t.revoked_at "
                        + "FROM hermit_agent_tokens t JOIN hermit_admin_users u ON u.id = t.user_id WHERE t.id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new TokenRow(new AgentTokenView(
                            rs.getLong("id"),
                            rs.getString("owner"),
                            rs.getString("name"),
                            rs.getString("token_prefix"),
                            rs.getTimestamp("created_at").toInstant(),
                            instant(rs.getTimestamp("last_used_at")),
                            instant(rs.getTimestamp("revoked_at")),
                            TokenSource.DATABASE));
                },
                id);
    }

    private AdminAccount require(String username) {
        AdminAccount account = users.find(username);
        if (account == null || account.disabled()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        return account;
    }

    private static AgentTokenView configView(String owner) {
        return new AgentTokenView(null, owner, CONFIG_NAME, null, null, null, null, TokenSource.CONFIG);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static byte[] decodeHash(String hash) {
        if (hash == null || hash.length() != 64) {
            return DUMMY_DIGEST;
        }
        try {
            return TokenDigests.hexToBytes(hash);
        } catch (IllegalArgumentException ex) {
            return DUMMY_DIGEST;
        }
    }

    private String detail(Map<String, ?> values) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(values));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("audit detail", ex);
        }
    }

    private record StoredToken(long id, long userId, String username, String tokenHash, Instant revokedAt,
            boolean disabled) {
    }

    private record TokenRow(AgentTokenView view) {
        String owner() {
            return view.owner();
        }

        String name() {
            return view.name();
        }

        String prefix() {
            return view.prefix();
        }

        Instant revokedAt() {
            return view.revokedAt();
        }
    }
}
