package dev.hermit.crab.secure;

import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;

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
        body.put("note", "keep");
        Map<String, Object> redacted = Redactor.of(Set.of()).redactFields(body);
        assertThat(redacted.get("note")).isEqualTo("keep");
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
    void rejectsANullSecretValue() {
        assertThatThrownBy(() -> Redactor.of(java.util.Collections.singleton(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
