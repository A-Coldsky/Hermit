export function unsubscribeMessage(conch: string): string {
  return `取消订阅后，本机会卸载 ${conch}，调用它的 OpenAPI 和 MCP 请求会失败。账号、凭证和配置会保留，重新订阅后可以直接使用。`
}
