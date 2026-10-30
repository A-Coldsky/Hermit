import { createClient, seg } from '@shared/http'

const http = createClient({ headers: () => ({ 'X-Hermit-Console': '1' }) })
const base = '/console/api'

export interface ConchSummary {
  conch: string
  version: string
  title: string
  capabilityCount: number
  accountCount: number
  /**
   * 缺必填配置、任一账号缺必填凭证、凭证无法解密，或任一账号自动登录已暂停。
   * 概览「待配置」按这个字段计数。
   */
  needsConfiguration: boolean
  /** 任一账号自动登录已暂停。原因和恢复方法在账号行。 */
  loginPaused: boolean
  /** `LOCAL` 为本机未签名包，`REEF` 为已安装的正式包。 */
  source: 'LOCAL' | 'REEF'
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
  version: string
  title: string
  description: string
  credentials: CredentialField[]
  config: ConfigDeclaration[]
  capabilities: CapabilityState[]
  /** 任一账号自动登录已暂停。账号行带原因和恢复方法。 */
  loginPaused: boolean
  /** `LOCAL` 为本机未签名包，`REEF` 为已安装的正式包。 */
  source: 'LOCAL' | 'REEF'
}

export interface Account {
  name: string
  default: boolean
  /** 缺必填凭证，或已存凭证无法用当前主密钥解密。自动登录暂停不计入这里。 */
  needsConfiguration: boolean
  missingFields: string[]
  /** 已保存的密文无法解密。为 true 时 needsConfiguration 也为 true。 */
  credentialsUnreadable: boolean
  /** 无法解密时为「凭证无法解密，需重新配置」，否则 null。 */
  credentialMessage: string | null
  loginPaused: boolean
  /** 暂停时为原因，否则 null。 */
  loginPauseReason: string | null
  /** 暂停时为恢复方法（修正凭证后执行测试登录），否则 null。 */
  loginPauseRecovery: string | null
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
  /** AuditKind 名称，例如 CREDENTIALS_WRITTEN、CONFIG_PUT、CAPABILITY_DISABLED。 */
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
  /** 调用时的来源。本地包为 `LOCAL`，其余记为 `REEF`。 */
  source: 'LOCAL' | 'REEF'
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
  | 'REJECTED'
  | 'ROLLED_BACK'
  | 'DISABLED'
  | 'SKIPPED_LOCAL'

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
}

export interface LocalImportResult {
  conch: string
  version: string
  replaced: boolean
  warnings: ValidationIssue[]
}

export interface LocalFileResult {
  file: string
  conch: string | null
  version: string | null
  ok: boolean
  issues: ValidationIssue[]
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

  config: (conch: string) => http.get<{ fields: Record<string, ConfigValue> }>(`${conchPath(conch)}/config`),
  writeConfig: (conch: string, values: Record<string, string>) =>
    http.put<{ updated: boolean }>(`${conchPath(conch)}/config`, values),

  setCapability: (conch: string, action: string, enabled: boolean) =>
    http.post<{ disabled: boolean }>(`${base}/capabilities/${seg(conch)}/${seg(action)}/${enabled ? 'enable' : 'disable'}`),

  apiKeys: () => http.get<{ apiKeys: ApiKey[] }>(`${base}/apikeys`),
  createApiKey: (body: { name: string; readOnly: boolean; conches: string[]; actions: string[] }) =>
    http.post<CreatedApiKey>(`${base}/apikeys`, body),
  revokeApiKey: (id: string) => http.del<{ revoked: boolean }>(`${base}/apikeys/${seg(id)}`),

  /** `action` 可以是能力名，或 `conch.action`（按第一个 `.` 拆开）。与 `conch` 同时给出且包名不同时，接口返回 400。 */
  calls: (query: { conch?: string; action?: string; limit?: number }) =>
    http.get<{ calls: CallSummary[] }>(`${base}/calls`, query),
  audit: (query: { conch?: string; account?: string; page?: number; limit?: number } = {}) =>
    http.get<ConfigAuditPage>(`${base}/audit`, query),
  call: (traceId: string) => http.get<CallDetail>(`${base}/calls/${seg(traceId)}`),

  debug: () => http.get<DebugState>(`${base}/debug`),
  setDebug: (enabled: boolean) => http.post<DebugState>(`${base}/debug`, { enabled }),

  syncStatus: () => http.get<{ report: SyncReport | null; latestCrab: CrabRelease | null }>(`${base}/sync`),
  sync: () => http.post<SyncReport>(`${base}/sync`),

  reportPreview: () => http.get<{ batch: ReportBatch; dropped: number }>(`${base}/reports/preview`),
  sendReport: () => http.post<{ attempted: boolean; accepted: boolean; status: number }>(`${base}/reports/send`),

  /** `file` 是 multipart 字段名。校验失败时 data 为 `{ errors, warnings }`。 */
  importLocalConch: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<LocalImportResult>(`${base}/local-conches`, form)
  },
  reloadLocalConches: () => http.post<{ results: LocalFileResult[] }>(`${base}/local-conches/reload`),
  deleteLocalConch: (conch: string) =>
    http.del<{ conch: string; deleted: boolean }>(`${base}/local-conches/${seg(conch)}`),
  /** 响应体是原始 YAML 文本，与磁盘文件一致。 */
  exportLocalConch: (conch: string) => http.text(`${base}/local-conches/${seg(conch)}/export`),
}
