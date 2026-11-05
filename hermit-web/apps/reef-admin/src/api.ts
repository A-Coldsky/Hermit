import { createClient, seg } from '@shared/http'
import { router } from './router'
import { session, signOut } from './auth'

const http = createClient({
  headers: (): Record<string, string> => (session.token ? { Authorization: `Bearer ${session.token}` } : {}),
  onUnauthorized: () => {
    if (session.token) {
      signOut()
      void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    }
  },
})

export interface ValidationIssue {
  code: string
  path: string
  message: string
}

export interface ActionChange {
  name: string
  input: boolean
  output: boolean
  steps: boolean
  method: boolean
  mcp: boolean
}

export interface ConchDiff {
  addedActions: string[]
  removedActions: string[]
  changedActions: ActionChange[]
  addedCredentials: string[]
  removedCredentials: string[]
  credentialRequiredChanges: { name: string; before: boolean; after: boolean }[]
  addedConfig: string[]
  removedConfig: string[]
  configDefaultChanges: { name: string; before: unknown; after: unknown }[]
  newRequiredCredentials: string[]
}

export interface ConchAvailability {
  conch: string
  disabled: boolean
  disabledAt: string | null
  disabledBy: string | null
  disabledReason: string | null
}

export interface ConchSummary {
  conch: string
  version: string | null
  title: string
  description: string
  owner: string | null
  tags: string[]
  capabilityCount: number
  publishedAt: string | null
  disabled: boolean
  disabledAt: string | null
  disabledBy: string | null
  disabledReason: string | null
  pullCount: number
}

export interface FieldDeclaration {
  type: string
  label: string
  required: boolean
  description: string | null
  options: string[] | null
  default?: unknown
}

export interface Capability {
  name: string
  title: string
  description: string
  effectiveMethod: string
  inputJsonSchema: Record<string, unknown>
  outputJsonSchema: Record<string, unknown>
  examples: { input: unknown; output: unknown }[]
  notes: string | null
  related: string[]
  mcp: { expose: boolean; readOnly: boolean; destructive: boolean }
}

export interface ConchDetail {
  conch: string
  version: string
  driver: string
  minCrabVersion: string
  baseUrl: string | null
  title: string
  description: string
  owner: string | null
  tags: string[]
  changelog: string | null
  credentials: Record<string, FieldDeclaration>
  config: Record<string, FieldDeclaration>
  actions: Record<string, Capability>
  disabled: boolean
  disabledAt: string | null
  disabledBy: string | null
  disabledReason: string | null
  pullCount: number
}

export interface ConchVersion {
  version: string
  status: 'UPLOADED' | 'PUBLISHED' | string
  uploadedBy: string
  uploadedAt: string
  publishedBy: string | null
  publishedAt: string | null
  current: boolean
  warningCount: number
  warnings: ValidationIssue[]
  pullCount: number
}

export interface UploadResult {
  conch: string
  version: string
  warnings: ValidationIssue[]
  diff: Partial<ConchDiff>
}

export interface TidePoint {
  conch: string
  action: string
  bucketStart: string
  success: number
  failure: number
  failureRate: number
  avgMillis: number | null
  p95Millis: number | null
}

export interface TideSummary {
  bucket: string
  from: string
  to: string
  p95Note: string
  series: TidePoint[]
}

export interface FailureGroup {
  conch: string
  conchVersion: string
  action: string
  stepIndex: number | null
  stepType: string | null
  errorCode: string
  owner: string | null
  count: number
  recent: { summary: string | null; traceId: string; owner: string | null }[]
}

export interface VersionStats {
  version: string
  calls: number
  failures: number
  failureRate: number
}

export interface RollbackEvent {
  id: number
  instanceId: string
  conch: string
  fromVersion: string
  toVersion: string
  reason: string
  occurredAt: string
  receivedAt: string
  owner: string | null
}

export type AdminRole = 'ADMIN' | 'USER'

export interface AdminUser {
  username: string
  role: AdminRole
  disabled: boolean
  createdAt: string
  tokenCount: number
}

export interface AgentToken {
  id: number | null
  owner: string
  name: string
  prefix: string | null
  createdAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  source: 'DATABASE' | 'CONFIG'
}

export interface IssuedAgentToken {
  token: AgentToken
  secret: string
  publicKey: string
}

export interface CrabInstance {
  owner: string
  instanceId: string
  tokenName: string | null
  firstSeenAt: string
  lastSeenAt: string
  crabVersion: string | null
}

export interface Alert {
  id: number
  createdAt: string
  kind: string
  conch: string
  conchVersion: string | null
  detail: Record<string, unknown> | null
  acknowledgedAt: string | null
  acknowledgedBy: string | null
}

export interface AuditEntry {
  id: number
  at: string
  actor: string
  action: string
  conch: string | null
  version: string | null
  detail: string | null
}

export interface AuditPage {
  items: AuditEntry[]
  page: number
  size: number
  total: number
}

export interface PurgeCounts {
  failureReports: number
  rollbackEvents: number
  loginFailures: number
}

export const api = {
  login: (username: string, password: string) =>
    http.post<{ token: string; expiresAt: string }>('/api/admin/login', { username, password }),
  me: () => http.get<{ username: string; role: AdminRole }>('/api/admin/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<AdminUser>('/api/admin/me/password', { oldPassword, newPassword }),
  signingKey: () => http.get<{ publicKey: string }>('/api/admin/signing-key'),

  users: () => http.get<AdminUser[]>('/api/admin/users'),
  createUser: (username: string, password: string, role: AdminRole) =>
    http.post<AdminUser>('/api/admin/users', { username, password, role }),
  updateUser: (username: string, body: { role?: AdminRole; disabled?: boolean }) =>
    http.patch<AdminUser>(`/api/admin/users/${seg(username)}`, body),
  resetPassword: (username: string, password: string) =>
    http.post<AdminUser>(`/api/admin/users/${seg(username)}/password`, { password }),

  tokens: (all = false) => http.get<AgentToken[]>('/api/admin/tokens', all ? { all: true } : undefined),
  createToken: (name: string) => http.post<IssuedAgentToken>('/api/admin/tokens', { name }),
  revokeToken: (id: number) => http.del<AgentToken>(`/api/admin/tokens/${id}`),
  crabs: () => http.get<CrabInstance[]>('/api/admin/crabs'),

  conches: () => http.get<ConchSummary[]>('/api/admin/conches'),
  conch: (conch: string) => http.get<ConchDetail>(`/api/admin/conches/${seg(conch)}`),
  versions: (conch: string) => http.get<ConchVersion[]>(`/api/admin/conches/${seg(conch)}/versions`),
  yaml: (conch: string, version: string) =>
    http.text(`/api/admin/conches/${seg(conch)}/versions/${seg(version)}/yaml`),
  /** 都不传时对比该包 semver 最高的两个版本，高的为 to。只传一个仍是 400。少于两个版本也是 400。 */
  diff: (conch: string, from?: string, to?: string) =>
    http.get<ConchDiff>(`/api/admin/conches/${seg(conch)}/diff`, { from, to }),
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<UploadResult>('/api/admin/conches', form)
  },
  publish: (conch: string, version: string) =>
    http.post<{ conch: string; version: string }>(
      `/api/admin/conches/${seg(conch)}/versions/${seg(version)}/publish`,
    ),
  discardVersion: (conch: string, version: string) =>
    http.del<{ conch: string; version: string }>(`/api/admin/conches/${seg(conch)}/versions/${seg(version)}`),
  disableConch: (conch: string, reason: string) =>
    http.post<ConchAvailability>(`/api/admin/conches/${seg(conch)}/disable`, { reason }),
  enableConch: (conch: string, reason: string) =>
    http.post<ConchAvailability>(`/api/admin/conches/${seg(conch)}/enable`, { reason }),

  tideSummary: (query: { conch?: string; from?: string; to?: string; bucket?: string }) =>
    http.get<TideSummary>('/api/admin/tide/summary', query),
  tideFailures: (query: { conch?: string; version?: string; action?: string; from?: string; to?: string }) =>
    http.get<{ groups: FailureGroup[] }>('/api/admin/tide/failures', query),
  tideCompare: (conch: string, versions?: string) =>
    http.get<{ conch: string; versions: VersionStats[] }>('/api/admin/tide/compare', { conch, versions }),
  tideRollbacks: (query: { conch?: string; from?: string; to?: string }) =>
    http.get<{ events: RollbackEvent[] }>('/api/admin/tide/rollbacks', query),

  alerts: (status?: 'open' | 'all') => http.get<{ alerts: Alert[] }>('/api/admin/alerts', { status }),
  ackAlert: (id: number) => http.post<Alert>(`/api/admin/alerts/${id}/ack`),

  audit: (query: { conch?: string | undefined; page: number; size: number }) => http.get<AuditPage>('/api/admin/audit', query),

  purgeRetention: () => http.post<PurgeCounts>('/api/admin/retention/purge'),
}
