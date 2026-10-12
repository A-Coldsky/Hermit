package dev.hermit.crab.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 老 OA 夹具。密码只以 MD5 形式保存，响应用于规则引擎测试。 */
final class LegacyOaFixture implements AutoCloseable {

    private static final String VIEW_STATE = "vs-fixture";

    private final String passwordMd5;
    private final HttpServer server;
    private final ExecutorService executor;
    private final String baseUrl;
    private final Set<String> validSessions = ConcurrentHashMap.newKeySet();
    private final List<Hit> hits = new ArrayList<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final AtomicInteger expireNext = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger arrived = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean rendezvous;
    private volatile boolean brokenTable;
    private volatile int todoStatus = 200;
    private volatile int homeStatus = 200;
    private volatile long delayMillis;
    private volatile String todoTitle = "张三的请假申请";
    private volatile String welcome = "欢迎回来";

    LegacyOaFixture(String password) throws IOException {
        this.passwordMd5 = md5(password);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "legacy-oa-fixture");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        this.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String baseUrl() {
        return baseUrl;
    }

    void breakTable(boolean broken) {
        this.brokenTable = broken;
    }

    void todoStatus(int status) {
        this.todoStatus = status;
    }

    void homeStatus(int status) {
        this.homeStatus = status;
    }

    void todoDelay(Duration delay) {
        this.delayMillis = delay.toMillis();
    }

    void todoTitle(String title) {
        this.todoTitle = title;
    }

    void welcome(String text) {
        this.welcome = text;
    }

    void expireNextAuthenticated() {
        expireNext.incrementAndGet();
    }

    void invalidateSessions() {
        validSessions.clear();
    }

    void armRendezvous() {
        rendezvous = true;
        arrived.set(0);
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    int count(String method, String path) {
        int count = 0;
        for (Hit hit : hits()) {
            if (hit.method().equals(method) && hit.path().equals(path)) {
                count++;
            }
        }
        return count;
    }

    List<Hit> hits() {
        synchronized (hits) {
            return List.copyOf(hits);
        }
    }

    @Override
    public void close() {
        closed = true;
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try {
            dispatch(exchange);
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getRawQuery();
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        synchronized (hits) {
            hits.add(new Hit(method, path, query, cookie));
        }
        byte[] rawBody;
        try (InputStream in = exchange.getRequestBody()) {
            rawBody = in.readNBytes(64 * 1024);
        }
        if ("/login.aspx".equals(path) && "GET".equals(method)) {
            html(exchange, 200, loginPage());
            return;
        }
        if ("/login.aspx".equals(path) && "POST".equals(method)) {
            postLogin(exchange, rawBody);
            return;
        }
        if ("/todo/list.aspx".equals(path)) {
            todo(exchange, cookie);
            return;
        }
        if ("/home.aspx".equals(path)) {
            home(exchange, cookie);
            return;
        }
        if ("/profile.aspx".equals(path)) {
            profile(exchange, cookie);
            return;
        }
        if ("/gbk/name.aspx".equals(path)) {
            byte[] body = gbkPage();
            bytes(exchange, 200, body, "text/html");
            return;
        }
        if ("/echo.aspx".equals(path)) {
            html(exchange, 200, "<html><body><span id=\"v\">{{credential.password}}</span></body></html>");
            return;
        }
        if ("/loop".equals(path)) {
            redirect(exchange, 302, "/loop", null);
            return;
        }
        if ("/once".equals(path)) {
            redirect(exchange, 302, "/done", null);
            return;
        }
        if ("/done".equals(path)) {
            html(exchange, 200, "<html><body><span id=\"m\">ok</span></body></html>");
            return;
        }
        if ("/away".equals(path)) {
            redirect(exchange, 302, "http://127.0.0.1:1/nope", null);
            return;
        }
        if ("/huge".equals(path)) {
            huge(exchange);
            return;
        }
        html(exchange, 404, "missing");
    }

    private void postLogin(HttpExchange exchange, byte[] rawBody) throws IOException {
        String body = new String(rawBody, StandardCharsets.UTF_8);
        String viewState = form(body, "__VIEWSTATE");
        String username = form(body, "username");
        String password = form(body, "password");
        if (!VIEW_STATE.equals(viewState) || username == null || username.isBlank() || !passwordMd5.equals(password)) {
            html(exchange, 200, loginPage());
            return;
        }
        String id = "sid-" + username.replaceAll("[^A-Za-z0-9_-]", "") + "-" + sessionSeq.incrementAndGet();
        validSessions.add(id);
        redirect(exchange, 302, "/home.aspx", "ASP.NET_SessionId=" + id + "; Path=/");
    }

    private void todo(HttpExchange exchange, String cookie) throws IOException {
        if (!acceptSession(exchange, cookie)) {
            return;
        }
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (rendezvous) {
                arrived.incrementAndGet();
                long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (arrived.get() < 2 && !closed && System.nanoTime() < until) {
                    Thread.sleep(10);
                }
            }
            long end = System.nanoTime() + Duration.ofMillis(delayMillis).toNanos();
            while (!closed && System.nanoTime() < end) {
                Thread.sleep(20);
            }
            if (todoStatus >= 500) {
                html(exchange, todoStatus, "err");
                return;
            }
            // 保留 table#todo，去掉单元格。只改表格 id 时 each 匹配 0 条，按契约是空列表。
            String row = brokenTable
                    ? "<tr class=\"changed\"></tr>"
                    : "<tr><td>A1024</td><td><a href=\"/todo/A1024\">%s</a></td><td>张三</td><td>2026/10/12</td></tr>"
                            .formatted(escape(todoTitle));
            html(exchange, 200, """
                    <html><body>
                    <table id="todo">
                    <tr><th>单号</th><th>标题</th><th>申请人</th><th>日期</th></tr>
                    %s
                    </table>
                    </body></html>
                    """.formatted(row));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void profile(HttpExchange exchange, String cookie) throws IOException {
        if (!acceptSession(exchange, cookie)) {
            return;
        }
        String sid = sessionId(cookie);
        html(exchange, 200, """
                <html><body>
                <span id="greet">%s</span>
                <span id="sid">%s</span>
                <span id="note">待办</span>
                </body></html>
                """.formatted(escape(welcome), escape(sid == null ? "" : sid)));
    }

    private void home(HttpExchange exchange, String cookie) throws IOException {
        if (homeStatus == 401 || homeStatus == 403) {
            html(exchange, homeStatus, "denied");
            return;
        }
        if (!acceptSession(exchange, cookie)) {
            return;
        }
        html(exchange, 200, "<html><body>home</body></html>");
    }

    private boolean acceptSession(HttpExchange exchange, String cookie) throws IOException {
        String id = sessionId(cookie);
        if (id == null || !validSessions.contains(id)) {
            redirect(exchange, 302, "/login.aspx", null);
            return false;
        }
        if (expireNext.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
            validSessions.remove(id);
            redirect(exchange, 302, "/login.aspx", null);
            return false;
        }
        return true;
    }

    private void huge(HttpExchange exchange) throws IOException {
        int total = HttpCalls.MAX_BODY_BYTES + 1;
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, total);
        try {
            byte[] chunk = new byte[8192];
            java.util.Arrays.fill(chunk, (byte) 'x');
            int left = total;
            OutputStream out = exchange.getResponseBody();
            while (left > 0) {
                int n = Math.min(chunk.length, left);
                out.write(chunk, 0, n);
                left -= n;
            }
        } catch (IOException ignored) {
            // 客户端在超过上限后会断开。
        }
        exchange.close();
    }

    private static byte[] gbkPage() {
        String html = "<html><head><meta charset=\"gbk\"></head><body><span id=\"name\">张三</span></body></html>";
        return html.getBytes(Charset.forName("GBK"));
    }

    private static String loginPage() {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"></head><body>
                <form method="post" action="/login.aspx">
                <input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="%s">
                <input type="text" name="username">
                <input type="password" name="password">
                </form>
                </body></html>
                """.formatted(VIEW_STATE);
    }

    private static void html(HttpExchange exchange, int status, String body) throws IOException {
        bytes(exchange, status, body.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    private static void bytes(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void redirect(HttpExchange exchange, int status, String location, String setCookie) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Location", location);
        if (setCookie != null) {
            headers.add("Set-Cookie", setCookie);
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static String sessionId(String cookie) {
        if (cookie == null) {
            return null;
        }
        for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && "ASP.NET_SessionId".equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }

    private static String form(String body, String name) {
        for (String part : body.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && name.equals(decode(pair[0]))) {
                return decode(pair[1]);
            }
        }
        return null;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    record Hit(String method, String path, String query, String cookie) {
    }
}
