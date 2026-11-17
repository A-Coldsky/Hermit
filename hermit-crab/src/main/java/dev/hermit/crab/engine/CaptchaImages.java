package dev.hermit.crab.engine;

import java.util.Arrays;

/**
 * 验证码图片只认魔数。不解码，也不把字节写进日志。
 */
final class CaptchaImages {

    static final int MAX_BYTES = 512 * 1024;

    static final String UNSUPPORTED = "验证码图片不是支持的格式";

    static final String TOO_LARGE = "验证码图片超过 512 KiB";

    private CaptchaImages() {
    }

    record Accepted(byte[] bytes, String mediaType) {
    }

    /** 先看大小，再看魔数。空正文算不支持的格式。 */
    static String reject(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return UNSUPPORTED;
        }
        if (bytes.length > MAX_BYTES) {
            return TOO_LARGE;
        }
        if (mediaType(bytes) == null) {
            return UNSUPPORTED;
        }
        return null;
    }

    static Accepted accept(byte[] bytes) {
        if (reject(bytes) != null) {
            return null;
        }
        return new Accepted(Arrays.copyOf(bytes, bytes.length), mediaType(bytes));
    }

    static String mediaType(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (startsWith(bytes, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return "image/png";
        }
        if (startsWith(bytes, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(bytes, new byte[] {'G', 'I', 'F', '8', '7', 'a'})
                || startsWith(bytes, new byte[] {'G', 'I', 'F', '8', '9', 'a'})) {
            return "image/gif";
        }
        if (bytes.length >= 12
                && startsWith(bytes, new byte[] {'R', 'I', 'F', 'F'})
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
