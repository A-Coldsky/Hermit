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
  ROLLBACK: '回滚',
  DISABLE: '停用',
  ENABLE: '启用',
  RETENTION_PURGE: '清理过期数据',
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
  DRIVER: { title: '不支持的 driver', hint: '目前只支持 driver: http。' },
  MISSING_TITLE: { title: '缺少标题', hint: '补上 title，控制台和 MCP 工具列表会显示它。' },
  MISSING_DESCRIPTION: { title: '缺少说明', hint: '补上 description，调用方和 AI 靠它理解这个字段或能力的用途。' },
  MISSING_CREDENTIALS: { title: '没有声明凭证', hint: '在 credentials 里声明登录要用的字段，如用户名和密码。' },
  MISSING_OUTPUT: { title: '没有声明输出', hint: '补上 output，说明调用成功后返回哪些字段。' },
  MISSING_EXAMPLES: { title: '缺少调用示例', hint: '在 examples 里至少写一个输入和预期输出的例子。' },
  UNSUPPORTED_STEP: { title: '不支持的步骤类型', hint: '步骤只能用 request、extract、assert、set、script。' },
  UNDECLARED_CREDENTIAL: { title: '引用了未声明的凭证', hint: '先在 credentials 里声明这个字段，再在模板里引用。' },
  UNDECLARED_CONFIG: { title: '引用了未声明的配置', hint: '先在 config 里声明这个配置项，再在模板里引用。' },
  CONFIG_SECRET: { title: '配置项不能是密文', hint: '密码、令牌等秘密放到 credentials，config 只放地址、选项等非秘密内容。' },
  PLAINTEXT_CREDENTIAL: { title: '规则里写了明文秘密', hint: '把秘密改成 credentials 字段，用 {{credential.xxx}} 引用。' },
  CREDENTIAL_IN_OUTPUT: { title: '输出会带出凭证', hint: '不要把 credential 放进 output，调用结果会返回给调用方。' },
  MIN_CRAB_VERSION: { title: '要求的 Crab 版本过高', hint: 'min_crab_version 高于当前 Crab 支持的版本。降低它，或先升级 Crab。' },
  TEMPLATE_SYNTAX: { title: '模板写法有误', hint: '检查 {{ }} 是否成对，过滤器名是否拼错。' },
  SCRIPT_STEP: { title: 'script 步骤不合规', hint: 'script 只允许 code、into、timeout_ms；code 必须是不超过 64KB 的字符串。' },
  MISSING_NOTES: {
    title: '能力没有写使用注意',
    hint: 'notes 会写进 MCP 工具说明，帮 AI 少犯错，例如分页从 1 开始、提交前先查询。不写也能发布。',
  },
  MISSING_RELATED: {
    title: '能力没有写相关能力',
    hint: 'related 让调用方发现前后衔接的能力，例如查待办后再审批。不写也能发布。',
  },
  UNKNOWN_RELATED: { title: 'related 指向不存在的能力', hint: '检查能力名是否拼错，或把它从 related 里去掉。不改也能发布。' },
}

export function isoDaysAgo(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString()
}

export const versionChangeReasons: Record<string, string> = {
  MANUAL_UPDATE: '使用者手动更新',
  RULE_MISMATCH: '旧版本的自动回退',
}
