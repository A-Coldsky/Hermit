package dev.hermit.crab.store;

import java.time.Instant;
import java.util.Objects;

import dev.hermit.crab.engine.SessionKey;

/**
 * 业务包（{@code auth.use}）的一个账号指向授权器的一个账号。
 * {@code boundBaseUrl} 是绑定时使用者确认过的地址。
 */
public record AuthBinding(SessionKey account, SessionKey authenticator, String boundBaseUrl, Instant boundAt) {
    public AuthBinding {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(authenticator, "authenticator");
        Objects.requireNonNull(boundBaseUrl, "boundBaseUrl");
    }
}
