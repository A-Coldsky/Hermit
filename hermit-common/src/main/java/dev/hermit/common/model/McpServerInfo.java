package dev.hermit.common.model;

/** 刷新时记在快照里的上游服务器名和版本。 */
public record McpServerInfo(String name, String version) {
}
