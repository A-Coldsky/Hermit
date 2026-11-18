package dev.hermit.common.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class UrlComponentsTest {
    private static final Charset UTF_8 = StandardCharsets.UTF_8;
    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void queryKeepsTheBrowserLiteralsAndStillEncodesSeparators() {
        assertThat(UrlComponents.query("mbox:getAllFolders", UTF_8)).isEqualTo("mbox:getAllFolders");
        assertThat(UrlComponents.query("a b:c", UTF_8)).isEqualTo("a%20b:c");
        assertThat(UrlComponents.query("Tok+NValue", UTF_8)).isEqualTo("Tok%2BNValue");
        assertThat(UrlComponents.query("~!*()$,;:@/?", UTF_8)).isEqualTo("~!*()$,;:@/?");
        assertThat(UrlComponents.query("a&b=c#d%e'f", UTF_8)).isEqualTo("a%26b%3Dc%23d%25e%27f");
        assertThat(UrlComponents.query("中", UTF_8)).isEqualTo("%E4%B8%AD");
        assertThat(UrlComponents.query("中", GBK)).isEqualTo("%D6%D0");
        assertThat(UrlComponents.query(null, UTF_8)).isEmpty();
        assertThat(UrlComponents.query("", UTF_8)).isEmpty();
    }

    @Test
    void queryRejectsCharactersTheCharsetCannotRepresent() {
        assertThatThrownBy(() -> UrlComponents.query("中文", StandardCharsets.ISO_8859_1))
                .isInstanceOf(UrlComponents.Unencodable.class)
                .hasMessage("字符无法用 ISO-8859-1 发送");
    }

    @Test
    void normalizeEncodesOnlyIllegalCharactersAndKeepsExistingPercentBytes() {
        assertThat(UrlComponents.normalize("/a b/中", UTF_8)).isEqualTo("/a%20b/%E4%B8%AD");
        assertThat(UrlComponents.normalize("/a b/中", GBK)).isEqualTo("/a%20b/%D6%D0");
        assertThat(UrlComponents.normalize("/p/%3A/%3a", UTF_8)).isEqualTo("/p/%3A/%3a");
        assertThat(UrlComponents.normalize("/p/%", UTF_8)).isEqualTo("/p/%25");
        assertThat(UrlComponents.normalize("/p/%3", UTF_8)).isEqualTo("/p/%253");
        assertThat(UrlComponents.normalize("/p/%3G", UTF_8)).isEqualTo("/p/%253G");
        assertThat(UrlComponents.normalize("/p/a//b", UTF_8)).isEqualTo("/p/a//b");
        assertThat(UrlComponents.normalize("/p/X#Y", UTF_8)).isEqualTo("/p/X#Y");
        assertThat(UrlComponents.normalize("/p/X[Y]", UTF_8)).isEqualTo("/p/X%5BY%5D");
        assertThat(UrlComponents.normalize("/p/X'Y", UTF_8)).isEqualTo("/p/X'Y");
        assertThat(UrlComponents.normalize("a\tb", UTF_8)).isEqualTo("a%09b");
    }

    @Test
    void relativeEmptySegmentsStayAndDotSegmentsMatchTheOldResolverOtherwise() {
        URI base = URI.create("http://127.0.0.1/app/");
        assertThat(UrlComponents.resolve(base, "a//b", UTF_8)).hasToString("http://127.0.0.1/app/a//b");
        assertThat(UrlComponents.resolve(base, "a//../b", UTF_8)).hasToString("http://127.0.0.1/app/a/b");
        assertThat(base.resolve("a//../b")).hasToString("http://127.0.0.1/app/b");

        assertThat(UrlComponents.resolve(base, "a/../c", UTF_8)).isEqualTo(base.resolve("a/../c"));
        assertThat(UrlComponents.resolve(base, "../secret", UTF_8)).isEqualTo(base.resolve("../secret"));
        assertThat(UrlComponents.resolve(base, "a/%2e%2e/b", UTF_8)).isEqualTo(base.resolve("a/%2e%2e/b"));
        assertThat(UrlComponents.resolve(base, "a/b?", UTF_8)).isEqualTo(base.resolve("a/b?"));
        assertThat(UrlComponents.resolve(base, "?x=1", UTF_8)).isEqualTo(base.resolve("?x=1"));

        URI absoluteDots = UrlComponents.resolve(base, "/app/a/../secret", UTF_8);
        assertThat(absoluteDots).isEqualTo(base.resolve("/app/a/../secret"));
        assertThat(absoluteDots.getRawPath()).isEqualTo("/app/a/../secret");

        URI absoluteEmpty = UrlComponents.resolve(base, "/a//b", UTF_8);
        assertThat(absoluteEmpty).isEqualTo(base.resolve("/a//b"));
        assertThat(absoluteEmpty.getRawPath()).isEqualTo("/a//b");

        assertThat(UrlComponents.resolve(base, "//evil/x", UTF_8)).isEqualTo(base.resolve("//evil/x"));
        assertThat(UrlComponents.resolve(base, "http://127.0.0.1/other", UTF_8))
                .isEqualTo(URI.create("http://127.0.0.1/other"));
    }

    @Test
    void jsonEscapeMatchesJacksonStringContents() {
        assertThat(UrlComponents.jsonEscape("ab\"cd")).isEqualTo("ab\\\"cd");
        assertThat(UrlComponents.jsonEscape("a\\b")).isEqualTo("a\\\\b");
        assertThat(UrlComponents.jsonEscape("a\nb")).isEqualTo("a\\nb");
        assertThat(UrlComponents.jsonEscape("plain")).isEqualTo("plain");
    }
}
