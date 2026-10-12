package dev.hermit.common.pkg;

import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class Ed25519Keys {

    private Ed25519Keys() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("无法生成 Ed25519 密钥", ex);
        }
    }

    public static String writePrivatePem(PrivateKey key) {
        requireEd25519(key);
        return pem("PRIVATE KEY", key.getEncoded());
    }

    public static String writePublicPem(PublicKey key) {
        requireEd25519(key);
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static PrivateKey readPrivatePem(String pem) {
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(pemBody(pem, "PRIVATE KEY")));
            requireEd25519(key);
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("不是 Ed25519 私钥");
        }
    }

    public static PublicKey readPublicPem(String pem) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(pemBody(pem, "PUBLIC KEY")));
            requireEd25519(key);
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalArgumentException("不是 Ed25519 公钥");
        }
    }

    private static void requireEd25519(Key key) {
        // JDK 把算法名报成 EdDSA，曲线名才是 Ed25519。
        if (!(key instanceof EdECKey ed) || ed.getParams() == null || !"Ed25519".equals(ed.getParams().getName())) {
            throw new IllegalArgumentException("不是 Ed25519 密钥");
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static byte[] pemBody(String pem, String type) {
        if (pem == null) {
            throw new IllegalArgumentException("PEM 为空");
        }
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < 0 || stop <= start) {
            throw new IllegalArgumentException("PEM 格式无效");
        }
        String body = pem.substring(start + begin.length(), stop);
        StringBuilder compact = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            if (!Character.isWhitespace(body.charAt(i))) {
                compact.append(body.charAt(i));
            }
        }
        return Base64.getDecoder().decode(compact.toString());
    }
}
