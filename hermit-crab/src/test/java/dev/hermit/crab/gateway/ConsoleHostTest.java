package dev.hermit.crab.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import dev.hermit.crab.config.CrabConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DNS rebinding: the socket is loopback but the browser resolved another name. */
class ConsoleHostTest {
    private static final String PASSWORD = "ConsoleHostSecret-4be1c7d0a2";

    @TempDir
    java.nio.file.Path temp;

    @Test
    void hostNamesAreLoopbackOnlyAndIgnorePorts() {
        assertThat(ConsoleHosts.hostAllowed("127.0.0.1")).isTrue();
        assertThat(ConsoleHosts.hostAllowed("127.0.0.1:7777")).isTrue();
        assertThat(ConsoleHosts.hostAllowed("LOCALHOST:9000")).isTrue();
        assertThat(ConsoleHosts.hostAllowed("[::1]:7777")).isTrue();
        assertThat(ConsoleHosts.hostAllowed("[::1]")).isTrue();

        assertThat(ConsoleHosts.hostAllowed("attacker.example:7777")).isFalse();
        assertThat(ConsoleHosts.hostAllowed("127.0.0.1.attacker.example")).isFalse();
        assertThat(ConsoleHosts.hostAllowed("localhost.attacker.example")).isFalse();
        assertThat(ConsoleHosts.hostAllowed("127.0.0.2")).isFalse();
        assertThat(ConsoleHosts.hostAllowed("127.0.0.1:77x")).isFalse();
        assertThat(ConsoleHosts.hostAllowed("::1")).isFalse();
        assertThat(ConsoleHosts.hostAllowed("")).isFalse();

        assertThat(ConsoleHosts.originAllowed("http://127.0.0.1:7777")).isTrue();
        assertThat(ConsoleHosts.originAllowed("http://localhost:5174")).isTrue();
        assertThat(ConsoleHosts.originAllowed("http://[::1]:7777")).isTrue();
        assertThat(ConsoleHosts.originAllowed("http://attacker.example:7777")).isFalse();
        assertThat(ConsoleHosts.originAllowed("null")).isFalse();
        assertThat(ConsoleHosts.originAllowed("file://")).isFalse();
    }

    @Test
    void reboundNamesCannotReachConsoleApiOrPages() throws Exception {
        try (TestServer server = TestServer.open(temp.resolve("rebind"), PASSWORD, CrabConfig.defaults().withListen("127.0.0.1", 0))) {
            int port = server.server.port();
            int keysBefore = server.runtime.admin().listApiKeys().size();

            String create = post(port, "/console/api/apikeys", "attacker.example:" + port, "http://attacker.example:" + port,
                    "{\"name\":\"rebind\",\"all\":true}");
            assertThat(create).startsWith("HTTP/1.1 403").contains("FORBIDDEN").contains(ConsoleHosts.DENIED);
            assertThat(server.runtime.admin().listApiKeys()).hasSize(keysBefore);

            String foreignOrigin = post(port, "/console/api/apikeys", "127.0.0.1:" + port, "http://attacker.example:" + port,
                    "{\"name\":\"rebind\",\"all\":true}");
            assertThat(foreignOrigin).startsWith("HTTP/1.1 403").contains(ConsoleHosts.DENIED);
            assertThat(server.runtime.admin().listApiKeys()).hasSize(keysBefore);

            String page = get(port, "/console/apikeys", "attacker.example:" + port);
            assertThat(page).startsWith("HTTP/1.1 403").doesNotContain("<div id=\"app\">");

            String duplicated = raw(port, "GET /console/api/conches HTTP/1.1\r\nHost: 127.0.0.1\r\nHost: attacker.example\r\n"
                    + "X-Hermit-Console: 1\r\nConnection: close\r\n\r\n");
            assertThat(duplicated).doesNotContain("\"success\":true");

            // 同机 SSH 隧道换了端口：名字仍是回环，照常放行。
            String tunnel = post(port, "/console/api/apikeys", "localhost:9000", "http://localhost:9000",
                    "{\"name\":\"tunnel\",\"all\":true}");
            assertThat(tunnel).startsWith("HTTP/1.1 200");
            assertThat(server.runtime.admin().listApiKeys()).hasSize(keysBefore + 1);
        }
    }

    private static String post(int port, String path, String host, String origin, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return raw(port, "POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\nOrigin: " + origin
                + "\r\nX-Hermit-Console: 1\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n" + body);
    }

    private static String get(int port, String path, String host) throws Exception {
        return raw(port, "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n");
    }

    private static String raw(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
