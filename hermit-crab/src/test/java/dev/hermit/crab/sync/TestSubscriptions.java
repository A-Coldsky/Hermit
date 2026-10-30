package dev.hermit.crab.sync;

import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

/** Grants the conch names existing sync fixtures publish, without going through subscribe. */
final class TestSubscriptions {
    private TestSubscriptions() {
    }

    static void grantKnown(DataSource dataSource) {
        Subscriptions subscriptions = new Subscriptions(dataSource);
        for (String name : List.of(
                "legacy_oa", "other_sys", "future_oa", "bad_sig", "bad_key", "bad_meta", "big_conch")) {
            subscriptions.record(name, "test", Instant.EPOCH);
        }
    }
}
