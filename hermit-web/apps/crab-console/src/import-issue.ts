import type { ValidationIssue } from './api'

/**
 * 控制台导入结果里的一条。
 * `label` 是代码旁边的短中文；未知代码为空，仍只显示代码。
 * 原因里已经写出 path 时，`location` 不再重复这段 path，也不改写成「文档」。
 * 本来就没有 path、也没有行号时，`location` 为「文档」。
 */
export interface ShownImportIssue {
  code: string
  label: string
  location: string
  reason: string
}

/**
 * 导入弹窗第一行会直接显示的代码。短中文只点明这一类问题，不写补法。
 * 未收录的代码不加说明。
 */
export const importIssueLabels: Record<string, string> = {
  PARSE: '解析失败',
  CONCH_NAME: '包名不合法',
  ACTION_NAME: '能力名不合法',
  DRIVER: '驱动无效',
  MISSING_TITLE: '缺少标题',
  MISSING_DESCRIPTION: '缺少说明',
  MISSING_CREDENTIALS: '缺少凭证',
  MISSING_OUTPUT: '缺少输出',
  MISSING_EXAMPLES: '缺少示例',
  UNSUPPORTED_STEP: '步骤不支持',
  UNDECLARED_CREDENTIAL: '凭证未声明',
  UNDECLARED_CONFIG: '配置未声明',
  CONFIG_SECRET: '配置不能是密文',
  PLAINTEXT_CREDENTIAL: '疑似明文秘密',
  LITERAL_CREDENTIAL: '明文凭证',
  CREDENTIAL_IN_OUTPUT: '输出含凭证',
  MIN_CRAB_VERSION: '版本要求过高',
  TEMPLATE_SYNTAX: '模板有误',
  MISSING_NOTES: '缺少使用注意',
  MISSING_RELATED: '缺少相关能力',
  UNKNOWN_RELATED: '相关能力不存在',
  SCRIPT_STEP: '脚本不合规',
  MCP_UPSTREAM: '上游无效',
  MCP_SCHEMA_REF: '结构引用无效',
  MCP_HEADER: '请求头无效',
  MCP_DESCRIPTION: '描述异常',
  MCP_NO_ACTIONS: '没有能力',
  HOSTNAME: '主机名不可用',
  SINGLE_LABEL_HOST: '单标签主机名',
  VERSION_INVALID: '版本号无效',
  RESERVED_VARIABLE: '保留变量',
  /** 请求上的未知字段，例如 `body`、`multipart`。警告，不阻止保存。 */
  IGNORED_REQUEST_FIELD: '字段不会发送',
  /** `query` 或 `form` 的值用了 `| urlencode`。警告，不阻止保存。 */
  DOUBLE_ENCODE: '会被编码两次',
  /** `query` 的字面值里已经有 `%xx`。警告，不阻止保存。 */
  PERCENT_ENCODED: '会被再编码',
  /** path 或 url 的字面值里有 `#`。警告，不阻止保存。 */
  FRAGMENT_DROPPED: '片段不会发出去',
  /** `query_style` 不是 `url` 或 `form`。错误，阻止保存。 */
  QUERY_STYLE: 'query_style 无效',
}

/**
 * 解析失败的 message 常常已经是「路径: 缺少 路径」。原因里去掉这层前缀；
 * 去掉之后仍然写出 path 时，位置不再单独显示 path，字段名只留一次。
 * 能从 message 或 `line` 定位到行时，位置写「第 N 行」。缺字段没有行，不编一个。
 */
export function formatImportIssue(issue: Partial<ValidationIssue> | null | undefined): ShownImportIssue {
  const code = text(issue?.code) || '校验'
  const path = text(issue?.path)
  const message = text(issue?.message)
  const line = positiveLine(issue?.line) ?? lineIn(message)
  const reason = withoutLocatedLine(stripPathPrefix(path, message), line)
  const locatedPath = reasonNamesPath(reason, path) ? '' : path
  const location = [line === undefined ? '' : `第 ${line} 行`, locatedPath].filter(Boolean).join(' ')
  return {
    code,
    label: importIssueLabels[code] ?? '',
    location: location || (path ? '' : '文档'),
    reason,
  }
}

function text(value: string | null | undefined): string {
  return typeof value === 'string' ? value.trim() : ''
}

function positiveLine(value: number | null | undefined): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined
}

function lineIn(message: string): number | undefined {
  const match = /第\s*(\d+)\s*行/.exec(message)
  if (!match) return undefined
  const line = Number(match[1])
  return line > 0 ? line : undefined
}

function stripPathPrefix(path: string, message: string): string {
  if (!path || !message.startsWith(`${path}:`)) return message
  const rest = message.slice(path.length + 1).trim()
  return rest || message
}

function withoutLocatedLine(reason: string, line: number | undefined): string {
  if (line === undefined) return reason
  const next = reason.replace(new RegExp(`（第\\s*${line}\\s*行）`), '').trim()
  return next || reason
}

/** path 作为完整字段名出现在原因里。`version` 不算出现在 `min_crab_version` 中。 */
function reasonNamesPath(reason: string, path: string): boolean {
  if (!path || !reason) return false
  const escaped = path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?:^|[^A-Za-z0-9_])${escaped}(?=$|[^A-Za-z0-9_])`).test(reason)
}
