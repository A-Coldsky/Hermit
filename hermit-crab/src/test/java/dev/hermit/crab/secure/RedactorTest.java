package dev.hermit.crab.secure;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedactorTest {
    @Test
    void replacesLongerSecretsFirstAndIgnoresValuesShorterThanFour() {
        Redactor overlap = Redactor.of(Set.of("password", "password1234"));
        assertThat(overlap.redact("password1234")).isEqualTo("***");
        assertThat(overlap.redact("xx password yy")).isEqualTo("xx *** yy");
        assertThat(overlap.redact("password password")).isEqualTo("*** ***");

        Redactor floor = Redactor.of(Set.of("abc", "abcd"));
        assertThat(floor.redact("abc abcd")).isEqualTo("abc ***");
        assertThat(floor.redact(null)).isNull();
        assertThat(Redactor.of(Set.of()).redact("unchanged")).isEqualTo("unchanged");
    }

    @Test
    void redactsUrlEncodedSecretsWithBothSpaceEncodings() {
        String secret = "p@ss!";
        Redactor redactor = Redactor.of(Set.of(secret));
        String query = "password=p%40ss%21&next=1";
        assertThat(redactor.redact(query)).isEqualTo("password=***&next=1");
        SecretChecks.absent(redactor.redact(query), secret);
        SecretChecks.absent(redactor.redact(query), "p%40ss%21");

        String spaced = "a b!c";
        Redactor spacedRedactor = Redactor.of(Set.of(spaced));
        assertThat(spacedRedactor.redact("q=a+b%21c")).isEqualTo("q=***");
        assertThat(spacedRedactor.redact("q=a%20b%21c")).isEqualTo("q=***");
        assertThat(spacedRedactor.redact("q=a b!c")).isEqualTo("q=***");
        SecretChecks.absent(spacedRedactor.redact("a+b%21c a%20b%21c a b!c"), spaced);
        SecretChecks.absent(spacedRedactor.redact("a+b%21c a%20b%21c a b!c"), "a+b%21c");
        SecretChecks.absent(spacedRedactor.redact("a+b%21c a%20b%21c a b!c"), "a%20b%21c");
    }

    @Test
    void redactsLegacyCharsetsAndLowercaseHex() {
        String password = "A密码B";
        Redactor redactor = Redactor.of(Set.of(password));
        String gbk = "A%C3%DC%C2%EBB";
        String gbkLower = "A%c3%dc%c2%ebB";
        assertThat(URLEncoder.encode(password, Charset.forName("GBK"))).isEqualTo(gbk);
        assertThat(redactor.redact("q=" + gbk + "&n=1")).isEqualTo("q=***&n=1");
        assertThat(redactor.redact("q=" + gbkLower + "&n=1")).isEqualTo("q=***&n=1");
        assertThat(redactor.redact(URLEncoder.encode(password, StandardCharsets.UTF_8))).isEqualTo("***");
        SecretChecks.absent(redactor.redact(gbk + " " + gbkLower), password);
        SecretChecks.absent(redactor.redact(gbk + " " + gbkLower), gbk);
        SecretChecks.absent(redactor.redact(gbkLower), gbkLower);

        Redactor slash = Redactor.of(Set.of("secret/value"));
        assertThat(slash.redact("secret%2Fvalue")).isEqualTo("***");
        assertThat(slash.redact("secret%2fvalue")).isEqualTo("***");
        SecretChecks.absent(slash.redact("secret%2Fvalue secret%2fvalue"), "secret/value");
        SecretChecks.absent(slash.redact("secret%2Fvalue secret%2fvalue"), "secret%2Fvalue");
        SecretChecks.absent(slash.redact("secret%2Fvalue secret%2fvalue"), "secret%2fvalue");

        Redactor spaced = Redactor.of(Set.of("a b/c"));
        assertThat(spaced.redact("a+b%2Fc")).isEqualTo("***");
        assertThat(spaced.redact("a+b%2fc")).isEqualTo("***");
        assertThat(spaced.redact("a%20b%2Fc")).isEqualTo("***");
        assertThat(spaced.redact("a%20b%2fc")).isEqualTo("***");

        String latin = "caf\u00e9";
        Redactor latinRedactor = Redactor.of(Set.of(latin));
        assertThat(URLEncoder.encode(latin, StandardCharsets.ISO_8859_1)).isEqualTo("caf%E9");
        assertThat(latinRedactor.redact("caf%E9")).isEqualTo("***");
        assertThat(latinRedactor.redact("caf%e9")).isEqualTo("***");
        SecretChecks.absent(latinRedactor.redact("caf%E9 caf%e9"), latin);
        SecretChecks.absent(latinRedactor.redact("caf%E9 caf%e9"), "caf%E9");
        SecretChecks.absent(latinRedactor.redact("caf%e9"), "caf%e9");

        String rare = "ab" + new String(Character.toChars(0x20000)) + "c";
        Redactor rareRedactor = Redactor.of(Set.of(rare));
        String gb18030 = URLEncoder.encode(rare, Charset.forName("GB18030"));
        assertThat(gb18030).isNotEqualTo(URLEncoder.encode(rare, Charset.forName("GBK")));
        assertThat(gb18030).isNotEqualTo(URLEncoder.encode(rare, StandardCharsets.UTF_8));
        assertThat(rareRedactor.redact(gb18030)).isEqualTo("***");
        SecretChecks.absent(rareRedactor.redact(gb18030), gb18030);

        String shortPassword = "密码";
        Redactor floor = Redactor.of(Set.of(shortPassword));
        String shortGbk = URLEncoder.encode(shortPassword, Charset.forName("GBK"));
        assertThat(shortPassword.length()).isLessThan(4);
        assertThat(floor.redact(shortPassword + " " + shortGbk)).isEqualTo(shortPassword + " " + shortGbk);
    }

    @Test
    void redactsQueryPathAndJsonFormsThatUrlEncoderDoesNotProduce() {
        Redactor spaced = Redactor.of(Set.of("a b:c"));
        assertThat(spaced.redact("q=a%20b:c")).isEqualTo("q=***");
        assertThat(spaced.redact("/p/a%20b:c")).isEqualTo("/p/***");
        assertThat(spaced.redact("f=a+b%3Ac")).isEqualTo("f=***");
        SecretChecks.absent(spaced.redact("a%20b:c a+b%3Ac a b:c"), "a%20b:c");

        Redactor password = Redactor.of(Set.of("p@ss word-91"));
        assertThat(password.redact("token=p@ss%20word-91")).isEqualTo("token=***");
        SecretChecks.absent(password.redact("p@ss%20word-91"), "p@ss%20word-91");

        Redactor quoted = Redactor.of(Set.of("ab\"cdEF"));
        assertThat(quoted.redact("{\"q\":\"ab\\\"cdEF\"}")).isEqualTo("{\"q\":\"***\"}");
        SecretChecks.absent(quoted.redact("ab\\\"cdEF"), "ab\\\"cdEF");

        String gbk = "A密 码B";
        Redactor gbkRedactor = Redactor.of(Set.of(gbk));
        assertThat(gbkRedactor.redact("q=A%C3%DC%20%C2%EBB")).isEqualTo("q=***");
        assertThat(gbkRedactor.redact("q=A%C3%DC+%C2%EBB")).isEqualTo("q=***");

        String rare = "ab" + new String(Character.toChars(0x20000)) + " cd";
        Redactor rareRedactor = Redactor.of(Set.of(rare));
        assertThat(rareRedactor.redact("ab%95%32%82%36%20cd")).isEqualTo("***");
        assertThat(rareRedactor.redact("ab%95%32%82%36+cd")).isEqualTo("***");

        Redactor latin = Redactor.of(Set.of("caf\u00e9!"));
        assertThat(latin.redact("caf%E9!")).isEqualTo("***");
        assertThat(latin.redact("caf%E9%21")).isEqualTo("***");
        SecretChecks.absent(latin.redact("caf%E9! caf%E9%21"), "caf%E9!");
    }

    @Test
    void replacementIsLiteralNotARegularExpression() {
        Redactor redactor = Redactor.of(Set.of("a.b+", "pa$$"));
        assertThat(redactor.redact("a.b+ axb pa$$")).isEqualTo("*** axb ***");
    }

    @Test
    void redactsSensitiveFieldNamesRecursivelyWithoutMutatingTheInput() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("api_key", "profile-key-value");
        profile.put("city", "bj");
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("token", "event-token-value");
        Map<String, Object> nestedSession = new LinkedHashMap<>();
        nestedSession.put("sessionId", "nested-session-value");
        List<Object> events = new ArrayList<>();
        events.add(event);
        events.add("plain");
        events.add(List.of(nestedSession));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user", "alice");
        body.put("Password", "hunter2-field-value");
        body.put("profile", profile);
        body.put("events", events);
        body.put("userPassword", Map.of("inner", "should-not-remain"));

        Map<String, Object> redacted = Redactor.of(Set.of()).redactFields(body);

        assertThat(redacted.get("user")).isEqualTo("alice");
        assertThat(redacted.get("Password")).isEqualTo("***");
        @SuppressWarnings("unchecked")
        Map<String, Object> redactedProfile = (Map<String, Object>) redacted.get("profile");
        assertThat(redactedProfile.get("api_key")).isEqualTo("***");
        assertThat(redactedProfile.get("city")).isEqualTo("bj");
        @SuppressWarnings("unchecked")
        List<Object> redactedEvents = (List<Object>) redacted.get("events");
        @SuppressWarnings("unchecked")
        Map<String, Object> redactedEvent = (Map<String, Object>) redactedEvents.get(0);
        assertThat(redactedEvent.get("token")).isEqualTo("***");
        assertThat(redactedEvents.get(1)).isEqualTo("plain");
        assertThat(redacted.get("userPassword")).isEqualTo("***");
        assertThat(body.get("Password")).isEqualTo("hunter2-field-value");
        assertThat(profile.get("api_key")).isEqualTo("profile-key-value");
        String rendered = String.valueOf(redacted);
        SecretChecks.absent(rendered, "hunter2-field-value");
        SecretChecks.absent(rendered, "profile-key-value");
        SecretChecks.absent(rendered, "event-token-value");
        SecretChecks.absent(rendered, "nested-session-value");
        SecretChecks.absent(rendered, "should-not-remain");
    }

    @Test
    void fieldRedactionMatchesTheContractKeyFragmentsCaseInsensitively() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("passwd", "v");
        body.put("PWD", "v");
        body.put("client_secret", "v");
        body.put("otpCode", "v");
        body.put("ApiKey", "v");
        body.put("set-cookie", "v");
        body.put("Authorization", "v");
        body.put("jwt", "v");
        body.put("ticketId", "v");
        body.put("sid", "v");
        body.put("side", "keep-side");
        body.put("note", "keep");
        Map<String, Object> redacted = Redactor.of(Set.of()).redactFields(body);
        assertThat(redacted.get("note")).isEqualTo("keep");
        assertThat(redacted.get("side")).isEqualTo("keep-side");
        assertThat(redacted.get("sid")).isEqualTo("***");
        assertThat(redacted.get("jwt")).isEqualTo("***");
        assertThat(redacted.get("ticketId")).isEqualTo("***");
        assertThat(redacted.values()).filteredOn(value -> "v".equals(value)).isEmpty();
        assertThat(redacted.get("passwd")).isEqualTo("***");
        assertThat(Redactor.of(Set.of()).redactFields(null)).isNull();
    }

    @Test
    void redactsSensitiveHeadersCaseInsensitivelyWithoutMutatingValues() {
        List<String> cookie = new ArrayList<>(List.of("a=b", "c=d"));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Cookie", cookie);
        headers.put("AUTHORIZATION", List.of("Bearer secret-token"));
        headers.put("X-Api-Key", List.of("hk_example"));
        headers.put("X-Hermit-Api-Key", List.of("hk_other"));
        headers.put("Set-Cookie", List.of("sid=1"));
        headers.put("X-Request-Id", List.of("trace-1"));

        Map<String, List<String>> redacted = Redactor.of(Set.of()).redactHeaders(headers);

        assertThat(redacted.get("Cookie")).containsExactly("***");
        assertThat(redacted.get("AUTHORIZATION")).containsExactly("***");
        assertThat(redacted.get("X-Api-Key")).containsExactly("***");
        assertThat(redacted.get("X-Hermit-Api-Key")).containsExactly("***");
        assertThat(redacted.get("Set-Cookie")).containsExactly("***");
        assertThat(redacted.get("X-Request-Id")).containsExactly("trace-1");
        assertThat(cookie).containsExactly("a=b", "c=d");
        assertThat(redacted.keySet()).containsExactly(
                "Cookie", "AUTHORIZATION", "X-Api-Key", "X-Hermit-Api-Key", "Set-Cookie", "X-Request-Id");
        SecretChecks.absent(String.valueOf(redacted), "Bearer secret-token");
        SecretChecks.absent(String.valueOf(redacted), "hk_example");
        assertThat(Redactor.of(Set.of()).redactHeaders(null)).isNull();
    }

    @Test
    void shortSecretsStillMaskInsideWordsAndLeaveRenamedDiagnostics() {
        Redactor json = Redactor.of(Set.of("json"));
        String sentence = "提取 name 失败：响应不是 JSON（JSON 路径 $.name）";
        assertThat(json.redact(sentence)).isEqualTo(sentence);
        assertThat(json.redact("本步脚本 第 2 行：Error: bad object at 0"))
                .isEqualTo("本步脚本 第 2 行：Error: bad object at 0");
        assertThat(json.redact("xxjsonyy")).isEqualTo("xx***yy");
        assertThat(json.redact("JSON 路径 $.json.name")).isEqualTo("JSON 路径 $.***.name");

        Redactor code = Redactor.of(Set.of("code"));
        assertThat(code.redact("本步脚本 第 2 行：Error: bad object at 0"))
                .isEqualTo("本步脚本 第 2 行：Error: bad object at 0");
        assertThat(code.redact("前缀code后缀")).isEqualTo("前缀***后缀");

        Redactor value = Redactor.of(Set.of("s3cret-value"));
        assertThat(value.redact("本步脚本 第 2 行：Error: s3cret-value"))
                .isEqualTo("本步脚本 第 2 行：Error: ***");
    }

    @Test
    void rejectsANullSecretValue() {
        assertThatThrownBy(() -> Redactor.of(java.util.Collections.singleton(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
