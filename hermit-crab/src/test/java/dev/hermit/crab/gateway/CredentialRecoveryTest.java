package dev.hermit.crab.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import dev.hermit.common.model.ShellDoc;
import dev.hermit.common.parse.ShellParser;
import dev.hermit.crab.config.CrabConfig;
import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.runtime.CrabRuntime;
import dev.hermit.crab.secure.CredentialDecryptException;
import dev.hermit.crab.secure.MasterKeyException;
import dev.hermit.crab.secure.MasterKeyProvider;
import dev.hermit.crab.store.Actors;
import dev.hermit.crab.store.ApiKeyScope;
import dev.hermit.crab.store.CrabPaths;
import dev.hermit.crab.store.CreatedApiKey;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CredentialRecoveryTest {
    private static final String PASSWORD = "HermitLostKey-91c0aa";

    @TempDir
    Path temp;

    @Test
    void missingMasterKeyDoesNotReplaceExistingCredentials() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("lost"));
        CrabConfig config = CrabConfig.defaults();
        try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
            ShellDoc shell = ShellParser.parse(TestServer.shellYaml());
            runtime.registry().put(shell);
            runtime.admin().createAccount(shell.shell(), "alice", Actors.CLI);
            runtime.admin().writeCredentials(
                    shell, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CLI);
        }
        Files.delete(paths.masterKeyFile());

        Throwable thrown = catchThrowable(() -> CrabRuntime.open(paths, config, name -> null));

        assertThat(thrown).isInstanceOf(MasterKeyException.class);
        assertThat(thrown.getMessage()).contains(CredentialDecryptException.MESSAGE);
        assertThat(thrown.getMessage()).doesNotContain("MasterKeyException");
        SecretChecks.absent(thrown.getMessage(), PASSWORD);
        assertThat(paths.masterKeyFile()).doesNotExist();
    }

    @Test
    void freshInstallStillGeneratesMasterKey() {
        CrabPaths paths = new CrabPaths(temp.resolve("fresh"));
        try (CrabRuntime ignored = CrabRuntime.open(paths, CrabConfig.defaults(), name -> null)) {
            assertThat(Files.isRegularFile(paths.masterKeyFile())).isTrue();
        }
    }

    @Test
    void missingKeyWithoutCiphertextStillGenerates() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("empty"));
        CrabConfig config = CrabConfig.defaults();
        try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
            runtime.admin().createAccount("legacy_oa", "alice", Actors.CLI);
        }
        Files.delete(paths.masterKeyFile());

        try (CrabRuntime ignored = CrabRuntime.open(paths, config, name -> null)) {
            assertThat(Files.isRegularFile(paths.masterKeyFile())).isTrue();
        }
    }

    @Test
    void environmentKeyKeepsWorkingWhenTheFileIsAbsent() throws Exception {
        String encoded = encode((byte) 4);
        CrabPaths paths = new CrabPaths(temp.resolve("env"));
        CrabConfig config = CrabConfig.defaults();
        java.util.function.Function<String, String> env = name -> MasterKeyProvider.ENV.equals(name) ? encoded : null;
        try (CrabRuntime runtime = CrabRuntime.open(paths, config, env)) {
            assertThat(paths.masterKeyFile()).doesNotExist();
            ShellDoc shell = ShellParser.parse(TestServer.shellYaml());
            runtime.registry().put(shell);
            runtime.admin().createAccount(shell.shell(), "alice", Actors.CLI);
            runtime.admin().writeCredentials(
                    shell, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CLI);
        }
        assertThat(paths.masterKeyFile()).doesNotExist();

        try (CrabRuntime runtime = CrabRuntime.open(paths, config, env)) {
            assertThat(runtime.admin().credentialsUnreadable("legacy_oa", "alice")).isFalse();
        }

        Throwable thrown = catchThrowable(() -> CrabRuntime.open(paths, config, name -> null));
        assertThat(thrown).isInstanceOf(MasterKeyException.class);
        assertThat(thrown.getMessage()).contains(CredentialDecryptException.MESSAGE);
        assertThat(paths.masterKeyFile()).doesNotExist();
    }

    @Test
    void replacedMasterKeyIsReportedInsteadOfInternalError() throws Exception {
        CrabPaths paths = new CrabPaths(temp.resolve("replaced"));
        CrabConfig config = CrabConfig.defaults().withListen("127.0.0.1", 0);
        ShellDoc shell;
        try (CrabRuntime runtime = CrabRuntime.open(paths, config, name -> null)) {
            shell = ShellParser.parse(TestServer.shellYaml());
            runtime.registry().put(shell);
            runtime.admin().createAccount(shell.shell(), "alice", Actors.CLI);
            runtime.admin().writeCredentials(
                    shell, "alice", Map.of("username", "alice", "password", PASSWORD), Actors.CLI);
        }
        String replacement = encode((byte) 9);
        Files.writeString(paths.masterKeyFile(), replacement);

        Logger logger = Logger.getLogger(CrabRuntime.class.getName());
        List<String> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage() != null) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARNING);
        logger.addHandler(handler);
        CrabRuntime runtime = null;
        CrabHttpServer server = null;
        try {
            runtime = CrabRuntime.open(paths, config, name -> null);
            String logged = String.join("\n", warnings);
            assertThat(logged).contains(CredentialDecryptException.MESSAGE);
            SecretChecks.absent(logged, PASSWORD);
            SecretChecks.absent(logged, replacement);

            runtime.registry().put(shell);
            CreatedApiKey apiKey = runtime.admin().createApiKey(
                    "gateway", new ApiKeyScope(Set.of(), Set.of(), false), Actors.CLI);
            server = new CrabHttpServer(runtime);
            server.start();
            String base = "http://127.0.0.1:" + server.port();

            JsonNode accounts = console(base, "GET", "/console/api/shells/legacy_oa/accounts", null);
            assertThat(accounts.path("success").asBoolean()).as(accounts.toString()).isTrue();
            JsonNode alice = accounts.path("data").path("accounts").get(0);
            assertThat(alice.path("name").asText()).isEqualTo("alice");
            assertThat(alice.path("needsConfiguration").asBoolean()).isTrue();
            assertThat(alice.path("credentialsUnreadable").asBoolean()).isTrue();
            assertThat(alice.path("credentialMessage").asText()).isEqualTo(CredentialDecryptException.MESSAGE);
            assertThat(alice.path("missingFields")).isEmpty();

            JsonNode shells = console(base, "GET", "/console/api/shells", null);
            JsonNode row = shells.path("data").path("shells").get(0);
            assertThat(row.path("needsConfiguration").asBoolean()).isTrue();

            HttpResponse<String> called = request(base, "GET", "/api/legacy_oa/list_todo", null, apiKey.plaintext());
            assertThat(called.statusCode()).isEqualTo(409);
            JsonNode error = ApiJson.tree(called.body()).path("error");
            assertThat(error.path("code").asText()).isEqualTo("NOT_CONFIGURED");
            assertThat(error.path("message").asText()).isEqualTo(CredentialDecryptException.MESSAGE);
            assertThat(called.body()).doesNotContain("INTERNAL");
            assertThat(called.body()).doesNotContain("CredentialDecryptException");
            SecretChecks.absent(called.body(), PASSWORD);

            JsonNode rejected = console(base, "PUT", "/console/api/shells/legacy_oa/accounts/alice/credentials",
                    "{\"username\":\"alice\"}");
            assertThat(rejected.path("error").path("code").asText()).isEqualTo("INVALID_INPUT");
            assertThat(rejected.path("error").path("message").asText()).contains(CredentialDecryptException.MESSAGE);

            JsonNode rewritten = console(base, "PUT", "/console/api/shells/legacy_oa/accounts/alice/credentials",
                    "{\"username\":\"alice\",\"password\":\"replacement-value\"}");
            assertThat(rewritten.path("success").asBoolean()).isTrue();
            JsonNode after = console(base, "GET", "/console/api/shells/legacy_oa/accounts", null);
            assertThat(after.path("data").path("accounts").get(0).path("credentialsUnreadable").asBoolean()).isFalse();
            assertThat(after.path("data").path("accounts").get(0).path("needsConfiguration").asBoolean()).isFalse();
            assertThat(after.path("data").path("accounts").get(0).path("credentialMessage").isNull()).isTrue();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(previous);
            if (server != null) {
                server.close();
            }
            if (runtime != null) {
                runtime.close();
            }
        }
    }

    private static JsonNode console(String base, String method, String path, String body) throws Exception {
        HttpResponse<String> response = request(base, method, path, body, null);
        return ApiJson.tree(response.body());
    }

    private static HttpResponse<String> request(String base, String method, String path, String body, String apiKey)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        } else {
            builder.header("X-Hermit-Console", "1");
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String encode(byte fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        return Base64.getEncoder().encodeToString(key);
    }
}
