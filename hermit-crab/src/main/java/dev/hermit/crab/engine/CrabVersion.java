package dev.hermit.crab.engine;

import dev.hermit.common.version.SemVer;

public final class CrabVersion {

    /**
     * 加载 Conch 时交给校验器的当前版本。1.1.0 起可以载入 {@code driver: mcp}，
     * 1.2.0 起可以载入脚本库，1.3.0 起可载入 {@code login} 与 {@code auth.cookies}，
     * 1.4.0 起可载入登录步骤 {@code ask}。1.5.0 起 query 默认按 URL 编码，
     * 规则可以用 {@code query_style: form} 退回表单编码。1.6.0 起可以载入引号下标。
     */
    public static final SemVer CURRENT = SemVer.parse("1.6.0");

    private CrabVersion() {
    }
}
