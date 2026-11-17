package dev.hermit.crab.engine;

import dev.hermit.common.version.SemVer;

public final class CrabVersion {

    /** 加载 Conch 时交给校验器的当前版本。1.1.0 起可以载入 {@code driver: mcp}，1.2.0 起可以载入脚本库。 */
    public static final SemVer CURRENT = SemVer.parse("1.2.0");

    private CrabVersion() {
    }
}
