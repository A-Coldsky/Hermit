package dev.hermit.common.template;

/**
 * XML 文本节点的五字符转义。先替换 {@code &}，所以结果不会被再扫一遍。
 * 调用方决定何时转义；请求正文本身不会自动走这里。
 */
public final class XmlEscape {

    private XmlEscape() {
    }

    public static String escape(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
