package dev.hermit.crab.engine;

import dev.hermit.common.version.SemVer;

public final class CrabVersion {

    /** 加载 Shell 时交给校验器的当前版本。 */
    public static final SemVer CURRENT = SemVer.parse("1.0.0");

    private CrabVersion() {
    }
}
