package dev.hermit.crab.engine;

import java.util.List;

/**
 * 身份：使用者把不同规则包的账号绑到一起，共用一份 Cookie 和一把锁。
 * 没绑身份的账号 {@link #identityOf} 返回 null，仍按 (origin, conch, account) 各存各的。
 * 实现不得回调 {@link SessionManager}。
 */
public interface SharedSessions {

    /** 这个账号绑到的身份；没绑为 null。 */
    String identityOf(SessionKey key);

    /**
     * 这个账号用的是别的账号的罐（绑到授权器账号的业务包账号）。它自己作废时只清它自己的登录状态，
     * 罐里的 Cookie 是授权器和其它包的，不能跟着清。
     */
    default boolean borrows(SessionKey key) {
        return false;
    }

    /** 这个身份现在的成员。 */
    List<SessionKey> members(String identity);

    List<StoredCookie> load(String identity);

    void save(String identity, List<StoredCookie> cookies);

    void delete(String identity);

    static SharedSessions none() {
        return new SharedSessions() {
            @Override
            public String identityOf(SessionKey key) {
                return null;
            }

            @Override
            public List<SessionKey> members(String identity) {
                return List.of();
            }

            @Override
            public List<StoredCookie> load(String identity) {
                return List.of();
            }

            @Override
            public void save(String identity, List<StoredCookie> cookies) {
            }

            @Override
            public void delete(String identity) {
            }
        };
    }
}
