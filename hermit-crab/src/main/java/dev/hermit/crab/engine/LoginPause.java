package dev.hermit.crab.engine;

/**
 * Copy for an account whose automatic login is paused after repeated authentication failures.
 * Test login clears the pause before it tries again.
 */
public final class LoginPause {
    public static final String REASON = "连续登录失败已达上限，自动登录已暂停";
    public static final String RECOVERY =
            "修正该账号凭证后，在本机控制台对该账号执行测试登录（POST /console/api/conches/{conch}/accounts/{account}/test-login）以恢复自动登录";

    private LoginPause() {
    }
}
