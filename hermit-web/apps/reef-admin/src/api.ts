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

export interface ShellDiff {
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

export interface ShellSummary {
  shell: string
  version: string | null
  title: string
  description: string
  owner: string | null
  tags: string[]
  capabilityCount: number
  publishedAt: string | null
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

export interface ShellDetail {
  shell: string
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
}

export interface ShellVersion {
  version: string
  status: 'UPLOADED' | 'PUBLISHED' | string
  uploadedBy: string
  uploadedAt: string
  publishedBy: string | null
  publishedAt: string | null
  current: boolean
  warningCount: number
  warnings: ValidationIssue[]
}

export interface UploadResult {
  shell: string
  version: string
  warnings: ValidationIssue[]
  diff: Partial<ShellDiff>
}

export interface TidePoint {
  shell: string
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
  shell: string
  shellVersion: string
  action: string
  stepIndex: number | null
  stepType: string | null
  errorCode: string
  count: number
  recent: { summary: string | null; traceId: string }[]
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
  shell: string
  fromVersion: string
  toVersion: string
  reason: string
  occurredAt: string
  receivedAt: string
}

export interface Alert {
  id: number
  createdAt: string
  kind: string
  shell: string
  shellVersion: string | null
  detail: Record<string, unknown> | null
  acknowledgedAt: string | null
  acknowledgedBy: string | null
}

export interface AuditEntry {
  id: number
  at: string
  actor: string
  action: string
  shell: string | null
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
  me: () => http.get<{ username: string }>('/api/admin/me'),
  signingKey: () => http.get<{ publicKey: string }>('/api/admin/signing-key'),

  shells: () => http.get<ShellSummary[]>('/api/admin/shells'),
  shell: (shell: string) => http.get<ShellDetail>(`/api/admin/shells/${seg(shell)}`),
  versions: (shell: string) => http.get<ShellVersion[]>(`/api/admin/shells/${seg(shell)}/versions`),
  yaml: (shell: string, version: string) =>
    http.text(`/api/admin/shells/${seg(shell)}/versions/${seg(version)}/yaml`),
  diff: (shell: string, from?: string, to?: string) =>
    http.get<ShellDiff>(`/api/admin/shells/${seg(shell)}/diff`, { from, to }),
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.upload<UploadResult>('/api/admin/shells', form)
  },
  publish: (shell: string, version: string) =>
    http.post<{ shell: string; version: string }>(
      `/api/admin/shells/${seg(shell)}/versions/${seg(version)}/publish`,
    ),
  rollback: (shell: string, version: string) =>
    http.post<{ shell: string; from: string | null; to: string }>(`/api/admin/shells/${seg(shell)}/rollback`, {
      version,
    }),

  tideSummary: (query: { shell?: string; from?: string; to?: string; bucket?: string }) =>
    http.get<TideSummary>('/api/admin/tide/summary', query),
  tideFailures: (query: { shell?: string; version?: string; action?: string; from?: string; to?: string }) =>
    http.get<{ groups: FailureGroup[] }>('/api/admin/tide/failures', query),
  tideCompare: (shell: string, versions?: string) =>
    http.get<{ shell: string; versions: VersionStats[] }>('/api/admin/tide/compare', { shell, versions }),
  tideRollbacks: (query: { shell?: string; from?: string; to?: string }) =>
    http.get<{ events: RollbackEvent[] }>('/api/admin/tide/rollbacks', query),

  alerts: (status?: 'open' | 'all') => http.get<{ alerts: Alert[] }>('/api/admin/alerts', { status }),
  ackAlert: (id: number) => http.post<Alert>(`/api/admin/alerts/${id}/ack`),

  audit: (query: { shell?: string | undefined; page: number; size: number }) => http.get<AuditPage>('/api/admin/audit', query),

  purgeRetention: () => http.post<PurgeCounts>('/api/admin/retention/purge'),
}
