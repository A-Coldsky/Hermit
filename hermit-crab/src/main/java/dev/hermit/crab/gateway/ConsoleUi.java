package dev.hermit.crab.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import dev.hermit.common.error.ErrorCode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

/**
 * Crab 本机页面。静态资源名只接受单段 {@code [A-Za-z0-9._-]}，拒绝 {@code ..}，避免 classpath 穿越。
 * 其它 {@code GET /console/**} 固定读 {@code index.html}，不把请求路径拼进资源名。
 */
final class ConsoleUi {
    static final String ROOT_PROPERTY = "hermit.web.crab-root";
    private static final String DEFAULT_ROOT = "hermit-web/crab-console/";
    private static final String HTML = "text/html;charset=UTF-8";
    private static final String PAGE_CACHE = "no-cache";
    private static final String ASSET_CACHE = "public, max-age=31536000, immutable";

    private ConsoleUi() {
    }

    static void register(Javalin http) {
        // Javalin 默认忽略末尾斜杠，/console 与 /console/ 会进同一条路由。用原始 URI 区分 302 和页面。
        http.get("/console", ctx -> {
            String uri = ctx.req().getRequestURI();
            if (uri != null && uri.endsWith("/console/")) {
                page(ctx);
                return;
            }
            ctx.redirect("/console/", HttpStatus.FOUND);
        });
        http.get("/console/assets/{name}", ctx -> asset(ctx));
        // <> 吃掉斜杠，一条路由覆盖多段深链。注册在具体路由之后；api 与 assets 再挡一层。
        http.get("/console/<path>", ctx -> fallback(ctx));
    }

    private static void fallback(Context ctx) throws IOException {
        String uri = ctx.req().getRequestURI();
        String path = ctx.path();
        if (traversal(uri) || traversal(path)) {
            GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, "not found");
            return;
        }
        if (apiPath(path) || assetPath(path)) {
            GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, "not found");
            return;
        }
        if (shellsPath(path)) {
            ctx.redirect("/console/conches", HttpStatus.FOUND);
            return;
        }
        page(ctx);
    }

    private static boolean apiPath(String path) {
        return "/console/api".equals(path) || path.startsWith("/console/api/");
    }

    private static boolean assetPath(String path) {
        return "/console/assets".equals(path) || path.startsWith("/console/assets/");
    }

    private static boolean shellsPath(String path) {
        return "/console/shells".equals(path) || path.startsWith("/console/shells/");
    }

    private static boolean traversal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("..") || lower.contains("%2e%2e");
    }

    private static void page(Context ctx) throws IOException {
        byte[] body = read(root() + "index.html");
        if (body == null) {
            GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, "not found");
            return;
        }
        GatewayResponses.writeBytes(ctx, 200, HTML, PAGE_CACHE, body);
    }

    private static void asset(Context ctx) throws IOException {
        String name = ctx.pathParam("name");
        String uri = ctx.req().getRequestURI();
        if (!safeFileName(name) || (uri != null && uri.contains(".."))) {
            GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, "not found");
            return;
        }
        byte[] body = read(root() + "assets/" + name);
        if (body == null) {
            GatewayResponses.writeError(ctx, 404, ErrorCode.NOT_FOUND, "not found");
            return;
        }
        GatewayResponses.writeBytes(ctx, 200, assetType(name), ASSET_CACHE, body);
    }

    static String root() {
        String configured = System.getProperty(ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_ROOT;
        }
        return configured.endsWith("/") ? configured : configured + "/";
    }

    static boolean safeFileName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            return false;
        }
        if (".".equals(name)) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '.'
                    || c == '_'
                    || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static byte[] read(String resource) throws IOException {
        try (InputStream in = ConsoleUi.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        }
    }

    private static String assetType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
            return "text/javascript";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".json") || lower.endsWith(".map")) {
            return "application/json";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".woff")) {
            return "font/woff";
        }
        if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (lower.endsWith(".wasm")) {
            return "application/wasm";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain;charset=UTF-8";
        }
        return "application/octet-stream";
    }
}
