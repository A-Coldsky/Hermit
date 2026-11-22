package dev.hermit.reef.admin;

import dev.hermit.reef.audit.AuditAction;
import dev.hermit.reef.audit.AuditService;
import dev.hermit.reef.security.IssuedJwt;
import dev.hermit.reef.security.JwtService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoginService {

    public static final Duration LOCK_WINDOW = Duration.ofMinutes(15);
    public static final int MAX_FAILURES = 5;
    public static final String FAILURE_MESSAGE = "authentication failed";
    /** 2 的幂。同一用户名的尝试落在同一把锁上，排队执行。 */
    static final int LOCK_STRIPES = 64;

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final AdminUsers users;
    private final Clock clock;
    private final String dummyHash;
    private final TransactionTemplate transactions;
    private final Object[] stripes = new Object[LOCK_STRIPES];

    public LoginService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService,
            AuditService auditService, AdminUsers users, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.users = users;
        this.clock = clock;
        // 用户不存在时也做一次 BCrypt，避免响应时间暴露账号是否存在。
        this.dummyHash = passwordEncoder.encode("dummy-password-not-used-for-login");
        this.transactions = new TransactionTemplate(transactionManager);
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
    }

    /**
     * 同一用户名的尝试串行：先数失败、再比口令、再记失败，整段在一个事务里，锁在事务提交之后才放。
     * 否则并发请求都读到同一个失败数，锁定前能多猜很多次。多个 Reef 进程共用一个库时仍各算各的。
     * 选锁键去掉尾随的 U+0020，再按 {@link Locale#ROOT} 小写，让 MySQL utf8mb4_unicode_ci 下的同一账号落在同一条带。
     * 查询和写入仍用调用方传入的原文。
     */
    public Optional<LoginResponse> login(String username, String password) {
        Object lock = stripes[lockStripe(username)];
        synchronized (lock) {
            return transactions.execute(status -> attempt(username, password));
        }
    }

    static int lockStripe(String username) {
        return lockKey(username).hashCode() & (LOCK_STRIPES - 1);
    }

    /** 去掉尾随的 U+0020，再按 ROOT 小写。前导空格留在键里。 */
    static String lockKey(String username) {
        String value = String.valueOf(username);
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private Optional<LoginResponse> attempt(String username, String password) {
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
            auditService.record(user != null ? user.username() : username, AuditAction.LOGIN_FAILURE, null, null, null);
            return Optional.empty();
        }
        String canonical = user.username();
        auditService.record(canonical, AuditAction.LOGIN_SUCCESS, null, null, null);
        IssuedJwt issued = jwtService.issue(canonical, user.role(), user.tokenVersion());
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
