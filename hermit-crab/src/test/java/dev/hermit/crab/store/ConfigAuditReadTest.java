package dev.hermit.crab.store;

import dev.hermit.crab.runtime.ApiJson;
import dev.hermit.crab.testing.SecretChecks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigAuditReadTest {
    private static final String PASSWORD = "HermitAuditSecret/91c0+e77b2";
    private static final String MARKER = "HermitAuditCiphertext-marker-c91e";

    @TempDir
    Path temp;

    private StoreFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = StoreFixture.open(temp);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void pageDropsSecretDetailAndKeepsFieldNames() throws Exception {
        fixture.accounts.create(Origin.OFFICIAL, "oa", "alice", Actors.CLI);
        fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("password", PASSWORD, "token", ""), Actors.CLI);
        byte[] ciphertext = ciphertext();
        String encoded = Base64.getEncoder().encodeToString(ciphertext);
        insert(
                Instant.parse("2020-01-01T00:00:00Z"),
                Actors.CONSOLE,
                "CREDENTIALS_WRITTEN",
                "OFFICIAL/oa/alice",
                "{\"fields\":[\"password\"],\"value\":\"" + json(PASSWORD) + "\",\"ciphertext\":\"" + MARKER + "\"}");
        insert(
                Instant.parse("2020-01-01T00:00:01Z"),
                Actors.CONSOLE,
                "CONFIG_PUT",
                "OFFICIAL/oa",
                PASSWORD);
        AuditLog.record(
                fixture.dataSource,
                Actors.SYNC,
                AuditKind.CREDENTIAL_DECLARATION,
                "oa",
                "{\"addedRequired\":[\"otp\"],\"removed\":[\"pin\"],\"secret\":\"" + json(PASSWORD) + "\"}");
        AuditLog.record(
                fixture.dataSource,
                Actors.CLI,
                AuditKind.API_KEY_CREATED,
                "apikey/abc",
                "{\"id\":\"abc\",\"name\":\"ci\",\"plaintext\":\"" + json(PASSWORD) + "\"}");
        AuditLog.record(
                fixture.dataSource,
                Actors.CONSOLE,
                AuditKind.CAPABILITY_DISABLED,
                "OFFICIAL/oa/list_todo",
                "{\"disabled\":true,\"token\":\"" + json(PASSWORD) + "\"}");
        AuditLog.record(fixture.dataSource, Actors.CONSOLE, AuditKind.DEBUG_CHANGED, "debug", "{\"enabled\":true}");
        AuditLog.record(fixture.dataSource, Actors.CLI, AuditKind.SYNC_REQUESTED, "sync", "{}");

        AuditLog.Page page = AuditLog.page(fixture.dataSource, null, null, 1, 50);
        String json = ApiJson.write(page);
        SecretChecks.absent(json, PASSWORD);
        SecretChecks.absent(json, java.net.URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8));
        SecretChecks.absent(json, MARKER);
        SecretChecks.absent(json, encoded);
        SecretChecks.absent(json, HexFormat.of().formatHex(ciphertext));
        assertThat(json).doesNotContain("\"detail\"").doesNotContain("ciphertext").doesNotContain("plaintext");
        assertThat(json).doesNotContain("enabled");

        AuditLog.Entry credentials = find(page, "CREDENTIALS_WRITTEN", "alice");
        assertThat(credentials.fields()).contains("password", "token");
        assertThat(credentials.origin()).isEqualTo("OFFICIAL");
        assertThat(credentials.conch()).isEqualTo("oa");
        assertThat(credentials.actor()).isEqualTo(Actors.CLI);

        AuditLog.Entry poisoned = find(page, "CREDENTIALS_WRITTEN", "alice");
        assertThat(page.entries().stream()
                .filter(entry -> "CREDENTIALS_WRITTEN".equals(entry.action()))
                .flatMap(entry -> entry.fields().stream()))
                .contains("password")
                .doesNotContain(PASSWORD);

        AuditLog.Entry raw = page.entries().stream()
                .filter(entry -> "CONFIG_PUT".equals(entry.action()) && entry.fields().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(raw.conch()).isEqualTo("oa");
        assertThat(raw.account()).isNull();

        AuditLog.Entry declaration = find(page, "CREDENTIAL_DECLARATION", null);
        assertThat(declaration.origin()).isNull();
        assertThat(declaration.conch()).isEqualTo("oa");
        assertThat(declaration.fields()).containsExactly("otp", "pin");

        AuditLog.Entry apiKey = find(page, "API_KEY_CREATED", null);
        assertThat(apiKey.conch()).isNull();
        assertThat(apiKey.account()).isNull();
        assertThat(apiKey.fields()).isEmpty();

        AuditLog.Entry capability = find(page, "CAPABILITY_DISABLED", null);
        assertThat(capability.conch()).isEqualTo("oa");
        assertThat(capability.account()).isNull();
        assertThat(capability.fields()).containsExactly("list_todo");

        assertThat(find(page, "DEBUG_CHANGED", null).fields()).isEmpty();
        assertThat(find(page, "SYNC_REQUESTED", null).conch()).isNull();
        assertThat(poisoned).isNotNull();
    }

    @Test
    void filtersByConchAndAccountAndPagesNewestFirst() throws Exception {
        Instant tie = Instant.parse("2024-01-01T00:00:00Z");
        insert(tie, Actors.CLI, "CONFIG_PUT", "OFFICIAL/oa", "{\"key\":\"first\"}");
        insert(tie, Actors.CLI, "CONFIG_PUT", "OFFICIAL/oa", "{\"key\":\"second\"}");
        insert(Instant.parse("2024-01-02T00:00:00Z"), Actors.CLI, "ACCOUNT_CREATED", "OFFICIAL/oa/alice", "{\"account\":\"alice\"}");
        insert(Instant.parse("2024-01-03T00:00:00Z"), Actors.CLI, "ACCOUNT_CREATED", "OFFICIAL/oa_extra/alice", "{\"account\":\"alice\"}");
        insert(Instant.parse("2024-01-04T00:00:00Z"), Actors.CLI, "ACCOUNT_CREATED", "LOCAL/oa/bob", "{\"account\":\"bob\"}");
        insert(Instant.parse("2024-01-05T00:00:00Z"), Actors.CLI, "CAPABILITY_ENABLED", "OFFICIAL/oa/list_todo", "{\"disabled\":false}");
        insert(Instant.parse("2024-01-06T00:00:00Z"), Actors.SYNC, "CREDENTIAL_DECLARATION", "oa", "{\"addedRequired\":[\"otp\"]}");
        insert(Instant.parse("2024-01-07T00:00:00Z"), Actors.CLI, "API_KEY_CREATED", "apikey/abc", "{\"id\":\"abc\"}");

        AuditLog.Page newest = AuditLog.page(fixture.dataSource, "oa", null, 1, 1);
        assertThat(newest.total()).isEqualTo(6);
        assertThat(newest.entries()).hasSize(1);
        assertThat(newest.entries().get(0).action()).isEqualTo("CREDENTIAL_DECLARATION");

        AuditLog.Page tied = AuditLog.page(fixture.dataSource, "oa", null, 1, 50);
        assertThat(tied.entries()).extracting(AuditLog.Entry::action).containsExactly(
                "CREDENTIAL_DECLARATION",
                "CAPABILITY_ENABLED",
                "ACCOUNT_CREATED",
                "ACCOUNT_CREATED",
                "CONFIG_PUT",
                "CONFIG_PUT");
        assertThat(tied.entries().get(2).account()).isEqualTo("bob");
        assertThat(tied.entries().get(2).origin()).isEqualTo("LOCAL");
        assertThat(tied.entries().get(3).account()).isEqualTo("alice");
        assertThat(tied.entries().get(4).fields()).containsExactly("second");
        assertThat(tied.entries().get(5).fields()).containsExactly("first");
        assertThat(tied.entries()).extracting(AuditLog.Entry::conch).containsOnly("oa");

        AuditLog.Page alice = AuditLog.page(fixture.dataSource, "oa", "alice", 1, 50);
        assertThat(alice.total()).isEqualTo(1);
        assertThat(alice.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.account()).isEqualTo("alice");
            assertThat(entry.origin()).isEqualTo("OFFICIAL");
            assertThat(entry.action()).isEqualTo("ACCOUNT_CREATED");
        });

        AuditLog.Page bob = AuditLog.page(fixture.dataSource, null, "bob", 1, 50);
        assertThat(bob.entries()).extracting(AuditLog.Entry::origin).containsExactly("LOCAL");

        AuditLog.Page extra = AuditLog.page(fixture.dataSource, "oa_extra", null, 1, 50);
        assertThat(extra.total()).isEqualTo(1);
        assertThat(extra.entries().get(0).conch()).isEqualTo("oa_extra");

        AuditLog.Page first = AuditLog.page(fixture.dataSource, "oa", null, 1, 2);
        AuditLog.Page second = AuditLog.page(fixture.dataSource, "oa", null, 2, 2);
        AuditLog.Page third = AuditLog.page(fixture.dataSource, "oa", null, 3, 2);
        AuditLog.Page past = AuditLog.page(fixture.dataSource, "oa", null, 4, 2);
        assertThat(first.entries()).extracting(AuditLog.Entry::action)
                .containsExactly("CREDENTIAL_DECLARATION", "CAPABILITY_ENABLED");
        assertThat(second.entries()).extracting(AuditLog.Entry::account).containsExactly("bob", "alice");
        assertThat(third.entries()).extracting(AuditLog.Entry::fields).containsExactly(
                java.util.List.of("second"), java.util.List.of("first"));
        assertThat(past.entries()).isEmpty();
        assertThat(past.total()).isEqualTo(6);
        assertThat(first.entries().get(0).id()).isGreaterThan(second.entries().get(0).id());

        assertThatThrownBy(() -> AuditLog.page(fixture.dataSource, "oa", null, 0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("页码超出范围。");
        assertThatThrownBy(() -> AuditLog.page(fixture.dataSource, "oa", null, Integer.MAX_VALUE, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("页码超出范围。");
        assertThatThrownBy(() -> AuditLog.page(fixture.dataSource, "oa", null, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("每页条数超出范围。");
        assertThatThrownBy(() -> AuditLog.page(fixture.dataSource, "oa", null, 1, 201))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("每页条数超出范围。");
        assertThatThrownBy(() -> AuditLog.page(fixture.dataSource, "oa/extra", null, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("规则包名称无效。");
        assertThatThrownBy(() -> AuditLog.page(fixture.dataSource, null, "bad name", 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("账号名只能包含字母、数字、下划线和连字符，长度 1 到 64。");
    }

    private static AuditLog.Entry find(AuditLog.Page page, String action, String account) {
        return page.entries().stream()
                .filter(entry -> action.equals(entry.action()) && java.util.Objects.equals(account, entry.account()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void purgeDropsOnlyEntriesOlderThanTheCutoff() throws Exception {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        insert(cutoff.minusSeconds(1), Actors.CONSOLE, "SYNC_REQUESTED", "sync", "{}");
        insert(cutoff, Actors.CONSOLE, "DEBUG_CHANGED", "debug", "{}");

        assertThat(AuditLog.purgeBefore(fixture.dataSource, cutoff)).isEqualTo(1);

        AuditLog.Page page = AuditLog.page(fixture.dataSource, null, null, 1, 50);
        assertThat(page.entries()).extracting(AuditLog.Entry::action).containsExactly("DEBUG_CHANGED");
    }

    @Test
    void atomicWritesAcrossStoresRollBackTogether() {
        AccountStore accounts = fixture.accounts;
        assertThatThrownBy(() -> ConchLocks.atomic(fixture.dataSource, Origin.OFFICIAL, "oa", () -> {
            accounts.create(Origin.OFFICIAL, "oa", "alice", Actors.CLI);
            fixture.vault.write(Origin.OFFICIAL, "oa", "alice", Map.of("password", PASSWORD), Actors.CLI);
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(accounts.list(Origin.OFFICIAL, "oa")).isEmpty();
        assertThat(AuditLog.page(fixture.dataSource, null, null, 1, 50).entries()).isEmpty();
        // 事务结束后同一线程的写入照常各自提交。
        accounts.create(Origin.OFFICIAL, "oa", "alice", Actors.CLI);
        assertThat(accounts.list(Origin.OFFICIAL, "oa")).hasSize(1);
    }

    private void insert(Instant at, String actor, String kind, String target, String detail) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO config_audit (at, actor, kind, target, detail)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            Db.setInstant(statement, 1, at);
            statement.setString(2, actor);
            statement.setString(3, kind);
            statement.setString(4, target);
            statement.setString(5, detail);
            statement.executeUpdate();
        }
    }

    private byte[] ciphertext() throws Exception {
        try (Connection connection = fixture.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ciphertext FROM credentials WHERE field = 'password'");
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            byte[] bytes = resultSet.getBytes(1);
            assertThat(bytes).isNotEmpty();
            return bytes;
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
