package dev.hermit.reef.admin;

/**
 * {@code secret} 只出现在创建响应里。{@code toString} 不带明文，避免日志把它打出来。
 */
public record IssuedAgentToken(AgentTokenView token, String secret, String publicKey) {

    @Override
    public String toString() {
        return "IssuedAgentToken[token=" + token + "]";
    }
}
