package dev.hermit.reef.ui;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 管理端页面。除静态资源外，GET 固定返回 {@code index.html}，不按请求路径读别的文件。
 * {@code /api/**} 与 {@code /agent/**} 即使进到这里也不回 HTML：没有对应接口是 JSON {@code NOT_FOUND}，
 * 路径属于其它方法时仍是 405。
 */
@Controller
public class AdminUiController {

    static final String HTML = "text/html;charset=UTF-8";
    static final String PAGE_CACHE = "no-cache";
    static final String ASSET_CACHE = "public, max-age=31536000, immutable";

    private final ResourceLoader resourceLoader;
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private final String root;

    public AdminUiController(ResourceLoader resourceLoader,
            ObjectProvider<RequestMappingHandlerMapping> mappings,
            @Value("${hermit.web.reef-root:classpath:/hermit-web/reef-admin/}") String root) {
        this.resourceLoader = resourceLoader;
        this.mappings = mappings;
        this.root = root.endsWith("/") ? root : root + "/";
    }

    @GetMapping({"/", "/{*page}"})
    public void page(HttpServletRequest request, HttpServletResponse response)
            throws IOException, HttpRequestMethodNotSupportedException {
        String path = requestPath(request);
        if (legacyShells(path)) {
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader(HttpHeaders.LOCATION, "/conches");
            return;
        }
        if (reserved(path)) {
            // 这条 GET 兜底比「只有 POST 的接口」更宽。路径对得上别的方法时仍是 405，完全没有的才是 404。
            if (mappedForAnotherMethod(request)) {
                throw new HttpRequestMethodNotSupportedException(request.getMethod());
            }
            throw notFound();
        }
        byte[] body = read(root + "index.html");
        if (body == null) {
            throw notFound();
        }
        write(response, body, HTML, PAGE_CACHE);
    }

    static boolean legacyShells(String path) {
        return "/shells".equals(path) || path.startsWith("/shells/");
    }

    static boolean reserved(String path) {
        return under(path, "/api") || under(path, "/agent") || under(path, "/assets");
    }

    private static boolean under(String path, String prefix) {
        return prefix.equals(path) || path.startsWith(prefix + "/");
    }

    private boolean mappedForAnotherMethod(HttpServletRequest request) {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getObject().getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (AdminUiController.class.isAssignableFrom(handler.getBeanType())
                    && "page".equals(handler.getMethod().getName())) {
                continue;
            }
            RequestMappingInfo info = entry.getKey();
            if (!pathMatches(info, request)) {
                continue;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty() || methods.contains(RequestMethod.GET)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean pathMatches(RequestMappingInfo info, HttpServletRequest request) {
        if (info.getPathPatternsCondition() != null
                && info.getPathPatternsCondition().getMatchingCondition(request) != null) {
            return true;
        }
        return info.getPatternsCondition() != null && info.getPatternsCondition().getMatchingCondition(request) != null;
    }

    static String requestPath(HttpServletRequest request) {
        String servlet = request.getServletPath();
        String info = request.getPathInfo();
        String path = (servlet == null ? "" : servlet) + (info == null ? "" : info);
        if (path.isEmpty()) {
            path = request.getRequestURI();
            String context = request.getContextPath();
            if (context != null && !context.isEmpty() && path.startsWith(context)) {
                path = path.substring(context.length());
            }
        }
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    @GetMapping("/assets/{*path}")
    public void asset(@PathVariable("path") String path, HttpServletResponse response) throws IOException {
        // PathPattern 的 {*path} 会带上前导斜杠。
        String relative = path != null && path.startsWith("/") ? path.substring(1) : path;
        if (!safeAssetPath(relative)) {
            throw notFound();
        }
        byte[] body = read(root + "assets/" + relative);
        if (body == null) {
            throw notFound();
        }
        write(response, body, assetType(relative), ASSET_CACHE);
    }

    static boolean safeAssetPath(String relative) {
        if (relative == null || relative.isEmpty() || relative.charAt(0) == '/' || relative.endsWith("/")) {
            return false;
        }
        if (relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : relative.split("/", -1)) {
            if (!safeSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    private byte[] read(String location) throws IOException {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists() || !resource.isReadable()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private static void write(HttpServletResponse response, byte[] body, String contentType, String cacheControl)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CACHE_CONTROL, cacheControl);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static String assetType(String name) {
        String file = name;
        int slash = file.lastIndexOf('/');
        if (slash >= 0) {
            file = file.substring(slash + 1);
        }
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
            return "text/javascript";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return MediaTypeFactory.getMediaType(file).map(MediaType::toString).orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private static boolean safeSegment(String segment) {
        if (segment.isEmpty() || ".".equals(segment) || segment.contains("..")) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
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

    private static ApiException notFound() {
        return new ApiException(404, ReefErrorCode.NOT_FOUND, ReefErrorCode.NOT_FOUND.message());
    }
}
