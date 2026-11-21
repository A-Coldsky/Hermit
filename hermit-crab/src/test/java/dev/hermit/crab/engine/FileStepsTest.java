package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FileStepsTest {
    @Test
    void syntaxStaysAsciiAndValuesFollowTheStepEncoding() {
        FileSteps.Multipart utf16 = FileSteps.multipart(Map.of("note", "你好"), StandardCharsets.UTF_16BE);
        String boundary = utf16.contentType().substring(utf16.contentType().indexOf("boundary=") + 9);
        String raw = new String(body(utf16), StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("--" + boundary + "\r\nContent-Disposition: form-data; name=\"");
        assertThat(raw).endsWith("\r\n--" + boundary + "--\r\n");
        assertThat(body(utf16)).containsSequence("你好".getBytes(StandardCharsets.UTF_16BE));

        FileSteps.Multipart gbk = FileSteps.multipart(Map.of("note", "你好"), Charset.forName("GBK"));
        assertThat(body(gbk)).containsSequence("你好".getBytes(Charset.forName("GBK")));
    }

    private static byte[] body(FileSteps.Multipart multipart) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            multipart.entity().writeTo(out);
            assertThat(multipart.entity().getContentLength()).isEqualTo(out.size());
            assertThat(multipart.entity().getContent().readAllBytes()).isEqualTo(out.toByteArray());
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
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
        assertThat(FileSteps.dispositionName("inline")).isNull();
    }
}
