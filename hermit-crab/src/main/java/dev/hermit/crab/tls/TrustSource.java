package dev.hermit.crab.tls;

/**
 * 一个信任来源的计数。{@code kind} 是 {@code JDK}、{@code OS} 或 {@code CA_DIR}。
 * {@code GET /console/api/tls/trust} 的 {@code sources} 由后续的信任管理 PR 暴露，这里只准备数据。
 */
public record TrustSource(String kind, String label, int count, String location, String error) {
}
