package dev.hermit.common.template;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

final class BuiltinFilters {

    private static final DateTimeFormatter[] INPUTS = new DateTimeFormatter[] {
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
    };

    private BuiltinFilters() {
    }

    static Filter hash(String name, String algorithm) {
        return (value, args) -> {
            if (value == null) {
                return null;
            }
            rejectArgs(args, name);
            try {
                byte[] digest = MessageDigest.getInstance(algorithm)
                        .digest(requireText(value, name).getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest);
            } catch (GeneralSecurityException ex) {
                throw new TemplateException(null, "无法计算 " + name, ex);
            }
        };
    }

    static Filter base64() {
        return (value, args) -> {
            if (value == null) {
                return null;
            }
            rejectArgs(args, "base64");
            return Base64.getEncoder().encodeToString(requireText(value, "base64").getBytes(StandardCharsets.UTF_8));
        };
    }

    static Filter urlencode() {
        return (value, args) -> {
            if (value == null) {
                return null;
            }
            rejectArgs(args, "urlencode");
            String encoded = java.net.URLEncoder.encode(requireText(value, "urlencode"), StandardCharsets.UTF_8);
            return encoded.replace("+", "%20");
        };
    }

    static Filter trim() {
        return (value, args) -> {
            if (value == null) {
                return null;
            }
            rejectArgs(args, "trim");
            return requireText(value, "trim").trim();
        };
    }

    static Filter defaultValue() {
        return (value, args) -> {
            if (args.size() != 1) {
                throw new TemplateException(null, "default 需要一个参数");
            }
            if (value == null || (value instanceof String text && text.isEmpty())) {
                return args.get(0);
            }
            return value;
        };
    }

    static Filter date(Clock clock) {
        return (value, args) -> {
            if (value == null) {
                return null;
            }
            if (args.size() != 1) {
                throw new TemplateException(null, "date 需要一个格式参数");
            }
            DateTimeFormatter output;
            try {
                output = DateTimeFormatter.ofPattern(String.valueOf(args.get(0)), Locale.ROOT);
            } catch (IllegalArgumentException ex) {
                throw new TemplateException(null, "日期格式无效");
            }
            LocalDateTime dateTime;
            if ("now".equals(value)) {
                dateTime = LocalDateTime.now(clock);
            } else if (value instanceof String text) {
                dateTime = parseDate(text);
            } else {
                throw new TemplateException(null, "日期需要文本");
            }
            return output.format(dateTime);
        };
    }

    static Filter rsa() {
        return (value, args) -> {
            if (value == null) {
                return null;
            }
            if (args.size() != 1) {
                throw new TemplateException(null, "rsa 需要一个公钥参数");
            }
            try {
                PublicKey key = rsaPublicKey(String.valueOf(args.get(0)));
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.ENCRYPT_MODE, key);
                byte[] encrypted = cipher.doFinal(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(encrypted);
            } catch (TemplateException ex) {
                throw ex;
            } catch (IllegalBlockSizeException ex) {
                throw new TemplateException(null, "明文超过这把公钥能加密的长度");
            } catch (InvalidKeyException ex) {
                throw new TemplateException(null, "RSA 公钥无效");
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                throw new TemplateException(null, "RSA 加密失败");
            }
        };
    }

    private static LocalDateTime parseDate(String text) {
        for (DateTimeFormatter formatter : INPUTS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (DateTimeParseException ignored) {
                // 日期格式不含时间时再按 LocalDate 试。
            }
            try {
                return LocalDate.parse(text, formatter).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                // 换下一个格式。
            }
        }
        throw new TemplateException(null, "日期无法解析");
    }

    private static PublicKey rsaPublicKey(String text) {
        try {
            byte[] der = text.contains("BEGIN")
                    ? pemBody(text)
                    : Base64.getMimeDecoder().decode(stripSpace(text));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new TemplateException(null, "RSA 公钥无效");
        }
    }

    private static byte[] pemBody(String pem) {
        int begin = pem.indexOf("BEGIN PUBLIC KEY");
        int end = pem.indexOf("END PUBLIC KEY");
        if (begin < 0 || end < 0 || end <= begin) {
            throw new TemplateException(null, "RSA 公钥无效");
        }
        int bodyStart = pem.indexOf('\n', begin);
        if (bodyStart < 0 || bodyStart >= end) {
            throw new TemplateException(null, "RSA 公钥无效");
        }
        return Base64.getMimeDecoder().decode(stripSpace(pem.substring(bodyStart, end)));
    }

    private static String stripSpace(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                sb.append(text.charAt(i));
            }
        }
        return sb.toString();
    }

    static Filter xmlEscape() {
        return (value, args) -> {
            if (value == null) {
                return null;
            }
            rejectArgs(args, "xml_escape");
            return XmlEscape.escape(requireText(value, "xml_escape"));
        };
    }

    private static void rejectArgs(List<Object> args, String name) {
        if (!args.isEmpty()) {
            throw new TemplateException(null, name + " 不接受参数");
        }
    }

    /** Strings pass through. Numbers and booleans stay decimal / {@code true}/{@code false}. */
    private static String requireText(Object value, String filter) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new TemplateException(null, filter + " 需要文本，得到的是对象");
    }
}
