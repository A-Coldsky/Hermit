package dev.hermit.crab.tls;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * 把标准 PKIX 交给当前快照里的信任库。失败后再看指纹表。
 * 指纹命中且证书仍在有效期内就直接返回，不再做链校验和主机名校验。
 * 主机名比对发生在委托的信任库里，而且只在套接字或引擎的
 * {@code endpointIdentificationAlgorithm} 为 {@code HTTPS} 时才会做。
 * 没有套接字也没有引擎时直接拒绝，不查指纹，避免两参重载静默跳过主机名。
 * Apache 保持 {@code HostnameVerificationPolicy.BUILTIN}。指纹命中时不能再委托，否则主机名还会被查一次。
 */
final class DelegatingTrustManager extends X509ExtendedTrustManager {

    private final java.util.concurrent.atomic.AtomicReference<TrustSnapshot> snapshot;

    DelegatingTrustManager(java.util.concurrent.atomic.AtomicReference<TrustSnapshot> snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        snapshot.get().manager().checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        snapshot.get().manager().checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        snapshot.get().manager().checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        // 两参重载没有套接字，委托方不会做主机名校验。这里直接拒绝，也不查指纹。
        throw missingEndpoint();
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        if (!(socket instanceof SSLSocket ssl)) {
            throw missingEndpoint();
        }
        checkServer(chain, authType, ssl, null);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        if (engine == null) {
            throw missingEndpoint();
        }
        checkServer(chain, authType, null, engine);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return snapshot.get().manager().getAcceptedIssuers();
    }

    private static CertificateException missingEndpoint() {
        return new CertificateException("没有套接字或引擎，无法核对主机名");
    }

    private void checkServer(X509Certificate[] chain, String authType, SSLSocket socket, SSLEngine engine)
            throws CertificateException {
        if (socket == null && engine == null) {
            throw missingEndpoint();
        }
        TrustSnapshot current = snapshot.get();
        HandshakeRecord record = HandshakeRecord.capture(chain, socket, engine, current.anchorSubjects());
        try {
            X509ExtendedTrustManager delegate = current.manager();
            if (engine != null) {
                delegate.checkServerTrusted(chain, authType, engine);
            } else {
                delegate.checkServerTrusted(chain, authType, socket);
            }
        } catch (CertificateException ex) {
            HandshakeRecord.attach(ex, record);
            if (acceptPin(current, chain, record)) {
                return;
            }
            throw ex;
        }
    }

    private static boolean acceptPin(
            TrustSnapshot current,
            X509Certificate[] chain,
            HandshakeRecord record
    ) throws CertificateException {
        if (chain == null || chain.length == 0 || chain[0] == null || record == null) {
            return false;
        }
        String host = record.peerHost();
        int port = record.peerPort();
        if (host == null || host.isBlank() || port < 0) {
            return false;
        }
        HostPins.Pin pin = current.pins().find(host.toLowerCase(Locale.ROOT), port);
        if (pin == null || !pin.sha256().equalsIgnoreCase(TrustCert.sha256(chain[0]))) {
            return false;
        }
        try {
            chain[0].checkValidity();
        } catch (CertificateExpiredException ex) {
            CertificateExpiredException rejected = new CertificateExpiredException("证书已过期");
            rejected.initCause(ex);
            HandshakeRecord.attach(rejected, record);
            throw rejected;
        } catch (CertificateNotYetValidException ex) {
            CertificateNotYetValidException rejected = new CertificateNotYetValidException("证书尚未生效");
            rejected.initCause(ex);
            HandshakeRecord.attach(rejected, record);
            throw rejected;
        }
        return true;
    }
}
