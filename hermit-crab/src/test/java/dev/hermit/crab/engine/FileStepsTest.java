package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.files.FileScope;
import dev.hermit.crab.files.FileStage;
import dev.hermit.crab.files.FileStageSettings;
import dev.hermit.crab.files.StagedFile;
import dev.hermit.crab.store.Origin;

class FileStepsTest {
    @Test
    void syntaxStaysAsciiAndValuesFollowTheStepEncoding() throws Exception {
        FileSteps.Multipart utf16 = FileSteps.multipart(Map.of("note", "你好"), StandardCharsets.UTF_16BE);
        String boundary = utf16.contentType().substring(utf16.contentType().indexOf("boundary=") + 9);
        byte[] utf16Bytes = write(utf16.entity());
        assertThat(utf16.entity().getContentLength()).isEqualTo(utf16Bytes.length);
        assertThat(utf16.entity().isChunked()).isFalse();
        assertThat(utf16.entity().isRepeatable()).isTrue();
        String raw = new String(utf16Bytes, StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("--" + boundary + "\r\nContent-Disposition: form-data; name=\"");
        assertThat(raw).endsWith("\r\n--" + boundary + "--\r\n");
        assertThat(utf16Bytes).containsSequence("你好".getBytes(StandardCharsets.UTF_16BE));

        FileSteps.Multipart gbk = FileSteps.multipart(Map.of("note", "你好"), Charset.forName("GBK"));
        byte[] gbkBytes = write(gbk.entity());
        assertThat(gbk.entity().getContentLength()).isEqualTo(gbkBytes.length);
        assertThat(gbkBytes).containsSequence("你好".getBytes(Charset.forName("GBK")));
    }

    @Test
    void streamsStagedFilesWithExactLengthAndRepeatableReads(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        byte[] payload = new byte[150_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31 + 7);
        }
        byte[] marker = "PAYLOAD-INNER-MARKER-77".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, payload, 70_000, marker.length);
        StagedFile big = stage.store(payload, "report.bin", "application/octet-stream", "k1");
        StagedFile empty = stage.store(new byte[0], "empty.txt", "text/plain", "k1");

        FileScope previous = FileScope.begin(stage, "k1", "http://127.0.0.1:8080");
        try {
            LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
            spec.put("note", "hello");
            spec.put("file", Map.of("file", big.fileId()));
            spec.put("zero", Map.of("file", empty.fileId()));

            FileSteps.Multipart part = FileSteps.multipart(spec, StandardCharsets.UTF_8);
            HttpEntity entity = part.entity();
            assertThat(entity.isChunked()).isFalse();
            assertThat(entity.isRepeatable()).isTrue();
            assertThat(entity.isStreaming()).isFalse();

            byte[] first = write(entity);
            byte[] second = write(entity);
            byte[] streamed;
            try (InputStream in = entity.getContent()) {
                streamed = in.readAllBytes();
            }

            assertThat(entity.getContentLength()).isEqualTo(first.length);
            assertThat(second).isEqualTo(first);
            assertThat(streamed).isEqualTo(first);
            assertThat(first).containsSequence(payload);
            assertThat(part.wire())
                    .contains("report.bin")
                    .contains(String.valueOf(payload.length))
                    .contains(big.sha256())
                    .doesNotContain("PAYLOAD-INNER-MARKER-77");
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void sanitizesControlCharsAndQuotesInMultipartHeaders(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        StagedFile staged = stage.store("abc".getBytes(StandardCharsets.UTF_8), "orig.txt", "text/plain", "k1");

        FileScope previous = FileScope.begin(stage, "k1", "http://127.0.0.1:8080");
        try {
            LinkedHashMap<String, Object> filePart = new LinkedHashMap<>();
            filePart.put("file", staged.fileId());
            filePart.put("filename", "a\r\n\0\\b\"c\u007f.txt");
            filePart.put("content_type", "text/plain\r\n\0\t\u007f; charset=utf-8");
            Map<String, Object> spec = Map.of("f\r\n\t\0\"\\x\u007f", filePart);

            FileSteps.Multipart part = FileSteps.multipart(spec, StandardCharsets.UTF_8);
            String raw = new String(write(part.entity()), StandardCharsets.UTF_8);
            assertThat(raw).contains("Content-Disposition: form-data; name=\"f%22%5Cx\"; filename=\"a%5Cb%22c.txt\"\r\n");
            assertThat(raw).contains("Content-Type: text/plain; charset=utf-8\r\n\r\n");
            assertThat(raw).doesNotContain("\0").doesNotContain("\u007f");
        } finally {
            FileScope.restore(previous);
        }
    }

    @Test
    void unencodableTextFailsInsteadOfBecomingQuestionMarks() {
        assertThatThrownBy(() -> FileSteps.multipart(Map.of("note", "😀"), Charset.forName("GBK")))
                .isInstanceOf(RuleProblem.class)
                .hasMessageContaining("multipart.note 含有编码无法表示的字符");
    }

    @Test
    void readsFileNamesFromContentDisposition() {
        assertThat(FileSteps.dispositionName("attachment; filename*=UTF-8''%E6%8A%A5%E8%A1%A8.pdf")).isEqualTo("报表.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename=\"a b.pdf\"")).isEqualTo("a b.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename=a.pdf")).isEqualTo("a.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename*=UTF-8''%20%20; filename=\"fallback.pdf\""))
                .isEqualTo("fallback.pdf");
        assertThat(FileSteps.dispositionName("attachment; filename*=UTF-8''; filename=bare.pdf"))
                .isEqualTo("bare.pdf");
        assertThat(FileSteps.dispositionName("inline")).isNull();
    }

    @Test
    void saveDiscardsSpoolOnEarlyExitOrUnexpectedFailure(@TempDir Path temp) {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());

        FileStage.Spool missingScopeSpool = stage.spool();
        missingScopeSpool.write(new byte[32], 0, 32);
        HttpResult ok = new HttpResult(
                200, "http://127.0.0.1/doc.pdf", "GET", "/doc.pdf", Map.of(), "", null, false,
                new byte[0], List.of(), null, missingScopeSpool);
        assertThatThrownBy(() -> FileSteps.save(Map.of("download", true), ok))
                .isInstanceOf(RuleProblem.class)
                .hasMessage(FileSteps.NO_SCOPE);
        assertThat(stage.usage()).containsEntry("bytes", 0L).containsEntry("files", 0);

        FileStage.Spool non2xxSpool = stage.spool();
        non2xxSpool.write(new byte[48], 0, 48);
        HttpResult notFound = new HttpResult(
                404, "http://127.0.0.1/doc.pdf", "GET", "/doc.pdf", Map.of(), "", null, false,
                new byte[0], List.of(), null, non2xxSpool);
        assertThat(FileSteps.save(Map.of("download", true), notFound)).isNull();
        assertThat(stage.usage()).containsEntry("bytes", 0L).containsEntry("files", 0);

        // 在解析文件名阶段抛错时也必须回收 spool，不能泄漏容量。
        FileStage.Spool brokenHeaderSpool = stage.spool();
        brokenHeaderSpool.write(new byte[64], 0, 64);
        HttpResult brokenHeaders = new HttpResult(
                200, "http://127.0.0.1/doc.pdf", "GET", "/doc.pdf", null, "", null, false,
                new byte[0], List.of(), null, brokenHeaderSpool);
        assertThatThrownBy(() -> FileSteps.save(Map.of("download", true), brokenHeaders))
                .isInstanceOf(NullPointerException.class);
        assertThat(stage.usage()).containsEntry("bytes", 0L).containsEntry("files", 0);
    }

    @Test
    void multipartRedirectToGetDropsRequestEntityAndWireBody(@TempDir Path temp) throws Exception {
        FileStage stage = new FileStage(temp.resolve("files"), Clock.systemUTC(), FileStageSettings.defaults());
        StagedFile staged = stage.store("upload-payload-body".getBytes(StandardCharsets.UTF_8), "doc.txt", "text/plain", "k1");

        FileScope previous = FileScope.begin(stage, "k1", "http://127.0.0.1:8080");
        try (ScriptedLoopback loop = ScriptedLoopback.start(); SessionManager sessions = new SessionManager()) {
            loop.reply(ScriptedLoopback.response(302, null, new byte[0], "Location: /done"));
            loop.reply(ScriptedLoopback.response(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8)));

            SessionKey key = new SessionKey(Origin.LOCAL, "upload_conch", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
                spec.put("method", "POST");
                spec.put("path", "/upload");
                spec.put("follow_redirects", true);
                spec.put("multipart", Map.of("file", Map.of("file", staged.fileId()), "note", "hi"));

                HttpResult result = HttpCalls.execute(
                        spec,
                        sessions.client(key),
                        () -> {
                        },
                        URI.create(loop.base()),
                        null,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(10));

                assertThat(result.status()).isEqualTo(200);
                assertThat(result.method()).isEqualTo("GET");
                assertThat(result.wire()).containsEntry("method", "GET");
                assertThat(result.wire().get("body")).isNull();

                List<ScriptedLoopback.Hit> hits = loop.hits();
                assertThat(hits).hasSize(2);
                assertThat(hits.get(0).method()).isEqualTo("POST");
                assertThat(hits.get(0).header("content-type")).startsWith("multipart/form-data; boundary=");
                assertThat(hits.get(0).header("content-length")).isNotNull();
                assertThat(Integer.parseInt(hits.get(0).header("content-length"))).isEqualTo(hits.get(0).body().length);
                assertThat(new String(hits.get(0).body(), StandardCharsets.UTF_8)).contains("upload-payload-body");

                assertThat(hits.get(1).method()).isEqualTo("GET");
                assertThat(hits.get(1).target()).isEqualTo("/done");
                assertThat(hits.get(1).body()).isEmpty();
                assertThat(hits.get(1).header("content-type")).isNull();
            } finally {
                sessions.unlock(key);
            }
        } finally {
            FileScope.restore(previous);
        }
    }

    private static byte[] write(HttpEntity entity) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        entity.writeTo(out);
        return out.toByteArray();
    }
}

