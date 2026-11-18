package dev.hermit.crab.sync;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.hermit.crab.tls.TransportFailure;

final class ReefClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final AtomicReference<SyncSettings> settings;
    private final HttpClient client;

    ReefClient(AtomicReference<SyncSettings> settings, HttpClient client) {
        this.settings = settings;
        this.client = client;
    }

    private SyncSettings settings() {
        return settings.get();
    }

    ManifestFetch manifest(String ifNoneMatch) {
        HttpRequest.Builder builder = get(endpoint("agent/conches/manifest"));
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            builder.header("If-None-Match", ifNoneMatch);
        }
        HttpResponse<InputStream> response = send(builder);
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            String etag = response.headers().firstValue("ETag").orElse(null);
            if (status == 304) {
                return new ManifestFetch(true, etag, List.of());
            }
            if (status != 200) {
                throw new SyncException(SyncReasons.http(status));
            }
            byte[] bytes = readLimited(body, ConchFiles.MAX_ZIP_BYTES);
            if (bytes == null) {
                throw new SyncException(SyncReasons.ZIP_TOO_LARGE);
            }
            return new ManifestFetch(false, etag, parseManifest(bytes));
        } catch (IOException ex) {
            throw new SyncException("manifest unreadable");
        }
    }

    Optional<CrabRelease> version() {
        HttpResponse<InputStream> response = send(get(endpoint("agent/version")));
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            byte[] bytes = readLimited(body, ConchFiles.MAX_ZIP_BYTES);
            if (bytes == null) {
                return Optional.empty();
            }
            JsonNode root = JSON.readTree(bytes);
            if (root == null || !root.path("success").asBoolean(false) || !root.path("data").isObject()) {
                return Optional.empty();
            }
            JsonNode data = root.get("data");
            return Optional.of(new CrabRelease(textOrNull(data, "latestVersion"), textOrNull(data, "downloadUrl")));
        } catch (IOException ex) {
            return Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    Download download(String conch, String version) {
        HttpResponse<InputStream> response = send(get(endpoint("agent/conches/" + conch + "/" + version)));
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status != 200) {
                return Download.failed(status);
            }
            byte[] bytes = readLimited(body, ConchFiles.MAX_ZIP_BYTES);
            if (bytes == null) {
                return Download.oversize();
            }
            return Download.ok(bytes);
        } catch (IOException ex) {
            throw new SyncException("package unreadable");
        }
    }

    private HttpRequest.Builder get(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("X-Hermit-Instance", settings().instanceId().toString())
                .GET();
        settings().token().ifPresent(token -> builder.header("X-Hermit-Token", token));
        return builder;
    }

    private HttpResponse<InputStream> send(HttpRequest.Builder builder) {
        HttpRequest request = builder.build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SyncException("reef request interrupted");
        } catch (IOException ex) {
            URI uri = request.uri();
            TransportFailure.observe(ex, uri.getHost(), TransportFailure.portOf(uri));
            throw new SyncException("reef request failed", ex);
        }
    }

    private URI endpoint(String path) {
        String text = settings().reefUrl().toString();
        if (!text.endsWith("/")) {
            text = text + "/";
        }
        return URI.create(text).resolve(path);
    }

    static List<CatalogEntry> parseManifest(byte[] bytes) {
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException ex) {
            throw new SyncException("manifest rejected");
        }
        if (root == null || !root.path("success").asBoolean(false) || !root.path("data").isArray()) {
            throw new SyncException("manifest rejected");
        }
        List<CatalogEntry> entries = new ArrayList<>();
        for (JsonNode node : root.get("data")) {
            JsonNode credentials = node.get("requiredCredentials");
            boolean known = credentials != null && credentials.isArray();
            entries.add(new CatalogEntry(
                    textOrNull(node, "conch"),
                    textOrNull(node, "version"),
                    textOrNull(node, "sha256"),
                    textOrNull(node, "minCrabVersion"),
                    textOrNull(node, "title"),
                    textOrNull(node, "description"),
                    countOrZero(node),
                    known ? credentialNames(credentials) : List.of(),
                    known));
        }
        return entries;
    }

    private static List<String> credentialNames(JsonNode array) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : array) {
            if (item != null && item.isTextual()) {
                String name = item.asText();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static int countOrZero(JsonNode node) {
        if (node == null) {
            return 0;
        }
        JsonNode value = node.get("capabilityCount");
        if (value == null || value.isNull() || !value.isIntegralNumber()) {
            return 0;
        }
        return value.intValue();
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > max) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    record CatalogEntry(
            String conch,
            String version,
            String sha256,
            String minCrabVersion,
            String title,
            String description,
            int capabilityCount,
            List<String> requiredCredentials,
            boolean requiredCredentialsKnown
    ) {
        CatalogEntry {
            if (title == null || title.isBlank()) {
                title = conch == null ? "" : conch;
            }
            if (capabilityCount < 0) {
                capabilityCount = 0;
            }
            if (requiredCredentials == null) {
                requiredCredentials = List.of();
            } else {
                List<String> names = new ArrayList<>();
                for (String name : requiredCredentials) {
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
                names.sort(String::compareTo);
                requiredCredentials = List.copyOf(names);
            }
        }
    }

    record ManifestFetch(boolean notModified, String etag, List<CatalogEntry> entries) {
    }

    record Download(int status, byte[] body, boolean tooLarge) {
        static Download ok(byte[] body) {
            return new Download(200, body, false);
        }

        static Download oversize() {
            return new Download(200, null, true);
        }

        static Download failed(int status) {
            return new Download(status, null, false);
        }
    }
}
