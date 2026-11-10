package dev.hermit.common.template;

/** Shared wording for a template path that was never declared or extracted. */
public final class UndefinedVariable {
    private UndefinedVariable() {
    }

    public static String message(String path) {
        return "引用了未定义的变量 " + path + "。";
    }

    /** {@code null} when the template failure is not a missing path. */
    public static String fromTemplateFailure(String message) {
        String marker = "路径不存在:";
        if (message == null) {
            return null;
        }
        int at = message.indexOf(marker);
        if (at < 0) {
            return null;
        }
        String path = message.substring(at + marker.length()).trim();
        if (path.isEmpty()) {
            return null;
        }
        return message(path);
    }
}
