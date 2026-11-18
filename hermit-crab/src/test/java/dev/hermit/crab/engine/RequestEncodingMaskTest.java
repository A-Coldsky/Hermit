package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.hermit.crab.secure.Redactor;
import dev.hermit.crab.testing.SecretChecks;

/** 秘密出现在 query、path、form、JSON 和 wire 里时，四种字符集的追踪都看不到原文。 */
class RequestEncodingMaskTest {
    private static final String QUOTED = "ab\"cdEF";

    @Test
    void masksSecretsForEveryCharset() throws Exception {
        try (RawLoopback loop = RawLoopback.open()) {
            mask(loop, "a b:c", StandardCharsets.UTF_8, null, "a%20b:c", "a+b%3Ac", "a%20b:c");
            mask(loop, "A密 码B", Charset.forName("GBK"), "GBK",
                    "A%C3%DC%20%C2%EBB", "A%C3%DC+%C2%EBB", "A%C3%DC%20%C2%EBB");
            String rare = "ab" + new String(Character.toChars(0x20000)) + " cd";
            mask(loop, rare, Charset.forName("GB18030"), "GB18030",
                    "ab%95%32%82%36%20cd", "ab%95%32%82%36+cd", "ab%95%32%82%36%20cd");
            mask(loop, "caf\u00e9!", StandardCharsets.ISO_8859_1, "ISO-8859-1",
                    "caf%E9!", "caf%E9%21", "caf%E9!");
        }
    }

    private static void mask(
            RawLoopback loop,
            String secret,
            Charset charset,
            String encoding,
            String queryForm,
            String formBody,
            String pathForm
    ) throws Exception {
        LinkedHashMap<String, Object> form = spec(secret, encoding);
        form.put("form", Map.of("f", secret));
        RawLoopback.Hit posted = loop.exchange(form);
        assertThat(posted.captured().target).contains(queryForm).contains(pathForm);
        assertThat(new String(posted.captured().body, charset)).contains(formBody);
        assertMasked(secret, form, posted.result(), queryForm, formBody, pathForm);

        LinkedHashMap<String, Object> json = spec(secret, encoding);
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("p", secret);
        body.put("q", QUOTED);
        json.put("json", body);
        RawLoopback.Hit written = loop.exchange(json);
        String text = new String(written.captured().body, charset);
        assertThat(text).contains(secret).contains("ab\\\"cdEF");
        assertThat(written.captured().body).doesNotContain((byte) '?');
        assertMasked(secret, json, written.result(), queryForm, secret, pathForm);
        String rendered = render(json, written.result(), secret);
        SecretChecks.absent(rendered, QUOTED);
        SecretChecks.absent(rendered, "ab\\\"cdEF");
    }

    private static void assertMasked(
            String secret,
            Map<String, Object> spec,
            HttpResult result,
            String queryForm,
            String bodyNeedle,
            String pathForm
    ) {
        String target = result.wire().get("target").toString();
        String body = result.wire().get("body").toString();
        assertThat(target).contains(queryForm).contains(pathForm);
        assertThat(body).contains(bodyNeedle);
        Redactor redactor = Redactor.of(Set.of(secret, QUOTED));
        SecretChecks.absent(redactor.redact(target), queryForm);
        SecretChecks.absent(redactor.redact(target), pathForm);
        SecretChecks.absent(redactor.redact(body), bodyNeedle);
        SecretChecks.absent(redactor.redact(body), secret);
        String rendered = render(spec, result, secret);
        SecretChecks.absent(rendered, secret);
        SecretChecks.absent(rendered, queryForm);
        SecretChecks.absent(rendered, pathForm);
        assertThat(rendered).contains("\"wire\"").contains("***");
    }

    private static String render(Map<String, Object> spec, HttpResult result, String secret) {
        DevTraceDraft draft = new DevTraceDraft(null);
        draft.add("action", 0, 1, DevTraceDraft.requestPiece(
                spec,
                result.method(),
                result.path(),
                result.status(),
                result.headers(),
                result.body(),
                result.wire()));
        return draft.render(Redactor.of(Set.of(secret, QUOTED)), List.of(), true);
    }

    private static LinkedHashMap<String, Object> spec(String secret, String encoding) {
        LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
        spec.put("method", "POST");
        spec.put("path", "/p/" + secret);
        spec.put("query", Map.of("q", secret));
        if (encoding != null) {
            spec.put("encoding", encoding);
        }
        return spec;
    }
}
