package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import dev.hermit.crab.store.Origin;
import org.junit.jupiter.api.Test;

class SessionContentionTest {
    private static final String SITE = "https://oa.corp.com:443";
    private static final SessionKey A = new SessionKey(Origin.OFFICIAL, "pkg_a", "default");
    private static final SessionKey B = new SessionKey(Origin.OFFICIAL, "pkg_b", "default");

    private final Ticker clock = new Ticker();
    private final SessionContention contention = new SessionContention(clock);

    @Test
    void threeAlternatingRoundsRaiseOnePair() {
        login(A);
        login(B);
        round();
        round();
        assertThat(contention.active()).isEmpty();
        round();
        assertThat(contention.active()).singleElement().satisfies(pair -> {
            assertThat(pair.site()).isEqualTo(SITE);
            assertThat(pair.first()).isEqualTo("pkg_a");
            assertThat(pair.second()).isEqualTo("pkg_b");
        });
    }

    @Test
    void firstLoginsAndLossesWithoutARivalLoginAreNotCounted() {
        // 两个包各自首次登录，然后 A 的会话自己过期了好几次：B 没有在中间登录过。
        login(A);
        login(B);
        contention.lost(SITE, A);
        login(A);
        for (int i = 0; i < 6; i++) {
            contention.lost(SITE, A);
            login(A);
        }
        assertThat(contention.active()).isEmpty();
    }

    @Test
    void onlyOneSideLosingIsNotAlternation() {
        login(A);
        for (int i = 0; i < 6; i++) {
            login(B);
            contention.lost(SITE, A);
            login(A);
        }
        assertThat(contention.active()).isEmpty();
    }

    @Test
    void roundsSpreadBeyondTheWindowDoNotAddUp() {
        login(A);
        login(B);
        for (int i = 0; i < 5; i++) {
            round();
            clock.advance(SessionContention.WINDOW.plusSeconds(1));
        }
        assertThat(contention.active()).isEmpty();
    }

    @Test
    void anotherSiteOrTheSamePackageIsNotARival() {
        SessionKey other = new SessionKey(Origin.OFFICIAL, "pkg_a", "second");
        login(A);
        for (int i = 0; i < 4; i++) {
            contention.loggedIn(SITE, other);
            contention.lost(SITE, A);
            login(A);
            contention.lost(SITE, other);
        }
        contention.loggedIn("https://erp.corp.com:443", B);
        contention.lost(SITE, A);
        assertThat(contention.active()).isEmpty();
    }

    @Test
    void alertExpiresAfterTheKeepPeriod() {
        login(A);
        login(B);
        round();
        round();
        round();
        clock.advance(SessionContention.KEEP.minusMinutes(1));
        assertThat(contention.active()).hasSize(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(contention.active()).isEmpty();
    }

    /** A 被 B 顶掉后重新登录，再把 B 顶掉，B 重新登录。 */
    private void round() {
        contention.lost(SITE, A);
        login(A);
        contention.lost(SITE, B);
        login(B);
    }

    private void login(SessionKey key) {
        clock.advance(Duration.ofSeconds(1));
        contention.loggedIn(SITE, key);
        clock.advance(Duration.ofSeconds(1));
    }

    private static final class Ticker extends Clock {
        private Instant now = Instant.parse("2026-11-23T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
