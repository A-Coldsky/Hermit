package dev.hermit.reef.admin;

import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.security.IssuedJwt;
import dev.hermit.reef.security.JwtService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {

    public static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    public static final int MAX_FAILURES = 5;
    public static final String FAILURE_MESSAGE = "authentication failed";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final AdminUsers users;
    private final Clock clock;
    private final String dummyHash;

    public LoginService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService,
            AuditService auditService, AdminUsers users, Clock clock) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.users = users;
        this.clock = clock;
        // 用户不存在时也做一次 BCrypt，避免响应时间暴露账号是否存在。
        this.dummyHash = passwordEncoder.encode("dummy-password-not-used-for-login");
    }

    @Transactional
    public Optional<LoginResponse> login(String username, String password) {
        Instant now = clock.instant();
        AdminAccount user = users.find(username);
        boolean locked = failureCount(username, now) >= MAX_FAILURES;
        String hash = user == null ? dummyHash : user.passwordHash();
        boolean passwordMatches = passwordEncoder.matches(password, hash);
        // 未知用户、密码错误、已禁用、锁定中都走同一条失败路径。
        boolean success = user != null && !user.disabled() && passwordMatches && !locked;
        if (!success) {
            // 锁定期间再记失败会把窗口往前推，锁就永远不会结束。
            if (!locked) {
                jdbc.update(
                        "INSERT INTO hermit_admin_login_failures (username, failed_at) VALUES (?, ?)",
                        username,
                        Timestamp.from(now));
            }
            auditService.record(username, AuditAction.LOGIN_FAILURE, null, null, null);
            return Optional.empty();
        }
        auditService.record(username, AuditAction.LOGIN_SUCCESS, null, null, null);
        IssuedJwt issued = jwtService.issue(username, user.role());
        return Optional.of(new LoginResponse(issued.token(), issued.expiresAt()));
    }

    private int failureCount(String username, Instant now) {
        Instant cutoff = now.minus(LOCK_WINDOW);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hermit_admin_login_failures WHERE username = ? AND failed_at > ?",
                Integer.class,
                username,
                Timestamp.from(cutoff));
        return count == null ? 0 : count;
    }
}
