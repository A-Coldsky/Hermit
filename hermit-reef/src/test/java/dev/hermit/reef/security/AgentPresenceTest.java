package dev.hermit.reef.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.testsupport.AdjustableClock;
import dev.hermit.reef.testsupport.ReefTestConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentPresenceTest {

    @Test
    void evictsExpiredEntriesWithoutClearingFreshOnes() {
        AdjustableClock clock = new AdjustableClock(ReefTestConfig.START);
        AtomicInteger writes = new AtomicInteger();
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                writes.incrementAndGet();
                return 1;
            }
        };
        AgentPresence presence = new AgentPresence(jdbc, clock);
        AgentPrincipal principal = new AgentPrincipal(1L, "admin", 10L);

        for (int i = 0; i < AgentPresence.EVICT_AT; i++) {
            presence.seen(principal, "old-" + i);
        }
        assertThat(presence.cacheSize()).isEqualTo(AgentPresence.EVICT_AT);

        clock.setInstant(ReefTestConfig.START.plus(AgentPresence.MIN_INTERVAL).plusSeconds(1));
        int writesBeforeFresh = writes.get();
        presence.seen(principal, "fresh");
        assertThat(presence.cacheSize()).isEqualTo(1);
        int writesAfterFresh = writes.get();
        assertThat(writesAfterFresh).isGreaterThan(writesBeforeFresh);

        clock.setInstant(clock.instant().plusSeconds(5));
        presence.seen(principal, "fresh");
        assertThat(writes.get()).isEqualTo(writesAfterFresh);
    }

    @Test
    void clearsCacheWhenOverflowContainsOnlyActiveEntries() {
        AdjustableClock clock = new AdjustableClock(ReefTestConfig.START);
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                return 1;
            }
        };
        AgentPresence presence = new AgentPresence(jdbc, clock);
        AgentPrincipal principal = new AgentPrincipal(1L, "admin", 10L);

        for (int i = 0; i < AgentPresence.EVICT_AT; i++) {
            presence.seen(principal, "active-" + i);
        }
        assertThat(presence.cacheSize()).isEqualTo(AgentPresence.EVICT_AT);

        presence.seen(principal, "overflow-trigger");
        assertThat(presence.cacheSize()).isZero();

        for (int i = 0; i < 100; i++) {
            presence.seen(principal, "after-clear-" + i);
        }
        assertThat(presence.cacheSize()).isEqualTo(100);
    }

    @Test
    void keepsInstanceRebindingDeleteOnWrite() {
        AdjustableClock clock = new AdjustableClock(ReefTestConfig.START);
        List<String> executed = new ArrayList<>();
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public int update(String sql, Object... args) {
                executed.add(sql);
                return 1;
            }
        };
        AgentPresence presence = new AgentPresence(jdbc, clock);
        presence.seen(new AgentPrincipal(2L, "member", 20L), "inst-1");

        assertThat(executed).anyMatch(sql ->
                sql.startsWith("DELETE FROM hermit_crab_instances WHERE instance_id = ? AND user_id <> ?"));
    }
}
