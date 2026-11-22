package dev.hermit.common.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;

import org.junit.jupiter.api.Test;

class TemplateEngineTest {

    private final TemplateEngine engine = new TemplateEngine();

    /** 解析结果按原文缓存：同一模板换变量照常求值，错的模板每次都报错，缓存有上限。 */
    @Test
    void repeatedTemplatesRenderFromTheCurrentVariables() {
        String template = "{{user.name | trim}}-{{n}}";
        assertThat(engine.render(template, Map.of("user", Map.of("name", " ann "), "n", 1))).isEqualTo("ann-1");
        assertThat(engine.render(template, Map.of("user", Map.of("name", "bob"), "n", 2))).isEqualTo("bob-2");
        assertThatThrownBy(() -> engine.render(template, Map.of("n", 3))).isInstanceOf(TemplateException.class);
        assertThat(engine.render(" {{n}} ", Map.of("n", 4))).isEqualTo(4);
        assertThat(engine.render(" {{n}} ", Map.of("n", "x"))).isEqualTo("x");
        assertThat(engine.render("a {{n}} ", Map.of("n", 5))).isEqualTo("a 5 ");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> engine.render("{{n", Map.of("n", 1)))
                    .isInstanceOf(TemplateException.class)
                    .hasMessageContaining("}}");
        }
        for (int i = 0; i < Expressions.CACHE_ENTRIES + 50; i++) {
            assertThat(engine.render("k" + i + "={{n}}", Map.of("n", i))).isEqualTo("k" + i + "=" + i);
        }
        assertThat(Expressions.cacheSize()).isLessThanOrEqualTo(Expressions.CACHE_ENTRIES);
        assertThat(engine.referencedPaths(template)).containsExactly("user.name", "n");
    }

    @Test
    void exactExpressionKeepsTheValueAndInterpolationStringifies() {
        List<String> items = List.of("a", "b");
        assertThat(engine.render("{{items}}", Map.of("items", items))).isSameAs(items);
        assertThat(engine.render("  {{items}}  ", Map.of("items", items))).isSameAs(items);
        assertThat(engine.render("n={{n}}", Map.of("n", 3))).isEqualTo("n=3");
        assertThat(engine.render("{{a}}-{{b}}", Map.of("a", "x", "b", "y"))).isEqualTo("x-y");
        assertThat(engine.render("id={{name}}", mapOf("name", null))).isEqualTo("id=");
        assertThat(engine.render("{{name}}", mapOf("name", null))).isNull();
        assertThat(engine.render(3, Map.of())).isEqualTo(3);
        assertThat(engine.render(List.of("{{n}}", 2), Map.of("n", "a"))).isEqualTo(List.of("a", 2));
    }

    @Test
    void interpolationSerializesMapsAndListsAsJson() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", "A");
        row.put("n", 1);
        LinkedHashMap<String, Object> obj = new LinkedHashMap<>();
        obj.put("ok", true);
        obj.put("row", row);
        assertThat(engine.render("{{row}}", Map.of("row", row))).isEqualTo(row);
        assertThat(engine.render("x={{row}}", Map.of("row", row))).isEqualTo("x={\"id\":\"A\",\"n\":1}");
        assertThat(engine.render("ids={{items}}", Map.of("items", List.of(row, "b"))))
                .isEqualTo("ids=[{\"id\":\"A\",\"n\":1},\"b\"]");
        assertThat(engine.render("{{obj}}!", Map.of("obj", obj)))
                .isEqualTo("{\"ok\":true,\"row\":{\"id\":\"A\",\"n\":1}}!");
        assertThat(engine.render("e={{items}}", Map.of("items", List.of()))).isEqualTo("e=[]");
    }

    @Test
    void missingPathThrowsUnlessDefaultIsInTheChain() {
        assertThatThrownBy(() -> engine.render("{{credential.password}}", Map.of()))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("credential.password")
                .extracting(ex -> ((TemplateException) ex).path())
                .isEqualTo("credential.password");
        assertThat(engine.render("{{missing | default('z')}}", Map.of())).isEqualTo("z");
        assertThat(engine.render("{{missing | md5 | default('z')}}", Map.of())).isEqualTo("z");
        assertThat(engine.render("{{name | default('z')}}", mapOf("name", null))).isEqualTo("z");
        assertThat(engine.render("{{name | default('z')}}", Map.of("name", ""))).isEqualTo("z");
        assertThat(engine.render("{{name | default(1)}}", Map.of("name", ""))).isEqualTo(1);
        assertThat(engine.render("{{name | default('a,b')}}", Map.of("name", ""))).isEqualTo("a,b");
        assertThat(engine.render("{{missing | default(input.page)}}", Map.of("input", Map.of("page", 4))))
                .isEqualTo(4);
        assertThatThrownBy(() -> engine.render("{{name | default(missing)}}", Map.of("name", "")))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void resolvesIndexesAndDoesNotMutateTheInputTree() {
        Map<String, Object> row = Map.of("id", "A1");
        assertThat(engine.render("{{items[0].id}}", Map.of("items", List.of(row)))).isEqualTo("A1");
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("label", "id={{input.id}}");
        Object rendered = engine.render(tree, Map.of("input", Map.of("id", "9")));
        assertThat(rendered).isEqualTo(Map.of("label", "id=9"));
        assertThat(tree).containsEntry("label", "id={{input.id}}");
    }

    @Test
    void builtinFilters() {
        assertThat(engine.render("{{name | trim | md5}}", Map.of("name", " hello ")))
                .isEqualTo("5d41402abc4b2a76b9719d911017c592");
        assertThat(engine.render("{{name | sha256}}", Map.of("name", "hello")))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(engine.render("{{name | base64}}", Map.of("name", "hello"))).isEqualTo("aGVsbG8=");
        assertThat(engine.render("{{name | urlencode}}", Map.of("name", "a b"))).isEqualTo("a%20b");
        assertThat(engine.render("{{name | trim}}", Map.of("name", "  ab  "))).isEqualTo("ab");
    }

    @Test
    void dateFilterParsesKnownPatternsAndNowUsesTheClock() {
        assertThat(engine.render("{{d | date('yyyy/MM/dd')}}", Map.of("d", "2026年10月12日")))
                .isEqualTo("2026/10/12");
        assertThat(engine.render("{{d | date('yyyy-MM-dd')}}", Map.of("d", "20261012")))
                .isEqualTo("2026-10-12");
        assertThat(engine.render("{{d | date('yyyyMMdd')}}", Map.of("d", "2026-10-12 01:02:03")))
                .isEqualTo("20261012");
        assertThat(engine.render("{{d | date('yyyy-MM-dd HH:mm:ss')}}", Map.of("d", "2026/10/12 01:02:03")))
                .isEqualTo("2026-10-12 01:02:03");
        assertThatThrownBy(() -> engine.render("{{d | date('yyyy-MM-dd')}}", Map.of("d", "not-a-date")))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("日期无法解析");
        assertThatThrownBy(() -> engine.render("{{now}}", Map.of()))
                .isInstanceOf(TemplateException.class);

        Clock clock = Clock.fixed(Instant.parse("2026-10-12T01:02:03Z"), ZoneOffset.UTC);
        TemplateEngine dated = new TemplateEngine(FilterRegistry.defaults(clock));
        assertThat(dated.render("{{now | date('yyyy-MM-dd HH:mm:ss')}}", Map.of()))
                .isEqualTo("2026-10-12 01:02:03");
        assertThat(dated.render("{{now | date('yyyy-MM-dd')}}", Map.of("now", "2020-01-02")))
                .isEqualTo("2020-01-02");
    }

    @Test
    void rsaEncryptsWithPemOrDer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = pem(pair.getPublic().getEncoded());
        String der = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String fromPem = (String) engine.render("{{secret | rsa(key)}}", Map.of("secret", "hello", "key", pem));
        String fromDer = (String) engine.render("{{secret | rsa(key)}}", Map.of("secret", "hello", "key", der));
        assertThat(decrypt(fromPem, pair)).isEqualTo("hello");
        assertThat(decrypt(fromDer, pair)).isEqualTo("hello");
        assertThatThrownBy(() -> engine.render("{{secret | rsa(key)}}", Map.of("secret", "hello", "key", "not-a-key")))
                .isInstanceOf(TemplateException.class)
                .hasMessage("secret: RSA 公钥无效");
    }

    @Test
    void quotedSubscriptsReadHyphenatedKeysAndHeaderNamesIgnoreCase() {
        LinkedHashMap<String, Object> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/json; charset=utf-8");
        headers.put("etag", "W/1");
        headers.put("x-token", "s3cret value");
        LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
        meta.put("ETag", "meta-value");
        LinkedHashMap<String, Object> response = new LinkedHashMap<>();
        response.put("headers", headers);
        response.put("body", "BODY-NOT-IN-ERRORS");
        response.put("meta", meta);
        Map<String, Object> vars = Map.of("response", response);

        assertThat(engine.render("{{response.headers['content-type']}}", vars))
                .isEqualTo("application/json; charset=utf-8");
        assertThat(engine.render("{{response.headers[\"content-type\"]}}", vars))
                .isEqualTo("application/json; charset=utf-8");
        assertThat(engine.render("{{response.headers[ 'content-type' ]}}", vars))
                .isEqualTo("application/json; charset=utf-8");
        assertThat(engine.render("t={{response.headers['Content-Type']}}", vars))
                .isEqualTo("t=application/json; charset=utf-8");
        assertThat(engine.render("{{response.headers.ETag}}", vars)).isEqualTo("W/1");
        assertThat(engine.render("{{response.headers.etag}}", vars)).isEqualTo("W/1");
        assertThat(engine.render("{{response.headers['ETag']}}", vars)).isEqualTo("W/1");
        assertThat(engine.render("{{response.meta.ETag}}", vars)).isEqualTo("meta-value");
        assertThat(engine.render("{{name | default(response.headers['x-token'])}}", mapWith(vars, "name", "")))
                .isEqualTo("s3cret value");
        assertThat(engine.referencedPaths("{{response.headers[\"content-type\"]}}"))
                .containsExactly("response.headers['content-type']");
        assertThat(engine.templateRefs("{{response.headers['content-type'] | default('')}}"))
                .containsExactly(new TemplateRef("response.headers['content-type']", true));
        assertThat(engine.referencesCredential("{{credential['password']}}")).isTrue();

        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("a'b", "quote");
        row.put("a\\b", "slash");
        row.put("a|b", "pipe");
        row.put("a.b", "dot");
        row.put("0", "key");
        row.put("内容", "text");
        row.put("Name", "A");
        assertThat(engine.render("{{row['a\\'b']}}", Map.of("row", row))).isEqualTo("quote");
        assertThat(engine.render("{{row['a\\\\b']}}", Map.of("row", row))).isEqualTo("slash");
        assertThat(engine.render("{{row['a|b']}}", Map.of("row", row))).isEqualTo("pipe");
        assertThat(engine.render("{{row['a.b']}}", Map.of("row", row))).isEqualTo("dot");
        assertThat(engine.render("{{row['内容']}}", Map.of("row", row))).isEqualTo("text");
        assertThat(engine.render("{{row['0']}}", Map.of("row", row))).isEqualTo("key");
        assertThat(engine.render("{{items[0]}}", Map.of("items", List.of("index")))).isEqualTo("index");
        assertThat(engine.render("{{bag['a-b'][0]}}", Map.of("bag", Map.of("a-b", List.of("x")))))
                .isEqualTo("x");
        assertThat(engine.render("{{row.Name}}", Map.of("row", row))).isEqualTo("A");
        assertThat(engine.referencedPaths("{{row['a\\'b']}}")).containsExactly("row['a\\'b']");

        assertThatThrownBy(() -> engine.render("{{row.name}}", Map.of("row", row)))
                .isInstanceOf(TemplateException.class)
                .hasMessage("row.name: 路径不存在: row.name");
        assertThatThrownBy(() -> engine.render("{{response.meta.etag}}", vars))
                .isInstanceOf(TemplateException.class)
                .hasMessage("response.meta.etag: 路径不存在: response.meta.etag");
        assertThatThrownBy(() -> engine.render("{{response.Body}}", vars))
                .isInstanceOf(TemplateException.class)
                .hasMessage("response.Body: 路径不存在: response.Body");
        assertThatThrownBy(() -> engine.render("{{response.headers.ContentType}}", vars))
                .isInstanceOf(TemplateException.class)
                .hasMessage("response.headers.ContentType: 路径不存在: response.headers.ContentType")
                .satisfies(error -> assertHidden(error, "application/json", "W/1", "s3cret", "BODY-NOT-IN-ERRORS",
                        "s3cret%20value", "s3cret+value"));
        assertThatThrownBy(() -> engine.render("{{response.headers['x-missing']}}", vars))
                .isInstanceOf(TemplateException.class)
                .hasMessage("response.headers['x-missing']: 路径不存在: response.headers['x-missing']")
                .satisfies(error -> assertHidden(error, "application/json", "W/1", "s3cret", "BODY-NOT-IN-ERRORS",
                        "%20", "charset"));
        assertThatThrownBy(() -> engine.render("{{response.headers.content-type}}", vars))
                .isInstanceOf(TemplateException.class)
                .hasMessage("response.headers.content: 模板语法错误")
                .satisfies(error -> assertHidden(error, "application/json", "W/1", "s3cret", "BODY-NOT-IN-ERRORS"));
        assertThatThrownBy(() -> engine.render("{{items[abc]}}", Map.of("items", List.of("a"))))
                .isInstanceOf(TemplateException.class)
                .hasMessage("items: 模板下标无效");
        assertThatThrownBy(() -> engine.render("{{response.headers['content-type'}}}", vars))
                .isInstanceOf(TemplateException.class)
                .hasMessage("response.headers: 模板下标无效");
        assertThatThrownBy(() -> engine.render("{{response.headers['content-type]}}", vars))
                .isInstanceOf(TemplateException.class)
                .hasMessage("模板缺少 '}}'");

        LinkedHashMap<String, Object> both = new LinkedHashMap<>();
        both.put("etag", "lower");
        both.put("ETag", "upper");
        assertThat(engine.render("{{response.headers.ETAG}}", Map.of("response", Map.of("headers", both))))
                .isEqualTo("lower");
        assertThat(engine.render("{{response.headers.ETag}}", Map.of("response", Map.of("headers", both))))
                .isEqualTo("upper");

        assertThat(engine.render(
                "{{response.headers.etag}}",
                Map.of("response", Map.of("headers", Map.of("ETag", "only-mixed")))))
                .isEqualTo("only-mixed");

        LinkedHashMap<String, String> ambiguous = new LinkedHashMap<>();
        ambiguous.put("ETag", "upper");
        ambiguous.put("ETAG", "shout");
        assertThatThrownBy(() -> engine.render(
                "{{response.headers.etag}}", Map.of("response", Map.of("headers", ambiguous))))
                .isInstanceOf(TemplateException.class)
                .hasMessage("response.headers.etag: 路径不存在: response.headers.etag")
                .satisfies(error -> assertHidden(error, "upper", "shout"));

        LinkedHashMap<String, Object> missingValue = new LinkedHashMap<>();
        missingValue.put("etag", null);
        assertThat(engine.render(
                "{{response.headers.ETag | default('z')}}",
                Map.of("response", Map.of("headers", missingValue)))).isEqualTo("z");
    }

    @Test
    void textFiltersRejectObjectsAndKeepNumbersAndBooleans() throws Exception {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("id", "A");
        row.put("n", 1);
        assertThat(engine.render("x={{row}}", Map.of("row", row))).isEqualTo("x={\"id\":\"A\",\"n\":1}");
        assertThat(engine.render("{{n | md5}}", Map.of("n", 1))).isEqualTo(digest("MD5", "1"));
        assertThat(engine.render("{{n | sha256}}", Map.of("n", true))).isEqualTo(digest("SHA-256", "true"));
        assertThat(engine.render("{{n | base64}}", Map.of("n", 1))).isEqualTo("MQ==");
        assertThat(engine.render("{{n | urlencode}}", Map.of("n", false))).isEqualTo("false");
        assertThat(engine.render("{{n | trim}}", Map.of("n", 1.5d))).isEqualTo("1.5");

        Object leak = new Object() {
            @Override
            public String toString() {
                return "LEAKED-TOSTRING";
            }
        };
        for (String filter : List.of("md5", "sha256", "base64", "urlencode", "trim", "xml_escape")) {
            assertThatThrownBy(() -> engine.render("{{row | " + filter + "}}", Map.of("row", leak)))
                    .isInstanceOf(TemplateException.class)
                    .hasMessage("row: " + filter + " 需要文本，得到的是对象")
                    .satisfies(error -> assertHidden(error, "LEAKED-TOSTRING"));
            assertThatThrownBy(() -> engine.render("{{row | " + filter + "}}", Map.of("row", row)))
                    .isInstanceOf(TemplateException.class)
                    .hasMessage("row: " + filter + " 需要文本，得到的是对象")
                    .satisfies(error -> assertHidden(error, "{id=A", "\"id\"", "n=1"));
        }
        assertThatThrownBy(() -> engine.render("{{items | md5}}", Map.of("items", List.of("s3cret value"))))
                .isInstanceOf(TemplateException.class)
                .hasMessage("items: md5 需要文本，得到的是对象")
                .satisfies(error -> assertHidden(error, "s3cret", "s3cret%20value", "s3cret+value", "%20"));
    }

    @Test
    void xmlEscapeEscapesFiveCharactersAndLeavesTheSurroundingText() {
        assertThat(engine.render("{{t | xml_escape}}", Map.of("t", "a&b<c>d\"e'f")))
                .isEqualTo("a&amp;b&lt;c&gt;d&quot;e&apos;f");
        assertThat(engine.render("{{t | xml_escape}}", Map.of("t", "a&amp;b"))).isEqualTo("a&amp;amp;b");
        assertThat(engine.render("{{n | xml_escape}}", Map.of("n", 1))).isEqualTo("1");
        assertThat(engine.render("{{n | xml_escape}}", Map.of("n", true))).isEqualTo("true");
        assertThat(engine.render("{{t | xml_escape}}", Map.of("t", ""))).isEqualTo("");
        assertThat(engine.render("<object>{{t}}</object>", Map.of("t", "a&b"))).isEqualTo("<object>a&b</object>");
        LinkedHashMap<String, Object> missing = new LinkedHashMap<>();
        missing.put("t", null);
        assertThat(engine.render("{{t | xml_escape}}", missing)).isNull();
        assertThatThrownBy(() -> engine.render("{{t | xml_escape('x')}}", Map.of("t", "a")))
                .isInstanceOf(TemplateException.class)
                .hasMessage("t: xml_escape 不接受参数");
    }

    @Test
    void defaultKeepsBlankZeroFalseAndEmptyList() {
        assertThat(engine.render("{{name | default('z')}}", Map.of("name", "   "))).isEqualTo("   ");
        assertThat(engine.render("{{name | trim | default('z')}}", Map.of("name", "   "))).isEqualTo("z");
        assertThat(engine.render("{{n | default(9)}}", Map.of("n", 0))).isEqualTo(0);
        assertThat(engine.render("{{flag | default('z')}}", Map.of("flag", false))).isEqualTo(false);
        assertThat(engine.render("{{items | default('z')}}", Map.of("items", List.of()))).isEqualTo(List.of());
        assertThat(engine.render("{{name | default('z')}}", Map.of("name", "0"))).isEqualTo("0");
    }

    @Test
    void dateDistinguishesNonTextFromUnparseableText() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("password", "s3cret value");
        assertThatThrownBy(() -> engine.render("{{d | date('yyyy-MM-dd')}}", Map.of("d", 20261012)))
                .isInstanceOf(TemplateException.class)
                .hasMessage("d: 日期需要文本")
                .satisfies(error -> assertHidden(error, "20261012", "日期无法解析"));
        assertThatThrownBy(() -> engine.render("{{d | date('yyyy-MM-dd')}}", Map.of("d", false)))
                .isInstanceOf(TemplateException.class)
                .hasMessage("d: 日期需要文本")
                .satisfies(error -> assertHidden(error, "false"));
        assertThatThrownBy(() -> engine.render("{{d | date('yyyy-MM-dd')}}", Map.of("d", row)))
                .isInstanceOf(TemplateException.class)
                .hasMessage("d: 日期需要文本")
                .satisfies(error -> assertHidden(error, "s3cret", "{password"));
        assertThatThrownBy(() -> engine.render("{{d | date('yyyy-MM-dd')}}", Map.of("d", "not-a-date")))
                .isInstanceOf(TemplateException.class)
                .hasMessage("d: 日期无法解析")
                .satisfies(error -> assertHidden(error, "not-a-date", "日期需要文本"));
        assertThatThrownBy(() -> engine.render("{{d | date('yyyy-MM-dd')}}", Map.of("d", "")))
                .isInstanceOf(TemplateException.class)
                .hasMessage("d: 日期无法解析");
    }

    @Test
    void rsaTooLongPlaintextAndBadKeyStayFixedChinese() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = pem(pair.getPublic().getEncoded());
        String plaintext = "S".repeat(300);
        assertThatThrownBy(() -> engine.render("{{secret | rsa(key)}}", Map.of("secret", plaintext, "key", pem)))
                .isInstanceOf(TemplateException.class)
                .hasMessage("secret: 明文超过这把公钥能加密的长度")
                .satisfies(error -> {
                    assertHidden(error, "SSSS", "IllegalBlockSize", "BEGIN PUBLIC KEY", plaintext.substring(0, 8));
                    assertThat(error.getCause()).isInstanceOf(TemplateException.class);
                    assertThat(error.getCause().getCause()).isNull();
                });
        String junk = "not-a-key-" + "Q".repeat(48);
        assertThatThrownBy(() -> engine.render("{{secret | rsa(key)}}", Map.of("secret", "hello", "key", junk)))
                .isInstanceOf(TemplateException.class)
                .hasMessage("secret: RSA 公钥无效")
                .satisfies(error -> {
                    assertHidden(error, junk, "hello", "InvalidKey", "RSA 加密失败");
                    assertThat(error.getCause()).isInstanceOf(TemplateException.class);
                    assertThat(error.getCause().getCause()).isNull();
                });
    }

    @Test
    void referencedPathsAndCustomFilters() {
        assertThat(engine.referencedPaths("id={{input.id | default(credential.password)}} and {{items[0].id}}"))
                .containsExactly("input.id", "credential.password", "items[0].id");
        assertThat(engine.referencedPaths(Map.of("nested", List.of("plain", "{{config.base_url}}"))))
                .containsExactly("config.base_url");
        FilterRegistry registry = FilterRegistry.defaults().register("upper", (value, args) ->
                String.valueOf(value).toUpperCase(java.util.Locale.ROOT));
        assertThat(new TemplateEngine(registry).render("{{name | upper}}", Map.of("name", "ab"))).isEqualTo("AB");
        assertThatThrownBy(() -> engine.render("{{name | no_such}}", Map.of("name", "a")))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("no_such");
        assertThatThrownBy(() -> engine.render("{{name", Map.of("name", "a")))
                .isInstanceOf(TemplateException.class);
    }

    private static Map<String, Object> mapWith(Map<String, Object> base, String key, Object value) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private static String digest(String algorithm, String text) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertHidden(Throwable error, String... forbidden) {
        String message = error.getMessage();
        assertThat(message).doesNotContain("Exception");
        for (String item : forbidden) {
            assertThat(message).doesNotContain(item);
        }
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static String pem(byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    private static String decrypt(String ciphertext, KeyPair pair) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, pair.getPrivate());
        return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), java.nio.charset.StandardCharsets.UTF_8);
    }
}
