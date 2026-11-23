package dev.hermit.common.http;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 请求线上的 query 与 path 编码。query 默认只转义会拆开参数或在 URL 里非法的字符；
 * path 在交给 {@link URI} 之前做宽松规范化。表单正文和 OAuth 仍用 {@code URLEncoder}，不经过这里。
 */
public final class UrlComponents {

    private static final java.util.regex.Pattern IPV6_AUTHORITY =
            java.util.regex.Pattern.compile("(?:[A-Za-z][A-Za-z0-9+.-]*:)?//\\[[0-9A-Fa-f:.]+]");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final ObjectMapper JSON = new ObjectMapper();

    private UrlComponents() {
    }

    /**
     * 一个 query 键或值。空格变成 {@code %20}。字母、数字和 {@code - . _ ~ ! $ ( ) * , ; : @ / ?}
     * 原样保留；其余字符按 {@code charset} 百分号编码。{@code + & = # %} 也会编码，保证一个值仍是一个参数。
     */
    public static String query(String text, Charset charset) {
        Objects.requireNonNull(charset, "charset");
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int cp = text.codePointAt(index);
            int count = Character.charCount(cp);
            if (queryLiteral(cp)) {
                out.appendCodePoint(cp);
            } else {
                appendEncoded(out, text, index, count, charset);
            }
            index += count;
        }
        return out.toString();
    }

    /**
     * 模板里的 path 或 url。只编码 URI 里非法的字符：控制字符、空格、非 ASCII、
     * 引号、尖括号、反斜杠、{@code ^ ` | }、方括号，以及后面没有两位十六进制的 {@code %}。
     * {@code / ? # & =} 和已经写好的 {@code %xx} 保持原样。方括号在 URI 里非法，编码后才能放上请求行。
     * 非 ASCII 按 {@code charset} 编码。
     */
    public static String normalize(String text, Charset charset) {
        Objects.requireNonNull(charset, "charset");
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '%' && index + 2 < text.length()
                    && isHex(text.charAt(index + 1)) && isHex(text.charAt(index + 2))) {
                out.append('%').append(text.charAt(index + 1)).append(text.charAt(index + 2));
                index += 3;
                continue;
            }
            int cp = text.codePointAt(index);
            int count = Character.charCount(cp);
            if (cp == '%' || pathEncoded(cp)) {
                appendEncoded(out, text, index, count, charset);
            } else {
                out.appendCodePoint(cp);
            }
            index += count;
        }
        return out.toString();
    }

    /**
     * 先做 {@link #normalize(String, Charset)}，再按 {@link URI#resolve(URI)} 拼接。
     * 相对路径里的空段不再被合并，{@code a//b} 保持 {@code //}。{@code .} 与 {@code ..} 的处理与原来一致，
     * 绝对路径里的 {@code ..} 不删除。
     */
    public static URI resolve(URI base, String reference, Charset charset) {
        Objects.requireNonNull(base, "base");
        URI ref = URI.create(normalizeReference(reference, charset));
        if (ref.isAbsolute()) {
            return ref;
        }
        if (ref.getRawAuthority() != null) {
            return base.resolve(ref);
        }
        String refPath = ref.getRawPath() == null ? "" : ref.getRawPath();
        // 以 / 开头的路径，以及不含连续斜杠的相对路径，继续交给 URI.resolve，
        // 这样 ..、空 query 和同源判断与原来逐字节一致。只有相对路径里的 // 要单独保留。
        if (refPath.startsWith("/") || !refPath.contains("//")) {
            return base.resolve(ref);
        }
        return resolvePreservingEmpty(base, ref, refPath);
    }

    /**
     * 绝对地址的主机是 IPv6 字面量（{@code http://[fd00::10]:8080/login}）时，主机两边的方括号是
     * 地址的一部分，不编码；其余部分照常 {@link #normalize(String, Charset)}。
     */
    private static String normalizeReference(String reference, Charset charset) {
        if (reference != null) {
            java.util.regex.Matcher literal = IPV6_AUTHORITY.matcher(reference);
            if (literal.lookingAt()) {
                return literal.group() + normalize(reference.substring(literal.end()), charset);
            }
        }
        return normalize(reference, charset);
    }

    /** 按字符集编码。表示不了的字符抛出 {@link Unencodable}，不替换成 {@code ?}。 */
    public static byte[] encode(String text, Charset charset) {
        Objects.requireNonNull(charset, "charset");
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException ex) {
            throw new Unencodable("字符无法用 " + charset.name() + " 发送");
        }
    }

    /**
     * Jackson 字符写出的字符串转义（引号、反斜杠、控制字符），不含外层引号。
     * 与请求 JSON 正文里这个值的写法一致；辅助平面字符保持原样，再由步骤编码。
     */
    public static String jsonEscape(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        try {
            String quoted = JSON.writeValueAsString(text);
            return quoted.substring(1, quoted.length() - 1);
        } catch (JsonProcessingException ex) {
            return text;
        }
    }

    private static URI resolvePreservingEmpty(URI base, URI ref, String refPath) {
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        int slash = basePath.lastIndexOf('/');
        String prefix = slash >= 0 ? basePath.substring(0, slash + 1) : "";
        String merged = removeDotSegments(prefix + refPath);
        if (base.isAbsolute() && (merged.isEmpty() || merged.charAt(0) != '/')) {
            merged = "/" + merged;
        }
        return compose(base.getScheme(), base.getRawAuthority(), merged, ref.getRawQuery(), ref.getRawFragment());
    }

    private static URI compose(
            String scheme,
            String rawAuthority,
            String rawPath,
            String rawQuery,
            String rawFragment
    ) {
        StringBuilder raw = new StringBuilder();
        if (scheme != null) {
            raw.append(scheme).append("://");
        }
        if (rawAuthority != null) {
            raw.append(rawAuthority);
        }
        if (rawPath == null || rawPath.isEmpty()) {
            raw.append('/');
        } else {
            if (scheme != null && !rawPath.startsWith("/")) {
                raw.append('/');
            }
            raw.append(rawPath);
        }
        if (rawQuery != null) {
            raw.append('?').append(rawQuery);
        }
        if (rawFragment != null) {
            raw.append('#').append(rawFragment);
        }
        return URI.create(raw.toString());
    }

    /** RFC 3986 remove_dot_segments，保留空段。 */
    static String removeDotSegments(String path) {
        String input = path == null ? "" : path;
        StringBuilder output = new StringBuilder(input.length());
        while (!input.isEmpty()) {
            if (input.startsWith("../")) {
                input = input.substring(3);
            } else if (input.startsWith("./")) {
                input = input.substring(2);
            } else if (input.startsWith("/./")) {
                input = "/" + input.substring(3);
            } else if ("/.".equals(input)) {
                input = "/";
            } else if (input.startsWith("/../")) {
                input = "/" + input.substring(4);
                popSegment(output);
            } else if ("/..".equals(input)) {
                input = "/";
                popSegment(output);
            } else if (".".equals(input) || "..".equals(input)) {
                input = "";
            } else {
                int start = input.charAt(0) == '/' ? 1 : 0;
                int slash = input.indexOf('/', start);
                if (slash < 0) {
                    output.append(input);
                    input = "";
                } else {
                    output.append(input, 0, slash);
                    input = input.substring(slash);
                }
            }
        }
        return output.toString();
    }

    private static void popSegment(StringBuilder output) {
        int slash = output.lastIndexOf("/");
        if (slash >= 0) {
            output.setLength(slash);
        } else {
            output.setLength(0);
        }
    }

    private static boolean queryLiteral(int cp) {
        if ((cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9')) {
            return true;
        }
        return switch (cp) {
            case '-', '.', '_', '~', '!', '$', '(', ')', '*', ',', ';', ':', '@', '/', '?' -> true;
            default -> false;
        };
    }

    private static boolean pathEncoded(int cp) {
        if (cp <= 0x1F || cp == 0x7F || cp >= 0x80) {
            return true;
        }
        return switch (cp) {
            case ' ', '"', '<', '>', '\\', '^', '`', '{', '|', '}', '[', ']' -> true;
            default -> false;
        };
    }

    private static void appendEncoded(StringBuilder out, String text, int offset, int count, Charset charset) {
        byte[] bytes = encode(text.substring(offset, offset + count), charset);
        for (byte value : bytes) {
            int b = value & 0xFF;
            out.append('%');
            out.append(HEX[b >>> 4]);
            out.append(HEX[b & 0x0F]);
        }
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    /** 字符集表示不了这个文本。调用方应让这一步失败，不能把字符换成 {@code ?}。 */
    public static final class Unencodable extends IllegalArgumentException {
        public Unencodable(String message) {
            super(message);
        }
    }
}
