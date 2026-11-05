package dev.hermit.common.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

class ToolSnapshotsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void schemaDigestIgnoresKeyOrderAndOmitsAbsentOutput() throws Exception {
        String canonical = "{\"inputSchema\":{\"type\":\"object\"}}";
        assertThat(ToolSnapshots.schemaSha256(Map.of("type", "object"), null)).isEqualTo(sha(canonical));

        LinkedHashMap<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("required", List.of("q"));
        reversed.put("type", "object");
        LinkedHashMap<String, Object> forward = new LinkedHashMap<>();
        forward.put("type", "object");
        forward.put("required", List.of("q"));
        assertThat(ToolSnapshots.schemaSha256(reversed, null))
                .isEqualTo(ToolSnapshots.schemaSha256(forward, null))
                .isEqualTo(sha("{\"inputSchema\":{\"required\":[\"q\"],\"type\":\"object\"}}"));
        assertThat(ToolSnapshots.schemaSha256(Map.of("type", "object"), Map.of()))
                .isEqualTo(sha("{\"inputSchema\":{\"type\":\"object\"},\"outputSchema\":{}}"));
    }

    @Test
    void toolsDigestSortsNamesAndKeysAndDropsMeta() throws Exception {
        LinkedHashMap<String, Object> later = new LinkedHashMap<>();
        later.put("name", "b");
        later.put("inputSchema", Map.of("type", "object"));
        LinkedHashMap<String, Object> earlier = new LinkedHashMap<>();
        earlier.put("_meta", Map.of("trace", "secret"));
        earlier.put("name", "a");
        earlier.put("description", "hi");
        LinkedHashMap<String, Object> nestedMeta = new LinkedHashMap<>();
        nestedMeta.put("type", "object");
        nestedMeta.put("_meta", Map.of("hidden", true));
        LinkedHashMap<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("inputSchema", nestedMeta);
        reordered.put("name", "b");

        String canonical = "{\"tools\":["
                + "{\"description\":\"hi\",\"name\":\"a\"},"
                + "{\"inputSchema\":{\"type\":\"object\"},\"name\":\"b\"}"
                + "]}";
        String expected = sha(canonical);
        assertThat(ToolSnapshots.toolsSha256(List.of(later, earlier))).isEqualTo(expected);
        assertThat(ToolSnapshots.toolsSha256(List.of(earlier, reordered))).isEqualTo(expected);

        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("nextCursor", "page-2");
        envelope.put("_meta", Map.of("ignored", true));
        envelope.put("tools", List.of(later, earlier));
        assertThat(ToolSnapshots.toolsSha256(envelope)).isEqualTo(expected);
        assertThat(ToolSnapshots.toolsSha256(mapper.readTree(mapper.writeValueAsString(envelope))))
                .isEqualTo(expected);
    }

    private static String sha(String json) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
    }
}
