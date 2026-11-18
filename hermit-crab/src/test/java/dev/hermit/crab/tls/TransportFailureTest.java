package dev.hermit.crab.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.testing.LabCertificates;
import dev.hermit.crab.testing.MutableClock;

import java.nio.file.Path;

class TransportFailureTest {

    @AfterEach
    void resetLog() {
        TransportLog.resetForTests();
    }

    @Test
    void classifiesEachKindFromTheCauseChain(@TempDir Path dir) throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(dir);
        TrustCert leaf = TrustCert.of(certs.leaf());
        TrustCert intermediate = TrustCert.of(certs.intermediate());
        String rootSubject = TrustCert.dn(certs.root().getSubjectX500Principal());

        TransportFailure.View dns = TransportFailure.classify(
                caused(new ConnectException(), new UnresolvedAddressException()), "mail.internal", 443);
        assertThat(dns.kind()).isEqualTo(TransportFailure.Kind.DNS);
        assertThat(dns.message()).isEqualTo("无法解析上游地址 mail.internal");

        assertThat(TransportFailure.isDns(new UnknownHostException("hermit-rejected-address"))).isFalse();
        TransportFailure.View rejectedAddress = TransportFailure.classify(
                new UnknownHostException("hermit-rejected-address"), "10.0.0.1", 443);
        assertThat(rejectedAddress.kind()).isNotEqualTo(TransportFailure.Kind.DNS);

        TransportFailure.View refused = TransportFailure.classify(
                new ConnectException("Connection refused"), "10.1.2.3", 8443);
        assertThat(refused.kind()).isEqualTo(TransportFailure.Kind.REFUSED);
        assertThat(refused.message()).isEqualTo("上游拒绝连接（10.1.2.3:8443）");
        assertThat(TransportFailure.classify(new ConnectException(""), "10.1.2.3", 9).kind())
                .isEqualTo(TransportFailure.Kind.OTHER);
        assertThat(TransportFailure.classify(new ConnectException(), "10.1.2.3", 9).kind())
                .isEqualTo(TransportFailure.Kind.OTHER);

        SocketTimeoutException timeout = new SocketTimeoutException("Read timed out");
        timeout.initCause(new ConnectException("Connection refused"));
        TransportFailure.View timed = TransportFailure.classify(timeout, "10.1.2.3", 443);
        assertThat(timed.kind()).isEqualTo(TransportFailure.Kind.TIMEOUT);
        assertThat(TransportFailure.isTimeout(timeout)).isTrue();

        CertificateException hostname = new CertificateException(
                "No subject alternative names matching IP address 127.0.0.1 found");
        SSLHandshakeException hostnameOuter = new SSLHandshakeException("(certificate_unknown) " + hostname.getMessage());
        hostnameOuter.initCause(hostname);
        TransportFailure.View name = TransportFailure.classify(hostnameOuter, "127.0.0.1", 443);
        assertThat(name.kind()).isEqualTo(TransportFailure.Kind.TLS_HOSTNAME);
        assertThat(name.message()).isEqualTo("上游证书与主机名 127.0.0.1 不符");

        HandshakeRecord expiredRecord = HandshakeRecord.of(List.of(TrustCert.of(certs.expired())), Set.of(), "127.0.0.1", 443);
        CertificateExpiredException expired = new CertificateExpiredException("NotAfter");
        HandshakeRecord.attach(expired, expiredRecord);
        TransportFailure.View expiredView = TransportFailure.classify(expired, "127.0.0.1", 443);
        assertThat(expiredView.kind()).isEqualTo(TransportFailure.Kind.TLS_EXPIRED);
        assertThat(expiredView.message()).isEqualTo("上游证书已过期（2020-01-02）");

        HandshakeRecord futureRecord = HandshakeRecord.of(
                List.of(TrustCert.of(certs.notYetValid())), Set.of(), "127.0.0.1", 443);
        CertificateNotYetValidException pending = new CertificateNotYetValidException("NotBefore");
        HandshakeRecord.attach(pending, futureRecord);
        assertThat(TransportFailure.classify(pending, "127.0.0.1", 443).message())
                .isEqualTo("上游证书尚未生效（2035-01-01）");

        CertificateException pkix = new CertificateException(
                "PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: "
                        + "unable to find valid certification path to requested target");
        HandshakeRecord leafOnly = HandshakeRecord.of(List.of(leaf), Set.of(rootSubject), "lab.example", 443);
        HandshakeRecord.attach(pkix, leafOnly);
        SSLHandshakeException prefixed = new SSLHandshakeException("(certificate_unknown) " + pkix.getMessage());
        prefixed.initCause(pkix);
        HandshakeRecord.attach(prefixed, leafOnly);
        TransportFailure.View untrusted = TransportFailure.classify(prefixed, "lab.example", 443);
        assertThat(untrusted.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
        assertThat(prefixed.getMessage()).doesNotStartWith("PKIX");
        assertThat(untrusted.message()).contains("Hermit Lab Root").contains("HTTPS 信任").doesNotContain("没有发送中间证书");
        assertThat(untrusted.chain()).hasSize(1);
        assertThat(untrusted.chain().get(0).get("sha256")).isEqualTo(leaf.sha256());
        assertThat(untrusted.fields()).containsKeys("kind", "host", "port", "cause", "chain");

        HandshakeRecord withIssuer = HandshakeRecord.of(
                List.of(TrustCert.of(certs.intermediateLeaf()), intermediate),
                Set.of(rootSubject),
                "lab.example",
                443);
        CertificateException missing = new CertificateException("unable to find valid certification path to requested target");
        HandshakeRecord.attach(missing, withIssuer);
        TransportFailure.View intermediateView = TransportFailure.classify(missing, "lab.example", 443);
        assertThat(intermediateView.kind()).isEqualTo(TransportFailure.Kind.TLS_INTERMEDIATE);
        assertThat(intermediateView.message()).contains("没有发送中间证书").contains("Hermit Lab Intermediate");

        HandshakeRecord leafSignedByIntermediate = HandshakeRecord.of(
                List.of(TrustCert.of(certs.intermediateLeaf())), Set.of(rootSubject), "lab.example", 443);
        CertificateException sameText = new CertificateException("unable to find valid certification path to requested target");
        HandshakeRecord.attach(sameText, leafSignedByIntermediate);
        TransportFailure.View leafOnlyUntrusted = TransportFailure.classify(sameText, "lab.example", 443);
        assertThat(leafOnlyUntrusted.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
        assertThat(leafOnlyUntrusted.message()).contains("Hermit Lab Intermediate").doesNotContain("没有发送中间证书");

        SSLHandshakeException aki = new SSLHandshakeException("Missing Authority Key Identifier");
        TransportFailure.View otherTls = TransportFailure.classify(aki, "lab.example", 443);
        assertThat(otherTls.kind()).isEqualTo(TransportFailure.Kind.TLS_OTHER);
        assertThat(otherTls.message()).startsWith("与上游的 TLS 握手失败：").contains("Missing Authority Key Identifier");
        assertThat(otherTls.message()).doesNotContain("不受信任");

        TransportFailure.View other = TransportFailure.classify(new ConnectException("reset"), "h", 1);
        assertThat(other.kind()).isEqualTo(TransportFailure.Kind.OTHER);
        assertThat(other.message()).isEqualTo("上游连接失败：ConnectException");

        String longCause = "😀".repeat(301);
        TransportFailure.View clipped = TransportFailure.classify(new SSLHandshakeException(longCause), "h", 1);
        assertThat(clipped.cause().codePointCount(0, clipped.cause().length())).isEqualTo(300);
        assertThat(clipped.cause()).startsWith("SSLHandshakeException: ");
    }

    @Test
    void legacyTlsAndHttpFailuresKeepTheUserSentenceFreeOfTheCause() {
        String secret = "token-secret-value";
        TransportFailure.View protocol = TransportFailure.classify(
                new SSLHandshakeException("Received fatal alert: protocol_version " + secret), "hr", 443);
        assertThat(protocol.kind()).isEqualTo(TransportFailure.Kind.TLS_PROTOCOL);
        assertThat(protocol.message()).isEqualTo("对方只支持 TLS 1.0/1.1 等旧协议，Crab 默认不连。");
        assertThat(protocol.message()).doesNotContain(secret);
        assertThat(protocol.cause()).startsWith("SSLHandshakeException: ").contains(secret);

        TransportFailure.View protocolFirst = TransportFailure.classify(
                new SSLHandshakeException("No appropriate protocol (protocol is disabled or cipher suites are inappropriate)"),
                "hr", 443);
        assertThat(protocolFirst.kind()).isEqualTo(TransportFailure.Kind.TLS_PROTOCOL);

        TransportFailure.View handshake = TransportFailure.classify(
                new SSLHandshakeException("Received fatal alert: handshake_failure"), "hr", 443);
        assertThat(handshake.kind()).isEqualTo(TransportFailure.Kind.TLS_CIPHER);
        assertThat(handshake.message()).isEqualTo(
                "TLS 握手失败，常见原因是对方只提供已禁用的加密套件（如 3DES、RC4、静态 RSA、弱 DH）。");
        TransportFailure.View dh = TransportFailure.classify(
                new SSLHandshakeException("DH ServerKeyExchange does not comply to algorithm constraints"), "hr", 443);
        assertThat(dh.kind()).isEqualTo(TransportFailure.Kind.TLS_CIPHER);
        assertThat(dh.message()).isEqualTo("对方只提供已禁用的加密套件（如 3DES、RC4、静态 RSA、弱 DH）。");
        TransportFailure.View both = TransportFailure.classify(
                new SSLHandshakeException("handshake_failure: DH key does not comply to algorithm constraints"), "hr", 443);
        assertThat(both.message()).isEqualTo(dh.message());

        TransportFailure.View shortKey = TransportFailure.classify(new SSLHandshakeException(
                "PKIX path validation failed: Algorithm constraints check failed on keysize limits: RSA 512 bit key"),
                "hr", 443);
        assertThat(shortKey.kind()).isEqualTo(TransportFailure.Kind.TLS_CERT_ALGORITHM);
        assertThat(shortKey.message()).isEqualTo("对方证书使用被禁的算法或密钥过短（如 RSA 512），需要换证书。");

        TransportFailure.View sni = TransportFailure.classify(
                new SSLHandshakeException("Received fatal alert: unrecognized_name"), "hr", 443);
        assertThat(sni.kind()).isEqualTo(TransportFailure.Kind.TLS_SNI);
        assertThat(sni.message()).isEqualTo("对方拒绝了 SNI 主机名，可改用 IP 或单标签名。");

        TransportFailure.View silent = TransportFailure.classify(
                new NoHttpResponseException("localhost:9 failed to respond"), "localhost", 9);
        assertThat(silent.kind()).isEqualTo(TransportFailure.Kind.NO_RESPONSE);
        assertThat(silent.message()).isEqualTo("上游没有返回 HTTP 响应。");

        TransportFailure.View header = TransportFailure.classify(
                new MessageConstraintException("Maximum line length limit exceeded"), "localhost", 9);
        assertThat(header.kind()).isEqualTo(TransportFailure.Kind.HEADER_LIMIT);
        assertThat(header.message()).isEqualTo("上游响应头超过长度或个数上限。");

        ConnectException embedded = new ConnectException(
                "Connect to http://localhost:9 [localhost/0:0:0:0:0:0:0:1] failed: Connection refused");
        TransportFailure.View refused = TransportFailure.classify(embedded, "localhost", 9);
        assertThat(refused.kind()).isEqualTo(TransportFailure.Kind.REFUSED);
        assertThat(refused.message()).contains("localhost:9").contains("::1");
        assertThat(TransportFailure.compactAddress("0:0:0:0:0:0:0:1")).isEqualTo("::1");
        assertThat(TransportFailure.compactAddress("2001:0:0:0:0:0:0:1")).isEqualTo("2001::1");
    }

    private static final class NoHttpResponseException extends java.io.IOException {
        private NoHttpResponseException(String message) {
            super(message);
        }
    }

    private static final class MessageConstraintException extends java.io.IOException {
        private MessageConstraintException(String message) {
            super(message);
        }
    }

    @Test
    void realPkixFailureCarriesTheIssuerEvenWithTheAlertPrefix(@TempDir Path dir) throws Exception {
        LabCertificates.Material certs = LabCertificates.generate(dir);
        CertificateException failure = untrusted(certs.leaf());
        HandshakeRecord.attach(failure, HandshakeRecord.of(List.of(TrustCert.of(certs.leaf())), Set.of(), "lab.example", 443));
        SSLHandshakeException outer = new SSLHandshakeException("(certificate_unknown) " + failure.getMessage());
        outer.initCause(failure);
        TransportFailure.View view = TransportFailure.classify(outer, "lab.example", 443);
        assertThat(view.kind()).isEqualTo(TransportFailure.Kind.TLS_UNTRUSTED);
        assertThat(view.message()).contains("Hermit Lab Root");
        assertThat(outer.getMessage()).doesNotStartWith("PKIX");
    }

    @Test
    void warningIsLimitedToOnePerHostAndKind() {
        Logger logger = Logger.getLogger(TransportLog.class.getName());
        CopyOnWriteArrayList<LogRecord> lines = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previous = logger.getLevel();
        boolean parent = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.WARNING);
        logger.addHandler(handler);
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        TransportLog.useClock(clock);
        try {
            UnknownHostException failure = new UnknownHostException("mail.internal");
            TransportFailure.observe(failure, "mail.internal", 443);
            TransportFailure.observe(failure, "mail.internal", 443);
            assertThat(lines).hasSize(1);
            assertThat(lines.get(0).getThrown()).isNull();
            assertThat(lines.get(0).getMessage()).contains("UnknownHostException").contains("mail.internal").doesNotContain("\n");
            clock.advance(Duration.ofMinutes(10));
            TransportFailure.observe(failure, "mail.internal", 443);
            assertThat(lines).hasSize(2);
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(parent);
            logger.setLevel(previous);
            TransportLog.resetForTests();
        }
    }

    private static CertificateException untrusted(X509Certificate leaf) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509TrustManager manager = null;
        for (TrustManager candidate : factory.getTrustManagers()) {
            if (candidate instanceof X509TrustManager x509) {
                manager = x509;
                break;
            }
        }
        try {
            manager.checkServerTrusted(new X509Certificate[] {leaf}, "RSA");
        } catch (CertificateException ex) {
            return ex;
        }
        throw new AssertionError("expected the lab leaf to be untrusted");
    }

    private static Throwable caused(Throwable outer, Throwable cause) {
        outer.initCause(cause);
        return outer;
    }
}
