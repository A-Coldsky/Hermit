package dev.hermit.crab.engine;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.validate.FileTransfers;
import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.files.FileStageException;
import dev.hermit.crab.files.StagedFile;

/** request 步骤里和文件有关的两件事：把暂存区的文件组装成 multipart，把响应正文存进暂存区。 */
final class FileSteps {
    static final String NO_SCOPE = "这次请求不在能力调用里，不能传文件（测试登录和保活请求不支持 multipart 和 download）";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern STAR = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED = Pattern.compile("filename\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE = Pattern.compile("filename\\s*=\\s*([^;\"]+)", Pattern.CASE_INSENSITIVE);

    private FileSteps() {
    }

    /** {@code wire} 是写进追踪的请求体摘要：字段名和文件元数据，没有文件内容。 */
    record Multipart(byte[] body, String contentType, String wire) {
    }

    static boolean downloads(Map<String, Object> spec) {
        Object download = spec.get("download");
        return Boolean.TRUE.equals(download) || download instanceof Map<?, ?>;
    }

    static FileScope scope() {
        FileScope scope = FileScope.current();
        if (scope == null) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, NO_SCOPE, null, null, null);
        }
        return scope;
    }

    static Multipart multipart(Object spec, Charset charset) {
        if (!(spec instanceof Map<?, ?> parts) || parts.isEmpty()) {
            throw new RuleProblem(ErrorCode.RULE_MISMATCH, FileTransfers.MULTIPART_TYPE, null, null, null);
        }
        byte[] seed = new byte[16];
        RANDOM.nextBytes(seed);
        String boundary = "----HermitCrab" + HexFormat.of().formatHex(seed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ArrayList<String> summary = new ArrayList<>();
        for (Map.Entry<?, ?> part : parts.entrySet()) {
            String field = String.valueOf(part.getKey());
            Object value = part.getValue();
            if (value == null) {
                continue;
            }
            write(out, "--" + boundary + "\r\n", charset);
            if (value instanceof Map<?, ?> filePart) {
                Object id = filePart.get("file");
                if (!(id instanceof String fileId) || fileId.isBlank()) {
                    throw new RuleProblem(
                            ErrorCode.RULE_MISMATCH, "multipart." + field + ".file 没有渲染出 fileId", null, null, null);
                }
                StagedFile file;
                byte[] bytes;
                try {
                    file = scope().require(fileId);
                    bytes = scope().stage().read(file);
                } catch (FileStageException ex) {
                    throw new RuleProblem(ErrorCode.INVALID_INPUT, ex.getMessage(), null, null, null);
                }
                String name = filePart.get("filename") instanceof String given && !given.isBlank()
                        ? given
                        : file.name();
                String type = filePart.get("content_type") instanceof String given && !given.isBlank()
                        ? given
                        : file.mime();
                write(out, "Content-Disposition: form-data; name=\"" + quote(field) + "\"; filename=\""
                        + quote(name) + "\"\r\nContent-Type: " + oneLine(type) + "\r\n\r\n", charset);
                out.writeBytes(bytes);
                summary.add(field + "=<文件 " + name + "，" + bytes.length + " 字节，" + type
                        + "，sha256 " + file.sha256() + ">");
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                write(out, "Content-Disposition: form-data; name=\"" + quote(field) + "\"\r\n\r\n", charset);
                write(out, String.valueOf(value), charset);
                summary.add(field + "=" + value);
            } else {
                throw new RuleProblem(ErrorCode.RULE_MISMATCH, FileTransfers.PART_TYPE, null, null, null);
            }
            write(out, "\r\n", charset);
        }
        write(out, "--" + boundary + "--\r\n", charset);
        return new Multipart(
                out.toByteArray(),
                "multipart/form-data; boundary=" + boundary,
                "multipart/form-data\n" + String.join("\n", summary));
    }

    /** 把 2xx 响应的正文存进暂存区，返回 {@code response.file} 的内容；其它状态返回 null。 */
    static StagedFile save(Map<String, Object> spec, HttpResult result) {
        if (result.status() < 200 || result.status() >= 300 || result.bodyBytes() == null) {
            return null;
        }
        String name = null;
        if (spec.get("download") instanceof Map<?, ?> options
                && options.get("filename") instanceof String given && !given.isBlank()) {
            name = given;
        }
        if (name == null) {
            name = dispositionName(result.headers().get("content-disposition"));
        }
        if (name == null) {
            name = pathName(result.url());
        }
        try {
            return scope().store(result.bodyBytes(), name, result.headers().get("content-type"));
        } catch (FileStageException ex) {
            throw new RuleProblem(
                    ErrorCode.UPSTREAM_ERROR, ex.getMessage(), result.method(), result.path(), result.status());
        }
    }

    static Map<String, Object> view(HttpResult result, StagedFile file) {
        LinkedHashMap<String, Object> view = new LinkedHashMap<>(result.view());
        if (file != null) {
            view.put("file", scope().view(file));
        }
        return java.util.Collections.unmodifiableMap(view);
    }

    static String dispositionName(String header) {
        if (header == null) {
            return null;
        }
        Matcher star = STAR.matcher(header);
        if (star.find()) {
            try {
                Charset charset = star.group(1).isBlank() ? StandardCharsets.UTF_8 : Charset.forName(star.group(1).trim());
                return URLDecoder.decode(star.group(2).trim().replace("+", "%2B"), charset);
            } catch (IllegalArgumentException ignored) {
                // 编码名或百分号转义不对时退回普通 filename。
            }
        }
        Matcher quoted = QUOTED.matcher(header);
        if (quoted.find() && !quoted.group(1).isBlank()) {
            return quoted.group(1);
        }
        Matcher bare = BARE.matcher(header);
        if (bare.find() && !bare.group(1).isBlank()) {
            return bare.group(1).trim();
        }
        return null;
    }

    private static String pathName(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) {
                return null;
            }
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.isBlank() ? null : last;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static void write(ByteArrayOutputStream out, String text, Charset charset) {
        out.writeBytes(text.getBytes(charset));
    }

    private static String quote(String value) {
        return oneLine(value).replace("\"", "%22");
    }

    private static String oneLine(String value) {
        return value.replace("\r", "").replace("\n", "");
    }
}
