export const alertKindLabels: Record<string, string> = {
  NEW_VERSION_REGRESSION: '新版本失败率上升',
  RULE_MISMATCH_SURGE: '规则不匹配激增',
  UPSTREAM_ERROR_SURGE: '老系统错误激增',
}

export const auditActionLabels: Record<string, string> = {
  LOGIN_SUCCESS: '登录成功',
  LOGIN_FAILURE: '登录失败',
  UPLOAD: '上传',
  PUBLISH: '发布',
  DISCARD: '丢弃',
  ROLLBACK: '回滚',
  DISABLE: '停用',
  ENABLE: '启用',
  RETENTION_PURGE: '清理过期数据',
  USER_CREATED: '新建用户',
  USER_UPDATED: '修改用户',
  USER_PASSWORD_RESET: '重置密码',
  PASSWORD_CHANGED: '修改密码',
  AGENT_TOKEN_CREATED: '生成令牌',
  AGENT_TOKEN_REVOKED: '吊销令牌',
}

export const versionStatusLabels: Record<string, string> = {
  UPLOADED: '已上传',
  PUBLISHED: '已发布',
}

export const credentialTypeLabels: Record<string, string> = {
  text: '文本',
  secret: '密文',
  select: '选项',
  url: '地址',
}

export interface IssueHint {
  title: string
  /** 影响和改法。 */
  hint: string
}

/** 校验码的说明。服务端 message 仍作为细节展示；未收录的码只显示 message。 */
export const issueHints: Record<string, IssueHint> = {
  CONCH_NAME: { title: '规则包名不合法', hint: '以小写字母开头，只含小写字母、数字和下划线，长 2 到 64 个字符。' },
  ACTION_NAME: { title: '能力名不合法', hint: '以小写字母开头，只含小写字母、数字和下划线，长 2 到 64 个字符。' },
  DRIVER: { title: '不支持的 driver', hint: '目前支持 driver: http 和 driver: mcp。' },
  MISSING_TITLE: { title: '缺少标题', hint: '补上 title，控制台和 MCP 工具列表会显示它。' },
  MISSING_DESCRIPTION: { title: '缺少说明', hint: '补上 description，调用方和 AI 靠它理解这个字段或能力的用途。' },
  MISSING_CREDENTIALS: { title: '没有声明凭证', hint: '在 credentials 里声明登录要用的字段，如用户名和密码。' },
  MISSING_OUTPUT: { title: '没有声明输出', hint: '补上 output，说明调用成功后返回哪些字段。' },
  MISSING_EXAMPLES: { title: '缺少调用示例', hint: '在 examples 里至少写一个输入和预期输出的例子。' },
  UNSUPPORTED_STEP: { title: '不支持的步骤类型', hint: '步骤可用 request、extract、assert、set、script、if、repeat，登录步骤还可用 ask。' },
  UNDECLARED_CREDENTIAL: { title: '引用了未声明的凭证', hint: '先在 credentials 里声明这个字段，再在模板里引用。' },
  UNDECLARED_CONFIG: { title: '引用了未声明的配置', hint: '先在 config 里声明这个配置项，再在模板里引用。' },
  CONFIG_SECRET: { title: '配置项不能是密文', hint: '密码、令牌等秘密放到 credentials，config 只放地址、选项等非秘密内容。' },
  PLAINTEXT_CREDENTIAL: { title: '规则里写了明文秘密', hint: '把秘密改成 credentials 字段，用 {{credential.xxx}} 引用。' },
  CREDENTIAL_IN_OUTPUT: { title: '输出会带出凭证', hint: '不要把 credential 放进 output，调用结果会返回给调用方。' },
  MIN_CRAB_VERSION: {
    title: 'min_crab_version 不满足所用特性要求',
    hint: '规则里用到了更高版本的特性，按下面提示把 min_crab_version 调到对应版本或以上。',
  },
  TEMPLATE_SYNTAX: { title: '模板写法有误', hint: '检查 {{ }} 是否成对，过滤器名是否拼错。' },
  SCRIPT_STEP: {
    title: 'script 步骤不合规',
    hint: 'script 允许 code、into、timeout_ms、use、credential。code 不超过 64KB。use 引用顶层 scripts 里的库，每个库不超过 256KB。用了 scripts 或 use 时，min_crab_version 不低于 1.2.0。',
  },
  MISSING_NOTES: {
    title: '能力没有写使用注意',
    hint: 'notes 会写进 MCP 工具说明，帮 AI 少犯错，例如分页从 1 开始、提交前先查询。不写也能发布。',
  },
  MISSING_RELATED: {
    title: '能力没有写相关能力',
    hint: 'related 让调用方发现前后衔接的能力，例如查待办后再审批。不写也能发布。',
  },
  UNKNOWN_RELATED: { title: 'related 指向不存在的能力', hint: '检查能力名是否拼错，或把它从 related 里去掉。不改也能发布。' },
  HOSTNAME: {
    title: '主机名无法连接',
    hint: '下划线或非 ASCII 主机名 Java 客户端连不上。改成 IP、DNS 别名，或 message 里的 punycode。',
  },
  SINGLE_LABEL_HOST: {
    title: '单标签主机名不会发送 SNI',
    hint: 'https 的单标签名可能拿到别的证书。不改也能保存。',
  },
  REQUEST_BODY: {
    title: '请求正文无效',
    hint: 'body、form、json、multipart 互斥；body 须是字符串模板，download / multipart / type: file 仅适用于 driver: http 且 min_crab_version 不低于 1.8.0。',
  },
  BODY_CONTENT_TYPE: {
    title: '未声明 Content-Type',
    hint: 'body 会按 text/plain 发送。需要 XML 时在 headers 里声明 Content-Type。不改也能保存。',
  },
  SAME_SITE: {
    title: '与已有规则包访问同一网站',
    hint: 'Reef 上已有其他已发布的规则包访问同一网站。没绑到同一个身份时它们各自登录、不共用 Cookie，网站只允许单会话时会互相顶掉登录。不改也能发布。',
  },
  SAME_AUTH: {
    title: '与已有规则包经同一认证地址登录',
    hint: 'Reef 上已有其他已发布的规则包经同一个认证地址（门户或 SSO）登录。没绑到同一个身份时它们各自登录、不共用 Cookie。不改也能发布。',
  },
  AUTH_ORIGIN_ADDED: {
    title: '新增了登录认证地址',
    hint: '这个版本在登录步骤里新增了 base_url 之外的认证地址（auth.origins），账号凭证会提交到那里。发布前请确认该地址可信。不改也能发布。',
  },
  AUTH_ORIGIN: {
    title: '认证地址（auth.origins）配置或用法有误',
    hint: '检查 auth.origins 的地址格式和引用；只有登录步骤可以访问认证地址，能力步骤和 alive_check 只能访问 base_url。作为警告出现时不阻止保存。',
  },
  GROUP_NAME: {
    title: '分组名不合法',
    hint: '分组和子分组名须以小写字母开头，只含小写字母、数字和下划线，长 2 到 64 个字符。',
  },
  GROUP_DEPTH: {
    title: '分组层级超过两层',
    hint: '能力分组最多支持一级分组和二级子分组，请把多余层级拍平。',
  },
  UNKNOWN_GROUP: {
    title: '能力引用了未声明的分组',
    hint: '检查 action 的 group 是否已在顶层 groups 中声明（最多两层）；不分组请直接省略 group 字段。',
  },
  EMPTY_GROUP: {
    title: '分组下没有任何能力',
    hint: '这个分组及其子分组里没有归属任何能力，可以删掉或把对应能力的 group 指到这里。不改也能发布。',
  },
  SESSION_EXPIRY: {
    title: '会话失效或断言规则写法有误',
    hint: '检查 auth.expired_when、auth.alive_check.expect 或 assert 的键名与格式（需要 min_crab_version 不低于 1.12.0）。',
  },
}

export function isoDaysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString()
}

export const versionChangeReasons: Record<string, string> = {
  MANUAL_UPDATE: '使用者手动更新',
  RULE_MISMATCH: '规则失配（旧版 Crab 记录，现在失配只上报不切换版本）',
}

export const pullCountHint = '只计 Crab 成功下载的已发布包，不按实例去重'

export const roleLabels: Record<string, string> = {
  ADMIN: '管理员',
  USER: '普通用户',
}

const userErrorTexts: Record<string, string> = {
  'at least one enabled administrator is required': '至少要保留一个启用中的管理员',
  'cannot change your own role or disabled flag': '不能修改自己的角色或禁用自己',
  'username already exists': '用户名已存在',
  'cannot reset your own password': '不能重置自己的密码，请用右上角「修改密码」',
  'old password does not match': '当前密码不正确',
}

/** Reef 用户管理的中文 message 原样显示，不再拼错误码标签；旧版 Reef 的英文 message 仍按表翻译。 */
export function userErrorText(message: string): string | null {
  return userErrorTexts[message] ?? (/[\u4e00-\u9fff]/.test(message) ? message : null)
}

/** Reef 按「令牌所属用户 + 实例号」区分 Crab；旧数据没有用户。 */
export function crabLabel(row: { owner?: string | null; instanceId: string }): string {
  return row.owner ? `${row.owner} · ${row.instanceId}` : row.instanceId
}

export const transportLabels: Record<string, string> = {
  streamable_http: 'Streamable HTTP',
}

export const mcpAuthLabels: Record<string, string> = {
  headers: '固定凭证请求头',
  oauth: 'OAuth 登录',
}

export function isMcp(row: { driver?: string | null }): boolean {
  return row.driver === 'mcp'
}

/** 认证地址条目给人看的写法：整项 `{{config.NAME}}` 写成「由 Crab 端配置（NAME）」，其余原样。 */
export function authOriginLabel(entry: string): string {
  const ref = /^\{\{\s*config\.([A-Za-z_][A-Za-z0-9_]*)\s*\}\}$/.exec(entry.trim())
  return ref ? `由 Crab 端配置（${ref[1]}）` : entry
}

/** 上游地址的主机名。地址缺失或写错时为 null。 */
export function upstreamHost(url: string | null | undefined): string | null {
  if (!url) {
    return null
  }
  try {
    return new URL(url).hostname
  } catch {
    return null
  }
}
