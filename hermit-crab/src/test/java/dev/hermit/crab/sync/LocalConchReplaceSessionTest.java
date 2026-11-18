package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.runtime.InvokeOutcome;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyPrincipal;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;

/**
 * 地址扩大的覆盖在 {@code registry.put} 之前清会话，成功后再清一次。
 * 进行中的调用仍打旧主机、带旧 Cookie；下一次调用读到新文档，并且不再带出旧 Cookie。
 * 两次清空之间新建的登录，在覆盖返回时会话和登录快照都不在。
 * 同边界的版本替换保留登录快照。
 */
class LocalConchReplaceSessionTest {

    @TempDir
    java.nio.file.Path temp;

    @Test
    void widenedReplaceLetsTheInFlightCallFinishOnTheOldHost() throws Exception {
        HttpServer oldHost = server();
        HttpServer newHost = server();
        AtomicInteger oldLogins = new AtomicInteger();
        AtomicInteger oldPings = new AtomicInteger();
        AtomicInteger newLogins = new AtomicInteger();
        AtomicInteger newPings = new AtomicInteger();
        AtomicReference<String> blockedCookie = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        oldHost.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/login".equals(path)) {
                oldLogins.incrementAndGet();
                exchange.getResponseHeaders().add("Set-Cookie", "sid=old-cookie-value; Path=/");
            } else if ("/ping".equals(path)) {
                if (oldPings.incrementAndGet() == 2) {
                    blockedCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
                    entered.countDown();
                    try {
                        if (!release.await(20, TimeUnit.SECONDS)) {
                            exchange.sendResponseHeaders(500, -1);
                            exchange.close();
                            return;
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                        return;
                    }
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        newHost.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/login".equals(path)) {
                newLogins.incrementAndGet();
                exchange.getResponseHeaders().add("Set-Cookie", "sid=new-cookie-value; Path=/");
            } else if ("/ping".equals(path)) {
                newPings.incrementAndGet();
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                if (cookie != null && cookie.contains("old-cookie-value")) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        oldHost.start();
        newHost.start();
        int oldPort = oldHost.getAddress().getPort();
        int newPort = newHost.getAddress().getPort();
        try (CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("flight")), CrabConfig.defaults(), name -> null)) {
            runtime.localConches().importBytes(flight("1.0.0", oldPort).getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.admin().createAccount("flight", "ada", Actors.CLI);
            ApiKeyPrincipal principal = principal(runtime);
            InvokeOutcome first = invoke(runtime, principal, "trace-first");
            assertThat(first.status()).as(body(first)).isEqualTo(200);
            assertThat(oldLogins).hasValue(1);
            assertThat(oldPings).hasValue(1);
            assertThat(loginRows(runtime)).isEqualTo(1);

            AtomicReference<InvokeOutcome> second = new AtomicReference<>();
            AtomicReference<Throwable> secondError = new AtomicReference<>();
            Thread inflight = new Thread(() -> {
                try {
                    second.set(invoke(runtime, principal, "trace-inflight"));
                } catch (Throwable ex) {
                    secondError.set(ex);
                }
            }, "inflight-call");
            inflight.start();
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(blockedCookie.get()).contains("old-cookie-value");
            assertThat(newLogins).hasValue(0);
            assertThat(newPings).hasValue(0);

            AtomicReference<Throwable> replaceError = new AtomicReference<>();
            Thread replacer = new Thread(() -> {
                try {
                    runtime.localConches().importBytes(
                            flight("1.0.1", newPort).getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
                } catch (Throwable ex) {
                    replaceError.set(ex);
                }
            }, "widen-replace");
            replacer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < deadline) {
                assertThat(runtime.registry().find("flight").orElseThrow().baseUrl())
                        .as("registry.put must wait until the in-flight call releases the account lock")
                        .contains(":" + oldPort);
                Thread.State state = replacer.getState();
                if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    Thread.sleep(50);
                    if (replacer.isAlive() && runtime.registry().find("flight").orElseThrow().baseUrl().contains(":" + oldPort)) {
                        waiting = true;
                        break;
                    }
                }
                Thread.sleep(20);
            }
            assertThat(waiting).as("replace did not block behind the in-flight call").isTrue();
            assertThat(loginRows(runtime)).isEqualTo(1);
            release.countDown();
            inflight.join(20_000);
            replacer.join(20_000);
            assertThat(secondError.get()).isNull();
            assertThat(replaceError.get()).isNull();
            assertThat(second.get().status()).as(body(second.get())).isEqualTo(200);
            assertThat(newLogins).hasValue(0);
            assertThat(newPings).hasValue(0);
            assertThat(oldPings).hasValue(2);
            assertThat(runtime.registry().find("flight").orElseThrow().baseUrl()).contains(":" + newPort);
            assertThat(loginRows(runtime)).isZero();

            InvokeOutcome third = invoke(runtime, principal, "trace-next");
            assertThat(third.status()).as(body(third)).isEqualTo(200);
            assertThat(newLogins).hasValue(1);
            assertThat(newPings).hasValue(1);
            assertThat(oldLogins).hasValue(1);
            assertThat(oldPings).hasValue(2);
        } finally {
            release.countDown();
            oldHost.stop(0);
            newHost.stop(0);
        }
    }

    @Test
    void loginInTheWidenGapDoesNotSurviveTheReplace() throws Exception {
        HttpServer oldHost = server();
        HttpServer newHost = server();
        AtomicInteger oldLogins = new AtomicInteger();
        AtomicInteger oldPings = new AtomicInteger();
        AtomicInteger newLogins = new AtomicInteger();
        AtomicInteger newPings = new AtomicInteger();
        oldHost.createContext("/", exchange -> {
            if ("/login".equals(exchange.getRequestURI().getPath())) {
                oldLogins.incrementAndGet();
                exchange.getResponseHeaders().add("Set-Cookie", "sid=old-cookie-value; Path=/");
            } else if ("/ping".equals(exchange.getRequestURI().getPath())) {
                oldPings.incrementAndGet();
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        newHost.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/login".equals(path)) {
                newLogins.incrementAndGet();
                exchange.getResponseHeaders().add("Set-Cookie", "sid=new-cookie-value; Path=/");
            } else if ("/ping".equals(path)) {
                newPings.incrementAndGet();
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                if (cookie != null && cookie.contains("old-cookie-value")) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        oldHost.start();
        newHost.start();
        int oldPort = oldHost.getAddress().getPort();
        int newPort = newHost.getAddress().getPort();
        try (CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("gap")), CrabConfig.defaults(), name -> null)) {
            runtime.localConches().importBytes(flight("1.0.0", oldPort).getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.admin().createAccount("flight", "ada", Actors.CLI);
            ApiKeyPrincipal principal = principal(runtime);
            assertThat(invoke(runtime, principal, "trace-first").status()).isEqualTo(200);
            assertThat(oldLogins).hasValue(1);
            assertThat(loginRows(runtime)).isEqualTo(1);
            assertThat(sessionRows(runtime)).isEqualTo(1);

            runtime.localConches().widenedGap(() -> {
                assertThat(runtime.registry().find("flight").orElseThrow().baseUrl()).contains(":" + oldPort);
                InvokeOutcome gap = invoke(runtime, principal, "trace-gap");
                assertThat(gap.status()).as(body(gap)).isEqualTo(200);
                assertThat(oldLogins).hasValue(2);
                assertThat(oldPings).hasValue(2);
                assertThat(newLogins).hasValue(0);
                try {
                    assertThat(loginRows(runtime)).isEqualTo(1);
                    assertThat(sessionRows(runtime)).isEqualTo(1);
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            });
            runtime.localConches().importBytes(
                    flight("1.0.1", newPort).getBytes(StandardCharsets.UTF_8), Actors.CLI, true);

            assertThat(runtime.registry().find("flight").orElseThrow().baseUrl()).contains(":" + newPort);
            assertThat(loginRows(runtime)).isZero();
            assertThat(sessionRows(runtime)).isZero();
            InvokeOutcome next = invoke(runtime, principal, "trace-next");
            assertThat(next.status()).as(body(next)).isEqualTo(200);
            assertThat(newLogins).hasValue(1);
            assertThat(newPings).hasValue(1);
            assertThat(oldLogins).hasValue(2);
            assertThat(oldPings).hasValue(2);
        } finally {
            oldHost.stop(0);
            newHost.stop(0);
        }
    }

    @Test
    void sameBoundaryReplaceKeepsTheLoginSnapshot() throws Exception {
        HttpServer host = server();
        AtomicInteger logins = new AtomicInteger();
        host.createContext("/", exchange -> {
            if ("/login".equals(exchange.getRequestURI().getPath())) {
                logins.incrementAndGet();
                exchange.getResponseHeaders().add("Set-Cookie", "sid=kept-cookie-value; Path=/");
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        host.start();
        int port = host.getAddress().getPort();
        try (CrabRuntime runtime = CrabRuntime.open(
                new CrabPaths(temp.resolve("same")), CrabConfig.defaults(), name -> null)) {
            runtime.localConches().importBytes(flight("1.0.0", port).getBytes(StandardCharsets.UTF_8), Actors.CLI);
            runtime.admin().createAccount("flight", "ada", Actors.CLI);
            ApiKeyPrincipal principal = principal(runtime);
            assertThat(invoke(runtime, principal, "trace-login").status()).isEqualTo(200);
            assertThat(logins).hasValue(1);
            assertThat(loginRows(runtime)).isEqualTo(1);

            runtime.localConches().importBytes(flight("1.0.1", port).getBytes(StandardCharsets.UTF_8), Actors.CLI, true);
            assertThat(loginRows(runtime)).isEqualTo(1);
            assertThat(invoke(runtime, principal, "trace-again").status()).isEqualTo(200);
            assertThat(logins).hasValue(1);
        } finally {
            host.stop(0);
        }
    }

    private static HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static ApiKeyPrincipal principal(CrabRuntime runtime) {
        CreatedApiKey key = runtime.admin().createApiKey(
                "all", ApiKeyScope.explicit(true, Set.of(), Set.of(), false), Actors.CLI);
        return runtime.apiKeys().authenticate(key.plaintext()).orElseThrow();
    }

    private static InvokeOutcome invoke(CrabRuntime runtime, ApiKeyPrincipal principal, String trace) {
        return runtime.invoker().invoke(
                principal, "flight", "ping", java.util.Map.of(), Optional.of("ada"), Optional.empty(), trace);
    }

    private static int loginRows(CrabRuntime runtime) throws Exception {
        return countRows(runtime, "session_login");
    }

    private static int sessionRows(CrabRuntime runtime) throws Exception {
        return countRows(runtime, "sessions");
    }

    private static int countRows(CrabRuntime runtime, String table) throws Exception {
        try (Connection connection = runtime.dataSource().getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE origin = 'LOCAL' AND conch = 'flight'")) {
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private static String body(InvokeOutcome outcome) {
        return outcome == null || outcome.body() == null ? "" : new String(outcome.body(), StandardCharsets.UTF_8);
    }

    private static String flight(String version, int port) {
        return """
                conch: flight
                version: %s
                driver: http
                min_crab_version: 1.3.0
                title: 飞行
                description: 说明
                base_url: http://127.0.0.1:%d/
                auth:
                  steps:
                    - request:
                        method: GET
                        path: /login
                    - set:
                        marker: ready
                    - assert:
                        cookie_exists: sid
                credentials: {}
                actions:
                  ping:
                    title: 探测
                    description: 说明
                    idempotent: true
                    steps:
                      - request:
                          method: GET
                          path: /ping
                      - assert:
                          status: 200
                    output:
                      ok: { type: boolean, description: 结果, value: true }
                    examples:
                      - input: {}
                        output: { ok: true }
                    notes: 注意
                    related: [ping]
                """.formatted(version, port);
    }
}
