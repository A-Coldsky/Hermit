package dev.hermit.it;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HexFormat;

import javax.crypto.Cipher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 本机 BOS 登录。2048 位测试密钥在进程内生成，不读取真实站点、邮箱或密钥文件。
 * 公钥接口返回小写 hex 模数。登录口令是 PKCS#1 v1.5 密文，用测试私钥解回明文再比对。
 * 列表同时核对查询串 {@code sid}、路径末段和 Cookie，三者必须是同一个仍有效的会话。
 */
final class MockBosServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final RSAPrivateKey privateKey;
    private final String modulusHex;
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private final Map<String, String> subjects = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final AtomicInteger sidCounter = new AtomicInteger();
    private final AtomicInteger forcedListFailures = new AtomicInteger();
    private final CopyOnWriteArrayList<PublicKeyHit> publicKeys = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LoginHit> logins = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ListHit> lists = new CopyOnWriteArrayList<>();

    MockBosServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        modulusHex = ((RSAPublicKey) pair.getPublic()).getModulus().toString(16);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mock-bos");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void allow(String username, String password, String subject) {
        passwords.put(username, password);
        subjects.put(username, subject);
    }

    void invalidate(String sid) {
        Session session = sessions.get(sid);
        if (session != null) {
            session.alive = false;
        }
    }

    /** 接下来这么多次列表都返回 401，不论 sid 是否仍有效。 */
    void failNextLists(int count) {
        forcedListFailures.set(count);
    }

    List<PublicKeyHit> publicKeys() {
        return List.copyOf(publicKeys);
    }

    List<LoginHit> logins() {
        return List.copyOf(logins);
    }

    List<ListHit> lists() {
        return List.copyOf(lists);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] raw = exchange.getRequestBody().readAllBytes();
        try {
            if ("/webmail/common/getloginrsapublickey.do".equals(path)) {
                publicKey(exchange);
                return;
            }
            if ("/webmail/login/loginapi.do".equals(path)) {
                login(exchange, raw);
                return;
            }
            if (path.equals("/RmWeb/mail") || path.startsWith("/RmWeb/mail/")) {
                list(exchange, path);
                return;
            }
            respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}", null);
        } catch (RuntimeException ex) {
            respond(exchange, 500, "{\"code\":\"ERROR\"}", null);
        }
    }

    private void publicKey(HttpExchange exchange) throws IOException {
        String rnd = query(exchange).getOrDefault("rnd", "");
        publicKeys.add(new PublicKeyHit(rnd));
        respond(exchange, 200, "{\"code\":\"S_OK\",\"var\":\"" + modulusHex + "\"}", null);
    }

    private void login(HttpExchange exchange, byte[] raw) throws IOException {
        Map<String, String> form = form(raw);
        String username = form.getOrDefault("usernumber", "");
        String ciphertext = form.getOrDefault("password", "");
        String loginType = form.getOrDefault("loginType", "");
        String plain = null;
        boolean decrypted = false;
        if ("WEB".equals(loginType)) {
            try {
                plain = decrypt(ciphertext);
                decrypted = true;
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                decrypted = false;
            }
        }
        String expected = passwords.get(username);
        boolean accepted = decrypted && expected != null && expected.equals(plain);
        String sid = null;
        if (accepted) {
            sid = "s+" + String.format("%04d", sidCounter.incrementAndGet()) + "tok";
            sessions.put(sid, new Session(username, true));
        }
        logins.add(new LoginHit(username, plain, ciphertext, sid, accepted));
        if (!accepted) {
            respond(exchange, 401, "{\"code\":\"FA_INVALID_PASSWORD\"}", null);
            return;
        }
        respond(exchange, 200, "{\"code\":\"S_OK\"}", sid);
    }

    private void list(HttpExchange exchange, String path) throws IOException {
        Map<String, String> query = query(exchange);
        String querySid = query.get("sid");
        String cookieSid = cookie(exchange, "sid");
        String pathSid = pathSid(path);
        String func = query.get("func");
        if (forcedListFailures.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
            lists.add(new ListHit(cookieSid, querySid, pathSid, 401, userOf(cookieSid), null));
            respond(exchange, 401, leak(cookieSid, querySid), null);
            return;
        }
        Session session = cookieSid == null ? null : sessions.get(cookieSid);
        boolean same = session != null
                && session.alive
                && cookieSid.equals(querySid)
                && cookieSid.equals(pathSid)
                && "mbox:listMessages".equals(func);
        if (!same) {
            lists.add(new ListHit(cookieSid, querySid, pathSid, 401, userOf(cookieSid), null));
            respond(exchange, 401, leak(cookieSid, querySid), null);
            return;
        }
        String subject = subjects.getOrDefault(session.username, "");
        lists.add(new ListHit(cookieSid, querySid, pathSid, 200, session.username, subject));
        respond(exchange, 200, successBody(subject, session.username, cookieSid), null);
    }

    private String userOf(String sid) {
        Session session = sid == null ? null : sessions.get(sid);
        return session == null ? null : session.username;
    }

    private static String pathSid(String path) {
        String prefix = "/RmWeb/mail/";
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            return "";
        }
        return path.substring(prefix.length());
    }

    private String successBody(String subject, String username, String sid) {
        String password = passwords.getOrDefault(username, "");
        return "{\"code\":\"S_OK\",\"subject\":\"" + json(subject) + "\""
                + echoes(password, sid)
                + "}";
    }

    private String leak(String cookieSid, String querySid) {
        Session session = cookieSid == null ? null : sessions.get(cookieSid);
        String username = session == null ? "" : session.username;
        String password = passwords.getOrDefault(username, "");
        String sid = querySid == null ? "" : querySid;
        return "{\"code\":\"FA_INVALID_SESSION\"" + echoes(password, sid) + "}";
    }

    private static String echoes(String password, String sid) {
        return ",\"echoPassword\":\"" + json(password) + "\""
                + ",\"echoPasswordEncoded\":\"" + json(url(password)) + "\""
                + ",\"echoSid\":\"" + json(sid) + "\""
                + ",\"echoSidEncoded\":\"" + json(url(sid)) + "\"";
    }

    private String decrypt(String hex) throws GeneralSecurityException {
        byte[] decoded = HexFormat.of().parseHex(hex);
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(HttpExchange exchange) {
        return split(exchange.getRequestURI().getRawQuery());
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
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (name.equals(trimmed.substring(0, eq).trim())) {
                    String value = trimmed.substring(eq + 1).trim();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            }
        }
        return null;
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body, String sid) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (sid != null) {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=" + sid + "; Path=/");
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class Session {
        private final String username;
        private volatile boolean alive;

        private Session(String username, boolean alive) {
            this.username = username;
            this.alive = alive;
        }
    }

    record PublicKeyHit(String rnd) {
    }

    record LoginHit(String username, String password, String ciphertext, String sid, boolean accepted) {
    }

    record ListHit(
            String cookieSid,
            String querySid,
            String pathSid,
            int status,
            String username,
            String subject
    ) {
    }

}
