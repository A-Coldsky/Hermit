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
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;

import dev.hermit.crab.http.OutboundHttp;
import dev.hermit.crab.http.StaleConnectionRetry;
import dev.hermit.crab.tls.OutboundTls;

/**
 * 每个 (origin, conch, account) 一把公平锁、一个客户端和一个 Cookie 存储。
 * 同地址范围的版本替换不会清掉这里的会话。
 * 地址范围扩大的本地覆盖会在写入前、以及 {@code registry.put} 成功后再对这个包的每个账号调用 {@link #invalidate}。
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
        AccountState state = states.get(key);
        if (state == null) {
            persistence.delete(key);
            loginSnapshots.delete(key);
            pending.discard(key);
            return;
        }
        // 进行中的调用持有这把锁，并且会在返回前把 Cookie 再写回库。
        // 清空必须发生在它放开锁之后，下一次调用和重启才不会带回旧会话。
        state.lock.lock();
        try {
            persistence.delete(key);
            loginSnapshots.delete(key);
            pending.discard(key);
            state.loggedIn = false;
            state.epoch++;
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
        state.gate.readLock().lock();
        try {
            return state.loginPaused;
        } finally {
            state.gate.readLock().unlock();
        }
    }

    boolean loggedIn(SessionKey key) {
        return state(key).loggedIn;
    }

    boolean idle(SessionKey key) {
        AccountState state = state(key);
        Instant last = state.lastActive;
        if (!state.loggedIn || last == null) {
            return false;
        }
        return Duration.between(last, clock.instant()).compareTo(settings.sessionIdleLimit()) > 0;
    }

    void markLoggedIn(SessionKey key) {
        AccountState state = state(key);
        state.loggedIn = true;
        state.epoch++;
        state.lastActive = clock.instant();
    }

    void markLoggedOut(SessionKey key) {
        AccountState state = state(key);
        state.loggedIn = false;
        state.epoch++;
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
        state.gate.readLock().lock();
        try {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            addRedaction(values, seen(state));
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
            state.gate.readLock().unlock();
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
        // 并行的只读调用各自写库：取罐和写库放在一起，后写的一定是更新的那份。
        synchronized (state.seenCookieValues) {
            List<StoredCookie> cookies = dump(state.cookies);
            for (StoredCookie cookie : cookies) {
                rememberCookieValue(state.seenCookieValues, cookie.value());
            }
            persistence.save(key, List.copyOf(cookies));
        }
    }

    private static List<String> seen(AccountState state) {
        synchronized (state.seenCookieValues) {
            return List.copyOf(state.seenCookieValues);
        }
    }

    /**
     * 只读能力用的共享锁：和别的只读调用一起持有，登录、作废和其它调用要等它们都放开。
     * 拿到后还要看 {@link #sharedReady}，会话不能直接用时放开，改走 {@link #tryLock}。
     */
    public boolean tryLockShared(SessionKey key, long deadlineNanos) throws InterruptedException {
        while (true) {
            AccountState state = states.computeIfAbsent(key, this::newState);
            long wait = deadlineNanos - System.nanoTime();
            if (wait <= 0) {
                return false;
            }
            if (!state.gate.readLock().tryLock(wait, TimeUnit.NANOSECONDS)) {
                return false;
            }
            if (states.get(key) == state) {
                return true;
            }
            state.gate.readLock().unlock();
        }
    }

    public void unlockShared(SessionKey key) {
        AccountState state = states.get(key);
        if (state != null && state.gate.getReadHoldCount() > 0) {
            state.gate.readLock().unlock();
        }
    }

    /** 已登录、没暂停、没有等输入的登录，也不到要跑 alive_check 的时候：共享锁下可以直接跑。 */
    boolean sharedReady(SessionKey key, boolean hasAliveCheck) {
        AccountState state = state(key);
        if (!state.loggedIn || state.loginPaused || pending.current(key) != null) {
            return false;
        }
        return !(hasAliveCheck && idle(key));
    }

    /** 测试用：清掉登录快照，模拟会话还在、登录变量已经没有的状态。 */
    void replaceSnapshotForTest(SessionKey key) {
        AccountState state = states.get(key);
        state.lock.lock();
        try {
            state.loginSnapshot.clear();
        } finally {
            state.lock.unlock();
        }
    }

    long epoch(SessionKey key) {
        return state(key).epoch;
    }

    /** 并行调用发现会话失效后，在独占锁下调用：期间没人重新登录过才登出。 */
    void markLoggedOutSince(SessionKey key, long epoch) {
        if (state(key).epoch == epoch) {
            markLoggedOut(key);
        }
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
        return seen(state);
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

    private void openClient(AccountState state) {
        OutboundTls tls = OutboundTls.current();
        PoolingHttpClientConnectionManager pool = OutboundHttp.connectionManager(
                tls,
                settings.headers(),
                settings.connectTimeout(),
                OutboundHttp.ENGINE_SOCKET,
                null,
                0,
                0);
        state.tls = tls;
        state.pool = pool;
        state.cookies = new BasicCookieStore();
        state.client = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultCookieStore(state.cookies)
                .setDefaultCookieSpecRegistry(LegacyCookies.registry())
                .addResponseInterceptorLast(LegacyCookies.notices())
                .addRequestInterceptorLast(HttpCalls.crossOriginStripper())
                .disableRedirectHandling()
                .setRetryStrategy(StaleConnectionRetry.INSTANCE)
                .build();
    }

    private static void closeClient(AccountState state) {
        if (state.tls != null) {
            state.tls.untrack(state.pool);
        }
        state.tls = null;
        state.pool = null;
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
        /** 写锁是原来的账号锁。读锁只给打开 concurrent_reads 的只读能力，持有期间不登录、不清会话。 */
        final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
        final ReentrantReadWriteLock.WriteLock lock = gate.writeLock();
        /** 并行的只读调用会同时写它。 */
        final Set<String> seenCookieValues = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        /** 每次登录、登出、作废加一。并行调用发现会话失效时，用它判断是不是已经有人重新登录过。 */
        long epoch;
        final Map<String, String> loginSnapshot = new LinkedHashMap<>();
        CloseableHttpClient client;
        BasicCookieStore cookies;
        OutboundTls tls;
        PoolingHttpClientConnectionManager pool;
        boolean loggedIn;
        volatile Instant lastActive;
        int authFailures;
        boolean loginPaused;
    }
}
