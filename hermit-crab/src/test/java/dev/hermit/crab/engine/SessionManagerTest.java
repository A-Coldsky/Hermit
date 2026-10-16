package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.impl.cookie.BasicClientCookie;
import org.junit.jupiter.api.Test;

import dev.hermit.crab.store.Origin;

class SessionManagerTest {

    @Test
    void seenCookieValuesStayUniqueAndDropTheOldestPastSixtyFour() throws Exception {
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.OFFICIAL, "cookie_history", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                putCookie(sessions, key, "same-cookie");
                sessions.persist(key);
                sessions.persist(key);
                sessions.persist(key);
                assertThat(sessions.seenCookieValues(key)).containsExactly("same-cookie");

                putCookie(sessions, key, "abcd");
                sessions.persist(key);
                assertThat(sessions.seenCookieValues(key)).containsExactly("same-cookie", "abcd");

                putCookie(sessions, key, "abc");
                sessions.persist(key);
                assertThat(sessions.seenCookieValues(key)).containsExactly("same-cookie", "abcd");

                List<String> rotated = new ArrayList<>();
                for (int i = 0; i < 65; i++) {
                    String value = "val-%04d".formatted(i);
                    rotated.add(value);
                    putCookie(sessions, key, value);
                    sessions.persist(key);
                }
                List<String> expected = rotated.subList(1, rotated.size());
                assertThat(sessions.seenCookieValues(key)).hasSize(64);
                assertThat(sessions.seenCookieValues(key)).doesNotContain(rotated.get(0));
                assertThat(sessions.seenCookieValues(key)).contains(rotated.get(rotated.size() - 1));
                assertThat(sessions.seenCookieValues(key)).containsExactlyElementsOf(expected);

                sessions.persist(key);
                assertThat(sessions.seenCookieValues(key)).containsExactlyElementsOf(expected);

                List<String> snapshot = sessions.seenCookieValues(key);
                sessions.invalidate(key);
                assertThat(snapshot).containsExactlyElementsOf(expected);
                assertThat(sessions.seenCookieValues(key)).isEmpty();
            } finally {
                sessions.unlock(key);
            }
        }
    }

    private static void putCookie(SessionManager sessions, SessionKey key, String value) {
        BasicClientCookie cookie = new BasicClientCookie("sid", value);
        cookie.setDomain("127.0.0.1");
        cookie.setPath("/");
        sessions.cookies(key).addCookie(cookie);
    }
}
