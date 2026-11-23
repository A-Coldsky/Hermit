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

    private static void putCookie(SessionManager sessions, SessionKey key, String value) {
        BasicClientCookie cookie = new BasicClientCookie("sid", value);
        cookie.setDomain("127.0.0.1");
        cookie.setPath("/");
        sessions.cookies(key).addCookie(cookie);
    }
}
