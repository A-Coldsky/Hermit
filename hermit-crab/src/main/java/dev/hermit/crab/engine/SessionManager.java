package dev.hermit.crab.engine;

import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.cookie.BasicCookieStore;
import org.apache.hc.client5.http.cookie.Cookie;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;

import org.apache.hc.core5.util.TimeValue;

import dev.hermit.crab.http.OutboundHttp;
import dev.hermit.crab.http.StaleConnectionRetry;
import dev.hermit.crab.tls.OutboundTls;

/**
 * 每个 (origin, conch, account) 一把公平锁、一个客户端和一个 Cookie 存储。
 * 使用者绑到同一个身份的账号共用这三样（{@link SharedSessions}）；是否已登录、登录变量、空闲计时和登录失败计数仍按账号。
 * 同地址范围的版本替换不会清掉这里的会话。
 * 地址范围扩大的本地覆盖会在写入前、以及 {@code registry.put} 成功后再对这个包的每个账号调用 {@link #invalidate}。
 */
public final class SessionManager implements AutoCloseable {

    /** 已见 Cookie 值去重后的上限；超出时按加入顺序丢掉最早的值。 */
    private static final int MAX_SEEN_COOKIE_VALUES = 64;
    /** 按 SessionKey 分段串行化无状态建新/迁移清理的条带锁数量；绝不与 Jar 锁反序嵌套。 */
    private static final int KEY_STRIPES = 64;
    /** 同一个成员在这段时间里最多因为「登录成功后又失效」清空一次身份的 Cookie。 */
    static final Duration RELAPSE_CLEAR_WINDOW = Duration.ofMinutes(10);
    /** 同一个授权器账号在 {@link #RELAPSE_CLEAR_WINDOW} 内，最多因为引用它的包判失效而重新登录这么多次。 */
    static final int MAX_LINKED_RELOGINS = 2;

    private final SessionPersistence persistence;
    private final LoginSnapshotStore loginSnapshots;
    private final EngineSettings settings;
    private final Clock clock;
    private final PendingLogins pending;
    private final ConcurrentMap<SessionKey, AccountState> states = new ConcurrentHashMap<>();
    private final SharedSessions shared;
    private final ConcurrentMap<String, Jar> jars = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Cleared> cleared = new ConcurrentHashMap<>();
    /** 绑定变了、状态要重建时带过去的登录失败次数和暂停：换个身份不等于凭证改对了。 */
    private final ConcurrentMap<SessionKey, Failures> carried = new ConcurrentHashMap<>();
    /** 全局单调会话代次，避免状态重建后 concurrent_reads 交接发生 ABA 碰撞。 */
    private final AtomicLong epochSeq = new AtomicLong();
    /** 按账号的作废代次：invalidate 或 clearIdentity 时递增，阻止过期的 seedIdentity 复活旧 Cookie。 */
    private final ConcurrentHashMap<SessionKey, AtomicLong> invalidationGen = new ConcurrentHashMap<>();
    private final Object[] keyGates = new Object[KEY_STRIPES];
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
        this(persistence, loginSnapshots, SharedSessions.none(), settings, clock);
    }

    public SessionManager(
            SessionPersistence persistence,
            LoginSnapshotStore loginSnapshots,
            SharedSessions shared,
            EngineSettings settings,
            Clock clock
    ) {
        this.shared = Objects.requireNonNull(shared, "shared");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.loginSnapshots = Objects.requireNonNull(loginSnapshots, "loginSnapshots");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pending = new PendingLogins(this.clock);
        for (int i = 0; i < keyGates.length; i++) {
            keyGates[i] = new Object();
        }
    }

    public PendingLogins pending() {
        return pending;
    }

    public Clock clock() {
        return clock;
    }

    public void resetLoginPause(SessionKey key) {
        while (true) {
            AccountState state = ensureState(key);
            state.lock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话管理器已关闭");
                }
                if (states.get(key) != state) {
                    continue;
                }
                state.loginPaused = false;
                state.authFailures = 0;
                carried.remove(key);
                return;
            } finally {
                state.lock.unlock();
            }
        }
    }

    public void invalidate(SessionKey key) {
        while (true) {
            AccountState state = states.get(key);
            if (state == null) {
                String identity;
                synchronized (keyGate(key)) {
                    state = states.get(key);
                    if (state == null) {
                        bumpInvalidationGen(key);
                        persistence.delete(key);
                        loginSnapshots.delete(key);
                        pending.discard(key);
                        identity = shared.borrows(key) ? null : shared.identityOf(key);
                    } else {
                        identity = null;
                    }
                }
                if (state == null) {
                    if (identity != null) {
                        // 绑了身份的账号：凭证变了，整个身份的 Cookie 都不能再用。
                        clearIdentity(identity);
                    }
                    if (states.get(key) == null) {
                        return;
                    }
                    continue;
                }
            }
            // 进行中的调用持有这把锁，并且会在返回前把 Cookie 再写回库。
            // 清空必须发生在它放开锁之后，下一次调用和重启才不会带回旧会话。
            state.lock.lock();
            try {
                if (states.get(key) != state) {
                    continue;
                }
                if (stale(key, state)) {
                    // 绑定刚变：先清掉旧状态，不把刚作废的独立 Cookie 带进新身份，再按新绑定清空。
                    evictStaleUnderLock(key, state);
                    continue;
                }
                bumpInvalidationGen(key);
                pending.discard(key);
                state.relapseClearedAt = null;
                if (state.jar.identity != null && shared.borrows(key)) {
                    // 借用授权器账号的罐：只作废这个账号自己换来的票，授权器和其它包的会话留着。
                    loginSnapshots.delete(key);
                    state.loggedIn = false;
                    state.epoch = epochSeq.incrementAndGet();
                    state.lastActive = null;
                    state.loginSnapshot.clear();
                    if (closed) {
                        states.remove(key, state);
                    }
                    return;
                }
                if (state.jar.identity != null) {
                    clearJar(state.jar);
                    if (closed) {
                        states.remove(key, state);
                    }
                    return;
                }
                persistence.delete(key);
                loginSnapshots.delete(key);
                state.loggedIn = false;
                state.epoch = epochSeq.incrementAndGet();
                state.lastActive = null;
                state.seenCookieValues.clear();
                state.loginSnapshot.clear();
                state.jar.persisted = null;
                closeClient(state.jar);
                if (closed) {
                    states.remove(key, state);
                } else if (states.get(key) == state) {
                    openClient(state.jar);
                }
                return;
            } finally {
                state.lock.unlock();
            }
        }
    }

    /**
     * 只清这一个账号自己的登录状态、登录快照和待输入登录。
     * 未绑身份时同时清掉它的独立 Cookie；已绑身份时不碰共享罐，避免误清同一身份里地址未变的其它成员。
     */
    public void clearAccountLogin(SessionKey key) {
        while (true) {
            AccountState state = states.get(key);
            if (state == null) {
                synchronized (keyGate(key)) {
                    state = states.get(key);
                    if (state == null) {
                        bumpInvalidationGen(key);
                        if (shared.identityOf(key) == null) {
                            persistence.delete(key);
                        }
                        loginSnapshots.delete(key);
                        pending.discard(key);
                        return;
                    }
                }
            }
            state.lock.lock();
            try {
                if (states.get(key) != state) {
                    continue;
                }
                if (stale(key, state)) {
                    evictStaleUnderLock(key, state);
                    continue;
                }
                bumpInvalidationGen(key);
                pending.discard(key);
                loginSnapshots.delete(key);
                state.loggedIn = false;
                state.epoch = epochSeq.incrementAndGet();
                state.lastActive = null;
                state.loginSnapshot.clear();
                if (state.jar.identity != null) {
                    if (closed) {
                        states.remove(key, state);
                    }
                    return;
                }
                persistence.delete(key);
                state.seenCookieValues.clear();
                state.jar.persisted = null;
                closeClient(state.jar);
                if (closed) {
                    states.remove(key, state);
                } else if (states.get(key) == state) {
                    openClient(state.jar);
                }
                return;
            } finally {
                state.lock.unlock();
            }
        }
    }

    /**
     * 绑定或解除之后调用：丢掉这个账号在内存里的旧状态，下一次调用按新的绑定重新建。
     * 账号原来自己的 Cookie、登录变量和待输入的验证码都清掉；刚加入身份、而身份还没有 Cookie 时，
     * 它原来的 Cookie 作为身份的初始内容。不同时持有两把锁。
     */
    public void rebound(SessionKey key) {
        Seed seed = null;
        while (true) {
            AccountState state = states.get(key);
            if (state == null) {
                synchronized (keyGate(key)) {
                    state = states.get(key);
                    if (state == null) {
                        long gen = invalidationGen(key);
                        List<StoredCookie> own = persistence.load(key);
                        boolean established = own != null && !own.isEmpty() && pending.current(key) == null;
                        persistence.delete(key);
                        loginSnapshots.delete(key);
                        pending.discard(key);
                        seed = own == null || own.isEmpty() ? null : new Seed(own, established, gen);
                        break;
                    }
                }
            }
            state.lock.lock();
            try {
                if (states.get(key) != state) {
                    continue;
                }
                if (!stale(key, state)) {
                    // 状态已经和当前绑定一致（例如已被并发调用先迁移好），不再抹掉它的新会话。
                    return;
                }
                seed = evictStaleUnderLock(key, state);
                break;
            } finally {
                state.lock.unlock();
            }
        }
        if (seed != null) {
            seedIdentity(key, seed);
        }
    }

    /** 调用方持有 {@code state.lock}，且已确认 {@code states.get(key) == state} 并与当前绑定不再一致。 */
    private Seed evictStaleUnderLock(SessionKey key, AccountState state) {
        long gen = invalidationGen(key);
        List<StoredCookie> own = state.jar.identity == null && state.jar.cookies != null
                ? dump(state.jar.cookies) : List.of();
        boolean established = state.jar.identity == null && state.loggedIn;
        persistence.delete(key);
        loginSnapshots.delete(key);
        pending.discard(key);
        carry(key, state);
        states.remove(key, state);
        if (state.jar.identity == null) {
            closeClient(state.jar);
        }
        return own.isEmpty() ? null : new Seed(own, established, gen);
    }

    private void seedIdentity(SessionKey key, Seed seed) {
        String identity = shared.identityOf(key);
        if (identity == null || seed == null || seed.cookies().isEmpty() || closed) {
            return;
        }
        if (invalidationGen(key) != seed.gen()) {
            return;
        }
        Jar jar = jars.computeIfAbsent(identity, this::sharedJar);
        jar.gate.writeLock().lock();
        try {
            seedIdentityUnderLock(key, identity, jar, seed);
        } finally {
            jar.gate.writeLock().unlock();
        }
    }

    private boolean seedIdentity(SessionKey key, Seed seed, long deadlineNanos) throws InterruptedException {
        String identity = shared.identityOf(key);
        if (identity == null || seed == null || seed.cookies().isEmpty() || closed) {
            return true;
        }
        if (invalidationGen(key) != seed.gen()) {
            return true;
        }
        Jar jar = jars.computeIfAbsent(identity, this::sharedJar);
        long wait = deadlineNanos - System.nanoTime();
        if (wait <= 0 || !jar.gate.writeLock().tryLock(wait, TimeUnit.NANOSECONDS)) {
            return false;
        }
        try {
            seedIdentityUnderLock(key, identity, jar, seed);
            return true;
        } finally {
            jar.gate.writeLock().unlock();
        }
    }

    private void seedIdentityUnderLock(SessionKey key, String identity, Jar jar, Seed seed) {
        if (closed || !identity.equals(shared.identityOf(key))) {
            if (jars.get(identity) == jar
                    && (closed || (shared.members(identity).isEmpty() && !hasStateForJar(jar)))) {
                jars.remove(identity, jar);
                closeClient(jar);
            }
            return;
        }
        if (invalidationGen(key) != seed.gen()) {
            return;
        }
        if (jars.get(identity) == jar && jar.cookies != null && jar.cookies.getCookies().isEmpty()) {
            synchronized (jar.seenCookieValues) {
                for (StoredCookie cookie : seed.cookies()) {
                    addCookie(jar.cookies, cookie);
                    rememberCookieValue(jar.seenCookieValues, cookie.value());
                }
            }
            if (seed.established()) {
                jar.sessionEstablished = true;
            }
            List<StoredCookie> snapshot = List.copyOf(dump(jar.cookies));
            shared.save(identity, snapshot);
            jar.persisted = snapshot;
        }
    }

    private record Seed(List<StoredCookie> cookies, boolean established, long gen) {
    }

    private record Failures(int count, boolean paused) {
    }

    /** 调用方持有这份状态的锁，随后把它移出 {@link #states}。 */
    private void carry(SessionKey key, AccountState state) {
        if (state.authFailures > 0 || state.loginPaused) {
            carried.put(key, new Failures(state.authFailures, state.loginPaused));
        }
    }

    /** 测试用：这个账号连续登录失败了几次。 */
    int authFailures(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            Failures kept = carried.get(key);
            return kept == null ? 0 : kept.count();
        }
        return state.authFailures;
    }

    /** 清空一个身份的 Cookie，成员都算登出。等进行中的调用放开锁之后才清。 */
    public void clearIdentity(String identity) {
        if (identity == null) {
            return;
        }
        while (true) {
            Jar jar = jars.get(identity);
            if (jar == null || closed) {
                for (SessionKey member : shared.members(identity)) {
                    bumpInvalidationGen(member);
                    loginSnapshots.delete(member);
                    pending.discard(member);
                }
                shared.delete(identity);
                cleared.remove(identity);
                if (closed || jars.get(identity) == null) {
                    return;
                }
                continue;
            }
            jar.gate.writeLock().lock();
            try {
                if (jars.get(identity) != jar) {
                    continue;
                }
                clearJar(jar);
                return;
            } finally {
                jar.gate.writeLock().unlock();
            }
        }
    }

    /**
     * 身份已经没有成员时清空它的 Cookie 并移除、关掉连接池；拿写锁后复查成员表，避免并发新绑进来的成员被误清。
     */
    public void clearIdentityIfEmpty(String identity) {
        if (identity == null) {
            return;
        }
        while (true) {
            Jar jar = jars.get(identity);
            if (jar == null || closed) {
                if (!shared.members(identity).isEmpty()) {
                    return;
                }
                shared.delete(identity);
                cleared.remove(identity);
                if (closed || jars.get(identity) == null) {
                    return;
                }
                continue;
            }
            jar.gate.writeLock().lock();
            try {
                if (jars.get(identity) != jar) {
                    continue;
                }
                if (!shared.members(identity).isEmpty()) {
                    return;
                }
                clearJar(jar);
                cleared.remove(identity);
                jars.remove(identity, jar);
                closeClient(jar);
                return;
            } finally {
                jar.gate.writeLock().unlock();
            }
        }
    }

    /** 最近一次「带着已有 Cookie 登录没成功、清空重来」是什么时候、哪个成员引起的。只在内存里。 */
    public Cleared lastCleared(String identity) {
        return identity == null ? null : cleared.get(identity);
    }

    public record Cleared(Instant at, SessionKey by) {
    }

    /** 身份已删除、成员都已 {@link #rebound}：关掉它的客户端。 */
    public void dropIdentity(String identity) {
        Jar jar = jars.get(identity);
        if (jar == null) {
            return;
        }
        jar.gate.writeLock().lock();
        try {
            jars.remove(identity, jar);
            cleared.remove(identity);
            closeClient(jar);
        } finally {
            jar.gate.writeLock().unlock();
        }
    }

    /** 这个账号是不是和别的账号共用 Cookie。调用方持有账号锁。 */
    boolean shared(SessionKey key) {
        return state(key).jar.identity != null;
    }

    /** 共用的罐里已经有已建立会话的 Cookie：这次登录带着它们跑，不从空白开始。调用方持有账号锁。 */
    boolean sharesCookies(SessionKey key) {
        Jar jar = state(key).jar;
        return jar.identity != null && jar.sessionEstablished
                && jar.cookies != null && !jar.cookies.getCookies().isEmpty();
    }

    /**
     * 清空这个身份的 Cookie，所有成员都算登出、登录变量作废。没绑身份的账号等同于 {@link #markLoggedOut}。
     * 调用方持有账号锁（身份的成员共用这一把）。
     */
    void clearShared(SessionKey key) {
        AccountState state = state(key);
        if (state.jar.identity == null) {
            markLoggedOut(key);
            return;
        }
        cleared.put(state.jar.identity, new Cleared(clock.instant(), key));
        clearJar(state.jar);
    }

    /**
     * 带着身份里已有的 Cookie 登录成功，同一次调用里能力又被判为会话失效。可能是留着的 Cookie 让登录看似成功，
     * 清空后从空白登录就好；也可能是规则把业务错误当成了失效，清空没有用，只会连累同身份的成员。两者分不清，
     * 所以同一个成员在 {@link #RELAPSE_CLEAR_WINDOW} 内只清空一次，窗口内再遇到只让它自己登出。
     * 返回是否清空了。调用方持有账号锁。
     */
    boolean clearSharedAfterRelapse(SessionKey key) {
        AccountState state = state(key);
        if (state.jar.identity == null) {
            markLoggedOut(key);
            return false;
        }
        Instant now = clock.instant();
        Instant last = state.relapseClearedAt;
        if (last != null && !now.isBefore(last) && now.isBefore(last.plus(RELAPSE_CLEAR_WINDOW))) {
            markLoggedOut(key);
            return false;
        }
        state.relapseClearedAt = now;
        clearShared(key);
        return true;
    }

    /**
     * 调用方持有 {@code holder} 的账号锁。让 {@code other} 的状态也可用，前提是两者共用同一个罐（同一把锁）：
     * 引用授权器的包的账号和它绑到的授权器账号。不共用时返回 false，什么都不建。
     */
    boolean join(SessionKey holder, SessionKey other) {
        AccountState mine = state(holder);
        if (mine.jar.identity == null || !mine.jar.identity.equals(shared.identityOf(other))) {
            return false;
        }
        AccountState theirs = ensureState(other);
        if (theirs.jar == mine.jar && !stale(other, theirs)) {
            return true;
        }
        // 对方的状态还是绑定变化之前的：这把锁管不到它，让调用方按「没配置好」处理，下一次调用会重建。
        return false;
    }

    /** 引用授权器的包上一次换票（或接手）时授权器账号的会话代次。调用方持有账号锁。 */
    long linkedEpoch(SessionKey key) {
        return state(key).linkedEpoch;
    }

    void linkedEpoch(SessionKey key, long epoch) {
        state(key).linkedEpoch = epoch;
    }

    /**
     * 引用授权器、又没写 {@code auth.exchange} 的包被判为会话失效，而授权器的探活是通过的：可能是导出的令牌比
     * 授权器的会话短命，重新登录授权器就好；也可能是规则把业务错误当成了失效。同一个包的账号在
     * {@link #RELAPSE_CLEAR_WINDOW} 内只让授权器重新登录一次，同一个授权器账号在窗口内最多
     * {@link #MAX_LINKED_RELOGINS} 次。返回这一次能不能重新登录，能的话已经记上。调用方持有账号锁。
     */
    boolean allowLinkedRelogin(SessionKey key, SessionKey authenticator) {
        AccountState mine = state(key);
        AccountState theirs = state(authenticator);
        Instant now = clock.instant();
        Instant last = mine.relapseClearedAt;
        if (last != null && !now.isBefore(last) && now.isBefore(last.plus(RELAPSE_CLEAR_WINDOW))) {
            return false;
        }
        theirs.linkedRelogins.removeIf(at -> now.isBefore(at) || !now.isBefore(at.plus(RELAPSE_CLEAR_WINDOW)));
        if (theirs.linkedRelogins.size() >= MAX_LINKED_RELOGINS) {
            return false;
        }
        mine.relapseClearedAt = now;
        theirs.linkedRelogins.add(now);
        return true;
    }

    /**
     * 只让这个账号登出，并删掉罐里只属于它的站点的 Cookie：域名就是 {@code base} 的主机（父域的不动）；
     * 主机同时是认证地址之一时，只删路径落在 {@code base} 路径下的。授权器和其它包的 Cookie 都留着。
     * 调用方持有账号锁。
     */
    void dropOwnCookies(SessionKey key, URI base, Set<String> authHosts) {
        AccountState state = state(key);
        markLoggedOut(key);
        if (base == null || base.getHost() == null || state.jar.cookies == null || state.jar.identity == null) {
            return;
        }
        String host = base.getHost().toLowerCase(java.util.Locale.ROOT);
        String prefix = base.getRawPath() == null || base.getRawPath().isEmpty() ? "/" : base.getRawPath();
        boolean sharedHost = authHosts != null && authHosts.contains(host);
        if (sharedHost && "/".equals(prefix)) {
            return;
        }
        List<Cookie> kept = new ArrayList<>();
        boolean removed = false;
        for (Cookie cookie : state.jar.cookies.getCookies()) {
            String domain = cookie.getDomain() == null ? "" : cookie.getDomain().toLowerCase(java.util.Locale.ROOT);
            if (domain.startsWith(".")) {
                domain = domain.substring(1);
            }
            boolean own = domain.equals(host) && (!sharedHost || underPath(cookie.getPath(), prefix));
            if (own) {
                removed = true;
            } else {
                kept.add(cookie);
            }
        }
        if (!removed) {
            return;
        }
        synchronized (state.seenCookieValues) {
            state.jar.cookies.clear();
            for (Cookie cookie : kept) {
                state.jar.cookies.addCookie(cookie);
            }
        }
        persist(key);
    }

    private static boolean underPath(String cookiePath, String prefix) {
        if (cookiePath == null) {
            return false;
        }
        String base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return cookiePath.equals(base) || cookiePath.startsWith(base + "/");
    }

    /** 测试登录用：绑了身份的账号清空身份的 Cookie，不记「清空重来」。调用方持有账号锁。 */
    void resetShared(SessionKey key) {
        AccountState state = state(key);
        state.relapseClearedAt = null;
        if (state.jar.identity != null) {
            clearJar(state.jar);
        }
    }

    private void clearJar(Jar jar) {
        if (jar.cookies != null) {
            jar.cookies.clear();
        }
        jar.sessionEstablished = false;
        LinkedHashSet<SessionKey> members = new LinkedHashSet<>(shared.members(jar.identity));
        for (Map.Entry<SessionKey, AccountState> entry : states.entrySet()) {
            AccountState member = entry.getValue();
            if (member.jar != jar) {
                continue;
            }
            member.loggedIn = false;
            member.epoch = epochSeq.incrementAndGet();
            member.lastActive = null;
            member.loginSnapshot.clear();
            members.add(entry.getKey());
        }
        for (SessionKey member : members) {
            bumpInvalidationGen(member);
            loginSnapshots.delete(member);
            // 等着验证码的登录是带着刚清掉的 Cookie 走到一半的，接不下去了：下一次调用从头登录。
            pending.discard(member);
        }
        shared.delete(jar.identity);
        jar.persisted = null;
    }

    /**
     * 账号已删除：丢掉这把锁、客户端和 Cookie，不再留在内存里。以前只 {@link #invalidate} 重开客户端，
     * 删掉的账号各自留着一个连接池。
     */
    public void forget(SessionKey key) {
        while (true) {
            AccountState state = states.get(key);
            if (state == null) {
                synchronized (keyGate(key)) {
                    state = states.get(key);
                    if (state == null) {
                        bumpInvalidationGen(key);
                        persistence.delete(key);
                        loginSnapshots.delete(key);
                        pending.discard(key);
                        carried.remove(key);
                        return;
                    }
                }
            }
            // 进行中的调用可能持有读锁或写锁并在返回前写库；等它放开后再删库和移出状态。
            state.lock.lock();
            try {
                if (states.get(key) != state) {
                    continue;
                }
                bumpInvalidationGen(key);
                persistence.delete(key);
                loginSnapshots.delete(key);
                pending.discard(key);
                states.remove(key, state);
                carried.remove(key);
                // 身份的罐留给其它成员。
                if (state.jar.identity == null) {
                    closeClient(state.jar);
                }
                return;
            } finally {
                state.lock.unlock();
            }
        }
    }

    public boolean tryLock(SessionKey key, long deadlineNanos) throws InterruptedException {
        while (true) {
            if (closed) {
                throw new IllegalStateException("会话管理器已关闭");
            }
            AccountState state = ensureState(key);
            long wait = deadlineNanos - System.nanoTime();
            if (wait <= 0) {
                return false;
            }
            if (!state.lock.tryLock(wait, TimeUnit.NANOSECONDS)) {
                return false;
            }
            if (closed) {
                state.lock.unlock();
                throw new IllegalStateException("会话管理器已关闭");
            }
            if (states.get(key) == state && !stale(key, state)) {
                return true;
            }
            // 等锁期间被 forget 了，或绑定变了：这份状态不能再用，换新的再等。
            Seed seed = null;
            try {
                if (states.get(key) == state) {
                    seed = evictStaleUnderLock(key, state);
                }
            } finally {
                state.lock.unlock();
            }
            if (seed != null && !seedIdentity(key, seed, deadlineNanos)) {
                return false;
            }
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
     * login in this process, so it is not paused. Reads volatile state without blocking on the
     * account lock.
     */
    public boolean loginPaused(SessionKey key) {
        AccountState state = states.get(key);
        if (state != null) {
            return state.loginPaused;
        }
        Failures kept = carried.get(key);
        return kept != null && kept.paused();
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
        state.epoch = epochSeq.incrementAndGet();
        state.lastActive = clock.instant();
        if (state.jar.identity != null) {
            state.jar.sessionEstablished = true;
        }
    }

    void markLoggedOut(SessionKey key) {
        AccountState state = state(key);
        state.loggedIn = false;
        state.epoch = epochSeq.incrementAndGet();
        state.lastActive = null;
        // 共用的 Cookie 不清：别的成员和认证中心的登录还在里面，这个成员带着它们重新登录。
        if (state.jar.identity == null) {
            state.jar.cookies.clear();
        }
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

    /**
     * Cookie 罐里同名的值，只看会发往 {@code base} 主机的。没有则为 null。空字符串算有值。
     * {@code base} 为 null 时不按主机筛。
     */
    String cookieValue(SessionKey key, String name, URI base) {
        AccountState state = state(key);
        if (name == null || state.jar.cookies == null) {
            return null;
        }
        for (Cookie cookie : state.jar.cookies.getCookies()) {
            if (name.equals(cookie.getName()) && AuthReach.readable(cookie, base, state.jar.cookies)) {
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
            return collectRedaction(state, true);
        } finally {
            state.gate.readLock().unlock();
        }
    }

    /**
     * 非阻塞读取打码名单：等锁超时或被中断时另一线程可能仍持有写锁，不能在这里死等读锁。
     * 能拿到读锁时与 {@link #redactionValues} 相同；拿不到时仍读出线程安全的已见 Cookie 与 Cookie 罐。
     */
    List<String> redactionValuesNow(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            return List.of();
        }
        if (state.gate.readLock().tryLock()) {
            try {
                return collectRedaction(state, true);
            } finally {
                state.gate.readLock().unlock();
            }
        }
        return collectRedaction(state, false);
    }

    private static List<String> collectRedaction(AccountState state, boolean locked) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        addRedaction(values, seen(state));
        BasicCookieStore jar = state.jar.cookies;
        if (jar != null) {
            ArrayList<String> cookies = new ArrayList<>();
            for (Cookie cookie : jar.getCookies()) {
                cookies.add(cookie.getValue());
            }
            addRedaction(values, cookies);
        }
        if (locked) {
            addRedaction(values, state.loginSnapshot.values());
        }
        return List.copyOf(values);
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
        return state(key).jar.client;
    }

    BasicCookieStore cookies(SessionKey key) {
        return state(key).jar.cookies;
    }

    void persist(SessionKey key) {
        AccountState state = state(key);
        // 并行的只读调用各自写库：取罐和写库放在一起，后写的一定是更新的那份。
        synchronized (state.seenCookieValues) {
            List<StoredCookie> cookies = dump(state.jar.cookies);
            for (StoredCookie cookie : cookies) {
                rememberCookieValue(state.seenCookieValues, cookie.value());
            }
            List<StoredCookie> snapshot = List.copyOf(cookies);
            // 每次上游交换之后都会走到这里；Cookie 没变就不再加密、写库。
            if (snapshot.equals(state.jar.persisted)) {
                return;
            }
            if (state.jar.identity == null) {
                persistence.save(key, snapshot);
            } else {
                shared.save(state.jar.identity, snapshot);
            }
            state.jar.persisted = snapshot;
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
            if (closed) {
                throw new IllegalStateException("会话管理器已关闭");
            }
            AccountState state = ensureState(key);
            long wait = deadlineNanos - System.nanoTime();
            if (wait <= 0) {
                return false;
            }
            if (!state.gate.readLock().tryLock(wait, TimeUnit.NANOSECONDS)) {
                return false;
            }
            if (closed) {
                state.gate.readLock().unlock();
                throw new IllegalStateException("会话管理器已关闭");
            }
            if (states.get(key) == state && !stale(key, state)) {
                return true;
            }
            state.gate.readLock().unlock();
            if (closed) {
                throw new IllegalStateException("会话管理器已关闭");
            }
            if (states.get(key) != state) {
                continue;
            }
            // 绑定变了：必须在剩余超时内升级成写锁来迁移旧状态，不能无界阻塞。
            wait = deadlineNanos - System.nanoTime();
            if (wait <= 0) {
                return false;
            }
            if (!state.lock.tryLock(wait, TimeUnit.NANOSECONDS)) {
                return false;
            }
            if (closed) {
                state.lock.unlock();
                throw new IllegalStateException("会话管理器已关闭");
            }
            Seed seed = null;
            try {
                if (states.get(key) == state && stale(key, state)) {
                    seed = evictStaleUnderLock(key, state);
                }
            } finally {
                state.lock.unlock();
            }
            if (seed != null && !seedIdentity(key, seed, deadlineNanos)) {
                return false;
            }
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
    boolean markLoggedOutSince(SessionKey key, long epoch) {
        if (state(key).epoch == epoch) {
            markLoggedOut(key);
            return true;
        }
        return false;
    }

    /**
     * 关掉每个账号连接池里闲置超过 {@code idle} 的连接和已过期的连接，返回处理过的连接池个数。
     * 只动空闲连接：借出去的不受影响，Cookie、登录状态和账号锁都不变，下一次调用重新建连。
     * 不拿账号锁，长调用不会挡住回收。
     */
    public int closeIdleConnections(Duration idle) {
        Objects.requireNonNull(idle, "idle");
        TimeValue limit = TimeValue.ofMilliseconds(Math.max(0, idle.toMillis()));
        int pools = 0;
        Set<Jar> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (AccountState state : states.values()) {
            if (closeIdle(state.jar, limit, seen)) {
                pools++;
            }
        }
        for (Jar jar : jars.values()) {
            if (closeIdle(jar, limit, seen)) {
                pools++;
            }
        }
        return pools;
    }

    private static boolean closeIdle(Jar jar, TimeValue limit, Set<Jar> seen) {
        if (jar == null) {
            return false;
        }
        PoolingHttpClientConnectionManager pool = jar.pool;
        // 身份的成员共用一个连接池，只算一次。
        if (pool == null || !seen.add(jar)) {
            return false;
        }
        // 连接池自己是线程安全的；这时被 forget、invalidate 或 dropIdentity 关掉也只是空操作。
        pool.closeExpired();
        pool.closeIdle(limit);
        return true;
    }

    /** 这个账号连接池里空闲着的连接数。 */
    int idleConnections(SessionKey key) {
        AccountState state = states.get(key);
        PoolingHttpClientConnectionManager pool = state == null ? null : state.jar.pool;
        return pool == null ? 0 : pool.getTotalStats().getAvailable();
    }

    /** Accounts with state in memory. */
    int size() {
        return states.size();
    }

    /** Shared identity jars currently in memory. */
    int identityJarCount() {
        return jars.size();
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
        return cookieNames(key, null);
    }

    /** 只返回会发往 {@code base} 主机的 Cookie 名；{@code base} 为 null 时不过滤。 */
    List<String> cookieNames(SessionKey key, URI base) {
        AccountState state = states.get(key);
        BasicCookieStore jar = state == null ? null : state.jar.cookies;
        if (jar == null) {
            return List.of();
        }
        ArrayList<String> names = new ArrayList<>();
        for (Cookie cookie : jar.getCookies()) {
            if (cookie.getName() != null && (base == null || AuthReach.readable(cookie, base, jar))) {
                names.add(cookie.getName());
            }
        }
        return List.copyOf(names);
    }

    @Override
    public void close() {
        closed = true;
        while (!states.isEmpty()) {
            for (Map.Entry<SessionKey, AccountState> entry : states.entrySet()) {
                AccountState state = entry.getValue();
                Jar jar = state.jar;
                jar.gate.writeLock().lock();
                try {
                    // 在放开锁之前一次性移出所有共用这只罐的状态及罐本身，
                    // 让等锁线程看到状态已失效，不会拿到已关掉的 client / cookies。
                    for (Map.Entry<SessionKey, AccountState> member : states.entrySet()) {
                        if (member.getValue().jar == jar) {
                            states.remove(member.getKey(), member.getValue());
                        }
                    }
                    if (jar.identity != null) {
                        jars.remove(jar.identity, jar);
                    }
                    closeClient(jar);
                } finally {
                    jar.gate.writeLock().unlock();
                }
            }
        }
        while (!jars.isEmpty()) {
            for (Map.Entry<String, Jar> entry : jars.entrySet()) {
                String identity = entry.getKey();
                Jar jar = entry.getValue();
                jar.gate.writeLock().lock();
                try {
                    for (Map.Entry<SessionKey, AccountState> member : states.entrySet()) {
                        if (member.getValue().jar == jar) {
                            states.remove(member.getKey(), member.getValue());
                        }
                    }
                    jars.remove(identity, jar);
                    closeClient(jar);
                } finally {
                    jar.gate.writeLock().unlock();
                }
            }
        }
    }

    private Object keyGate(SessionKey key) {
        return keyGates[(key.hashCode() & 0x7fffffff) % KEY_STRIPES];
    }

    private long invalidationGen(SessionKey key) {
        AtomicLong counter = invalidationGen.get(key);
        return counter == null ? 0L : counter.get();
    }

    private void bumpInvalidationGen(SessionKey key) {
        invalidationGen.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
    }

    private boolean hasStateForJar(Jar jar) {
        for (AccountState state : states.values()) {
            if (state.jar == jar) {
                return true;
            }
        }
        return false;
    }

    private void dropOrphanJar(String identity, Jar jar) {
        jar.gate.writeLock().lock();
        try {
            if (jars.get(identity) == jar
                    && (closed || (shared.members(identity).isEmpty() && !hasStateForJar(jar)))) {
                jars.remove(identity, jar);
                closeClient(jar);
            }
        } finally {
            jar.gate.writeLock().unlock();
        }
    }

    private AccountState state(SessionKey key) {
        AccountState state = states.get(key);
        if (state == null) {
            throw new IllegalStateException("会话尚未加锁");
        }
        return state;
    }

    private boolean stale(SessionKey key, AccountState state) {
        String identity = shared.identityOf(key);
        if (!Objects.equals(identity, state.jar.identity)) {
            return true;
        }
        return identity != null && jars.get(identity) != state.jar;
    }

    private AccountState ensureState(SessionKey key) {
        AccountState existing = states.get(key);
        if (existing != null) {
            return existing;
        }
        synchronized (keyGate(key)) {
            existing = states.get(key);
            if (existing != null) {
                return existing;
            }
            Failures kept = carried.get(key);
            AccountState created = newState(key, kept);
            states.put(key, created);
            if (kept != null) {
                carried.remove(key, kept);
            }
            if (closed) {
                created.lock.lock();
                try {
                    if (created.jar.identity == null) {
                        states.remove(key, created);
                        closeClient(created.jar);
                    } else {
                        for (Map.Entry<SessionKey, AccountState> member : states.entrySet()) {
                            if (member.getValue().jar == created.jar) {
                                states.remove(member.getKey(), member.getValue());
                            }
                        }
                        if (jars.remove(created.jar.identity, created.jar)) {
                            closeClient(created.jar);
                        }
                    }
                } finally {
                    created.lock.unlock();
                }
                throw new IllegalStateException("会话管理器已关闭");
            }
            return created;
        }
    }

    private AccountState newState(SessionKey key, Failures kept) {
        while (true) {
            if (closed) {
                throw new IllegalStateException("会话管理器已关闭");
            }
            String identity = shared.identityOf(key);
            Jar jar = identity == null ? privateJar(key) : jars.computeIfAbsent(identity, this::sharedJar);
            if (identity != null && (closed || !identity.equals(shared.identityOf(key)))) {
                dropOrphanJar(identity, jar);
                if (closed) {
                    throw new IllegalStateException("会话管理器已关闭");
                }
                continue;
            }
            AccountState state = new AccountState(jar, epochSeq.incrementAndGet());
            // 绑了身份的成员总是从「未登录」开始：罐里的 Cookie 是不是对它有效，要探活或登录一次才知道。
            if (identity == null && !jar.cookies.getCookies().isEmpty()) {
                state.loggedIn = true;
                state.lastActive = Instant.EPOCH;
            }
            Map<String, String> snapshot;
            try {
                snapshot = loginSnapshots.load(key);
            } catch (RuntimeException ex) {
                if (identity == null) {
                    closeClient(jar);
                } else if (closed || !identity.equals(shared.identityOf(key))) {
                    dropOrphanJar(identity, jar);
                }
                throw ex;
            }
            if (closed) {
                if (identity == null) {
                    closeClient(jar);
                } else {
                    dropOrphanJar(identity, jar);
                }
                throw new IllegalStateException("会话管理器已关闭");
            }
            if (snapshot != null) {
                state.loginSnapshot.putAll(snapshot);
            }
            if (kept != null) {
                state.authFailures = kept.count();
                state.loginPaused = kept.paused();
            }
            return state;
        }
    }

    private Jar privateJar(SessionKey key) {
        return fill(new Jar(null), () -> persistence.load(key));
    }

    private Jar sharedJar(String identity) {
        if (closed) {
            throw new IllegalStateException("会话管理器已关闭");
        }
        Jar jar = fill(new Jar(identity), () -> shared.load(identity));
        if (!jar.cookies.getCookies().isEmpty()) {
            jar.sessionEstablished = true;
        }
        return jar;
    }

    private Jar fill(Jar jar, java.util.function.Supplier<List<StoredCookie>> load) {
        openClient(jar);
        List<StoredCookie> loaded;
        try {
            loaded = load.get();
        } catch (RuntimeException ex) {
            closeClient(jar);
            throw ex;
        }
        if (loaded == null) {
            loaded = List.of();
        }
        for (StoredCookie cookie : loaded) {
            addCookie(jar.cookies, cookie);
            rememberCookieValue(jar.seenCookieValues, cookie.value());
        }
        return jar;
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

    private void openClient(Jar state) {
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
        state.cookies = state.identity == null ? new BasicCookieStore() : new SharedCookieStore();
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

    private static void closeClient(Jar state) {
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
                    httpOnly,
                    cookie.getDomain() != null && !cookie.containsAttribute(Cookie.DOMAIN_ATTR)));
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
            if (!cookie.hostOnly()) {
                restored.setAttribute(Cookie.DOMAIN_ATTR, cookie.domain());
            }
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

    /** Cookie、客户端和锁。没绑身份的账号各有一个；绑到同一个身份的账号共用一个。 */
    private static final class Jar {
        /** 身份 id；账号自己的罐为 null。 */
        final String identity;
        /** 写锁是原来的账号锁。读锁只给打开 concurrent_reads 的只读能力，持有期间不登录、不清会话。 */
        final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
        /** 并行的只读调用会同时写它。 */
        final Set<String> seenCookieValues = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        CloseableHttpClient client;
        volatile BasicCookieStore cookies;
        /** 是否已由任一成员完成登录、从持久化加载或从已登录成员播种；防止 ask 暂停期写入的登录前 Cookie 误导同身份其它成员。 */
        volatile boolean sessionEstablished;
        /** 上一次写进库里的 Cookie；null 表示库里的内容未知或已被删除。 */
        List<StoredCookie> persisted;
        OutboundTls tls;
        volatile PoolingHttpClientConnectionManager pool;

        Jar(String identity) {
            this.identity = identity;
        }
    }

    /** 一个账号自己的状态：是否已登录、登录变量、空闲计时、登录失败计数。Cookie 和锁在 {@link Jar} 里。 */
    private static final class AccountState {
        final Jar jar;
        final ReentrantReadWriteLock gate;
        final ReentrantReadWriteLock.WriteLock lock;
        final Set<String> seenCookieValues;
        /** 全局单调会话代次。并行调用发现会话失效时，用它判断是不是已经有人重新登录过。 */
        long epoch;
        final Map<String, String> loginSnapshot = new LinkedHashMap<>();
        boolean loggedIn;
        volatile Instant lastActive;
        volatile int authFailures;
        volatile boolean loginPaused;
        /**
         * 上一次因为「带着旧 Cookie 登录成功、能力却又失效」清空身份的时间。只在持有账号锁时读写，
         * 所以改凭证和测试登录清掉它之后，进行中的调用不会再把它写回来；状态重建（绑定变了、删除账号）时随之丢弃。
         */
        Instant relapseClearedAt;
        /** 引用授权器的包：上一次换票或接手时授权器账号的会话代次。0 表示还没有。只在持有账号锁时读写。 */
        long linkedEpoch;
        /** 授权器账号：因为引用它的包判失效而重新登录的时间。只在持有账号锁时读写。 */
        final List<Instant> linkedRelogins = new ArrayList<>();

        AccountState(Jar jar, long initialEpoch) {
            this.jar = jar;
            this.gate = jar.gate;
            this.lock = jar.gate.writeLock();
            this.seenCookieValues = jar.seenCookieValues;
            this.epoch = initialEpoch;
        }
    }
}
