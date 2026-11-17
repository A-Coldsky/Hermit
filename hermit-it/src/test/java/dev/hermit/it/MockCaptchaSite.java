package dev.hermit.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 本机验证码站点。不连真实网站、短信网关或邮箱。
 * 图片站在 {@code GET /captcha.png} 下发 PNG，并用会话 Cookie {@code cap} 记住答案。
 * 短信站在 {@code POST /sms/send} 发码，响应正文不含验证码；测试用 {@link #issuedAnswer()} 读取。
 * 登录核对 Cookie、路径和表单里的是不是同一次答案。成功后的令牌放进 Cookie {@code auth} 和 JSON。
 */
final class MockCaptchaSite implements AutoCloseable {
    enum Kind {
        IMAGE,
        SMS
    }

    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Kind kind;
    private final HttpServer server;
    private final ExecutorService executor;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Object lock = new Object();
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private final Map<String, String> subjects = new LinkedHashMap<>();
    private final Map<String, String> answers = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Challenge> challenges = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LoginHit> logins = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InboxHit> inboxes = new CopyOnWriteArrayList<>();
    private volatile String lastSendBody = "";

    private MockCaptchaSite(Kind kind) throws IOException {
        this.kind = kind;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, kind == Kind.IMAGE ? "mock-image-captcha" : "mock-sms-captcha");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    static MockCaptchaSite image() throws IOException {
        return new MockCaptchaSite(Kind.IMAGE);
    }

    static MockCaptchaSite sms() throws IOException {
        return new MockCaptchaSite(Kind.SMS);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void allow(String username, String password, String subject) {
        synchronized (lock) {
            passwords.put(username, password);
            subjects.put(username, subject);
        }
    }

    /** 按发出顺序。图片字节里嵌着当次答案，用来发现把图片正文写进日志的情况。 */
    List<Challenge> challenges() {
        return List.copyOf(challenges);
    }

    List<LoginHit> logins() {
        return List.copyOf(logins);
    }

    List<InboxHit> inboxes() {
        return List.copyOf(inboxes);
    }

    /** 最近一次发出的验证码。短信响应正文里没有这个值。 */
    String issuedAnswer() {
        if (challenges.isEmpty()) {
            throw new AssertionError("no captcha has been issued");
        }
        return challenges.get(challenges.size() - 1).answer();
    }

    String lastSendBody() {
        return lastSendBody;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        try {
            if (kind == Kind.IMAGE && "GET".equals(exchange.getRequestMethod()) && "/captcha.png".equals(path)) {
                issueImage(exchange);
                return;
            }
            if (kind == Kind.SMS && "POST".equals(exchange.getRequestMethod()) && "/sms/send".equals(path)) {
                issueCode(exchange);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/login/")) {
                login(exchange, path.substring("/login/".length()), raw);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/inbox/")) {
                inbox(exchange, path.substring("/inbox/".length()));
                return;
            }
            send(exchange, 404, "application/json", "{\"ok\":false}", null);
        } catch (RuntimeException ex) {
            send(exchange, 500, "application/json", "{\"ok\":false}", null);
        }
    }

    private void issueImage(HttpExchange exchange) throws IOException {
        Issued issued = issue();
        byte[] png = png(issued.mark(), issued.answer());
        challenges.add(new Challenge(issued.cookie(), issued.answer(), png));
        send(exchange, 200, "image/png", png, issued.cookie());
    }

    private void issueCode(HttpExchange exchange) throws IOException {
        Issued issued = issue();
        challenges.add(new Challenge(issued.cookie(), issued.answer(), null));
        String body = "{\"sent\":true}";
        lastSendBody = body;
        send(exchange, 200, "application/json", body, issued.cookie());
    }

    private Issued issue() {
        int mark = sequence.incrementAndGet();
        String cookie = "c+" + String.format("%04d", mark) + "sid";
        String answer = answer();
        synchronized (lock) {
            answers.put(cookie, answer);
        }
        return new Issued(cookie, answer, mark);
    }

    private String answer() {
        StringBuilder text = new StringBuilder();
        byte[] draw = new byte[5];
        random.nextBytes(draw);
        for (int i = 0; i < draw.length; i++) {
            text.append(ALPHABET.charAt((draw[i] & 0xff) % ALPHABET.length()));
            if (i == 1) {
                text.append('+');
            }
        }
        return text.toString();
    }

    private void login(HttpExchange exchange, String pathCode, byte[] raw) throws IOException {
        Map<String, String> form = form(raw);
        String cookie = cookie(exchange, "cap");
        String formCode = form.getOrDefault("code", "");
        String username = form.getOrDefault("username", "");
        String password = form.getOrDefault("password", "");
        String expected;
        String knownPassword;
        synchronized (lock) {
            expected = cookie == null ? null : answers.get(cookie);
            knownPassword = passwords.get(username);
        }
        boolean accepted = expected != null
                && expected.equals(pathCode)
                && expected.equals(formCode)
                && knownPassword != null
                && knownPassword.equals(password);
        String token = null;
        if (accepted) {
            token = "t+" + String.format("%04d", sequence.incrementAndGet()) + "tok";
            synchronized (lock) {
                sessions.put(token, new Session(username));
            }
        }
        logins.add(new LoginHit(username, cookie, pathCode, formCode, accepted, token));
        if (!accepted) {
            send(exchange, 401, "application/json", "{\"ok\":false}", null);
            return;
        }
        byte[] body = ("{\"token\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Set-Cookie", "auth=" + token + "; Path=/");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void inbox(HttpExchange exchange, String pathToken) throws IOException {
        String cookieToken = cookie(exchange, "auth");
        Session session;
        synchronized (lock) {
            session = cookieToken == null ? null : sessions.get(cookieToken);
        }
        boolean same = session != null && cookieToken.equals(pathToken);
        String subject = null;
        int status = 401;
        if (same) {
            synchronized (lock) {
                subject = subjects.get(session.username);
            }
            status = 200;
        }
        inboxes.add(new InboxHit(cookieToken, pathToken, status, subject));
        if (!same) {
            send(exchange, 401, "application/json", "{\"ok\":false}", null);
            return;
        }
        send(exchange, 200, "application/json", "{\"subject\":\"" + json(subject) + "\"}", null);
    }

    private static byte[] png(int mark, String answer) {
        byte[] text = answer.getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[PNG_MAGIC.length + 1 + text.length];
        System.arraycopy(PNG_MAGIC, 0, body, 0, PNG_MAGIC.length);
        body[PNG_MAGIC.length] = (byte) mark;
        System.arraycopy(text, 0, body, PNG_MAGIC.length + 1, text.length);
        return body;
    }

    private static Map<String, String> form(byte[] raw) {
        return split(new String(raw, StandardCharsets.UTF_8));
    }

    private static Map<String, String> split(String raw) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return fields;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                fields.put(decode(pair), "");
            } else {
                fields.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return fields;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String cookie(HttpExchange exchange, String name) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        ArrayList<String> found = new ArrayList<>();
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (name.equals(trimmed.substring(0, eq).trim())) {
                    found.add(trimmed.substring(eq + 1).trim());
                }
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return found.get(found.size() - 1);
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void send(HttpExchange exchange, int status, String type, String body, String cap) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8), cap);
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body, String cap) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (cap != null) {
            exchange.getResponseHeaders().add("Set-Cookie", "cap=" + cap + "; Path=/");
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private record Issued(String cookie, String answer, int mark) {
    }

    private static final class Session {
        private final String username;

        private Session(String username) {
            this.username = username;
        }
    }

    record Challenge(String cookie, String answer, byte[] image) {
    }

    record LoginHit(
            String username,
            String cookie,
            String pathCode,
            String formCode,
            boolean accepted,
            String token
    ) {
    }

    record InboxHit(String cookieToken, String pathToken, int status, String subject) {
    }
}
