package dev.hermit.crab.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
                assertThat(sessions.cookieValue(key, "sid")).isEqualTo("cookie-kept");

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

    private static void putCookie(SessionManager sessions, SessionKey key, String value) {
        BasicClientCookie cookie = new BasicClientCookie("sid", value);
        cookie.setDomain("127.0.0.1");
        cookie.setPath("/");
        sessions.cookies(key).addCookie(cookie);
    }
}
