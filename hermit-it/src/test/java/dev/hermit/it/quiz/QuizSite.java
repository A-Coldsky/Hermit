package dev.hermit.it.quiz;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** 写规则测验用的本机模拟站点。每次试跑新开一个，状态不带到下一次。 */
final class QuizSite implements AutoCloseable {

    private final HttpServer server;
    private final List<String> hits = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger reportCalls = new AtomicInteger();
    private volatile boolean agreed;
    private volatile boolean loggedIn;

    QuizSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 按顺序的「方法 路径」，不含查询串。 */
    List<String> hits() {
        return List.copyOf(hits);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = pairs(exchange.getRequestURI().getRawQuery());
        Map<String, String> form = pairs(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        hits.add(method + " " + path);
        try {
            route(exchange, method, path, query, form);
        } catch (RuntimeException ex) {
            send(exchange, 400, "text/plain", "bad request");
        }
    }

    private void route(
            HttpExchange exchange, String method, String path, Map<String, String> query, Map<String, String> form)
            throws IOException {
        switch (method + " " + path) {
            case "GET /api/ping" -> json(exchange, 200, "{\"state\":\"ok\",\"node\":\"n1\"}");
            case "GET /notices" -> html(exchange, """
                    <table id="notices"><tr><th>编号</th><th>标题</th></tr>
                    <tr class="row"><td>N1</td><td>放假通知</td></tr>
                    <tr class="row"><td>N2</td><td>系统升级</td></tr></table>""");
            case "POST /order/submit" -> {
                String id = form.get("id");
                if ("A1".equals(id)) {
                    html(exchange, "<p>金额较大，请再次确认</p>");
                } else {
                    json(exchange, 200, "{\"order_no\":\"SO-" + id + "\"}");
                }
            }
            case "POST /order/confirm" -> json(exchange, 200, "{\"order_no\":\"SO-" + form.get("id") + "-C\"}");
            case "GET /profile" -> json(exchange, 200,
                    "{\"level\":\"" + ("ann".equals(query.get("user")) ? "vip" : "normal") + "\"}");
            case "GET /vip/quota" -> json(exchange, 200, "{\"quota\":500}");
            case "GET /quota" -> json(exchange, 200, "{\"quota\":100}");
            case "GET /list" -> {
                int page = Integer.parseInt(query.getOrDefault("page", "1"));
                StringBuilder rows = new StringBuilder("<table>");
                for (int i = 1; i <= 2; i++) {
                    rows.append("<tr class=\"item\"><td class=\"id\">L").append(page).append(i)
                            .append("</td><td class=\"name\">条目").append(page).append(i).append("</td></tr>");
                }
                rows.append("</table>");
                if (page < 3) {
                    rows.append("<a class=\"next\" href=\"/list?page=").append(page + 1).append("\">下一页</a>");
                }
                html(exchange, rows.toString());
            }
            case "GET /feed" -> {
                String cursor = query.getOrDefault("cursor", "");
                Map<String, String> next = new LinkedHashMap<>();
                next.put("start", "c2");
                next.put("c2", "c3");
                if (!"start".equals(cursor) && !next.containsValue(cursor)) {
                    json(exchange, 400, "{\"error\":\"bad cursor\"}");
                    return;
                }
                String after = next.get(cursor);
                json(exchange, 200, "{\"items\":[{\"id\":\"F-" + cursor + "-1\"},{\"id\":\"F-" + cursor + "-2\"}],"
                        + "\"next_cursor\":" + (after == null ? "null" : "\"" + after + "\"") + "}");
            }
            case "GET /big" -> {
                int page = Integer.parseInt(query.getOrDefault("page", "1"));
                json(exchange, 200, "{\"rows\":[{\"id\":\"B" + page + "a\"},{\"id\":\"B" + page + "b\"}],"
                        + "\"has_more\":" + (page < 50) + "}");
            }
            case "GET /report" -> {
                if (reportCalls.incrementAndGet() <= 2) {
                    send(exchange, 503, "text/plain", "busy");
                } else {
                    json(exchange, 200, "{\"total\":42}");
                }
            }
            case "POST /login" -> {
                if (!"S3cret!pwd".equals(form.get("password"))) {
                    html(exchange, "<p>密码错误</p>");
                    return;
                }
                loggedIn = true;
                exchange.getResponseHeaders().add("Set-Cookie", "SID=quiz-session-1; Path=/");
                html(exchange, agreed ? "<p>欢迎</p>" : "<p>首次登录，请先同意用户协议</p>");
            }
            case "POST /agree" -> {
                agreed = true;
                html(exchange, "<p>已同意</p>");
            }
            case "GET /me" -> {
                String cookie = exchange.getRequestHeaders().getFirst("Cookie");
                if (!loggedIn || cookie == null || !cookie.contains("SID=quiz-session-1")) {
                    send(exchange, 401, "text/plain", "login required");
                } else if (!agreed) {
                    send(exchange, 403, "text/plain", "agreement required");
                } else {
                    json(exchange, 200, "{\"name\":\"张三\"}");
                }
            }
            default -> send(exchange, 404, "text/plain", "not found");
        }
    }

    private static Map<String, String> pairs(String raw) {
        Map<String, String> pairs = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return pairs;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            pairs.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return pairs;
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", body);
    }

    private static void html(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "text/html; charset=utf-8", "<html><body>" + body + "</body></html>");
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
