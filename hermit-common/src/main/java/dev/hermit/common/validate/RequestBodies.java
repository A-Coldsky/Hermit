package dev.hermit.common.validate;

import dev.hermit.common.version.SemVer;

/**
 * request 的原始 {@code body} 和 {@code xml_escape} 从 1.7.0 起可以保存。
 * headers 里的 Content-Type 覆盖 form/json 默认值是运行时行为，不在这里设门槛。
 */
public final class RequestBodies {

    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.7.0");
    public static final String FILTER = "xml_escape";
    public static final String BODY_VERSION = "使用 body 时 min_crab_version 必须不低于 1.7.0";
    public static final String XML_ESCAPE_VERSION = "使用 xml_escape 时 min_crab_version 必须不低于 1.7.0";
    public static final String EXCLUSIVE = "body、form 与 json 互斥";
    public static final String BODY_TYPE = "body 必须是字符串";
    public static final String NOT_TEXT = "body 的渲染结果不是文本";
    public static final String CONTENT_TYPE =
            "body 没有在 headers 里声明 Content-Type，将按 text/plain 发送。需要 XML 时请声明 Content-Type。";

    private RequestBodies() {
    }

    /** 提取步骤的 {@code filter} 链，不是模板。名字相等才算，避免 {@code xml_escape_more} 误伤。 */
    public static boolean filterChain(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String piece : text.split("\\|", -1)) {
            String name = piece.trim();
            int paren = name.indexOf('(');
            if (paren >= 0) {
                name = name.substring(0, paren).trim();
            }
            if (FILTER.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
