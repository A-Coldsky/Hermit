package dev.hermit.crab.tls;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/**
 * 一次握手看到的对端证书链，以及当时信任锚的主题。
 * 校验失败时按异常对象挂上，调用线程和 {@code java.net.http} 的握手线程都能取回。
 */
final class HandshakeRecord {

    private static final Map<Throwable, HandshakeRecord> ATTACHED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final List<TrustCert> chain;
    private final Set<String> anchorSubjects;
    private final String peerHost;
    private final int peerPort;

    private HandshakeRecord(List<TrustCert> chain, Set<String> anchorSubjects, String peerHost, int peerPort) {
        this.chain = chain;
        this.anchorSubjects = anchorSubjects == null ? Set.of() : Set.copyOf(anchorSubjects);
        this.peerHost = peerHost;
        this.peerPort = peerPort;
    }

    static HandshakeRecord capture(
            X509Certificate[] certificates,
            SSLSocket socket,
            SSLEngine engine,
            Set<String> anchorSubjects
    ) {
        ArrayList<TrustCert> chain = new ArrayList<>();
        if (certificates != null) {
            for (X509Certificate certificate : certificates) {
                if (certificate != null) {
                    chain.add(TrustCert.of(certificate));
                }
            }
        }
        SSLSession session = session(socket, engine);
        String host = session == null ? null : session.getPeerHost();
        int port = session == null ? -1 : session.getPeerPort();
        if (socket != null) {
            if (host == null || host.isBlank()) {
                host = socket.getInetAddress() == null ? null : socket.getInetAddress().getHostAddress();
            }
            if (port < 0) {
                port = socket.getPort();
            }
        }
        return new HandshakeRecord(List.copyOf(chain), anchorSubjects, host, port);
    }

    /** 测试和分类器用。不经过一次真实握手。 */
    static HandshakeRecord of(List<TrustCert> chain, Set<String> anchorSubjects, String host, int port) {
        return new HandshakeRecord(
                List.copyOf(chain),
                anchorSubjects == null ? Set.of() : Set.copyOf(anchorSubjects),
                host,
                port);
    }

    static SSLSession session(SSLSocket socket, SSLEngine engine) {
        if (engine != null) {
            return engine.getHandshakeSession();
        }
        if (socket != null) {
            return socket.getHandshakeSession();
        }
        return null;
    }

    static void attach(Throwable failure, HandshakeRecord record) {
        if (failure == null || record == null) {
            return;
        }
        ATTACHED.put(failure, record);
    }

    static HandshakeRecord find(Throwable failure) {
        Throwable current = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            HandshakeRecord record = ATTACHED.get(current);
            if (record != null) {
                return record;
            }
            current = current.getCause();
        }
        return null;
    }

    List<TrustCert> chain() {
        return chain;
    }

    Set<String> anchorSubjects() {
        return anchorSubjects;
    }

    String peerHost() {
        return peerHost;
    }

    int peerPort() {
        return peerPort;
    }
}
