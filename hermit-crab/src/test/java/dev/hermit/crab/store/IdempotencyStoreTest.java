package dev.hermit.crab.store;

import dev.hermit.crab.secure.CredentialCipher;
import dev.hermit.crab.secure.MasterKeyProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyStoreTest {
    @TempDir
    Path temp;

    @Test
    void entriesAreIsolatedByApiKeyIdAndSupportSameKeyCoexistence() {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("isolated"))) {
            MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));
            IdempotencyStore store = new IdempotencyStore(fixture.dataSource, cipher(fixture.paths), clock);

            store.save(Origin.OFFICIAL, "oa", "alice", "save_note", "key-a", "same-key", "{\"data\":\"from-a\"}", "sha-a");
            store.save(Origin.OFFICIAL, "oa", "alice", "save_note", "key-b", "same-key", "{\"data\":\"from-b\"}", "sha-b");
            store.save(Origin.OFFICIAL, "oa", "alice", "save_note", null, "same-key", "{\"data\":\"legacy\"}", "sha-legacy");

            assertThat(store.findFresh(Origin.OFFICIAL, "oa", "alice", "save_note", "key-a", "same-key"))
                    .get()
                    .satisfies(cached -> {
                        assertThat(cached.envelope()).isEqualTo("{\"data\":\"from-a\"}");
                        assertThat(cached.bodySha256()).isEqualTo("sha-a");
                    });
            assertThat(store.findFresh(Origin.OFFICIAL, "oa", "alice", "save_note", "key-b", "same-key"))
                    .get()
                    .satisfies(cached -> {
                        assertThat(cached.envelope()).isEqualTo("{\"data\":\"from-b\"}");
                        assertThat(cached.bodySha256()).isEqualTo("sha-b");
                    });
            assertThat(store.findFresh(Origin.OFFICIAL, "oa", "alice", "save_note", "key-c", "same-key")).isEmpty();
            // null 规范化为 ""，与旧签名重载一致。
            assertThat(store.findFresh(Origin.OFFICIAL, "oa", "alice", "save_note", "same-key"))
                    .get()
                    .extracting(IdempotencyStore.Cached::envelope)
                    .isEqualTo("{\"data\":\"legacy\"}");

            // 删除单把 Key 的记录不影响另一把 Key。
            store.delete(Origin.OFFICIAL, "oa", "alice", "save_note", "key-a", "same-key");
            assertThat(store.findFresh(Origin.OFFICIAL, "oa", "alice", "save_note", "key-a", "same-key")).isEmpty();
            assertThat(store.findFresh(Origin.OFFICIAL, "oa", "alice", "save_note", "key-b", "same-key")).isPresent();
        }
    }

    @Test
    void customMaxAgeExpiresEarlierWithoutExceedingDefaultTtl() {
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("ttl"))) {
            MutableClock clock = new MutableClock(Instant.parse("2026-11-23T08:00:00Z"));
            IdempotencyStore store = new IdempotencyStore(fixture.dataSource, cipher(fixture.paths), clock);

            store.save(Origin.OFFICIAL, "oa", "alice", "export", "key-a", "dl-1", "{\"data\":\"file\"}", "sha-1");
            clock.advance(Duration.ofMinutes(59));
            assertThat(store.findFresh(
                    Origin.OFFICIAL, "oa", "alice", "export", "key-a", "dl-1", Duration.ofHours(1))).isPresent();

            clock.advance(Duration.ofMinutes(2));
            assertThat(store.findFresh(
                    Origin.OFFICIAL, "oa", "alice", "export", "key-a", "dl-1", Duration.ofHours(1))).isEmpty();
        }
    }

    private static CredentialCipher cipher(CrabPaths paths) {
        byte[] key = MasterKeyProvider.load(paths, Optional.empty(), name -> null);
        try {
            return new CredentialCipher(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
