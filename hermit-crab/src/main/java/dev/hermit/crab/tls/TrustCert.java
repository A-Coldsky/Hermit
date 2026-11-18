package dev.hermit.crab.tls;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.security.auth.x500.X500Principal;

/** 一张证书里给错误提示和开发追踪用的字段。主题和指纹不是秘密。 */
public record TrustCert(
        String subject,
        String issuer,
        String sha256,
        String notBefore,
        String notAfter,
        boolean ca
) {
    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ISO_INSTANT;

    public static TrustCert of(X509Certificate certificate) {
        return new TrustCert(
                dn(certificate.getSubjectX500Principal()),
                dn(certificate.getIssuerX500Principal()),
                sha256(certificate),
                INSTANT.format(certificate.getNotBefore().toInstant().atOffset(ZoneOffset.UTC)),
                INSTANT.format(certificate.getNotAfter().toInstant().atOffset(ZoneOffset.UTC)),
                certificate.getBasicConstraints() >= 0);
    }

    public static String dn(X500Principal principal) {
        return principal == null ? "" : principal.getName(X500Principal.RFC2253);
    }

    public static String sha256(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(Character.forDigit((value >>> 4) & 0xf, 16));
                text.append(Character.forDigit(value & 0xf, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException | CertificateEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String dayNotAfter() {
        return day(notAfter);
    }

    public String dayNotBefore() {
        return day(notBefore);
    }

    public Map<String, Object> fields() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("subject", subject);
        map.put("issuer", issuer);
        map.put("sha256", sha256);
        map.put("notBefore", notBefore);
        map.put("notAfter", notAfter);
        map.put("ca", ca);
        return map;
    }

    private static String day(String instant) {
        if (instant == null || instant.length() < 10) {
            return "";
        }
        return instant.substring(0, 10);
    }
}
