/**
 * 发给 Agent 的接入说明：让它自己把 Crab 加进 MCP 配置，Key 留占位符，最后告诉用户 Key 填在哪里。
 * 具体能力的说明在 MCP 里。不含 Key 明文。
 */
export function connectPrompt(endpoint: string): string {
  return [
    '请帮我把 Hermit Crab 接入为你的 MCP 服务，之后用它提供的工具访问内部系统。',
    '',
    '一、配置',
    `1. 在你的 MCP 配置里新增一个服务，名字用 hermit，地址是 ${endpoint}，传输方式是 Streamable HTTP（有的客户端写作 http），请求头是 Authorization: Bearer <API Key>。`,
    '2. Key 的位置先原样写占位符 <API Key>。不要向我索要 Key，也不要让我把 Key 发到对话里。',
    '3. 你能修改自己的配置就直接改；不能改，就给我完整的配置内容，并说明要放到哪个文件或哪个设置页。',
    '4. 配好后明确告诉我三件事：配置写在哪个文件（完整路径）；占位符 <API Key> 在其中的哪个位置；我替换后需不需要重启或重新加载才生效。Key 我会在 Crab 控制台的「API Key」页创建，然后自己替换占位符。',
    '',
    '二、接入后',
    '1. 先看 tools/list。工具名形如 规则包__能力，说明里写了用途、注意事项和相关能力，参数在 inputSchema 里。',
    '2. 如果只看到 list_systems、search_capabilities、describe_capability、call_capability，说明能力较多：用 list_systems 看有哪些规则包，用 search_capabilities 找能力，用 describe_capability 看参数和示例，再用 call_capability 调用。',
    '3. 需要了解某个规则包的全貌时，读资源 hermit://conches/<规则包>/guide。',
    '4. 列表里没有的能力就是这把 Key 不能用或已停用，不要猜工具名。调用失败时按返回里的 code 和 hint 处理。',
    '5. 一个规则包有多个账号时，用 account 参数指定账号。',
    '6. 连接返回 401 说明 Key 还没填或填错了，提醒我去检查占位符的位置，不要让我把 Key 发给你。',
  ].join('\n')
}
