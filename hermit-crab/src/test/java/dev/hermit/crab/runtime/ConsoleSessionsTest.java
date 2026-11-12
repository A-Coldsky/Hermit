package dev.hermit.crab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ConsoleSessionsTest {

    /** 没人再拿来校验的过期会话，下一次签发时清掉，不一直留在内存里。 */
    @Test
    void issuingDropsExpiredSessions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-12T00:00:00Z"));
        ConsoleSessions sessions = new ConsoleSessions(clock);
        String first = sessions.issue();
        sessions.issue();
        assertThat(sessions.size()).isEqualTo(2);

        clock.now = clock.now.plus(ConsoleSessions.TTL).plus(Duration.ofSeconds(1));
        String fresh = sessions.issue();
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(sessions.valid(first)).isFalse();
        assertThat(sessions.valid(fresh)).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
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
