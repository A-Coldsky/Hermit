package dev.hermit.reef.security;

/**
 * 一次通过的 Agent 调用身份。{@code tokenId} 为 null 表示用的是配置令牌。
 * {@code userId} 为 null 表示配置令牌当时还没有可归属的管理员。
 */
public record AgentPrincipal(Long userId, String username, Long tokenId) {
}
