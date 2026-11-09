import { type ApiError, createClient, seg } from '@shared/http'

let unauthorized: (error: ApiError) => void = () => {}

/** 由路由注册，避免 api 反向依赖路由。 */
export function onUnauthorized(handler: (error: ApiError) => void): void {
  unauthorized = handler
}

const http = createClient({
  headers: () => ({ 'X-Hermit-Console': '1' }),
  onUnauthorized: (error) => unauthorized(error),
})
const base = '/console/api'

/** 未设置口令时 `required` 为 false，`authenticated` 恒为 true。 */
export interface ConsoleSession {
  required: boolean
  authenticated: boolean
}

export interface ConchSummary {
  conch: string
  version: string
  title: string
  capabilityCount: number
  /** 本机是否已订阅。未订阅的正式包不会出现在这份已加载列表里；本地包为 false。 */
  subscribed: boolean
  accountCount: number
  /**
   * 账号数为 0、缺必填配置、任一账号缺必填凭证、凭证无法解密，或任一账号自动登录已暂停。
   * 已经有账号时，`required: false` 的凭证可以不填。
   * 概览「待配置」按这个字段计数。
   */
  needsConfiguration: boolean
  /** 任一账号自动登录已暂停。原因和恢复方法在账号行。 */
  loginPaused: boolean
  /** `LOCAL` 为本机未签名包，`REEF` 为已安装的正式包。 */
  source: 'LOCAL' | 'REEF'
  /** 缺省或空白为 `http`。 */
  driver: 'http' | 'mcp'
  /**
   * Reef 已停用。本机仍保留已安装版本，HTTP/MCP 照常调用；同步不再更新这份包。
   * 本地包为 false。
   */
  reefDisabled: boolean
  /** 本机第一次记下停用墓碑的时间，ISO-8601。未停用为 null。 */
  reefDisabledAt: string | null
  /** 最近一次同步的清单版本高于已安装版本，且 Crab 可以安装它。 */
  updateAvailable: boolean
  /** 清单上的版本。本地包，以及清单里已经没有的停用包，为 null。 */
  latestVersion: string | null
  /** 清单要求、但当前已安装版本未要求的凭证名，已排序。 */
  newRequiredCredentials: string[]
}

export interface CredentialField {
  name: string
  type: string
  label: string
  required: boolean
  description: string | null
  options: string[] | null
}

export interface ConfigDeclaration extends CredentialField {
  defaultValue: unknown
}

export interface CapabilityState {
  action: string
  title: string
  description: string
  method: string
  readOnly: boolean
  disabled: boolean
}

export interface ConchDetail {
  conch: string
  /** 已加载版本。签名损坏且未载入时为 null，不表示已安装。 */
  version: string | null
  title: string
  description: string | null
  credentials: CredentialField[]
  config: ConfigDeclaration[]
  capabilities: CapabilityState[]
  /** 本机是否已订阅。本地包为 false。 */
  subscribed: boolean
  /**
   * 与列表相同：账号数为 0、缺必填配置、任一账号缺必填凭证、凭证无法解密，或任一账号自动登录已暂停。
   * 已经有账号时，`required: false` 的凭证可以不填。
   * 账号行的同名字段不含「没有账号」，也不含自动登录暂停。
   * 签名损坏且未载入时为 false，不改已加载包的计算。
   */
  needsConfiguration: boolean
  /** 任一账号自动登录已暂停。账号行带原因和恢复方法。 */
  loginPaused: boolean
  /** `LOCAL` 为本机未签名包，`REEF` 为已安装的正式包。签名损坏且未载入时仍是 `REEF`。 */
  source: 'LOCAL' | 'REEF'
  /** 缺省或空白为 `http`。签名损坏且未载入时为 null，不从损坏的包里读。 */
  driver: 'http' | 'mcp' | null
  /** Reef 已停用。本机仍保留已安装版本并可调用。本地包为 false。 */
  reefDisabled: boolean
  /** 本机第一次记下停用墓碑的时间，ISO-8601。未停用为 null。 */
  reefDisabledAt: string | null
  /** 最近一次同步的清单版本高于已安装版本，且 Crab 可以安装它。 */
  updateAvailable: boolean
  /** 清单上的版本。本地包，以及清单里已经没有的停用包，为 null。 */
  latestVersion: string | null
  /** 清单要求、但当前已安装版本未要求的凭证名，已排序。 */
  newRequiredCredentials: string[]
  /**
   * 已订阅但签名损坏、没有载入时为 false。已加载的详情没有这个字段。
   */
  installed?: boolean
  /** 已订阅但签名损坏、没有载入时为 true。已加载的详情没有这个字段。 */
  repairRequired?: boolean
  /** `BAD_SIGNATURE`，否则没有这个字段。 */
  repairReason?: string | null
  /**
   * 签名损坏时为「签名已损坏，当前不可信。可以手动修复。」。
   * 已加载的详情没有这个字段。
   */
  message?: string | null
}

export interface Account {
  name: string
  default: boolean
  /** 该账号缺必填凭证，或已存凭证无法用当前主密钥解密。没有账号、自动登录暂停都不计入这里。 */
  needsConfiguration: boolean
  missingFields: string[]
  /** 已保存的密文无法解密。为 true 时 needsConfiguration 也为 true。 */
  credentialsUnreadable: boolean
  /** 无法解密时为「凭证无法解密，需重新配置」，否则 null。 */
  credentialMessage: string | null
  loginPaused: boolean
  /** 暂停时为原因，否则 null。 */
  loginPauseReason: string | null
  /** 暂停时为恢复方法（修正凭证后测试登录成功才恢复），否则 null。 */
  loginPauseRecovery: string | null
  /**
   * 非 OAuth 包为 null。OAuth 包给出是否已连接，以及访问令牌的到期时间。
   * 未连接，或上游没有给到期时间时 expiresAt 为 null。
   * needsConfiguration 不把「还没连接上游」算进去。
   */
  oauth: OAuthStatus | null
}

export interface OAuthStatus {
  connected: boolean
  /** ISO-8601。未连接，或上游没有给到期时间时为 null。 */
  expiresAt: string | null
}

export interface OAuthStart {
  authorizationUrl: string
}

export interface ConfigValue {
  type: string
  label: string
  required: boolean
  description: string | null
  options: string[] | null
  value: string | null
  defaultValue: unknown
  missing: boolean
}

export interface ApiKeyScope {
  /** 全部规则包的全部能力，包括本地包。两个集合都为空的旧 Key 也是 true。只读仍只放行只读能力。 */
  all: boolean
  conches: string[] | null
  actions: string[] | null
  readOnly: boolean
}

export interface ApiKey {
  id: string
  name: string
  prefix: string
  scope: ApiKeyScope
  createdAt: string
  revokedAt: string | null
  lastUsedAt: string | null
}

export interface CreatedApiKey extends Omit<ApiKey, 'revokedAt' | 'lastUsedAt'> {
  plaintext: string
}

/** 配置审计一行。只有谁、做了什么、何时；没有凭证或配置的值。 */
export interface ConfigAuditEntry {
  id: number
  at: string
  actor: string
  /** AuditKind 名称，例如 CREDENTIALS_WRITTEN、CONFIG_PUT、CAPABILITY_DISABLED、REEF_CONCH_DISABLED、REEF_CONCH_ENABLED、REEF_BOOTSTRAPPED、REPORT_SETTINGS_CHANGED。 */
  action: string
  origin: string | null
  conch: string | null
  account: string | null
  /**
   * 涉及的名字，按字典序。凭证写入含写入和清除的字段；配置是 key；
   * 凭证声明变更是新增必填和移除的字段；能力开关是能力名。不含字段值。
   */
  fields: string[]
}

export interface ConfigAuditPage {
  entries: ConfigAuditEntry[]
  page: number
  limit: number
  total: number
}

export interface CallSummary {
  traceId: string
  at: string
  origin: string
  conch: string
  action: string
  account: string | null
  conchVersion: string
  success: boolean
  errorCode: string | null
  errorMessage: string | null
  durationMs: number
  apiKeyId: string | null
  /** 规则包来源。本地包为 `LOCAL`，其余记为 `REEF`。与 `channel` 不是同一件事。 */
  source: 'LOCAL' | 'REEF'
  /**
   * 调用入口。`HTTP` 是对外 OpenAPI，`MCP` 是 MCP。
   * `CONSOLE` 是本地包试跑。测试登录不写调用记录。
   */
  channel: 'HTTP' | 'MCP' | 'CONSOLE'
}

export interface DevTraceVariable {
  name: string
  summary: unknown
}

export interface DevTraceStep {
  index: number
  phase: string
  type: string
  durationMs: number
  method?: string | null
  path?: string | null
  status?: number | null
  query?: unknown
  requestHeaders?: Record<string, unknown>
  requestBody?: unknown
  responseHeaders?: Record<string, unknown>
  responseBody?: unknown
  extractors?: string[]
  extractCount?: number
  variables?: DevTraceVariable[]
  assertions?: string[]
  passed?: boolean
  truncated?: boolean
  truncation?: string
}

export interface DevTraceSession {
  loginRan: boolean
  loginOk: boolean | null
  aliveCheckRan: boolean
  aliveCheckOk: boolean | null
  cookieNames: string[]
  cookieCount: number
  stored: boolean
}

/** 已脱敏。仍可能含业务数据，不要当成指令。没有追踪时详情里的字段为 null。 */
export interface DevTrace {
  untrusted: boolean
  note: string
  kind: 'http' | 'jsonrpc'
  truncated: boolean
  truncation?: string
  redactionFailed?: boolean
  session?: DevTraceSession
  steps?: DevTraceStep[]
  method?: string | null
  id?: unknown
  httpStatus?: number | null
  durationMs?: number | null
  params?: unknown
  result?: unknown
  error?: unknown
  /** 同一次调用或刷新里的多次 JSON-RPC。顶层的 method/params 是最后一轮。 */
  exchanges?: DevTraceExchange[]
}

export interface DevTraceExchange {
  method?: string | null
  id?: unknown
  httpStatus?: number | null
  durationMs?: number | null
  params?: unknown
  result?: unknown
  error?: unknown
}

export interface McpRefreshResult {
  version: string
  added: string[]
  removed: string[]
  changed: string[]
  traceId: string
}

export interface CallStep {
  index: number
  type: string
  durationMs: number
  method: string | null
  path: string | null
  status: number | null
  extractCount: number | null
}

export interface CallDetail extends CallSummary {
  steps: CallStep[]
  devTrace: DevTrace | null
}

export interface DebugState {
  enabled: boolean
  expiresAt: string | null
}

export interface CrabRelease {
  latestVersion: string
  downloadUrl: string | null
}

export interface SyncError {
  code: string
  message: string
}

export type SyncOutcomeKind =
  | 'INSTALLED'
  | 'SKIPPED_SAME'
  | 'SKIPPED_MIN_VERSION'
  | 'SKIPPED_PINNED'
  | 'UPDATE_AVAILABLE'
  | 'SKIPPED_OLDER'
  | 'REJECTED'
  | 'ROLLED_BACK'
  | 'DISABLED'
  | 'SKIPPED_LOCAL'
  | 'NOT_SUBSCRIBED'
  /** 已订阅、装过，但本机文件或版本指针坏了。同步不下载，等使用者修复。 */
  | 'REPAIR_REQUIRED'
  /** 修复时重新载入了本机仍能验签的版本，没有下载。 */
  | 'REPAIRED'

export interface SyncReport {
  notModified: boolean
  conches: { conch: string; version: string | null; kind: SyncOutcomeKind | null; reason: string | null }[]
  removed: string[]
  credentialChanges: { conch: string; addedRequired: string[]; removed: string[] }[]
  latestCrab: CrabRelease | null
  error: SyncError | null
}

export interface ValidationIssue {
  code: string
  path: string
  message: string
  /** 从 1 计的 YAML 行。定位不到时没有这个字段。 */
  line?: number | null
}

export interface LocalImportResult {
  conch: string
  version: string
  replaced: boolean
  warnings: ValidationIssue[]
}

/**
 * `GET /local-conches` 的一行。已载入的本地包和载入失败的坏包都在这里。
 * 坏包不在 `GET /conches` 里，详情、导出和调用都是 404，只能删除后重新导入。
 */
export interface LocalConchEntry {
  conch: string
  file: string
  /** 已载入的版本。坏包为 null，不表示已安装。 */
  version: string | null
  loaded: boolean
  broken: boolean
  /** 坏包为中文原因，含文件名、包名和原因；已载入为 null。 */
  reason: string | null
}

/** 同名本地包已存在、未带 `confirm=true` 时 409 `CONFLICT` 的 data。 */
export interface LocalImportConflict {
  conch: string
  confirm: true
}

export interface LocalFileResult {
  file: string
  conch: string | null
  version: string | null
  ok: boolean
  issues: ValidationIssue[]
}

/**
 * 目录里的一条。`disabled` 与停用墓碑一致。
 * Reef 清单已经不再列出、但本机有墓碑时，`version` 为 null，`installedVersion` 是本机保留的版本。
 */
export interface CatalogConch {
  conch: string
  version: string | null
  title: string
  description: string | null
  capabilityCount: number
  subscribed: boolean
  installed: boolean
  installedVersion: string | null
  disabled: boolean
  localConflict: boolean
  updateAvailable: boolean
  latestVersion: string | null
  newRequiredCredentials: string[]
  /** 已订阅且因签名损坏没有载入时为 true。其它情况为 false。 */
  repairRequired: boolean
  /** `BAD_SIGNATURE`，没有需要修复的签名损坏时为 null。 */
  repairReason: string | null
  /** `BAD_SIGNATURE` 时为「签名已损坏，当前不可信。」，否则 null。 */
  message: string | null
}

export interface Catalog {
  conches: CatalogConch[]
  syncedAt: string | null
  configured: boolean
}

export interface SubscriptionOutcome {
  conch: string
  version: string | null
  kind: SyncOutcomeKind | null
  reason: string | null
}

/** `subscribed` 为 false 时这次没有订上，`outcome` 为 null。 */
export interface SubscriptionResult {
  conch: string
  subscribed: boolean
  outcome: SubscriptionOutcome | null
}

export interface UpdateResult {
  conch: string
  outcome: SubscriptionOutcome | null
}

export interface UnsubscribeResult {
  conch: string
  subscribed: false
}

/** `source` 为 `BOOTSTRAP`、`CONFIG`，或尚未配置时的 null。响应不含令牌明文。 */
export interface ReefConnection {
  configured: boolean
  source: 'BOOTSTRAP' | 'CONFIG' | null
  url: string | null
  owner: string | null
  tokenName: string | null
  tokenPrefix: string | null
  publicKeyFingerprint: string | null
  instanceId: string
  importedAt: string | null
}

/** 控制台保存的上报开关。`intervalSeconds` 至少为 60，并覆盖 config.yml。 */
export interface ReportSettings {
  enabled: boolean
  intervalSeconds: number
}

export interface ReportBatch {
  instanceId: string
  crabVersion: string
  sentAt: string
  failures: {
    traceId: string
    conch: string
    conchVersion: string
    action: string
    stepIndex: number | null
    stepType: string | null
    errorCode: string
    summary: string | null
    occurredAt: string
  }[]
  counts: {
    conch: string
    conchVersion: string
    action: string
    hourStart: string
    success: number
    failure: number
    avgMillis: number
    p95Millis: number
  }[]
  events: {
    conch: string
    fromVersion: string
    toVersion: string
    reason: string
    occurredAt: string
    eventId: string
  }[]
}

const conchPath = (conch: string) => `${base}/conches/${seg(conch)}`
const accountPath = (conch: string, account: string) => `${conchPath(conch)}/accounts/${seg(account)}`

export const api = {
  session: () => http.get<ConsoleSession>(`${base}/session`),
  /**
   * 成功后服务端写 HttpOnly Cookie `hermit_console`，12 小时有效。
   * 口令错误或已锁定都是 401「口令错误」；未设置口令是 409 `NOT_CONFIGURED`。
   */
  login: (password: string) => http.post<{ ok: true }>(`${base}/session`, { password }),
  /** 成功后服务端清掉所有会话，包括当前这个，需要重新登录。 */
  changePassword: (current: string, next: string) =>
    http.post<{ ok: true }>(`${base}/session/password`, { current, next }),

  conches: () => http.get<{ conches: ConchSummary[] }>(`${base}/conches`),
  conch: (conch: string) => http.get<ConchDetail>(conchPath(conch)),

  accounts: (conch: string) => http.get<{ accounts: Account[] }>(`${conchPath(conch)}/accounts`),
  createAccount: (conch: string, name: string) =>
    http.post<{ name: string; default: boolean }>(`${conchPath(conch)}/accounts`, { name }),
  deleteAccount: (conch: string, account: string) => http.del<{ deleted: boolean }>(accountPath(conch, account)),
  makeDefault: (conch: string, account: string) => http.post<{ name: string }>(`${accountPath(conch, account)}/default`),
  credentialNames: (conch: string, account: string) =>
    http.get<{ fields: string[] }>(`${accountPath(conch, account)}/credentials`),
  writeCredentials: (conch: string, account: string, values: Record<string, string>) =>
    http.put<{ updated: boolean }>(`${accountPath(conch, account)}/credentials`, values),
  testLogin: (conch: string, account: string) => http.post<unknown>(`${accountPath(conch, account)}/test-login`),
  /**
   * 开始上游 OAuth。浏览器打开 authorizationUrl；授权端点再跳回 {@link oauthCallbackPath}。
   * 回调不在 /console/api 下，响应是 HTML，不是 JSON。
   */
  startOAuth: (conch: string, account: string) =>
    http.post<OAuthStart>(`${accountPath(conch, account)}/oauth/start`),
  disconnectOAuth: (conch: string, account: string) =>
    http.del<{ oauth: OAuthStatus }>(`${accountPath(conch, account)}/oauth`),
  /** 浏览器导航地址，不走 http 客户端。 */
  oauthCallbackPath: '/console/oauth/callback',

  config: (conch: string) => http.get<{ fields: Record<string, ConfigValue> }>(`${conchPath(conch)}/config`),
  writeConfig: (conch: string, values: Record<string, string>) =>
    http.put<{ updated: boolean }>(`${conchPath(conch)}/config`, values),

  setCapability: (conch: string, action: string, enabled: boolean) =>
    http.post<{ disabled: boolean }>(`${base}/capabilities/${seg(conch)}/${seg(action)}/${enabled ? 'enable' : 'disable'}`),

  apiKeys: () => http.get<{ apiKeys: ApiKey[] }>(`${base}/apikeys`),
  createApiKey: (body: { name: string; readOnly: boolean; all: boolean; conches: string[]; actions: string[] }) =>
    http.post<CreatedApiKey>(`${base}/apikeys`, body),
  revokeApiKey: (id: string) => http.del<{ revoked: boolean }>(`${base}/apikeys/${seg(id)}`),

  /**
   * `action` 可以是能力名，或 `conch.action`（按第一个 `.` 拆开）。与 `conch` 同时给出且包名不同时，接口返回 400。
   * `channel` 为 `HTTP`、`MCP` 或 `CONSOLE`。不传 `limit` 时服务端默认 100，最大 500。
   */
  calls: (query: { conch?: string; action?: string; channel?: 'HTTP' | 'MCP' | 'CONSOLE'; limit?: number }) =>
    http.get<{ calls: CallSummary[] }>(`${base}/calls`, query),
  audit: (query: { conch?: string; account?: string; page?: number; limit?: number } = {}) =>
    http.get<ConfigAuditPage>(`${base}/audit`, query),
  call: (traceId: string) => http.get<CallDetail>(`${base}/calls/${seg(traceId)}`),

  debug: () => http.get<DebugState>(`${base}/debug`),
  setDebug: (enabled: boolean) => http.post<DebugState>(`${base}/debug`, { enabled }),

  syncStatus: () => http.get<{ report: SyncReport | null; latestCrab: CrabRelease | null }>(`${base}/sync`),
  sync: () => http.post<SyncReport>(`${base}/sync`),

  /** Reef 未配置时 `configured` 为 false，`conches` 为空。 */
  catalog: () => http.get<Catalog>(`${base}/catalog`),
  subscribe: (conch: string) => http.post<SubscriptionResult>(`${conchPath(conch)}/subscription`),
  /** 安装最近一次同步清单里的更高版本。不重新拉取清单。 */
  update: (conch: string) => http.post<UpdateResult>(`${conchPath(conch)}/update`),
  unsubscribe: (conch: string) => http.del<UnsubscribeResult>(`${conchPath(conch)}/subscription`),

  /** 当前生效的 Reef 连接。令牌只返回前缀。 */
  reef: () => http.get<ReefConnection>(`${base}/reef`),
  /** 导入引导 JSON。服务端会先连 Reef 校验令牌，再热更新本进程。 */
  importReefBootstrap: (body: unknown) => http.post<ReefConnection>(`${base}/reef/bootstrap`, body),

  reportSettings: () => http.get<ReportSettings>(`${base}/reports/settings`),
  updateReportSettings: (body: ReportSettings) => http.put<ReportSettings>(`${base}/reports/settings`, body),

  reportPreview: () => http.get<{ batch: ReportBatch; dropped: number }>(`${base}/reports/preview`),
  sendReport: () => http.post<{ attempted: boolean; accepted: boolean; status: number }>(`${base}/reports/send`),

  /** 按 `conch` 排序，含 `broken` 为 true 的坏包。 */
  localConches: () => http.get<{ conches: LocalConchEntry[] }>(`${base}/local-conches`),
  /**
   * `file` 是 multipart 字段名。校验失败时 400，data 为 `{ errors, warnings }`。
   * 同名本地包（含坏包）已存在且未确认时 409 `CONFLICT`，data 为 {@link LocalImportConflict}，
   * 用户同意后带 `confirm` 再传一次。与正式包或已订阅名字重名是 409 `INVALID_INPUT`，确认也不能覆盖。
   */
  importLocalConch: (file: File, confirm = false) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<LocalImportResult>(`${base}/local-conches${confirm ? '?confirm=true' : ''}`, form)
  },
  reloadLocalConches: () => http.post<{ results: LocalFileResult[] }>(`${base}/local-conches/reload`),
  deleteLocalConch: (conch: string) =>
    http.del<{ conch: string; deleted: boolean }>(`${base}/local-conches/${seg(conch)}`),
  /** 响应体是原始 YAML 文本，与磁盘文件一致。 */
  exportLocalConch: (conch: string) => http.text(`${base}/local-conches/${seg(conch)}/export`),
  /**
   * 本地 HTTP 或 MCP 包试跑。正式包为 409 `CONFLICT`。开关关掉为 409 `NOT_CONFIGURED`。
   * 成功时 data 是能力输出。MCP 成功时优先 structuredContent，否则 `{content}`。追踪在调用详情的 `devTrace`。
   */
  runLocal: (conch: string, action: string, body: { account?: string; input?: Record<string, unknown> }) =>
    http.postEnvelope<Record<string, unknown>>(`${base}/local-conches/${seg(conch)}/actions/${seg(action)}/run`, body),
  /**
   * 调试开关打开时，从上游 tools/list 重写本地 MCP 包。正式包为 409 `CONFLICT`。
   * OAuth 包使用已连接的令牌；没连上是 409 `NOT_CONFIGURED`，不打上游。
   * version 不变。traceId 指向 action 为 mcp_refresh 的 JSON-RPC 追踪，没有对应调用记录。
   */
  refreshLocalMcp: (conch: string, account?: string) => {
    const query = account ? `?account=${encodeURIComponent(account)}` : ''
    return http.post<McpRefreshResult>(`${base}/local-conches/${seg(conch)}/mcp/refresh${query}`)
  },
}
