package dev.hermit.crab.engine;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

/**
 * 每个 (origin, conch, account) 一把公平锁、一个客户端和一个 Cookie 存储。
 * 替换 Conch 版本不会清掉这里的会话。
 */
public final class SessionManager implements AutoCloseable {

    /** 已见 Cookie 值去重后的上限；超出时按加入顺序丢掉最早的值。 */
    private static final int MAX_SEEN_COOKIE_VALUES = 64;

    private final SessionPersistence persistence;
    private final LoginSnapshotStore loginSnapshots;
    private final EngineSettings settings;
    private final Clock clock;
    private final PendingLogins pending;
    private final ConcurrentMap<SessionKey, AccountState> states = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SessionManager() {
        this(new NopSessionPersistence(), EngineSettings.defaults(), Clock.systemUTC());
    }

    public SessionManager(SessionPersistence persistence) {
        this(persistence, EngineSettings.defaults(), Clock.systemUTC());
    }

    public SessionManager(SessionPersistence persistence, EngineSettings settings, Clock clock) {
        this(persistence, new NopLoginSnapshotStore(), settings, clock);
    }

    public SessionManager(
            SessionPersistence persistence,
            LoginSnapshotStore loginSnapshots,
            EngineSettings settings,
            Clock clock
    ) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.loginSnapshots = Objects.requireNonNull(loginSnapshots, "loginSnapshots");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pending = new PendingLogins(this.clock);
    }

    public PendingLogins pending() {
        return pending;
    }

    public Clock clock() {
        return clock;
    }

    public void resetLoginPause(SessionKey key) {
        AccountState state = states.computeIfAbsent(key, this::newState);
        state.lock.lock();
        try {
            state.loginPaused = false;
            state.authFailures = 0;
        } finally {
            state.lock.unlock();
        }
    }

    public void invalidate(SessionKey key) {
        persistence.delete(key);
        loginSnapshots.delete(key);
        AccountState state = states.get(key);
        if (state == null) {
            pending.discard(key);
            return;
        }
        state.lock.lock();
        try {
            pending.discard(key);
            state.loggedIn = false;
            state.lastActive = null;
            state.seenCookieValues.clear();
            state.loginSnapshot.clear();
            closeClient(state);
            openClient(state);
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * 账号已删除：丢掉这把锁、客户端和 Cookie，不再留在内存里。以前只 {@link #invalidate} 重开客户端，
     * 删掉的账号各自留着一个连接池。
     */
    public void forget(SessionKey key) {
        persistence.delete(key);
        loginSnapshots.delete(key);
        AccountState state = states.get(key);
        if (state == null) {
            pending.discard(key);
            return;
        }
        state.lock.lock();
        try {
            pending.discard(key);
            states.remove(key, state);
            closeClient(state);
        } finally {
            state.lock.unlock();
        }
    }

    public boolean tryLock(SessionKey key, long deadlineNanos) throws InterruptedException {
        while (true) {
            AccountState state = states.computeIfAbsent(key, this::newState);
            long wait = deadlineNanos - System.nanoTime();
            if (wait <= 0) {
                return false;
            }
            if (!state.lock.tryLock(wait, TimeUnit.NANOSECONDS)) {
                return false;
            }
            if (states.get(key) == state) {
                return true;
            }
            // 等锁期间被 forget 了：这份状态已经关掉，换新的再等。
            state.lock.unlock();
        }
    }

    public void unlock(SessionKey key) {
        AccountState state = states.get(key);
        if (state != null && state.lock.isHeldByCurrentThread()) {
            state.lock.unlock();
        }
    }

    /**
     * Whether automatic login is paused. Absent session state means the account has not failed
     * login in this process, so it is not paused. The read takes the account lock; callers that
     * already hold it re-enter safely.
     */
    public boolean loginPaused(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            return false;
        }
        state.lock.lock();
        try {
            return state.loginPaused;
        } finally {
            state.lock.unlock();
        }
    }

    boolean loggedIn(SessionKey key) {
        return state(key).loggedIn;
    }

    boolean idle(SessionKey key) {
        AccountState state = state(key);
        if (!state.loggedIn || state.lastActive == null) {
            return false;
        }
        return Duration.between(state.lastActive, clock.instant()).compareTo(settings.sessionIdleLimit()) > 0;
    }

    void markLoggedIn(SessionKey key) {
        AccountState state = state(key);
        state.loggedIn = true;
        state.lastActive = clock.instant();
    }

    void markLoggedOut(SessionKey key) {
        AccountState state = state(key);
        state.loggedIn = false;
        state.lastActive = null;
        state.cookies.clear();
        state.loginSnapshot.clear();
        loginSnapshots.delete(key);
        persist(key);
    }

    /** 登录成功后整表替换。空映射删掉这一行。调用方持有账号锁。 */
    void replaceLoginSnapshot(SessionKey key, Map<String, String> snapshot) {
        AccountState state = state(key);
        state.loginSnapshot.clear();
        if (snapshot != null) {
            state.loginSnapshot.putAll(snapshot);
        }
        loginSnapshots.save(key, Map.copyOf(state.loginSnapshot));
    }

    Map<String, String> loginSnapshot(SessionKey key) {
        return Map.copyOf(state(key).loginSnapshot);
    }

    /** Cookie 罐里同名的值。没有则为 null。空字符串算有值。 */
    String cookieValue(SessionKey key, String name) {
        AccountState state = state(key);
        if (name == null || state.cookies == null) {
            return null;
        }
        for (Cookie cookie : state.cookies.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 本次打码用的值：已见 Cookie、当前罐里的值，以及登录快照。短于 4 个字符的不放入。
     * 不写 {@code config_audit}，也不写进程级已知秘密。
     */
    public List<String> redactionValues(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            return List.of();
        }
        state.lock.lock();
        try {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            addRedaction(values, state.seenCookieValues);
            if (state.cookies != null) {
                ArrayList<String> jar = new ArrayList<>();
                for (Cookie cookie : state.cookies.getCookies()) {
                    jar.add(cookie.getValue());
                }
                addRedaction(values, jar);
            }
            addRedaction(values, state.loginSnapshot.values());
            return List.copyOf(values);
        } finally {
            state.lock.unlock();
        }
    }

    void recordAuthSuccess(SessionKey key) {
        AccountState state = state(key);
        state.authFailures = 0;
        state.loginPaused = false;
    }

    void recordAuthFailure(SessionKey key) {
        AccountState state = state(key);
        state.authFailures++;
        if (state.authFailures >= settings.maxLoginFailures()) {
            state.loginPaused = true;
        }
    }

    void touch(SessionKey key) {
        AccountState state = state(key);
        if (state.loggedIn) {
            state.lastActive = clock.instant();
        }
    }

    CloseableHttpClient client(SessionKey key) {
        return state(key).client;
    }

    BasicCookieStore cookies(SessionKey key) {
        return state(key).cookies;
    }

    void persist(SessionKey key) {
        AccountState state = state(key);
        List<StoredCookie> cookies = dump(state.cookies);
        for (StoredCookie cookie : cookies) {
            rememberCookieValue(state.seenCookieValues, cookie.value());
        }
        persistence.save(key, List.copyOf(cookies));
    }

    /** Accounts with state in memory. */
    int size() {
        return states.size();
    }

    List<String> seenCookieValues(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            return List.of();
        }
        return List.copyOf(state.seenCookieValues);
    }

    /** Cookie names only. Values stay in the jar and are not copied here. */
    List<String> cookieNames(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null || state.cookies == null) {
            return List.of();
        }
        ArrayList<String> names = new ArrayList<>();
        for (Cookie cookie : state.cookies.getCookies()) {
            if (cookie.getName() != null) {
                names.add(cookie.getName());
            }
        }
        return List.copyOf(names);
    }

    @Override
    public void close() {
        closed = true;
        for (AccountState state : states.values()) {
            state.lock.lock();
            try {
                closeClient(state);
            } finally {
                state.lock.unlock();
            }
        }
        states.clear();
    }

    private AccountState state(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            throw new IllegalStateException("会话尚未加锁");
        }
        return state;
    }

    private AccountState newState(SessionKey key) {
        if (closed) {
            throw new IllegalStateException("会话管理器已关闭");
        }
        AccountState state = new AccountState();
        openClient(state);
        List<StoredCookie> loaded;
        try {
            loaded = persistence.load(key);
        } catch (RuntimeException ex) {
            closeClient(state);
            throw ex;
        }
        if (loaded == null) {
            loaded = List.of();
        }
        for (StoredCookie cookie : loaded) {
            addCookie(state.cookies, cookie);
            rememberCookieValue(state.seenCookieValues, cookie.value());
        }
        if (!loaded.isEmpty()) {
            state.loggedIn = true;
            state.lastActive = Instant.EPOCH;
        }
        Map<String, String> snapshot;
        try {
            snapshot = loginSnapshots.load(key);
        } catch (RuntimeException ex) {
            closeClient(state);
            throw ex;
        }
        if (snapshot != null) {
            state.loginSnapshot.putAll(snapshot);
        }
        return state;
    }

    private static void addRedaction(Set<String> into, Iterable<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && value.length() >= 4) {
                into.add(value);
            }
        }
    }

    private static void openClient(AccountState state) {
        state.cookies = new BasicCookieStore();
        state.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(15))
                                .setSocketTimeout(Timeout.ofSeconds(15))
                                .build())
                        .build())
                .setDefaultCookieStore(state.cookies)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
    }

    private static void closeClient(AccountState state) {
        if (state.client == null) {
            return;
        }
        try {
            state.client.close();
        } catch (IOException ignored) {
            // 关闭连接失败不影响会话作废。
        }
        state.client = null;
        state.cookies = null;
    }

    private static void rememberCookieValue(Set<String> seen, String value) {
        if (value == null || value.length() < 4) {
            return;
        }
        if (!seen.add(value)) {
            return;
        }
        if (seen.size() > MAX_SEEN_COOKIE_VALUES) {
            Iterator<String> oldest = seen.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private static List<StoredCookie> dump(BasicCookieStore store) {
        ArrayList<StoredCookie> cookies = new ArrayList<>();
        for (Cookie cookie : store.getCookies()) {
            Long expiry = cookie.getExpiryInstant() == null ? null : cookie.getExpiryInstant().toEpochMilli();
            boolean httpOnly = cookie instanceof BasicClientCookie basic && basic.isHttpOnly();
            cookies.add(new StoredCookie(
                    cookie.getName(),
                    cookie.getValue(),
                    cookie.getDomain(),
                    cookie.getPath(),
                    expiry,
                    cookie.isSecure(),
                    httpOnly));
        }
        return cookies;
    }

    private static void addCookie(BasicCookieStore store, StoredCookie cookie) {
        if (cookie.name() == null) {
            return;
        }
        BasicClientCookie restored = new BasicClientCookie(cookie.name(), cookie.value());
        if (cookie.domain() != null) {
            restored.setDomain(cookie.domain());
            restored.setAttribute(Cookie.DOMAIN_ATTR, cookie.domain());
        }
        String path = cookie.path() == null ? "/" : cookie.path();
        restored.setPath(path);
        restored.setAttribute(Cookie.PATH_ATTR, path);
        if (cookie.expiryEpochMillis() != null) {
            restored.setExpiryDate(Instant.ofEpochMilli(cookie.expiryEpochMillis()));
        }
        restored.setSecure(cookie.secure());
        restored.setHttpOnly(cookie.httpOnly());
        restored.setCreationDate(Instant.now());
        store.addCookie(restored);
    }

    private static final class AccountState {
        final ReentrantLock lock = new ReentrantLock(true);
        final Set<String> seenCookieValues = new LinkedHashSet<>();
        final Map<String, String> loginSnapshot = new LinkedHashMap<>();
        CloseableHttpClient client;
        BasicCookieStore cookies;
        boolean loggedIn;
        Instant lastActive;
        int authFailures;
        boolean loginPaused;
    }
}
