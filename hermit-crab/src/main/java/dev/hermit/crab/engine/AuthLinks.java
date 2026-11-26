package dev.hermit.crab.engine;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.hermit.common.model.ConchDoc;

/**
 * 引用授权器的包（{@code auth.use}）的账号这一次调用该用哪个授权器账号。
 * 引擎每次调用在拿到账号锁之后问一次；绑定、凭证、版本和地址是否仍然有效由实现判断。
 */
public interface AuthLinks {
    /** 没有接上授权器绑定：引用授权器的包一律按「没有绑定」处理。 */
    AuthLinks NONE = (key, conch, site) -> null;

    /**
     * 没有可用的绑定时返回 null。实现不得回调 {@link SessionManager} 里要等账号锁的方法。
     *
     * @param site 这一次调用实际要访问的站点（{@code SiteAddress.effective} 的结果）。调用排队期间配置可能改过，
     *             实现必须拿它、而不是拿当前保存的配置去核对使用者确认过的地址
     */
    Link resolve(SessionKey key, ConchDoc conch, String site);

    /**
     * @param key 授权器账号
     * @param authenticator 授权器那份规则（{@code driver: auth}）
     * @param credentials 授权器账号的凭证，只在授权器自己的登录步骤里可见
     * @param config 授权器的配置
     * @param siblingSites 绑到同一个授权器账号的其它业务包账号的站点地址。它们的 Cookie 和这个包的在同一个罐里，
     *                     这个包作废自己的会话时不能删到它们的
     */
    record Link(
            SessionKey key,
            ConchDoc authenticator,
            Map<String, String> credentials,
            Map<String, String> config,
            Set<String> siblingSites) {
        public Link(SessionKey key, ConchDoc authenticator, Map<String, String> credentials,
                Map<String, String> config) {
            this(key, authenticator, credentials, config, Set.of());
        }

        public Link {
            siblingSites = siblingSites == null ? Set.of() : Set.copyOf(siblingSites);
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(authenticator, "authenticator");
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
            config = config == null ? Map.of() : Map.copyOf(config);
        }

        @Override
        public String toString() {
            return "Link[***]";
        }
    }
}
