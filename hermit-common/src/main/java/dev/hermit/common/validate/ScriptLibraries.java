package dev.hermit.common.validate;

import dev.hermit.common.version.SemVer;

/**
 * 顶层 {@code scripts} 与 script 步骤 {@code use} 的共享界限。
 * 名字规则与模板标识符相同：字母或下划线开头，其后为字母、数字或下划线。
 */
public final class ScriptLibraries {

    public static final int MAX_BYTES = 256 * 1024;
    public static final SemVer MIN_CRAB_VERSION = SemVer.parse("1.2.0");
    public static final String NAME_INVALID = "脚本库名无效";
    public static final String TOO_LARGE = "脚本库超过 256KB";
    public static final String USE_TYPE = "script use 必须是库名列表";
    public static final String CREDENTIAL_TYPE = "script credential 必须是布尔值";
    public static final String VERSION = "使用脚本库时 min_crab_version 必须不低于 1.2.0";
    public static final String DUPLICATE_BARE = "脚本库名重复";

    private ScriptLibraries() {
    }

    public static boolean name(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (first != '_' && !Character.isLetter(first)) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current != '_' && !Character.isLetter(current) && !Character.isDigit(current)) {
                return false;
            }
        }
        return true;
    }

    public static String duplicate(String libraryName) {
        if (!name(libraryName)) {
            return DUPLICATE_BARE;
        }
        return "脚本库 " + libraryName + " 重复";
    }

    public static String missing(String libraryName) {
        if (!name(libraryName)) {
            return "脚本库不存在";
        }
        return "脚本库 " + libraryName + " 不存在";
    }

    /** 合法且不太长的名字才能出现在 path 里，避免把异常键回显出去。 */
    public static String path(String libraryName) {
        if (name(libraryName) && libraryName.length() <= 64) {
            return "scripts." + libraryName;
        }
        return "scripts";
    }
}
