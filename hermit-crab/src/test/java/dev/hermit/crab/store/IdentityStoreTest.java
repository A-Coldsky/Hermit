package dev.hermit.crab.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.StoredCookie;
import dev.hermit.crab.secure.CredentialCipher;

class IdentityStoreTest {
    @TempDir
    Path temp;

    @Test
    void timestampsAndAuditEntriesUseInjectedClock() throws Exception {
        Instant fixed = Instant.parse("2026-06-15T08:30:00Z");
        Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);
        CredentialCipher cipher = new CredentialCipher(new byte[32]);
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("clock"))) {
            IdentityStore store = new IdentityStore(fixture.dataSource, cipher, clock);
            Identity created = store.create("研发", Actors.CONSOLE);
            assertThat(created.createdAt()).isEqualTo(fixed);

            SessionKey key = new SessionKey(Origin.OFFICIAL, "oa", "alice");
            store.bind(created.id(), key, Actors.CONSOLE);
            store.save(created.id(), List.of(
                    new StoredCookie("sid", "cookie-val-1", "127.0.0.1", "/", null, false, false, true)));
            store.rename(created.id(), "研发组", Actors.CONSOLE);

            try (Connection connection = fixture.dataSource.getConnection()) {
                assertThat(singleInstant(connection,
                        "SELECT created_at FROM identities WHERE id = ?", "created_at", created.id())).isEqualTo(fixed);
                assertThat(singleInstant(connection,
                        "SELECT bound_at FROM identity_members WHERE identity_id = ?", "bound_at", created.id())).isEqualTo(fixed);
                assertThat(singleInstant(connection,
                        "SELECT updated_at FROM identity_sessions WHERE identity_id = ?", "updated_at", created.id())).isEqualTo(fixed);

                List<Instant> auditTimes = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT at FROM config_audit WHERE kind LIKE 'IDENTITY_%'");
                     ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        auditTimes.add(Db.instant(rows, "at"));
                    }
                }
                assertThat(auditTimes).hasSize(3).containsOnly(fixed);
            }
        }
    }

    @Test
    void concurrentDeleteWithBindAndSaveNeverLeavesOrphanRows() throws Exception {
        CredentialCipher cipher = new CredentialCipher(new byte[32]);
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("race"))) {
            IdentityStore store = new IdentityStore(fixture.dataSource, cipher, Clock.systemUTC());
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                List<StoredCookie> cookies = List.of(
                        new StoredCookie("sid", "secret-val", "127.0.0.1", "/", null, false, false, true));
                for (int round = 0; round < 30; round++) {
                    Identity identity = store.create("id-" + round, Actors.CONSOLE);
                    SessionKey member = new SessionKey(Origin.OFFICIAL, "oa", "user" + round);
                    CyclicBarrier barrier = new CyclicBarrier(3);
                    Future<?> deleter = pool.submit(() -> {
                        await(barrier);
                        store.delete(identity.id(), Actors.CONSOLE);
                    });
                    Future<?> binder = pool.submit(() -> {
                        await(barrier);
                        try {
                            store.bind(identity.id(), member, Actors.CONSOLE);
                        } catch (IllegalArgumentException ignored) {
                            // 删除先执行时身份已不存在。
                        }
                    });
                    Future<?> saver = pool.submit(() -> {
                        await(barrier);
                        store.save(identity.id(), cookies);
                    });
                    deleter.get(10, TimeUnit.SECONDS);
                    binder.get(10, TimeUnit.SECONDS);
                    saver.get(10, TimeUnit.SECONDS);

                    assertThat(store.identityOf(member)).isNull();
                    assertThat(store.members(identity.id())).isEmpty();
                    try (Connection connection = fixture.dataSource.getConnection()) {
                        assertThat(count(connection,
                                "SELECT COUNT(*) FROM identity_members WHERE identity_id NOT IN (SELECT id FROM identities)"))
                                .isZero();
                        assertThat(count(connection,
                                "SELECT COUNT(*) FROM identity_sessions WHERE identity_id NOT IN (SELECT id FROM identities)"))
                                .isZero();
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void concurrentCreatesRejectDuplicateNamesAndStaleOrphanRowDoesNotBlockBind() throws Exception {
        CredentialCipher cipher = new CredentialCipher(new byte[32]);
        try (StoreFixture fixture = StoreFixture.open(temp.resolve("dup"))) {
            IdentityStore store = new IdentityStore(fixture.dataSource, cipher, Clock.systemUTC());
            ExecutorService pool = Executors.newFixedThreadPool(4);
            try {
                for (int round = 0; round < 10; round++) {
                    String name = "same-name-" + round;
                    CyclicBarrier barrier = new CyclicBarrier(4);
                    AtomicInteger ok = new AtomicInteger();
                    AtomicInteger rejected = new AtomicInteger();
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        futures.add(pool.submit(() -> {
                            await(barrier);
                            try {
                                store.create(name, Actors.CONSOLE);
                                ok.incrementAndGet();
                            } catch (IllegalArgumentException ex) {
                                rejected.incrementAndGet();
                            }
                        }));
                    }
                    for (Future<?> future : futures) {
                        future.get(10, TimeUnit.SECONDS);
                    }
                    assertThat(ok.get()).isEqualTo(1);
                    assertThat(rejected.get()).isEqualTo(3);
                }
            } finally {
                pool.shutdownNow();
            }

            // 历史残留孤儿行：启动载入不进内存，也不挡住重新绑定。
            SessionKey orphanKey = new SessionKey(Origin.OFFICIAL, "oa", "orphan");
            try (Connection connection = fixture.dataSource.getConnection();
                 PreparedStatement insert = connection.prepareStatement("""
                         INSERT INTO identity_members (origin, conch, account, identity_id, bound_at)
                         VALUES (?, ?, ?, ?, ?)
                         """)) {
                insert.setString(1, orphanKey.origin().name());
                insert.setString(2, orphanKey.conch());
                insert.setString(3, orphanKey.account());
                insert.setString(4, "missing-identity-id");
                Db.setInstant(insert, 5, Instant.now());
                insert.executeUpdate();
            }

            IdentityStore reopened = new IdentityStore(fixture.dataSource, cipher, Clock.systemUTC());
            assertThat(reopened.identityOf(orphanKey)).isNull();
            Identity target = reopened.create("新身份", Actors.CONSOLE);
            reopened.bind(target.id(), orphanKey, Actors.CONSOLE);
            assertThat(reopened.identityOf(orphanKey)).isEqualTo(target.id());
        }
    }

    private static Instant singleInstant(Connection connection, String sql, String column, String id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return Db.instant(rows, column);
            }
        }
    }

    private static int count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
