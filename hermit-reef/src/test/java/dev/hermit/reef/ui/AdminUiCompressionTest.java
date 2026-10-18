package dev.hermit.reef.ui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hermit.reef.testsupport.ReefTestConfig;
import dev.hermit.reef.testsupport.TestDataDirs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 压缩发生在 Tomcat 里，MockMvc 看不到，所以这里起真实端口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReefTestConfig.class)
class AdminUiCompressionTest {

    private static final String SCRIPT = "console.log('hermit');\n".repeat(400);
    private static final Path ROOT = uiRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("hermit.reef.data-dir", () -> TestDataDirs.create("admin-ui-gzip").toString());
        registry.add("hermit.web.reef-root", () -> ROOT.toUri().toString());
    }

    @LocalServerPort
    int port;

    @Test
    void largeAssetsAreGzippedWhenTheBrowserAsks() throws Exception {
        HttpResponse<byte[]> gzipped = get("/assets/app.js", "gzip");
        assertThat(gzipped.statusCode()).isEqualTo(200);
        assertThat(gzipped.headers().firstValue("Content-Encoding")).hasValue("gzip");
        assertThat(gzipped.headers().firstValue("Cache-Control")).hasValue(AdminUiController.ASSET_CACHE);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped.body()))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(SCRIPT);
        }

        HttpResponse<byte[]> plain = get("/assets/app.js", null);
        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(new String(plain.body(), StandardCharsets.UTF_8)).isEqualTo(SCRIPT);
    }

    @Test
    void smallAssetsStayUncompressed() throws Exception {
        HttpResponse<byte[]> response = get("/assets/tiny.css", "gzip");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
    }

    private HttpResponse<byte[]> get(String path, String encoding) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (encoding != null) {
            request.header("Accept-Encoding", encoding);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Path uiRoot() {
        try {
            Path root = TestDataDirs.create("admin-ui-gzip-root");
            Files.createDirectories(root.resolve("assets"));
            Files.writeString(root.resolve("index.html"), "<div id=\"app\"></div>");
            Files.writeString(root.resolve("assets/app.js"), SCRIPT);
            Files.writeString(root.resolve("assets/tiny.css"), "body{margin:0}");
            return root;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
