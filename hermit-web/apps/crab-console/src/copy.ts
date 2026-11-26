export function unsubscribeMessage(conch: string): string {
  return `取消订阅后，本机会卸载 ${conch}，调用它的 OpenAPI 和 MCP 请求会失败。账号、凭证和配置会保留，重新订阅后可以直接使用。`
}

/** 退订授权器的确认文案。`accounts` 是绑在它上面的业务包账号，形如 `corp_oa/default`。 */
export function unsubscribeAuthenticatorMessage(conch: string, accounts: string[]): string {
  const affected = accounts.length
    ? `下面 ${accounts.length} 个账号绑在它上面，取消订阅后调用会失败：${accounts.join('、')}。`
    : '现在没有账号绑在它上面。'
  return `取消订阅后，本机会卸载授权器 ${conch}。${affected}授权器的账号、凭证和这些绑定都会保留，重新订阅后可以直接使用。`
}

/** 订阅业务包、本机还没有它引用的授权器时的确认文案。 */
export function subscribeWithAuthenticatorMessage(conch: string, authUse: string): string {
  return `${conch} 的登录由授权器 ${authUse} 负责，本机还没有订阅它。一起订阅后，要在授权器上建账号、填凭证，再把 ${conch} 的账号绑上去才能调用。`
}
