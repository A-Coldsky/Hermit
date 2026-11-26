package dev.hermit.crab.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reef 清单可以多带字段。旧 Crab 只读自己认识的名字，不能因为 {@code driver} 或 {@code upstreamHost} 拒绝整份清单。
 */
class ManifestCompatibilityTest {

    @Test
    void parseManifestIgnoresDriverUpstreamHostAndNestedUnknownFields() {
        List<ReefClient.CatalogEntry> entries = ReefClient.parseManifest("""
                {"success":true,"traceId":"ignored","data":[
                  {
                    "conch":"legacy_oa","version":"1.0.0","sha256":"aa","minCrabVersion":"1.0.0",
                    "title":"标题","description":"说明","capabilityCount":1,
                    "requiredCredentials":["password","username"],
                    "driver":"http","upstreamHost":null,
                    "upstream":{"url":"https://crm.example/mcp","headers":{"Authorization":"Bearer secret"}},
                    "snapshot":{"fetchedAt":"2026-11-05T03:00:00Z"},
                    "tools":[{"name":"search_customer","schemaSha256":"ab"}]
                  },
                  {
                    "conch":"vendor_crm","version":"1.1.0","sha256":"bb","minCrabVersion":"1.1.0",
                    "title":"客户","description":null,"capabilityCount":2,
                    "driver":"mcp","upstreamHost":"crm.intra.example.com",
                    "future":{"nested":[1,true]}
                  }
                ]}
                """.getBytes(StandardCharsets.UTF_8));

        assertThat(entries).hasSize(2);
        ReefClient.CatalogEntry http = entries.get(0);
        assertThat(http.conch()).isEqualTo("legacy_oa");
        assertThat(http.version()).isEqualTo("1.0.0");
        assertThat(http.sha256()).isEqualTo("aa");
        assertThat(http.minCrabVersion()).isEqualTo("1.0.0");
        assertThat(http.title()).isEqualTo("标题");
        assertThat(http.description()).isEqualTo("说明");
        assertThat(http.capabilityCount()).isEqualTo(1);
        assertThat(http.requiredCredentials()).containsExactly("password", "username");
        assertThat(http.requiredCredentialsKnown()).isTrue();

        ReefClient.CatalogEntry mcp = entries.get(1);
        assertThat(mcp.conch()).isEqualTo("vendor_crm");
        assertThat(mcp.version()).isEqualTo("1.1.0");
        assertThat(mcp.sha256()).isEqualTo("bb");
        assertThat(mcp.minCrabVersion()).isEqualTo("1.1.0");
        assertThat(mcp.title()).isEqualTo("客户");
        assertThat(mcp.description()).isNull();
        assertThat(mcp.capabilityCount()).isEqualTo(2);
        assertThat(mcp.requiredCredentialsKnown()).isFalse();
        assertThat(mcp.requiredCredentials()).isEmpty();
    }

    @Test
    void catalogCacheIgnoresUnknownManifestFields() {
        String raw = """
                [{
                  "conch":"vendor_crm","version":"1.1.0","sha256":"bb","minCrabVersion":"1.1.0",
                  "title":"客户","description":null,"capabilityCount":2,
                  "requiredCredentials":["token"],
                  "driver":"mcp","upstreamHost":"crm.intra.example.com",
                  "upstream":{"url":"https://crm.intra.example.com/mcp"}
                }]
                """;

        List<ReefClient.CatalogEntry> entries = CatalogCache.decode(raw).orElseThrow();
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.conch()).isEqualTo("vendor_crm");
            assertThat(entry.version()).isEqualTo("1.1.0");
            assertThat(entry.sha256()).isEqualTo("bb");
            assertThat(entry.minCrabVersion()).isEqualTo("1.1.0");
            assertThat(entry.title()).isEqualTo("客户");
            assertThat(entry.description()).isNull();
            assertThat(entry.capabilityCount()).isEqualTo(2);
            assertThat(entry.requiredCredentials()).containsExactly("token");
            assertThat(entry.requiredCredentialsKnown()).isTrue();
        });

        String stored = CatalogCache.encode(entries);
        assertThat(stored).contains("\"driver\":\"mcp\"");
        assertThat(stored).doesNotContain("authUse");
        assertThat(stored).doesNotContain("upstreamHost");
        assertThat(stored).doesNotContain("Bearer");
        assertThat(CatalogCache.decode(stored)).contains(entries);
    }

    @Test
    void aCacheSavedBeforeDriverWasStoredCountsAsMissing() {
        String legacy = """
                [{"conch":"corp_sso","version":"1.0.0","sha256":"aa","minCrabVersion":"1.13.0",
                  "title":"统一认证","description":null,"capabilityCount":0,"requiredCredentials":[]}]
                """;

        // 没有类型就分不出授权器：当作没有缓存，同步会丢掉 ETag 重新读一次清单。
        assertThat(CatalogCache.decode(legacy)).isEmpty();
    }
}
