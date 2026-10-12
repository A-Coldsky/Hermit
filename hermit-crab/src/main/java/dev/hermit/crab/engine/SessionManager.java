package dev.hermit.crab.engine;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * 每个 (origin, shell, account) 一把公平锁、一个客户端和一个 Cookie 存储。
 * 替换 Shell 版本不会清掉这里的会话。
 */
public final class SessionManager implements AutoCloseable {

    private final SessionPersistence persistence;
    private final EngineSettings settings;
    private final Clock clock;
    private final ConcurrentMap<SessionKey, AccountState> states = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SessionManager() {
        this(new NopSessionPersistence(), EngineSettings.defaults(), Clock.systemUTC());
    }

    public SessionManager(SessionPersistence persistence) {
        this(persistence, EngineSettings.defaults(), Clock.systemUTC());
    }

    public SessionManager(SessionPersistence persistence, EngineSettings settings, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        AccountState state = states.get(key);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            state.loggedIn = false;
            state.lastActive = null;
            state.seenCookieValues.clear();
            closeClient(state);
            openClient(state);
        } finally {
            state.lock.unlock();
        }
    }

    boolean tryLock(SessionKey key, long deadlineNanos) throws InterruptedException {
        AccountState state = states.computeIfAbsent(key, this::newState);
        long wait = deadlineNanos - System.nanoTime();
        if (wait <= 0) {
            return false;
        }
        return state.lock.tryLock(wait, TimeUnit.NANOSECONDS);
    }

    void unlock(SessionKey key) {
        AccountState state = states.get(key);
        if (state != null && state.lock.isHeldByCurrentThread()) {
            state.lock.unlock();
        }
    }

    boolean loginPaused(SessionKey key) {
        return state(key).loginPaused;
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
        persist(key);
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
            if (cookie.value() != null && cookie.value().length() >= 4) {
                state.seenCookieValues.add(cookie.value());
            }
        }
        persistence.save(key, List.copyOf(cookies));
    }

    List<String> seenCookieValues(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            return List.of();
        }
        return List.copyOf(state.seenCookieValues);
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
            if (cookie.value() != null && cookie.value().length() >= 4) {
                state.seenCookieValues.add(cookie.value());
            }
        }
        if (!loaded.isEmpty()) {
            state.loggedIn = true;
            state.lastActive = Instant.EPOCH;
        }
        return state;
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
        final List<String> seenCookieValues = new ArrayList<>();
        CloseableHttpClient client;
        BasicCookieStore cookies;
        boolean loggedIn;
        Instant lastActive;
        int authFailures;
        boolean loginPaused;
    }
}
