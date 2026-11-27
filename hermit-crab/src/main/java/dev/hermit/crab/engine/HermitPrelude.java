package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import org.mozilla.javascript.ConsString;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.UniqueTag;
import org.mozilla.javascript.typedarrays.NativeArrayBuffer;
import org.mozilla.javascript.typedarrays.NativeUint8Array;

/**
 * Frozen {@code hermit} global. Codec helpers are plain JavaScript. {@code randomBytes},
 * {@code now}, GBK codec, and standard JDK 17 JCE digest/HMAC/AES/RSA functions are Rhino
 * functions that return a {@code Uint8Array}, string, or number — never a wrapped Java object.
 */
final class HermitPrelude {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Charset GBK = Charset.forName("GBK");
    private static final int RANDOM_MIN = 1;
    private static final int RANDOM_MAX = 1024;
    private static final int MAX_CRYPTO_INPUT_BYTES = ScriptStep.MAX_RESULT_BYTES;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] RSA_OID_ALGORITHM_ID = {
            0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
            (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
    };

    private static final String PRELUDE = """
            (function () {
              function utf8(text) {
                var s = String(text);
                var bytes = [];
                for (var i = 0; i < s.length; i++) {
                  var c = s.charCodeAt(i);
                  if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
                    var next = s.charCodeAt(i + 1);
                    if (next >= 0xDC00 && next <= 0xDFFF) {
                      c = 0x10000 + ((c - 0xD800) << 10) + (next - 0xDC00);
                      i++;
                    }
                  }
                  if (c < 0x80) {
                    bytes.push(c);
                  } else if (c < 0x800) {
                    bytes.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
                  } else if (c < 0x10000) {
                    bytes.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
                  } else {
                    bytes.push(
                      0xF0 | (c >> 18),
                      0x80 | ((c >> 12) & 0x3F),
                      0x80 | ((c >> 6) & 0x3F),
                      0x80 | (c & 0x3F));
                  }
                }
                return new Uint8Array(bytes);
              }
              function fromUtf8(bytes) {
                var out = "";
                var i = 0;
                while (i < bytes.length) {
                  var b = bytes[i] & 255;
                  var cp;
                  var need;
                  if (b < 0x80) {
                    cp = b;
                    need = 1;
                  } else if ((b & 0xE0) === 0xC0) {
                    cp = b & 0x1F;
                    need = 2;
                  } else if ((b & 0xF0) === 0xE0) {
                    cp = b & 0x0F;
                    need = 3;
                  } else if ((b & 0xF8) === 0xF0) {
                    cp = b & 0x07;
                    need = 4;
                  } else {
                    throw new Error("utf8");
                  }
                  if (i + need > bytes.length) {
                    throw new Error("utf8");
                  }
                  for (var j = 1; j < need; j++) {
                    var n = bytes[i + j] & 255;
                    if ((n & 0xC0) !== 0x80) {
                      throw new Error("utf8");
                    }
                    cp = (cp << 6) | (n & 0x3F);
                  }
                  i += need;
                  if (cp > 0xFFFF) {
                    cp -= 0x10000;
                    out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
                  } else {
                    out += String.fromCharCode(cp);
                  }
                }
                return out;
              }
              var HEX = "0123456789abcdef";
              function hex(bytes) {
                var out = "";
                for (var i = 0; i < bytes.length; i++) {
                  var v = bytes[i] & 255;
                  out += HEX.charAt(v >> 4) + HEX.charAt(v & 15);
                }
                return out;
              }
              function unhex(text) {
                var s = String(text);
                if (s.length % 2 !== 0) {
                  throw new Error("hex");
                }
                var bytes = [];
                for (var i = 0; i < s.length; i += 2) {
                  var v = parseInt(s.substr(i, 2), 16);
                  if (v !== v) {
                    throw new Error("hex");
                  }
                  bytes.push(v);
                }
                return new Uint8Array(bytes);
              }
              var B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
              var B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
              function encode(bytes, alphabet, pad) {
                var out = "";
                var i = 0;
                for (; i + 2 < bytes.length; i += 3) {
                  var n = ((bytes[i] & 255) << 16) | ((bytes[i + 1] & 255) << 8) | (bytes[i + 2] & 255);
                  out += alphabet.charAt(n >> 18) + alphabet.charAt((n >> 12) & 63)
                      + alphabet.charAt((n >> 6) & 63) + alphabet.charAt(n & 63);
                }
                if (i < bytes.length) {
                  var rest = (bytes[i] & 255) << 16;
                  if (i + 1 < bytes.length) {
                    rest |= (bytes[i + 1] & 255) << 8;
                  }
                  out += alphabet.charAt(rest >> 18) + alphabet.charAt((rest >> 12) & 63);
                  if (i + 1 < bytes.length) {
                    out += alphabet.charAt((rest >> 6) & 63);
                    if (pad) {
                      out += "=";
                    }
                  } else if (pad) {
                    out += "==";
                  }
                }
                return out;
              }
              function decode(text, alphabet) {
                var s = String(text).replace(/=+$/, "");
                if (s.length % 4 === 1) {
                  throw new Error("base64");
                }
                var out = [];
                var buf = 0;
                var bits = 0;
                for (var i = 0; i < s.length; i++) {
                  var v = alphabet.indexOf(s.charAt(i));
                  if (v < 0) {
                    throw new Error("base64");
                  }
                  buf = (buf << 6) | v;
                  bits += 6;
                  if (bits >= 8) {
                    bits -= 8;
                    out.push((buf >> bits) & 255);
                    buf &= (1 << bits) - 1;
                  }
                }
                return new Uint8Array(out);
              }
              return {
                utf8: utf8,
                fromUtf8: fromUtf8,
                hex: hex,
                unhex: unhex,
                base64: function (bytes) { return encode(bytes, B64, true); },
                unbase64: function (text) { return decode(text, B64); },
                base64url: function (bytes) { return encode(bytes, B64URL, false); },
                unbase64url: function (text) { return decode(text, B64URL); }
              };
            })()
            """;
    private static final String FREEZE = """
            (function () {
              var names = ["utf8", "fromUtf8", "hex", "unhex", "base64", "unbase64",
                "base64url", "unbase64url", "randomBytes", "now", "gbk", "fromGbk",
                "md5", "sha1", "sha256", "sha512", "hmacSha1", "hmacSha256",
                "aesEncrypt", "aesDecrypt", "rsaEncrypt"];
              for (var i = 0; i < names.length; i++) {
                Object.freeze(hermit[names[i]]);
              }
              Object.freeze(hermit);
            })()
            """;

    private HermitPrelude() {
    }

    static void install(Context cx, Scriptable scope, Clock clock) {
        Object created = CompiledScripts.evaluate(cx, scope, PRELUDE, "hermit");
        if (!(created instanceof Scriptable hermit)) {
            throw ScriptRuntime.typeError("denied");
        }
        ScriptableObject.putProperty(hermit, "randomBytes", randomBytes(cx, scope));
        ScriptableObject.putProperty(hermit, "now", now(scope, clock));
        ScriptableObject.putProperty(hermit, "gbk", gbk(scope));
        ScriptableObject.putProperty(hermit, "fromGbk", fromGbk(scope));
        ScriptableObject.putProperty(hermit, "md5", digest(scope, "md5", "MD5"));
        ScriptableObject.putProperty(hermit, "sha1", digest(scope, "sha1", "SHA-1"));
        ScriptableObject.putProperty(hermit, "sha256", digest(scope, "sha256", "SHA-256"));
        ScriptableObject.putProperty(hermit, "sha512", digest(scope, "sha512", "SHA-512"));
        ScriptableObject.putProperty(hermit, "hmacSha1", hmac(scope, "hmacSha1", "HmacSHA1", 64));
        ScriptableObject.putProperty(hermit, "hmacSha256", hmac(scope, "hmacSha256", "HmacSHA256", 64));
        ScriptableObject.putProperty(hermit, "aesEncrypt", aes(scope, "aesEncrypt", Cipher.ENCRYPT_MODE));
        ScriptableObject.putProperty(hermit, "aesDecrypt", aes(scope, "aesDecrypt", Cipher.DECRYPT_MODE));
        ScriptableObject.putProperty(hermit, "rsaEncrypt", rsaEncrypt(scope));
        ScriptableObject.defineProperty(
                scope,
                "hermit",
                hermit,
                ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        CompiledScripts.evaluate(cx, scope, FREEZE, "hermit");
    }

    private static Scriptable randomBytes(Context cx, Scriptable scope) {
        return new LambdaFunction(scope, "randomBytes", 1, (context, callScope, thisObj, args) -> {
            int length = randomLength(args);
            byte[] bytes = new byte[length];
            RANDOM.nextBytes(bytes);
            return uint8(context, scope, bytes);
        });
    }

    private static Scriptable now(Scriptable scope, Clock clock) {
        Clock source = clock == null ? Clock.systemUTC() : clock;
        return new LambdaFunction(scope, "now", 0, (context, callScope, thisObj, args) -> (double) source.millis());
    }

    private static Scriptable gbk(Scriptable scope) {
        return new LambdaFunction(scope, "gbk", 1, (context, callScope, thisObj, args) -> {
            String text = requireText(args, 0, "gbk");
            if (text.length() > MAX_CRYPTO_INPUT_BYTES) {
                throw ScriptRuntime.typeError("gbk: input too large");
            }
            try {
                ByteBuffer buf = GBK.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(CharBuffer.wrap(text));
                byte[] out = new byte[buf.remaining()];
                buf.get(out);
                return uint8(context, scope, out);
            } catch (CharacterCodingException ex) {
                throw ScriptRuntime.typeError("gbk: unencodable character");
            }
        });
    }

    private static Scriptable fromGbk(Scriptable scope) {
        return new LambdaFunction(scope, "fromGbk", 1, (context, callScope, thisObj, args) -> {
            byte[] input = requireBytesOnly(args, 0, "fromGbk");
            try {
                return GBK.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(input))
                        .toString();
            } catch (CharacterCodingException ex) {
                throw ScriptRuntime.typeError("gbk: invalid byte sequence");
            }
        });
    }

    private static Scriptable digest(Scriptable scope, String fnName, String jceAlgorithm) {
        return new LambdaFunction(scope, fnName, 1, (context, callScope, thisObj, args) -> {
            byte[] data = requireBytesOrUtf8(args, 0, fnName);
            try {
                MessageDigest md = MessageDigest.getInstance(jceAlgorithm);
                return uint8(context, scope, md.digest(data));
            } catch (GeneralSecurityException ex) {
                throw ScriptRuntime.typeError(fnName + " failed");
            }
        });
    }

    private static Scriptable hmac(Scriptable scope, String fnName, String jceAlgorithm, int blockBytes) {
        return new LambdaFunction(scope, fnName, 2, (context, callScope, thisObj, args) -> {
            byte[] keyBytes = requireBytesOrUtf8(args, 0, fnName + " key");
            byte[] dataBytes = requireBytesOrUtf8(args, 1, fnName + " data");
            if (keyBytes.length == 0) {
                // RFC 2104: 空密钥等价于全零块。JCE SecretKeySpec 不接受空字节数组。
                keyBytes = new byte[blockBytes];
            }
            try {
                Mac mac = Mac.getInstance(jceAlgorithm);
                mac.init(new SecretKeySpec(keyBytes, jceAlgorithm));
                return uint8(context, scope, mac.doFinal(dataBytes));
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                throw ScriptRuntime.typeError(fnName + " failed");
            }
        });
    }

    private static Scriptable aes(Scriptable scope, String fnName, int cipherMode) {
        return new LambdaFunction(scope, fnName, 4, (context, callScope, thisObj, args) -> {
            String modeRaw = requireText(args, 0, fnName + " mode").trim().toUpperCase(Locale.ROOT);
            byte[] key = requireBytesOrUtf8(args, 1, fnName + " key");
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw ScriptRuntime.typeError(fnName + ": key must be 16, 24, or 32 bytes");
            }
            byte[] iv = optionalBytesOrUtf8(args, 2, fnName + " iv");
            byte[] input = cipherMode == Cipher.DECRYPT_MODE
                    ? requireBytesOnly(args, 3, fnName + " ciphertext")
                    : requireBytesOrUtf8(args, 3, fnName + " plaintext");
            try {
                Cipher cipher;
                SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
                switch (modeRaw) {
                    case "CBC", "AES-CBC", "AES/CBC/PKCS5PADDING", "AES/CBC/PKCS7PADDING" -> {
                        if (iv == null || iv.length != 16) {
                            throw ScriptRuntime.typeError(fnName + ": CBC requires a 16-byte iv");
                        }
                        cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                        cipher.init(cipherMode, keySpec, new IvParameterSpec(iv));
                    }
                    case "ECB", "AES-ECB", "AES/ECB/PKCS5PADDING", "AES/ECB/PKCS7PADDING" -> {
                        if (iv != null && iv.length != 0) {
                            throw ScriptRuntime.typeError(fnName + ": ECB does not accept an iv");
                        }
                        cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
                        cipher.init(cipherMode, keySpec);
                    }
                    case "GCM", "AES-GCM", "AES/GCM/NOPADDING" -> {
                        if (iv == null || iv.length < 12 || iv.length > 16) {
                            throw ScriptRuntime.typeError(fnName + ": GCM requires a 12..16-byte iv");
                        }
                        cipher = Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(cipherMode, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
                    }
                    default -> throw ScriptRuntime.typeError(fnName + ": unsupported mode");
                }
                return uint8(context, scope, cipher.doFinal(input));
            } catch (GeneralSecurityException ex) {
                throw ScriptRuntime.typeError(fnName + " failed");
            }
        });
    }

    private static Scriptable rsaEncrypt(Scriptable scope) {
        return new LambdaFunction(scope, "rsaEncrypt", 3, (context, callScope, thisObj, args) -> {
            if (args == null || args.length < 2 || args[0] == null || Undefined.isUndefined(args[0])) {
                throw ScriptRuntime.typeError("rsaEncrypt: publicKey required");
            }
            RSAPublicKey publicKey = parseRsaPublicKey(args[0]);
            byte[] plain = requireBytesOrUtf8(args, 1, "rsaEncrypt data");
            String padding = "PKCS1";
            if (args.length >= 3 && args[2] != null && !Undefined.isUndefined(args[2])) {
                padding = requireText(args, 2, "rsaEncrypt padding").trim().toUpperCase(Locale.ROOT);
            }
            try {
                Cipher cipher;
                switch (padding) {
                    case "PKCS1", "RSA-PKCS1", "RSA/ECB/PKCS1PADDING" -> {
                        cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
                    }
                    case "OAEP-SHA1", "RSA/ECB/OAEPWITHSHA-1ANDMGF1PADDING" -> {
                        cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
                        cipher.init(
                                Cipher.ENCRYPT_MODE,
                                publicKey,
                                new OAEPParameterSpec(
                                        "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
                    }
                    case "OAEP-SHA256", "OAEP", "RSA/ECB/OAEPWITHSHA-256ANDMGF1PADDING" -> {
                        cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
                        cipher.init(
                                Cipher.ENCRYPT_MODE,
                                publicKey,
                                new OAEPParameterSpec(
                                        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
                    }
                    case "NONE", "RAW", "RSA/ECB/NOPADDING" -> {
                        cipher = Cipher.getInstance("RSA/ECB/NoPadding");
                        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
                    }
                    default -> throw ScriptRuntime.typeError("rsaEncrypt: unsupported padding");
                }
                return uint8(context, scope, cipher.doFinal(plain));
            } catch (GeneralSecurityException ex) {
                throw ScriptRuntime.typeError("rsaEncrypt failed");
            }
        });
    }

    private static RSAPublicKey parseRsaPublicKey(Object raw) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PublicKey pub;
            if (isPlainText(raw)) {
                String pem = raw.toString().trim();
                boolean pkcs1 = pem.contains("BEGIN RSA PUBLIC KEY");
                String stripped = pem
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                        .replace("-----END RSA PUBLIC KEY-----", "")
                        .replaceAll("\\s+", "");
                if (stripped.isEmpty()) {
                    throw ScriptRuntime.typeError("rsaEncrypt: empty publicKey");
                }
                byte[] der = Base64.getDecoder().decode(stripped);
                byte[] spki = pkcs1 ? wrapPkcs1InSpki(der) : der;
                pub = kf.generatePublic(new X509EncodedKeySpec(spki));
            } else if (raw instanceof Scriptable obj && !(raw instanceof NativeArray)) {
                Object modRaw = obj.get("modulus", obj);
                if (modRaw == UniqueTag.NOT_FOUND || !isPlainText(modRaw)) {
                    throw ScriptRuntime.typeError("rsaEncrypt: modulus hex string required");
                }
                Object expRaw = obj.get("exponent", obj);
                String expHex = (expRaw == null || expRaw == UniqueTag.NOT_FOUND || Undefined.isUndefined(expRaw))
                        ? "010001"
                        : (isPlainText(expRaw) ? expRaw.toString().trim() : null);
                if (expHex == null || expHex.isEmpty()) {
                    throw ScriptRuntime.typeError("rsaEncrypt: invalid exponent");
                }
                BigInteger mod = new BigInteger(1, parseHex(modRaw.toString().trim()));
                BigInteger exp = new BigInteger(1, parseHex(expHex));
                pub = kf.generatePublic(new RSAPublicKeySpec(mod, exp));
            } else {
                throw ScriptRuntime.typeError("rsaEncrypt: invalid publicKey");
            }
            if (!(pub instanceof RSAPublicKey rsa)) {
                throw ScriptRuntime.typeError("rsaEncrypt: not an RSA key");
            }
            int bits = rsa.getModulus().bitLength();
            if (bits < 512 || bits > 8192) {
                throw ScriptRuntime.typeError("rsaEncrypt: unsupported RSA key size");
            }
            return rsa;
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw ScriptRuntime.typeError("rsaEncrypt: invalid publicKey");
        }
    }

    private static byte[] parseHex(String hex) {
        String normalized = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("empty hex");
        }
        if ((normalized.length() & 1) == 1) {
            normalized = "0" + normalized;
        }
        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(normalized.charAt(i * 2), 16);
            int lo = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static byte[] wrapPkcs1InSpki(byte[] pkcs1) {
        ByteArrayOutputStream bitString = new ByteArrayOutputStream(pkcs1.length + 8);
        bitString.write(0x03);
        writeDerLength(bitString, pkcs1.length + 1);
        bitString.write(0x00);
        bitString.writeBytes(pkcs1);
        byte[] bs = bitString.toByteArray();

        ByteArrayOutputStream spki = new ByteArrayOutputStream(RSA_OID_ALGORITHM_ID.length + bs.length + 8);
        spki.write(0x30);
        writeDerLength(spki, RSA_OID_ALGORITHM_ID.length + bs.length);
        spki.writeBytes(RSA_OID_ALGORITHM_ID);
        spki.writeBytes(bs);
        return spki.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int len) {
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xff) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xffff) {
            out.write(0x82);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        } else {
            out.write(0x83);
            out.write((len >> 16) & 0xff);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
    }

    private static boolean isPlainText(Object value) {
        return value instanceof ConsString || (value instanceof CharSequence && !(value instanceof Scriptable));
    }

    private static String requireText(Object[] args, int index, String label) {
        if (args == null || args.length <= index || args[index] == null || Undefined.isUndefined(args[index])) {
            throw ScriptRuntime.typeError(label + ": string required");
        }
        if (!isPlainText(args[index])) {
            throw ScriptRuntime.typeError(label + ": string required");
        }
        return args[index].toString();
    }

    private static byte[] optionalBytesOrUtf8(Object[] args, int index, String label) {
        if (args == null || args.length <= index || args[index] == null || Undefined.isUndefined(args[index])) {
            return null;
        }
        return readBytes(args[index], true, label);
    }

    private static byte[] requireBytesOrUtf8(Object[] args, int index, String label) {
        if (args == null || args.length <= index || args[index] == null || Undefined.isUndefined(args[index])) {
            throw ScriptRuntime.typeError(label + ": bytes or string required");
        }
        return readBytes(args[index], true, label);
    }

    private static byte[] requireBytesOnly(Object[] args, int index, String label) {
        if (args == null || args.length <= index || args[index] == null || Undefined.isUndefined(args[index])) {
            throw ScriptRuntime.typeError(label + ": Uint8Array required");
        }
        return readBytes(args[index], false, label);
    }

    private static byte[] readBytes(Object raw, boolean allowText, String label) {
        if (raw instanceof NativeUint8Array u8) {
            int len = u8.getByteLength();
            if (len < 0 || len > MAX_CRYPTO_INPUT_BYTES) {
                throw ScriptRuntime.typeError(label + ": input too large");
            }
            byte[] out = new byte[len];
            System.arraycopy(u8.getBuffer().getBuffer(), u8.getByteOffset(), out, 0, len);
            return out;
        }
        if (raw instanceof NativeArray array) {
            long len = array.getLength();
            if (len < 0 || len > MAX_CRYPTO_INPUT_BYTES) {
                throw ScriptRuntime.typeError(label + ": input too large");
            }
            byte[] out = new byte[(int) len];
            for (int i = 0; i < out.length; i++) {
                Object elem = array.get(i, array);
                if (!(elem instanceof Number num)) {
                    throw ScriptRuntime.typeError(label + ": invalid byte array element");
                }
                double d = num.doubleValue();
                if (!Double.isFinite(d) || d != Math.rint(d) || d < 0 || d > 255) {
                    throw ScriptRuntime.typeError(label + ": byte out of range");
                }
                out[i] = (byte) ((int) d);
            }
            return out;
        }
        if (allowText && isPlainText(raw)) {
            byte[] utf8 = raw.toString().getBytes(StandardCharsets.UTF_8);
            if (utf8.length > MAX_CRYPTO_INPUT_BYTES) {
                throw ScriptRuntime.typeError(label + ": input too large");
            }
            return utf8;
        }
        throw ScriptRuntime.typeError(label + (allowText ? ": bytes or string required" : ": Uint8Array required"));
    }

    private static int randomLength(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null || Undefined.isUndefined(args[0])) {
            throw ScriptRuntime.typeError("range");
        }
        if (!(args[0] instanceof Number number)) {
            throw ScriptRuntime.typeError("range");
        }
        double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < RANDOM_MIN || raw > RANDOM_MAX) {
            throw ScriptRuntime.typeError("range");
        }
        return (int) raw;
    }

    private static Scriptable uint8(Context cx, Scriptable scope, byte[] data) {
        Scriptable buf = cx.newObject(scope, "ArrayBuffer", new Object[] {data.length});
        if (buf instanceof NativeArrayBuffer nativeBuf && data.length > 0) {
            System.arraycopy(data, 0, nativeBuf.getBuffer(), 0, data.length);
        }
        return cx.newObject(scope, "Uint8Array", new Object[] {buf});
    }
}
