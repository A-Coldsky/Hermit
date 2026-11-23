package dev.hermit.crab.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;

import dev.hermit.common.error.ErrorCode;
import dev.hermit.common.http.UrlComponents;
import dev.hermit.common.validate.FileTransfers;
import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.files.FileStage;
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

    /** {@code entity} 按确切长度流式写出；{@code wire} 是写进追踪的请求体摘要，没有文件内容。 */
    record Multipart(HttpEntity entity, String contentType, String wire) {
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
        ArrayList<Segment> segments = new ArrayList<>();
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        ArrayList<String> summary = new ArrayList<>();
        for (Map.Entry<?, ?> part : parts.entrySet()) {
            String field = String.valueOf(part.getKey());
            Object value = part.getValue();
            if (value == null) {
                continue;
            }
            ascii(pending, "--" + boundary + "\r\n");
            if (value instanceof Map<?, ?> filePart) {
                Object id = filePart.get("file");
                if (!(id instanceof String fileId) || fileId.isBlank()) {
                    throw new RuleProblem(
                            ErrorCode.RULE_MISMATCH, "multipart." + field + ".file 没有渲染出 fileId", null, null, null);
                }
                FileScope current = scope();
                StagedFile file;
                try {
                    file = current.require(fileId);
                    if (!Files.isRegularFile(file.path())) {
                        throw new FileStageException(FileStage.MISSING);
                    }
                } catch (FileStageException ex) {
                    throw new RuleProblem(ErrorCode.INVALID_INPUT, ex.getMessage(), null, null, null);
                }
                String name = filePart.get("filename") instanceof String given && !given.isBlank()
                        ? given
                        : file.name();
                String type = filePart.get("content_type") instanceof String given && !given.isBlank()
                        ? given
                        : file.mime();
                ascii(pending, "Content-Disposition: form-data; name=\"");
                text(pending, quote(field), charset, field);
                ascii(pending, "\"; filename=\"");
                text(pending, quote(name), charset, field);
                ascii(pending, "\"\r\nContent-Type: ");
                text(pending, oneLine(type), charset, field);
                ascii(pending, "\r\n\r\n");
                flushBytes(pending, segments);
                segments.add(new FileSegment(current.stage(), file));
                summary.add(field + "=<文件 " + name + "，" + file.size() + " 字节，" + type
                        + "，sha256 " + file.sha256() + ">");
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                ascii(pending, "Content-Disposition: form-data; name=\"");
                text(pending, quote(field), charset, field);
                ascii(pending, "\"\r\n\r\n");
                text(pending, String.valueOf(value), charset, field);
                summary.add(field + "=" + value);
            } else {
                throw new RuleProblem(ErrorCode.RULE_MISMATCH, FileTransfers.PART_TYPE, null, null, null);
            }
            ascii(pending, "\r\n");
        }
        ascii(pending, "--" + boundary + "--\r\n");
        flushBytes(pending, segments);
        long total = 0;
        for (Segment segment : segments) {
            total = Math.addExact(total, segment.length());
        }
        return new Multipart(
                new MultipartEntity(List.copyOf(segments), total),
                "multipart/form-data; boundary=" + boundary,
                "multipart/form-data\n" + String.join("\n", summary));
    }

    /** 把 2xx 响应的正文存进暂存区，返回 {@code response.file} 的内容；其它状态或 204 无正文返回 null。 */
    static StagedFile save(Map<String, Object> spec, HttpResult result) {
        try {
            if (result.status() < 200 || result.status() >= 300 || result.status() == 204 || result.bodyBytes() == null) {
                return null;
            }
            if (result.downloaded() && result.spool() == null) {
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
            String type = result.headers().get("content-type");
            FileScope current = scope();
            StagedFile saved = result.spool() != null
                    ? current.commit(result.spool(), name, type)
                    : current.store(result.bodyBytes(), name, type);
            current.recordCommitted(saved.fileId());
            current.recordToken(saved.token());
            return saved;
        } catch (FileStageException ex) {
            throw new RuleProblem(
                    ErrorCode.UPSTREAM_ERROR, ex.getMessage(), result.method(), result.path(), result.status());
        } finally {
            if (result.spool() != null) {
                result.spool().discard();
            }
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
                String decoded = URLDecoder.decode(star.group(2).trim().replace("+", "%2B"), charset);
                if (!decoded.isBlank()) {
                    return decoded;
                }
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

    private static void flushBytes(ByteArrayOutputStream pending, List<Segment> segments) {
        if (pending.size() > 0) {
            segments.add(new BytesSegment(pending.toByteArray()));
            pending.reset();
        }
    }

    /** 分隔符和头的语法固定按 ASCII 写，不跟步骤的 encoding 走。 */
    private static void ascii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    /** 字段名、文件名和文本值按步骤的 encoding 写；表示不了的字符失败，不换成问号。 */
    private static void text(ByteArrayOutputStream out, String text, Charset charset, String field) {
        try {
            out.writeBytes(UrlComponents.encode(text, charset));
        } catch (UrlComponents.Unencodable ex) {
            throw new RuleProblem(
                    ErrorCode.RULE_MISMATCH, "multipart." + field + " 含有编码无法表示的字符", null, null, null);
        }
    }

    private static String quote(String value) {
        return oneLine(value).replace("\\", "%5C").replace("\"", "%22");
    }

    /** 去掉所有 ASCII 控制字符（含 CR、LF 和 DEL），避免注入 multipart 分段头。 */
    private static String oneLine(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private sealed interface Segment {
        long length();
    }

    private record BytesSegment(byte[] data) implements Segment {
        @Override
        public long length() {
            return data.length;
        }
    }

    private record FileSegment(FileStage stage, StagedFile file) implements Segment {
        @Override
        public long length() {
            return file.size();
        }
    }

    /**
     * 按预先算好的确切 Content-Length 分段流式写出，不把整个文件读进内存。
     * 每次写出都重新打开文件流，支持 307/308 跳转或重试。
     */
    private static final class MultipartEntity extends AbstractHttpEntity {
        private static final int BUFFER_SIZE = 64 * 1024;

        private final List<Segment> segments;
        private final long contentLength;

        private MultipartEntity(List<Segment> segments, long contentLength) {
            super((String) null, null, false);
            this.segments = segments;
            this.contentLength = contentLength;
        }

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public long getContentLength() {
            return contentLength;
        }

        @Override
        public boolean isStreaming() {
            return false;
        }

        @Override
        public InputStream getContent() {
            return new SegmentInputStream(segments);
        }

        @Override
        public void writeTo(OutputStream out) throws IOException {
            byte[] buffer = null;
            for (Segment segment : segments) {
                if (segment instanceof BytesSegment bytes) {
                    out.write(bytes.data());
                    continue;
                }
                FileSegment file = (FileSegment) segment;
                long remaining = file.file().size();
                if (remaining == 0) {
                    continue;
                }
                if (buffer == null) {
                    buffer = new byte[BUFFER_SIZE];
                }
                try (InputStream in = file.stage().open(file.file())) {
                    while (remaining > 0) {
                        int want = (int) Math.min(buffer.length, remaining);
                        int read = in.read(buffer, 0, want);
                        if (read < 0) {
                            throw new IOException("staged file shorter than expected length");
                        }
                        out.write(buffer, 0, read);
                        remaining -= read;
                    }
                } catch (FileStageException ex) {
                    throw new IOException(ex.getMessage(), ex);
                }
            }
            out.flush();
        }

        @Override
        public void close() {
            // 文件流在每次 writeTo 或 getContent 结束后自行关闭。
        }
    }

    private static final class SegmentInputStream extends InputStream {
        private final List<Segment> segments;
        private int index;
        private InputStream current;
        private long remaining;

        private SegmentInputStream(List<Segment> segments) {
            this.segments = segments;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : (single[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            while (true) {
                if (current == null && !advance()) {
                    return -1;
                }
                int want = (int) Math.min(length, remaining);
                int read = current.read(buffer, offset, want);
                if (read >= 0) {
                    remaining -= read;
                    if (remaining == 0) {
                        closeCurrent();
                    }
                    return read;
                }
                if (remaining > 0) {
                    closeCurrent();
                    throw new IOException("staged file shorter than expected length");
                }
                closeCurrent();
            }
        }

        private boolean advance() throws IOException {
            while (index < segments.size()) {
                Segment next = segments.get(index++);
                if (next.length() == 0) {
                    continue;
                }
                if (next instanceof BytesSegment bytes) {
                    current = new ByteArrayInputStream(bytes.data());
                    remaining = bytes.data().length;
                    return true;
                }
                FileSegment file = (FileSegment) next;
                try {
                    current = file.stage().open(file.file());
                } catch (FileStageException ex) {
                    throw new IOException(ex.getMessage(), ex);
                }
                remaining = file.file().size();
                return true;
            }
            return false;
        }

        private void closeCurrent() throws IOException {
            if (current != null) {
                InputStream closing = current;
                current = null;
                remaining = 0;
                closing.close();
            }
        }

        @Override
        public void close() throws IOException {
            index = segments.size();
            closeCurrent();
        }
    }
}
