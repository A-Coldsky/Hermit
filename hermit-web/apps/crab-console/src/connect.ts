/** 发给 Agent 的接入说明。只讲怎么接入和怎么找能力；具体能力的说明在 MCP 里。不含 Key 明文。 */
export function connectPrompt(endpoint: string): string {
  return [
    '请接入 Hermit Crab 的 MCP 服务，用它提供的工具访问内部系统。',
    '',
    `接入地址是 ${endpoint}（Streamable HTTP）。请求头用 Authorization: Bearer <API Key> 或 X-Hermit-Key。Key 由 Crab 本机签发，我会自己配置到 MCP 客户端里。不要把 Key 明文写进对话，也不要向我索要。`,
    '',
    '接入后：',
    '1. 先看 tools/list。工具名形如 规则包__能力，说明里写了用途、注意事项和相关能力，参数在 inputSchema 里。',
    '2. 如果只看到 list_systems、search_capabilities、describe_capability、call_capability，说明能力较多：用 list_systems 看有哪些规则包，用 search_capabilities 找能力，用 describe_capability 看参数和示例，再用 call_capability 调用。',
    '3. 需要了解某个规则包的全貌时，读资源 hermit://conches/<规则包>/guide。',
    '4. 列表里没有的能力就是这把 Key 不能用或已停用，不要猜工具名。调用失败时按返回里的 code 和 hint 处理。',
    '5. 一个规则包有多个账号时，用 account 参数指定账号。',
  ].join('\n')
}
