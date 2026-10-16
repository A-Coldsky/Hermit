import { createClient, seg } from '@shared/http'

const http = createClient({ headers: () => ({ 'X-Hermit-Console': '1' }) })
const base = '/console/api'

export interface ShellSummary {
  shell: string
  version: string
  title: string
  capabilityCount: number
  accountCount: number
  needsConfiguration: boolean
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

export interface ShellDetail {
  shell: string
  version: string
  title: string
  description: string
  credentials: CredentialField[]
  config: ConfigDeclaration[]
  capabilities: CapabilityState[]
}

export interface Account {
  name: string
  default: boolean
  needsConfiguration: boolean
  missingFields: string[]
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
  shells: string[] | null
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

export interface CallSummary {
  traceId: string
  at: string
  origin: string
  shell: string
  action: string
  account: string | null
  shellVersion: string
  success: boolean
  errorCode: string | null
  errorMessage: string | null
  durationMs: number
  apiKeyId: string | null
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

export interface SyncReport {
  notModified: boolean
  shells: { shell: string; version: string; kind: string | null; reason: string | null }[]
  removed: string[]
  credentialChanges: { shell: string; addedRequired: string[]; removed: string[] }[]
  latestCrab: CrabRelease | null
}

export interface ReportBatch {
  instanceId: string
  crabVersion: string
  sentAt: string
  failures: {
    traceId: string
    shell: string
    shellVersion: string
    action: string
    stepIndex: number | null
    stepType: string | null
    errorCode: string
    summary: string | null
    occurredAt: string
  }[]
  counts: {
    shell: string
    shellVersion: string
    action: string
    hourStart: string
    success: number
    failure: number
    avgMillis: number
    p95Millis: number
  }[]
  events: {
    shell: string
    fromVersion: string
    toVersion: string
    reason: string
    occurredAt: string
    eventId: string
  }[]
}

const shellPath = (shell: string) => `${base}/shells/${seg(shell)}`
const accountPath = (shell: string, account: string) => `${shellPath(shell)}/accounts/${seg(account)}`

export const api = {
  shells: () => http.get<{ shells: ShellSummary[] }>(`${base}/shells`),
  shell: (shell: string) => http.get<ShellDetail>(shellPath(shell)),

  accounts: (shell: string) => http.get<{ accounts: Account[] }>(`${shellPath(shell)}/accounts`),
  createAccount: (shell: string, name: string) =>
    http.post<{ name: string; default: boolean }>(`${shellPath(shell)}/accounts`, { name }),
  deleteAccount: (shell: string, account: string) => http.del<{ deleted: boolean }>(accountPath(shell, account)),
  makeDefault: (shell: string, account: string) => http.post<{ name: string }>(`${accountPath(shell, account)}/default`),
  credentialNames: (shell: string, account: string) =>
    http.get<{ fields: string[] }>(`${accountPath(shell, account)}/credentials`),
  writeCredentials: (shell: string, account: string, values: Record<string, string>) =>
    http.put<{ updated: boolean }>(`${accountPath(shell, account)}/credentials`, values),
  testLogin: (shell: string, account: string) => http.post<unknown>(`${accountPath(shell, account)}/test-login`),

  config: (shell: string) => http.get<{ fields: Record<string, ConfigValue> }>(`${shellPath(shell)}/config`),
  writeConfig: (shell: string, values: Record<string, string>) =>
    http.put<{ updated: boolean }>(`${shellPath(shell)}/config`, values),

  setCapability: (shell: string, action: string, enabled: boolean) =>
    http.post<{ disabled: boolean }>(`${base}/capabilities/${seg(shell)}/${seg(action)}/${enabled ? 'enable' : 'disable'}`),

  apiKeys: () => http.get<{ apiKeys: ApiKey[] }>(`${base}/apikeys`),
  createApiKey: (body: { name: string; readOnly: boolean; shells: string[]; actions: string[] }) =>
    http.post<CreatedApiKey>(`${base}/apikeys`, body),
  revokeApiKey: (id: string) => http.del<{ revoked: boolean }>(`${base}/apikeys/${seg(id)}`),

  calls: (query: { shell?: string; action?: string; limit?: number }) =>
    http.get<{ calls: CallSummary[] }>(`${base}/calls`, query),
  call: (traceId: string) => http.get<CallDetail>(`${base}/calls/${seg(traceId)}`),

  debug: () => http.get<DebugState>(`${base}/debug`),
  setDebug: (enabled: boolean) => http.post<DebugState>(`${base}/debug`, { enabled }),

  syncStatus: () => http.get<{ report: SyncReport | null; latestCrab: CrabRelease | null }>(`${base}/sync`),
  sync: () => http.post<SyncReport>(`${base}/sync`),

  reportPreview: () => http.get<{ batch: ReportBatch; dropped: number }>(`${base}/reports/preview`),
  sendReport: () => http.post<{ attempted: boolean; accepted: boolean; status: number }>(`${base}/reports/send`),
}
