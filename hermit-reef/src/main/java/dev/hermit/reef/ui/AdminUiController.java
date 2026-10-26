package dev.hermit.reef.ui;

import dev.hermit.reef.web.ApiException;
import dev.hermit.reef.web.ReefErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class AdminUiController {

    static final String HTML = "text/html;charset=UTF-8";
    static final String PAGE_CACHE = "no-cache";
    static final String ASSET_CACHE = "public, max-age=31536000, immutable";

    private final ResourceLoader resourceLoader;
    private final String root;

    public AdminUiController(ResourceLoader resourceLoader,
            @Value("${hermit.web.reef-root:classpath:/hermit-web/reef-admin/}") String root) {
        this.resourceLoader = resourceLoader;
        this.root = root.endsWith("/") ? root : root + "/";
    }

    @GetMapping({
            "/",
            "/login",
            "/conches",
            "/conches/upload",
            "/conches/{conch}",
            "/tide",
            "/alerts",
            "/audit",
            "/settings"
    })
    public void page(HttpServletResponse response) throws IOException {
        byte[] body = read(root + "index.html");
        if (body == null) {
            throw notFound();
        }
        write(response, body, HTML, PAGE_CACHE);
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
