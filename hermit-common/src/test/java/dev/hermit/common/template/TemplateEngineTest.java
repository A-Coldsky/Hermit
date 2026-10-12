package dev.hermit.common.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;

import org.junit.jupiter.api.Test;

class TemplateEngineTest {

    private final TemplateEngine engine = new TemplateEngine();

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
                .hasMessageContaining("RSA 公钥无效");
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
