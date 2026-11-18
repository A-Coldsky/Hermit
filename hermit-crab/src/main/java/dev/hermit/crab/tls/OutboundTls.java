package dev.hermit.crab.tls;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.util.TimeValue;

/**
 * 进程内唯一的出站 TLS 材料。六个客户端共用这一个 {@link SSLContext}。
 * 重新加载只替换快照，不重建客户端，也不清 Cookie。删掉信任锚或指纹时，清掉会话缓存和空闲连接。
 * {@code java.net.http} 没有公开的空闲连接池，会话失效是它能用的手段。
 */
public final class OutboundTls {

    private static final Logger LOG = Logger.getLogger(OutboundTls.class.getName());
    private static final AtomicReference<OutboundTls> SHARED = new AtomicReference<>();
    private static final AtomicReference<OutboundTls> CURRENT = new AtomicReference<>();

    private final SSLContext context;
    private final AtomicReference<TrustSnapshot> snapshot = new AtomicReference<>();
    private final AtomicReference<HostPins> pins = new AtomicReference<>(HostPins.NONE);
    private final CopyOnWriteArraySet<PoolingHttpClientConnectionManager> pools = new CopyOnWriteArraySet<>();

    private OutboundTls(java.nio.file.Path caDir, boolean systemTrust) {
        DelegatingTrustManager trustManager = new DelegatingTrustManager(snapshot);
        try {
            this.context = SSLContext.getInstance("TLS");
            this.snapshot.set(TrustSnapshot.load(caDir, systemTrust, HostPins.NONE));
            this.context.init(null, new javax.net.ssl.TrustManager[] {trustManager}, null);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("outbound TLS could not be initialized", ex);
        }
        logProblems(snapshot.get());
    }

    public static OutboundTls open(java.nio.file.Path caDir, boolean systemTrust) {
        return new OutboundTls(caDir, systemTrust);
    }

    /** 没有安装运行时实例时，客户端用 JDK 默认锚加上操作系统信任库。 */
    public static OutboundTls shared() {
        OutboundTls existing = SHARED.get();
        if (existing != null) {
            return existing;
        }
        OutboundTls created = open(null, true);
        if (SHARED.compareAndSet(null, created)) {
            return created;
        }
        return SHARED.get();
    }

    public static OutboundTls current() {
        OutboundTls installed = CURRENT.get();
        return installed != null ? installed : shared();
    }

    /** @return 换上之前的实例，关闭运行时时再交回 {@link #restore} */
    public static OutboundTls install(OutboundTls tls) {
        return CURRENT.getAndSet(Objects.requireNonNull(tls, "tls"));
    }

    public static void restore(OutboundTls previous) {
        CURRENT.set(previous);
    }

    public SSLContext sslContext() {
        return context;
    }

    /** {@code HostnameVerificationPolicy.BUILTIN}。指纹命中时由信任库直接返回，不再做主机名校验。 */
    public TlsSocketStrategy tlsStrategy() {
        return new DefaultClientTlsStrategy(context);
    }

    public HttpClient.Builder httpClientBuilder() {
        return HttpClient.newBuilder().sslContext(context);
    }

    public PoolingHttpClientConnectionManager track(PoolingHttpClientConnectionManager manager) {
        if (manager != null) {
            pools.add(manager);
        }
        return manager;
    }

    public void untrack(PoolingHttpClientConnectionManager manager) {
        if (manager != null) {
            pools.remove(manager);
        }
    }

    public List<String> loadErrors() {
        return snapshot.get().loadErrors();
    }

    public int anchorCount() {
        return snapshot.get().anchorCount();
    }

    public String systemSource() {
        return snapshot.get().systemSource();
    }

    public int systemCount() {
        return snapshot.get().systemCount();
    }

    /** JDK、操作系统、CA 目录各一条，给 {@code GET /console/api/tls/trust} 的 {@code sources}。 */
    public List<TrustSource> sources() {
        return snapshot.get().sources();
    }

    public List<CaDirectory.FileEntry> caFiles() {
        return snapshot.get().caFiles();
    }

    public void setPins(HostPins next) {
        HostPins updated = next == null ? HostPins.NONE : next;
        pins.set(updated);
        TrustSnapshot previous = snapshot.get();
        TrustSnapshot replacement = previous.withPins(updated);
        snapshot.set(replacement);
        if (removedTrust(previous, replacement)) {
            dropIdleSessions();
        }
    }

    public void reload(java.nio.file.Path caDir, boolean systemTrust) {
        TrustSnapshot previous = snapshot.get();
        TrustSnapshot replacement = TrustSnapshot.load(caDir, systemTrust, pins.get());
        snapshot.set(replacement);
        logProblems(replacement);
        if (removedTrust(previous, replacement)) {
            dropIdleSessions();
        }
    }

    private static boolean removedTrust(TrustSnapshot previous, TrustSnapshot next) {
        if (previous == null || next == null) {
            return false;
        }
        if (!next.fingerprints().containsAll(previous.fingerprints())) {
            return true;
        }
        return !next.pinKeys().containsAll(previous.pinKeys());
    }

    private void dropIdleSessions() {
        invalidate(context.getClientSessionContext());
        invalidate(context.getServerSessionContext());
        for (PoolingHttpClientConnectionManager pool : pools) {
            try {
                pool.closeIdle(TimeValue.ZERO_MILLISECONDS);
            } catch (RuntimeException ignored) {
                pools.remove(pool);
            }
        }
    }

    private static void invalidate(SSLSessionContext sessions) {
        if (sessions == null) {
            return;
        }
        java.util.Enumeration<byte[]> ids = sessions.getIds();
        while (ids.hasMoreElements()) {
            SSLSession session = sessions.getSession(ids.nextElement());
            if (session != null) {
                session.invalidate();
            }
        }
    }

    private static void logProblems(TrustSnapshot loaded) {
        if (loaded.systemError() != null) {
            LOG.warning("操作系统信任库没有加载：" + loaded.systemError());
        }
        ArrayList<String> extra = new ArrayList<>();
        for (String error : loaded.loadErrors()) {
            if (!error.startsWith("操作系统信任库没有加载")) {
                extra.add(error);
            }
        }
        if (!extra.isEmpty()) {
            LOG.warning("附加 CA：" + String.join("；", extra));
        }
    }
}
