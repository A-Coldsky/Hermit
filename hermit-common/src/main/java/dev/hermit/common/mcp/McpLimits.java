package dev.hermit.common.mcp;

import java.util.Locale;
import java.util.Set;

import dev.hermit.common.version.SemVer;

/** {@code driver: mcp} 的包内限制。转发客户端以后用同一份名单，避免校验和运行时各写一套。 */
public final class McpLimits {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.1.0");
    public static final String TRANSPORT = "streamable_http";
    public static final int SCHEMA_MAX_BYTES = 64 * 1024;
    public static final int SCHEMA_MAX_DEPTH = 32;
    public static final int DESCRIPTION_MAX_BYTES = 2 * 1024;

    /** 比较时用小写。{@code oauth} 另外禁止 {@code authorization}。 */
    public static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host",
            "content-length",
            "cookie",
            "mcp-session-id",
            "mcp-protocol-version",
            "accept",
            "content-type");

    private McpLimits() {
    }

    public static String headerKey(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
