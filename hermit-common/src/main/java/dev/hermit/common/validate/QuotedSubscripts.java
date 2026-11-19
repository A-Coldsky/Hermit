package dev.hermit.common.validate;

import dev.hermit.common.version.SemVer;

/**
 * 模板引号下标 {@code ['key']} 从 1.6.0 起可以保存。
 * 头名忽略大小写和过滤器拒绝对象是运行时行为，不在这里设门槛。
 */
public final class QuotedSubscripts {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.6.0");
    public static final String VERSION = "使用引号下标时 min_crab_version 必须不低于 1.6.0";
    public static final String USE_DOT = "这里请用点号";

    private static final String[] RESERVED_ROOTS = {"credential", "login", "config", "input", "now"};

    private QuotedSubscripts() {
    }

    public static boolean quoted(String path) {
        return path != null && path.contains("['");
    }

    /** 保留根上直接写方括号。点号后面的下标不算。 */
    public static boolean reservedBracket(String path) {
        if (path == null) {
            return false;
        }
        for (String root : RESERVED_ROOTS) {
            if (path.startsWith(root + "[")) {
                return true;
            }
        }
        return false;
    }
}
