package dev.hermit.crab.secure;

import dev.hermit.crab.store.Origin;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * AES-256-GCM for one credential field.
 *
 * <p>Stored bytes are {@code 0x01 || nonce || ciphertext+tag}. The nonce is 12 bytes and the
 * tag is 128 bits. AAD is the UTF-8 of {@code hermit:cred:v1|origin|shell|account|field}, using
 * {@link Origin#name()}. A blob decrypted with any other tuple fails the tag check.
 */
public final class CredentialCipher {
    static final int KEY_LENGTH = 32;
    private static final int VERSION = 0x01;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_LENGTH = TAG_BITS / 8;

    private final byte[] masterKey;
    private final SecureRandom random;

    public CredentialCipher(byte[] masterKey) {
        this(masterKey, new SecureRandom());
    }

    CredentialCipher(byte[] masterKey, SecureRandom random) {
        if (masterKey == null || masterKey.length != KEY_LENGTH) {
            throw new IllegalArgumentException("master key must be 32 bytes");
        }
        this.masterKey = Arrays.copyOf(masterKey, masterKey.length);
        this.random = Objects.requireNonNull(random, "random");
    }

    public byte[] encrypt(Origin origin, String shell, String account, String field, String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is required");
        }
        byte[] associated = associatedData(origin, shell, account, field);
        byte[] nonce = new byte[NONCE_LENGTH];
        random.nextBytes(nonce);
        byte[] plainBytes = plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            byte[] body = transform(Cipher.ENCRYPT_MODE, nonce, associated, plainBytes);
            byte[] stored = new byte[1 + NONCE_LENGTH + body.length];
            stored[0] = (byte) VERSION;
            System.arraycopy(nonce, 0, stored, 1, NONCE_LENGTH);
            System.arraycopy(body, 0, stored, 1 + NONCE_LENGTH, body.length);
            return stored;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("credential encryption failed");
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    public String decrypt(Origin origin, String shell, String account, String field, byte[] stored) {
        if (stored == null || stored.length < 1 + NONCE_LENGTH + TAG_LENGTH || (stored[0] & 0xFF) != VERSION) {
            throw new CredentialDecryptException();
        }
        byte[] nonce = Arrays.copyOfRange(stored, 1, 1 + NONCE_LENGTH);
        byte[] body = Arrays.copyOfRange(stored, 1 + NONCE_LENGTH, stored.length);
        byte[] plainBytes;
        try {
            plainBytes = transform(Cipher.DECRYPT_MODE, nonce, associatedData(origin, shell, account, field), body);
        } catch (GeneralSecurityException ex) {
            throw new CredentialDecryptException();
        }
        try {
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plainBytes, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "CredentialCipher";
    }

    private byte[] transform(int mode, byte[] nonce, byte[] associated, byte[] input) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associated);
        return cipher.doFinal(input);
    }

    private static byte[] associatedData(Origin origin, String shell, String account, String field) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(shell, "shell");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(field, "field");
        String value = "hermit:cred:v1|" + origin.name() + "|" + shell + "|" + account + "|" + field;
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
