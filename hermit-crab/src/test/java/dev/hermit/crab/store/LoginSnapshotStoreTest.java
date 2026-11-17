package dev.hermit.crab.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hermit.common.parse.ConchParser;
import dev.hermit.crab.engine.SessionKey;
import dev.hermit.crab.engine.StoredCookie;
import dev.hermit.crab.secure.CredentialCipher;

class LoginSnapshotStoreTest {
    @TempDir
    java.nio.file.Path temp;

    @Test
    void decryptFailureDropsOnlyTheLoginRow() throws Exception {
        try (CrabDatabase database = new CrabDatabase(new CrabPaths(temp.resolve("crab")))) {
            CredentialCipher cipher = new CredentialCipher(new byte[32]);
            EncryptedSessionPersistence sessions = new EncryptedSessionPersistence(database.dataSource(), cipher);
            EncryptedLoginSnapshotStore logins = new EncryptedLoginSnapshotStore(database.dataSource(), cipher);
            SessionKey key = new SessionKey(Origin.OFFICIAL, "sample_login", "alice");
            SessionKey other = new SessionKey(Origin.LOCAL, "sample_login", "alice");
            logins.save(key, Map.of("token", "Tok+1Value"));
            logins.save(other, Map.of("token", "other-token"));
            assertThat(logins.load(key)).containsEntry("token", "Tok+1Value");
            assertThat(logins.load(other)).containsEntry("token", "other-token");

            sessions.save(key, List.of(new StoredCookie(
                    "sid", "sid-cookie-value", "127.0.0.1", "/", null, false, false)));
            byte[] sessionCipher = queryBytes(database, "SELECT ciphertext FROM sessions");
            try (Connection connection = database.dataSource().getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM session_login WHERE origin = 'OFFICIAL'");
                statement.executeUpdate(
                        "INSERT INTO session_login (origin, conch, account, ciphertext, updated_at) VALUES ("
                                + "'OFFICIAL', 'sample_login', 'alice', X'"
                                + hex(sessionCipher)
                                + "', CURRENT_TIMESTAMP)");
            }
            assertThat(logins.load(key)).isEmpty();
            assertThat(count(database, "SELECT COUNT(*) FROM session_login WHERE origin = 'OFFICIAL'")).isZero();
            assertThat(sessions.load(key)).hasSize(1);
            assertThat(logins.load(other)).containsEntry("token", "other-token");
        }
    }

    @Test
    void canonicalJsonOmitsAnEmptyCookieMap() throws Exception {
        ObjectMapper mapper = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        String plain = mapper.writeValueAsString(ConchParser.parse("""
                conch: sample_login
                version: 1.0.0
                driver: http
                min_crab_version: 1.0.0
                title: 标题
                description: 说明
                credentials: {}
                actions:
                  read:
                    title: 读取
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set: { marker: ok }
                """));
        assertThat(plain).doesNotContain("cookies");
        String aliased = mapper.writeValueAsString(ConchParser.parse("""
                conch: sample_login
                version: 1.0.0
                driver: http
                min_crab_version: 1.3.0
                title: 标题
                description: 说明
                credentials: {}
                auth:
                  cookies: { xsrf: XSRF-TOKEN }
                actions:
                  read:
                    title: 读取
                    description: 说明
                    output:
                      value: { type: string, description: 结果, value: ok }
                    examples:
                      - input: {}
                        output: { value: ok }
                    notes: 注意
                    steps:
                      - set: { marker: ok }
                """));
        assertThat(aliased).contains("XSRF-TOKEN");
    }

    private static byte[] queryBytes(CrabDatabase database, String sql) throws Exception {
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getBytes(1);
        }
    }

    private static int count(CrabDatabase database, String sql) throws Exception {
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
