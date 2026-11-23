package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    /** 删掉的账号不再留着状态和连接池；正在等锁的请求拿到的是新状态，不会用到已关闭的客户端。 */
    @Test
    void forgetDropsTheStateAndWaitersGetAFreshClient() throws Exception {
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.LOCAL, "forget_probe", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            putCookie(sessions, key, "cookie-before-forget");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean usable = new AtomicBoolean();
            Thread waiter = new Thread(() -> {
                try {
                    if (sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(10))) {
                        try {
                            usable.set(sessions.client(key) != null && sessions.cookies(key) != null);
                        } finally {
                            sessions.unlock(key);
                        }
                    }
                } catch (Throwable ex) {
                    failure.set(ex);
                }
            });
            Thread forgetter = new Thread(() -> sessions.forget(key));
            waiter.start();
            forgetter.start();
            Thread.sleep(100);
            sessions.unlock(key);
            waiter.join(10_000);
            forgetter.join(10_000);
            assertThat(failure.get()).isNull();
            assertThat(usable).isTrue();

            sessions.forget(key);
            assertThat(sessions.size()).isZero();
            assertThat(sessions.seenCookieValues(key)).isEmpty();
        }
    }

    /** 删除账号时，必须等进行中的写锁和读锁都放开后再删会话和登录快照，防止在途调用重新写回。 */
    @Test
    void forgetWaitsForExclusiveAndSharedHoldersBeforeDeletingStorage() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        SessionPersistence persistence = new SessionPersistence() {
            @Override
            public List<StoredCookie> load(SessionKey key) {
                return List.of();
            }

            @Override
            public void save(SessionKey key, List<StoredCookie> cookies) {
                events.add("session.save");
            }

            @Override
            public void delete(SessionKey key) {
                events.add("session.delete");
            }
        };
        LoginSnapshotStore snapshots = new LoginSnapshotStore() {
            @Override
            public void save(SessionKey key, Map<String, String> snapshot) {
                events.add("snapshot.save");
            }

            @Override
            public Map<String, String> load(SessionKey key) {
                return Map.of();
            }

            @Override
            public void delete(SessionKey key) {
                events.add("snapshot.delete");
            }
        };
        try (SessionManager sessions = new SessionManager(
                persistence, snapshots, EngineSettings.defaults(), Clock.systemUTC())) {
            SessionKey key = new SessionKey(Origin.LOCAL, "forget_order", "alice");

            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            CountDownLatch exclusiveStarted = new CountDownLatch(1);
            Thread forgetExclusive = new Thread(() -> {
                exclusiveStarted.countDown();
                sessions.forget(key);
            });
            forgetExclusive.start();
            awaitBlocked(forgetExclusive, exclusiveStarted);
            assertThat(events).isEmpty();
            putCookie(sessions, key, "cookie-in-flight");
            sessions.persist(key);
            sessions.replaceLoginSnapshot(key, Map.of("token", "tok-1"));
            assertThat(events).containsExactly("session.save", "snapshot.save");
            sessions.unlock(key);
            forgetExclusive.join(10_000);
            assertThat(events).containsExactly("session.save", "snapshot.save", "session.delete", "snapshot.delete");
            assertThat(sessions.size()).isZero();

            events.clear();
            assertThat(sessions.tryLockShared(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            CountDownLatch sharedStarted = new CountDownLatch(1);
            Thread forgetShared = new Thread(() -> {
                sharedStarted.countDown();
                sessions.forget(key);
            });
            forgetShared.start();
            awaitBlocked(forgetShared, sharedStarted);
            assertThat(events).isEmpty();
            putCookie(sessions, key, "cookie-shared");
            sessions.persist(key);
            assertThat(events).containsExactly("session.save");
            sessions.unlockShared(key);
            forgetShared.join(10_000);
            assertThat(events).containsExactly("session.save", "session.delete", "snapshot.delete");
            assertThat(sessions.size()).isZero();
        }
    }

    /** close 在锁内移出状态，排队等独占锁或共享锁的线程不会醒来看到空的 client 或 cookies。 */
    @Test
    void closeRemovesStatesUnderLockSoWaitersNeverSeeNullClientOrCookies() throws Exception {
        SessionManager sessions = new SessionManager();
        SessionKey key = new SessionKey(Origin.LOCAL, "close_race", "alice");
        assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();

        CountDownLatch closerStarted = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            closerStarted.countDown();
            sessions.close();
        });
        closer.start();
        awaitBlocked(closer, closerStarted);

        AtomicReference<Throwable> exclusiveError = new AtomicReference<>();
        CountDownLatch exclusiveStarted = new CountDownLatch(1);
        Thread exclusiveWaiter = new Thread(() -> {
            exclusiveStarted.countDown();
            try {
                if (sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(10))) {
                    try {
                        if (sessions.client(key) == null || sessions.cookies(key) == null) {
                            exclusiveError.set(new AssertionError("null client/cookies after close"));
                        }
                    } finally {
                        sessions.unlock(key);
                    }
                }
            } catch (Throwable ex) {
                exclusiveError.set(ex);
            }
        });
        exclusiveWaiter.start();
        awaitBlocked(exclusiveWaiter, exclusiveStarted);

        AtomicReference<Throwable> sharedError = new AtomicReference<>();
        CountDownLatch sharedStarted = new CountDownLatch(1);
        Thread sharedWaiter = new Thread(() -> {
            sharedStarted.countDown();
            try {
                if (sessions.tryLockShared(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(10))) {
                    try {
                        if (sessions.client(key) == null || sessions.cookies(key) == null) {
                            sharedError.set(new AssertionError("null client/cookies after close"));
                        }
                    } finally {
                        sessions.unlockShared(key);
                    }
                }
            } catch (Throwable ex) {
                sharedError.set(ex);
            }
        });
        sharedWaiter.start();
        awaitBlocked(sharedWaiter, sharedStarted);

        sessions.unlock(key);
        closer.join(10_000);
        exclusiveWaiter.join(10_000);
        sharedWaiter.join(10_000);

        assertThat(exclusiveError.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("会话管理器已关闭");
        assertThat(sharedError.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("会话管理器已关闭");
        assertThat(sessions.size()).isZero();
    }

    private static void awaitBlocked(Thread thread, CountDownLatch started) throws InterruptedException {
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("thread did not block in time: " + thread.getState());
    }

    /** Cookie 没变时不再写库；变了、登出了、作废之后都要照常写。 */
    @Test
    void unchangedCookiesAreNotSavedAgain() throws Exception {
        List<List<StoredCookie>> saved = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        SessionPersistence counting = new SessionPersistence() {
            @Override
            public List<StoredCookie> load(SessionKey key) {
                return List.of(new StoredCookie("sid", "loaded-cookie", "127.0.0.1", "/", null, false, false));
            }

            @Override
            public void save(SessionKey key, List<StoredCookie> cookies) {
                saved.add(cookies);
            }

            @Override
            public void delete(SessionKey key) {
                deleted.add(key.account());
            }
        };
        try (SessionManager sessions = new SessionManager(counting)) {
            SessionKey key = new SessionKey(Origin.OFFICIAL, "persist_probe", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                // 载入后的第一次总是写：库里的内容和内存里的不一定逐字段相同。
                sessions.persist(key);
                sessions.persist(key);
                sessions.persist(key);
                assertThat(saved).hasSize(1);

                putCookie(sessions, key, "rotated-cookie");
                sessions.persist(key);
                sessions.persist(key);
                assertThat(saved).hasSize(2);
                assertThat(saved.get(1)).extracting(StoredCookie::value).containsExactly("rotated-cookie");

                sessions.markLoggedOut(key);
                assertThat(saved).hasSize(3);
                assertThat(saved.get(2)).isEmpty();
                sessions.persist(key);
                assertThat(saved).hasSize(3);

                // 作废会删库里的行；之后即使 Cookie 和上次写的一样（都是空），也要再写一次。
                sessions.invalidate(key);
                assertThat(deleted).containsExactly("alice");
                sessions.persist(key);
                assertThat(saved).hasSize(4);
            } finally {
                sessions.unlock(key);
            }
        }
    }

    /** 写库失败时不记成已写，下一次还会再试。 */
    @Test
    void failedSaveIsRetriedOnTheNextPersist() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        List<List<StoredCookie>> saved = new ArrayList<>();
        SessionPersistence flaky = new SessionPersistence() {
            @Override
            public List<StoredCookie> load(SessionKey key) {
                return List.of();
            }

            @Override
            public void save(SessionKey key, List<StoredCookie> cookies) {
                if (fail.get()) {
                    throw new IllegalStateException("db down");
                }
                saved.add(cookies);
            }

            @Override
            public void delete(SessionKey key) {
            }
        };
        try (SessionManager sessions = new SessionManager(flaky)) {
            SessionKey key = new SessionKey(Origin.OFFICIAL, "persist_retry", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                putCookie(sessions, key, "kept-cookie");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> sessions.persist(key))
                        .isInstanceOf(IllegalStateException.class);
                fail.set(false);
                sessions.persist(key);
                assertThat(saved).hasSize(1);
            } finally {
                sessions.unlock(key);
            }
        }
    }

    /** 空闲连接到点关掉；Cookie 和登录状态不动，下一次请求照常建连。 */
    @Test
    void idleConnectionsAreClosedWithoutTouchingTheSession() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (SessionManager sessions = new SessionManager()) {
            SessionKey key = new SessionKey(Origin.LOCAL, "idle_probe", "alice");
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                putCookie(sessions, key, "cookie-kept");
                sessions.markLoggedIn(key);
                assertThat(get(sessions, key, url)).isEqualTo("ok");
                assertThat(sessions.idleConnections(key)).isEqualTo(1);

                // 还没闲到时限的连接留着。
                assertThat(sessions.closeIdleConnections(java.time.Duration.ofHours(1))).isEqualTo(1);
                assertThat(sessions.idleConnections(key)).isEqualTo(1);

                Thread.sleep(20);
                sessions.closeIdleConnections(java.time.Duration.ofMillis(1));
                assertThat(sessions.idleConnections(key)).isZero();
                assertThat(sessions.loggedIn(key)).isTrue();
                assertThat(sessions.cookieValue(key, "sid", null)).isEqualTo("cookie-kept");

                assertThat(get(sessions, key, url)).isEqualTo("ok");
            } finally {
                sessions.unlock(key);
            }
        } finally {
            server.stop(0);
        }
    }

    private static String get(SessionManager sessions, SessionKey key, String url) throws Exception {
        return sessions.client(key).execute(
                new org.apache.hc.client5.http.classic.methods.HttpGet(url),
                response -> org.apache.hc.core5.http.io.entity.EntityUtils.toString(response.getEntity()));
    }

    /** 没写 Domain 的 Cookie 重启后仍然只属于那个主机；按名字读 Cookie 只看会发往 base_url 的。 */
    @Test
    void hostOnlyCookieStaysHostOnlyAcrossARestartAndNamedReadsFollowTheBaseHost() throws Exception {
        List<List<StoredCookie>> saved = new ArrayList<>();
        SessionPersistence store = new SessionPersistence() {
            @Override
            public List<StoredCookie> load(SessionKey key) {
                return List.of(
                        new StoredCookie("host", "host-only-value", "corp.example", "/", null, false, false, true),
                        new StoredCookie("wide", "domain-value", "corp.example", "/", null, false, false, false),
                        // 这个字段加入之前存的：没有 hostOnly，按原来的域名匹配。
                        new StoredCookie("old", "legacy-value", "corp.example", "/", null, false, false),
                        new StoredCookie("sso", "sso-value", "sso.other.example", "/", null, false, false, true));
            }

            @Override
            public void save(SessionKey key, List<StoredCookie> cookies) {
                saved.add(cookies);
            }

            @Override
            public void delete(SessionKey key) {
            }
        };
        try (SessionManager sessions = new SessionManager(store)) {
            SessionKey key = new SessionKey(Origin.OFFICIAL, "persist_probe", "alice");
            assertThat(sessions.tryLock(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                java.net.URI sub = java.net.URI.create("https://oa.corp.example/app");
                java.net.URI apex = java.net.URI.create("https://CORP.example./");
                assertThat(sessions.cookieValue(key, "host", sub)).isNull();
                assertThat(sessions.cookieValue(key, "host", apex)).isEqualTo("host-only-value");
                assertThat(sessions.cookieValue(key, "wide", sub)).isEqualTo("domain-value");
                assertThat(sessions.cookieValue(key, "old", sub)).isEqualTo("legacy-value");
                assertThat(sessions.cookieValue(key, "sso", sub)).isNull();
                assertThat(sessions.cookieValue(key, "sso", null)).isEqualTo("sso-value");
                assertThat(sessions.cookieValue(key, "wide", java.net.URI.create("https://evilcorp.example/")))
                        .isNull();

                sessions.persist(key);
                assertThat(saved).hasSize(1);
                assertThat(saved.get(0)).filteredOn(StoredCookie::hostOnly)
                        .extracting(StoredCookie::name).containsExactlyInAnyOrder("host", "sso");
            } finally {
                sessions.unlock(key);
            }
        }
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new StoredCookie("sid", "v", "h", "/", null, false, false));
        assertThat(json).as("旧版本读不了多出来的字段，没用到时不写").doesNotContain("hostOnly");
    }

    /** H4：状态与当前绑定不一致时，invalidate 必须先迁出旧状态，不能误清旧身份，也不能漏清当前身份。 */
    @Test
    void invalidateOnStaleStateRespectsCurrentBindingAndDoesNotWipePreviousIdentity() throws Exception {
        MemoryShared shared = new MemoryShared();
        SessionKey alice = new SessionKey(Origin.OFFICIAL, "oa", "alice");
        SessionKey erp = new SessionKey(Origin.OFFICIAL, "erp", "alice");
        shared.bind("id1", alice, erp);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), shared,
                EngineSettings.defaults(), Clock.systemUTC())) {
            assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                putCookie(sessions, alice, "shared-cookie-1");
                sessions.persist(alice);
            } finally {
                sessions.unlock(alice);
            }
            assertThat(shared.saved.get("id1")).extracting(StoredCookie::value).containsExactly("shared-cookie-1");

            // alice 已经离开 id1，但还没来得及 rebound；此时 invalidate(alice) 不能误清 id1 的 Cookie。
            shared.unbind(alice);
            sessions.invalidate(alice);
            assertThat(shared.saved.get("id1")).extracting(StoredCookie::value).containsExactly("shared-cookie-1");

            // 反向：alice 在独立会话中登录后刚绑进 id1（还没 rebound），此时 invalidate(alice) 必须清掉 id1，
            // 且不能把刚作废的独立 Cookie 带进 id1。
            assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                putCookie(sessions, alice, "standalone-stale");
                sessions.persist(alice);
            } finally {
                sessions.unlock(alice);
            }
            shared.bind("id1", alice);
            sessions.invalidate(alice);
            assertThat(shared.saved.get("id1")).isNull();
            assertThat(sessions.tryLock(erp, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                assertThat(sessions.cookies(erp).getCookies()).isEmpty();
            } finally {
                sessions.unlock(erp);
            }
        }
    }

    /** M1：绑定变更后 tryLock 已先迁移并建了新会话，迟到的 rebound 不能抹掉新的登录快照和待输入。 */
    @Test
    void delayedReboundAfterConcurrentTryLockMigrationDoesNotWipeFreshSession() throws Exception {
        MemoryShared shared = new MemoryShared();
        java.util.concurrent.ConcurrentHashMap<SessionKey, Map<String, String>> snapshotsMap =
                new java.util.concurrent.ConcurrentHashMap<>();
        LoginSnapshotStore snapshots = new LoginSnapshotStore() {
            @Override
            public void save(SessionKey key, Map<String, String> snapshot) {
                if (snapshot == null || snapshot.isEmpty()) {
                    snapshotsMap.remove(key);
                } else {
                    snapshotsMap.put(key, Map.copyOf(snapshot));
                }
            }

            @Override
            public Map<String, String> load(SessionKey key) {
                return snapshotsMap.getOrDefault(key, Map.of());
            }

            @Override
            public void delete(SessionKey key) {
                snapshotsMap.remove(key);
            }
        };
        SessionKey alice = new SessionKey(Origin.OFFICIAL, "oa", "alice");
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), snapshots, shared, EngineSettings.defaults(), Clock.systemUTC())) {
            // 先作为独立账号登录，留下一份旧 Cookie 和旧快照。
            assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                putCookie(sessions, alice, "own-cookie-seed");
                sessions.persist(alice);
                sessions.replaceLoginSnapshot(alice, Map.of("old", "stale-token"));
            } finally {
                sessions.unlock(alice);
            }

            // 绑到身份 id1，在 rebound 到来前，并发调用先走 tryLock 完成迁移并写入新快照和待输入。
            shared.bind("id1", alice);
            assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                assertThat(sessions.shared(alice)).isTrue();
                assertThat(sessions.loginSnapshot(alice)).as("旧独立快照已在迁移时清掉").isEmpty();
                assertThat(sessions.cookieValue(alice, "sid", null))
                        .as("旧独立 Cookie 已带入新身份").isEqualTo("own-cookie-seed");
                sessions.markLoggedIn(alice);
                sessions.replaceLoginSnapshot(alice, Map.of("fresh", "new-token"));
                sessions.pending().store(alice, new PendingLogins.Draft(
                        "1.0.0", "hash-1", 0, "code", "code", "验证码", null, 300, false, Map.of(), null, null));
            } finally {
                sessions.unlock(alice);
            }

            // 迟到的 rebound 看到状态已与当前绑定一致，不能抹掉新会话。
            sessions.rebound(alice);
            assertThat(snapshotsMap.get(alice)).containsEntry("fresh", "new-token");
            assertThat(sessions.pending().current(alice)).isNotNull();
            assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                assertThat(sessions.loggedIn(alice)).isTrue();
                assertThat(sessions.loginSnapshot(alice)).containsEntry("fresh", "new-token");
            } finally {
                sessions.unlock(alice);
            }
        }
    }

    /** M1：绑定变更后 tryLockShared 升级写锁迁移必须遵守 deadline 和中断，不能无界阻塞。 */
    @Test
    void tryLockSharedBoundsAndInterruptsStaleMigration() throws Exception {
        MemoryShared shared = new MemoryShared();
        SessionKey alice = new SessionKey(Origin.OFFICIAL, "oa", "alice");
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), shared,
                EngineSettings.defaults(), Clock.systemUTC())) {
            assertThat(sessions.tryLockShared(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                // 另一线程改了绑定，当前线程仍持有旧状态的读锁：新线程拿到读锁后发现 stale，释放读锁再升级写锁时会被阻塞。
                shared.bind("id1", alice);

                long start = System.nanoTime();
                AtomicBoolean timedOut = new AtomicBoolean(true);
                Thread bounded = new Thread(() -> {
                    try {
                        timedOut.set(sessions.tryLockShared(
                                alice, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(120)));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
                bounded.start();
                bounded.join(5_000);
                assertThat(bounded.isAlive()).isFalse();
                assertThat(timedOut).isFalse();
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3_000L);

                AtomicBoolean interrupted = new AtomicBoolean();
                CountDownLatch started = new CountDownLatch(1);
                Thread interruptible = new Thread(() -> {
                    started.countDown();
                    try {
                        sessions.tryLockShared(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
                    } catch (InterruptedException ex) {
                        interrupted.set(true);
                    }
                });
                interruptible.start();
                awaitBlocked(interruptible, started);
                interruptible.interrupt();
                interruptible.join(5_000);
                assertThat(interruptible.isAlive()).isFalse();
                assertThat(interrupted).isTrue();
            } finally {
                sessions.unlockShared(alice);
            }
        }
    }

    /** M2：rebound 往共享罐写入超过 64 个 Cookie 时，与并发读打码名单不抛出 ConcurrentModificationException。 */
    @Test
    void concurrentSeedAndRedactionReadDoNotThrowOnSeenCookieValues() throws Exception {
        MemoryShared shared = new MemoryShared();
        SessionKey alice = new SessionKey(Origin.OFFICIAL, "oa", "alice");
        SessionKey erp = new SessionKey(Origin.OFFICIAL, "erp", "alice");
        shared.bind("id1", erp);
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), shared,
                EngineSettings.defaults(), Clock.systemUTC())) {
            assertThat(sessions.tryLock(erp, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            sessions.unlock(erp);

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicReference<Throwable> readerError = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                while (running.get()) {
                    try {
                        sessions.redactionValuesNow(erp);
                    } catch (Throwable ex) {
                        readerError.set(ex);
                        return;
                    }
                }
            });
            reader.start();
            try {
                for (int round = 0; round < 10; round++) {
                    shared.unbind(alice);
                    sessions.rebound(alice);
                    assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
                    try {
                        for (int i = 0; i < 80; i++) {
                            BasicClientCookie c = new BasicClientCookie("c" + i, "val-" + round + "-" + i);
                            c.setDomain("127.0.0.1");
                            c.setPath("/");
                            sessions.cookies(alice).addCookie(c);
                        }
                    } finally {
                        sessions.unlock(alice);
                    }
                    sessions.clearIdentity("id1");
                    shared.bind("id1", alice);
                    sessions.rebound(alice);
                }
            } finally {
                running.set(false);
                reader.join(5_000);
            }
            assertThat(readerError.get()).isNull();
        }
    }

    /** Low：clearIdentity 在内存没有罐时不新建 Jar；closeIdleConnections 也回收无成员状态的身份罐空闲连接。 */
    @Test
    void clearIdentityDoesNotEagerlyAllocateJarAndCloseIdleSweepsOrphanIdentityJars() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        MemoryShared shared = new MemoryShared();
        try (SessionManager sessions = new SessionManager(
                new NopSessionPersistence(), new NopLoginSnapshotStore(), shared,
                EngineSettings.defaults(), Clock.systemUTC())) {
            shared.save("cold-id", List.of(
                    new StoredCookie("sid", "cold-cookie", "127.0.0.1", "/", null, false, false, true)));
            sessions.clearIdentity("cold-id");
            assertThat(shared.saved.get("cold-id")).isNull();
            assertThat(sessions.identityJarCount()).as("未载入内存的身份清空后不该新建连接池").isZero();

            SessionKey alice = new SessionKey(Origin.LOCAL, "idle_shared", "alice");
            shared.bind("warm-id", alice);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                assertThat(get(sessions, alice, url)).isEqualTo("ok");
                assertThat(sessions.idleConnections(alice)).isEqualTo(1);
            } finally {
                sessions.unlock(alice);
            }
            // 唯一成员的账号状态被移除，但身份罐仍在内存里：closeIdleConnections 仍应扫到它。
            sessions.forget(alice);
            assertThat(sessions.size()).isZero();
            assertThat(sessions.identityJarCount()).isEqualTo(1);
            Thread.sleep(20);
            assertThat(sessions.closeIdleConnections(java.time.Duration.ofMillis(1))).isEqualTo(1);
            assertThat(sessions.tryLock(alice, System.nanoTime() + TimeUnit.SECONDS.toNanos(5))).isTrue();
            try {
                assertThat(sessions.idleConnections(alice)).isZero();
            } finally {
                sessions.unlock(alice);
            }
        } finally {
            server.stop(0);
        }
    }

    private static final class MemoryShared implements SharedSessions {
        private final java.util.concurrent.ConcurrentHashMap<SessionKey, String> bound =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, List<StoredCookie>> saved =
                new java.util.concurrent.ConcurrentHashMap<>();

        void bind(String identity, SessionKey... keys) {
            for (SessionKey key : keys) {
                bound.put(key, identity);
            }
        }

        void unbind(SessionKey key) {
            bound.remove(key);
        }

        @Override
        public String identityOf(SessionKey key) {
            return bound.get(key);
        }

        @Override
        public List<SessionKey> members(String identity) {
            ArrayList<SessionKey> members = new ArrayList<>();
            bound.forEach((key, id) -> {
                if (id.equals(identity)) {
                    members.add(key);
                }
            });
            return members;
        }

        @Override
        public List<StoredCookie> load(String identity) {
            return saved.getOrDefault(identity, List.of());
        }

        @Override
        public void save(String identity, List<StoredCookie> cookies) {
            saved.put(identity, List.copyOf(cookies));
        }

        @Override
        public void delete(String identity) {
            saved.remove(identity);
        }
    }

    private static void putCookie(SessionManager sessions, SessionKey key, String value) {
        BasicClientCookie cookie = new BasicClientCookie("sid", value);
        cookie.setDomain("127.0.0.1");
        cookie.setPath("/");
        sessions.cookies(key).addCookie(cookie);
    }
}
