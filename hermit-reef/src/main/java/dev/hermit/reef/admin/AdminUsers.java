package dev.hermit.reef.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.config.ReefProperties;
import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUsers {

    public static final String LAST_ENABLED_ADMIN = "至少要保留一个启用中的管理员";
    public static final String CANNOT_CHANGE_SELF = "不能修改自己的角色或禁用自己";
    public static final String USERNAME_EXISTS = "用户名已存在";
    public static final String CANNOT_RESET_OWN_PASSWORD = "不能重置自己的密码，请用右上角「修改密码」";
    public static final String OLD_PASSWORD_MISMATCH = "当前密码不正确";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ReefProperties properties;
    private final ConfigTokenVoids configTokenVoids;
    private final AgentTokenService agentTokens;

    public AdminUsers(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuditService auditService, Clock clock,
            ObjectMapper objectMapper, ReefProperties properties, ConfigTokenVoids configTokenVoids,
            @Lazy AgentTokenService agentTokens) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.configTokenVoids = configTokenVoids;
        this.agentTokens = agentTokens;
    }

    public AdminAccount find(String username) {
        return jdbc.query(
                "SELECT id, username, password_hash, disabled, role, created_at, token_version FROM hermit_admin_users "
                        + "WHERE username = ?",
                rs -> rs.next() ? map(rs) : null,
                username);
    }

    /**
     * id 最小的 ADMIN，含已禁用。配置令牌归这个人；没有 ADMIN 时返回 null。
     */
    public AdminAccount initialAdmin() {
        return jdbc.query(
                "SELECT id, username, password_hash, disabled, role, created_at, token_version FROM hermit_admin_users "
                        + "WHERE role = 'ADMIN' ORDER BY id LIMIT 1",
                rs -> rs.next() ? map(rs) : null);
    }

    public List<UserSummary> list() {
        List<AdminAccount> accounts = jdbc.query(
                "SELECT id, username, password_hash, disabled, role, created_at, token_version FROM hermit_admin_users ORDER BY id",
                (rs, rowNum) -> map(rs));
        return accounts.stream().map(this::summarize).toList();
    }

    @Transactional
    public UserSummary create(String actor, String username, String password, AdminRole role) {
        if (role == null) {
            throw ApiException.invalidInput();
        }
        if (find(username) != null) {
            throw conflict(USERNAME_EXISTS);
        }
        Instant now = clock.instant();
        try {
            jdbc.update(
                    "INSERT INTO hermit_admin_users (username, password_hash, created_at, disabled, role) "
                            + "VALUES (?, ?, ?, FALSE, ?)",
                    username,
                    passwordEncoder.encode(password),
                    Timestamp.from(now),
                    role.name());
        } catch (DataIntegrityViolationException ex) {
            throw conflict(USERNAME_EXISTS);
        }
        clearLoginFailures(username, username);
        auditService.record(actor, AuditAction.USER_CREATED, null, null, detail(Map.of(
                "username", username,
                "role", role.name())));
        return summarize(find(username));
    }

    @Transactional
    public UserSummary update(String actor, String username, AdminRole requestedRole, Boolean requestedDisabled) {
        if (requestedRole == null && requestedDisabled == null) {
            throw ApiException.invalidInput();
        }
        AdminAccount target = lockByUsername(username);
        if (target == null) {
            throw notFound();
        }
        AdminRole role = requestedRole == null ? target.role() : requestedRole;
        boolean disabled = requestedDisabled == null ? target.disabled() : requestedDisabled;
        boolean wasEnabledAdmin = target.role() == AdminRole.ADMIN && !target.disabled();
        boolean willBeEnabledAdmin = role == AdminRole.ADMIN && !disabled;
        if (wasEnabledAdmin && !willBeEnabledAdmin && lockEnabledAdmins() <= 1) {
            throw conflict(LAST_ENABLED_ADMIN);
        }
        if (actor.equals(target.username()) && (role != AdminRole.ADMIN || disabled)) {
            throw conflict(CANNOT_CHANGE_SELF);
        }
        boolean disabling = !target.disabled() && disabled;
        boolean enabling = target.disabled() && !disabled;
        boolean voidConfig = disabling && configOwner(target);
        jdbc.update(
                "UPDATE hermit_admin_users SET role = ?, disabled = ? WHERE id = ?",
                role.name(),
                disabled,
                target.id());
        if (enabling) {
            // 重新启用时清掉禁用期间可能累计的登录失败记录，避免启用后仍被锁定。
            clearLoginFailures(username, target.username());
        }
        auditService.record(actor, AuditAction.USER_UPDATED, null, null, detail(Map.of(
                "username", target.username(),
                "role", role.name(),
                "disabled", disabled)));
        if (disabling) {
            agentTokens.revokeForDisabledUser(actor, target.id(), target.username(), voidConfig);
        }
        return summarize(find(target.username()));
    }

    /**
     * 签发令牌前锁住用户行，和禁用共用这把锁，避免禁用提交前又插入一把未吊销令牌。
     */
    @Transactional
    public AdminAccount lockEnabled(String username) {
        AdminAccount account = lockByUsername(username);
        if (account == null || account.disabled()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        return account;
    }

    @Transactional
    public UserSummary changePassword(String username, String oldPassword, String newPassword) {
        AdminAccount user = find(username);
        if (user == null || user.disabled()) {
            throw new ApiException(401, ReefErrorCode.UNAUTHORIZED, ReefErrorCode.UNAUTHORIZED.message());
        }
        if (!passwordEncoder.matches(oldPassword, user.passwordHash())) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, OLD_PASSWORD_MISMATCH);
        }
        // 版本号加一，已经签出的 JWT（包括调用方自己这一张）随即失效。
        jdbc.update(
                "UPDATE hermit_admin_users SET password_hash = ?, token_version = token_version + 1 WHERE id = ?",
                passwordEncoder.encode(newPassword),
                user.id());
        clearLoginFailures(username, user.username());
        auditService.record(username, AuditAction.PASSWORD_CHANGED, null, null,
                detail(Map.of("username", username)));
        return summarize(find(username));
    }

    @Transactional
    public UserSummary resetPassword(String actor, String username, String newPassword) {
        AdminAccount target = find(username);
        if (target == null) {
            throw notFound();
        }
        if (actor.equals(target.username())) {
            throw new ApiException(400, ReefErrorCode.INVALID_INPUT, CANNOT_RESET_OWN_PASSWORD);
        }
        // 版本号加一，被重置用户已经签出的 JWT 随即失效；同时清除登录失败锁定记录，使新密码立即可用。
        jdbc.update(
                "UPDATE hermit_admin_users SET password_hash = ?, token_version = token_version + 1 WHERE id = ?",
                passwordEncoder.encode(newPassword),
                target.id());
        clearLoginFailures(username, target.username());
        auditService.record(actor, AuditAction.USER_PASSWORD_RESET, null, null,
                detail(Map.of("username", target.username())));
        return summarize(target);
    }

    private void clearLoginFailures(String requestedUsername, String canonicalUsername) {
        if (requestedUsername == null || requestedUsername.equals(canonicalUsername)) {
            jdbc.update("DELETE FROM hermit_admin_login_failures WHERE username = ?", canonicalUsername);
            return;
        }
        jdbc.update(
                "DELETE FROM hermit_admin_login_failures WHERE username = ? OR username = ?",
                requestedUsername,
                canonicalUsername);
    }

    private boolean isLocked(String username) {
        Instant cutoff = clock.instant().minus(LoginService.LOCK_WINDOW);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_admin_login_failures WHERE username = ? AND failed_at > ?",
                Integer.class,
                username,
                Timestamp.from(cutoff));
        return count != null && count >= LoginService.MAX_FAILURES;
    }

    private UserSummary summarize(AdminAccount account) {
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_agent_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class,
                account.id());
        int tokens = active == null ? 0 : active;
        AdminAccount initial = initialAdmin();
        if (properties.configuredAgentToken() != null && initial != null && initial.id() == account.id()
                && !configTokenVoids.currentIsVoided()) {
            tokens++;
        }
        boolean locked = !account.disabled() && isLocked(account.username());
        return new UserSummary(
                account.username(),
                account.role(),
                account.disabled(),
                locked,
                account.createdAt(),
                tokens);
    }

    private boolean configOwner(AdminAccount account) {
        if (properties.configuredAgentToken() == null) {
            return false;
        }
        AdminAccount initial = initialAdmin();
        return initial != null && initial.id() == account.id();
    }

    private AdminAccount lockByUsername(String username) {
        return jdbc.query(
                "SELECT id, username, password_hash, disabled, role, created_at, token_version FROM hermit_admin_users "
                        + "WHERE username = ? FOR UPDATE",
                rs -> rs.next() ? map(rs) : null,
                username);
    }

    private int lockEnabledAdmins() {
        List<Long> ids = jdbc.query(
                "SELECT id FROM hermit_admin_users WHERE role = 'ADMIN' AND disabled = FALSE FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"));
        return ids.size();
    }

    private AdminAccount map(ResultSet rs) throws SQLException {
        return new AdminAccount(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getBoolean("disabled"),
                AdminRole.valueOf(rs.getString("role")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getInt("token_version"));
    }

    private String detail(Map<String, ?> values) {
        Map<String, Object> ordered = new LinkedHashMap<>(values);
        try {
            return objectMapper.writeValueAsString(ordered);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("audit detail", ex);
        }
    }

    private static ApiException conflict(String message) {
        return new ApiException(409, ReefErrorCode.CONFLICT, message);
    }

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }
}
