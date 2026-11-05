package dev.hermit.common.model;

/**
 * 刷新命令写入的快照。{@code fetchedAt} 是写进包的时间原文，校验要求能解析为 {@link java.time.Instant}。
 * 没有 {@code snapshot} 键时为 null。本批不根据 actions 重算 {@code toolsSha256}。
 */
public record McpSnapshot(
        String fetchedAt,
        String protocolVersion,
        McpServerInfo server,
        String toolsSha256
) {
}
