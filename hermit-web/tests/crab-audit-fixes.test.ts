import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { deferred, mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conch: vi.fn(),
  conches: vi.fn(),
  localConches: vi.fn(),
  pendingLocalOverwrites: vi.fn(),
  localOverrideGrants: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
  debug: vi.fn(),
  credentialNames: vi.fn(),
  writeCredentials: vi.fn(),
  catalog: vi.fn(),
  subscribe: vi.fn(),
  sync: vi.fn(),
  syncStatus: vi.fn(),
  tlsTrust: vi.fn(),
  tlsFailures: vi.fn(),
  probeTls: vi.fn(),
  calls: vi.fn(),
  call: vi.fn(),
  reportPreview: vi.fn(),
  reportSettings: vi.fn(),
  sendReport: vi.fn(),
  files: vi.fn(),
  testLogin: vi.fn(),
  apiKeys: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import type { Account, ConchDetail, TlsFailure, TlsProbeResult } from '../apps/crab-console/src/api'
import AccountsPanel from '../apps/crab-console/src/components/AccountsPanel.vue'
import CredentialDialog from '../apps/crab-console/src/components/CredentialDialog.vue'
import DevTracePanel from '../apps/crab-console/src/components/DevTracePanel.vue'
import FieldInput from '../apps/crab-console/src/components/FieldInput.vue'
import { configAuditLabels, stepTypeLabels } from '../apps/crab-console/src/labels'
import { isPinnableTlsKind, plainFailure, transportKindLabels, trustLocation } from '../apps/crab-console/src/tls'
import CallsView from '../apps/crab-console/src/views/CallsView.vue'
import CatalogView from '../apps/crab-console/src/views/CatalogView.vue'
import ConchDetailView from '../apps/crab-console/src/views/ConchDetailView.vue'
import ConchesView from '../apps/crab-console/src/views/ConchesView.vue'
import FilesView from '../apps/crab-console/src/views/FilesView.vue'
import OverviewView from '../apps/crab-console/src/views/OverviewView.vue'
import ReportsView from '../apps/crab-console/src/views/ReportsView.vue'
import TrustView from '../apps/crab-console/src/views/TrustView.vue'

function sampleDetail(over: Partial<ConchDetail> = {}): ConchDetail {
  return {
    conch: 'legacy_oa',
    version: '1.0.0',
    title: '协同办公 OA',
    description: '待办和审批',
    credentials: [{ name: 'password', type: 'secret', label: '密码', required: true, description: '填原始密码', options: null }],
    config: [],
    capabilities: [
      { action: 'list_todo', title: '待办列表', description: '', method: 'GET', readOnly: true, disabled: false },
    ],
    subscribed: true,
    needsConfiguration: false,
    loginPaused: false,
    source: 'REEF',
    driver: 'http',
    reefDisabled: false,
    reefDisabledAt: null,
    updateAvailable: false,
    latestVersion: null,
    newRequiredCredentials: [],
    ...over,
  }
}

function sampleAccount(over: Partial<Account> = {}): Account {
  return {
    name: 'zhangsan',
    default: true,
    needsConfiguration: false,
    missingFields: [],
    credentialsUnreadable: false,
    credentialMessage: null,
    loginPaused: false,
    loginPauseReason: null,
    loginPauseRecovery: null,
    loginInputRequired: false,
    loginInputExpiresAt: null,
    oauth: null,
    ...over,
  }
}

function sampleFailure(over: Partial<TlsFailure> = {}): TlsFailure {
  return {
    at: '2026-11-22T10:00:00Z',
    host: 'oa.internal',
    port: 443,
    kind: 'TLS_UNTRUSTED',
    message: 'handshake failed',
    chain: [],
    ...over,
  }
}

function sampleProbe(over: Partial<TlsProbeResult> = {}): TlsProbeResult {
  return {
    host: 'oa.internal',
    port: 443,
    trusted: false,
    kind: 'TLS_UNTRUSTED',
    message: 'untrusted',
    chain: [
      {
        subject: 'CN=oa.internal',
        issuer: 'CN=Internal CA',
        notBefore: '2026-01-01T00:00:00Z',
        notAfter: '2028-01-01T00:00:00Z',
        sha256: 'AA:BB:CC:DD',
        ca: false,
      },
    ],
    ...over,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.conch.mockResolvedValue(sampleDetail())
  crab.conches.mockResolvedValue({ conches: [] })
  crab.localConches.mockResolvedValue({ conches: [] })
  crab.pendingLocalOverwrites.mockResolvedValue({ pending: [] })
  crab.localOverrideGrants.mockResolvedValue({ grants: [] })
  crab.accounts.mockResolvedValue({ accounts: [sampleAccount()] })
  crab.config.mockResolvedValue({ fields: {} })
  crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
  crab.credentialNames.mockResolvedValue({ fields: [] })
  crab.writeCredentials.mockResolvedValue({ updated: true })
  crab.catalog.mockResolvedValue({ conches: [], syncedAt: null, configured: true })
  crab.syncStatus.mockResolvedValue({ report: null, latestCrab: null, intervalSeconds: 300 })
  crab.tlsTrust.mockResolvedValue({ sources: [], caFiles: [], trustedHosts: [], loadErrors: [] })
  crab.tlsFailures.mockResolvedValue({ failures: [] })
  crab.calls.mockResolvedValue({ calls: [] })
  crab.reportPreview.mockResolvedValue({
    batch: {
      instanceId: 'crab-1',
      crabVersion: '1.9.0',
      counts: [{ hourStart: '2026-11-22T10:00:00Z', conch: 'legacy_oa', action: 'list_todo', conchVersion: '1.0.0', success: 1, failure: 0, avgMillis: 12 }],
      failures: [],
      events: [],
    },
    dropped: 0,
  })
  crab.reportSettings.mockResolvedValue({ enabled: true, intervalSeconds: 300 })
  crab.apiKeys.mockResolvedValue({ apiKeys: [] })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('H3: ConchDetailView 卸载与过期响应守卫', () => {
  it('加载途中卸载组件时，不会对新路由调用 router.replace', async () => {
    const pendingConch = deferred<ConchDetail>()
    crab.conch.mockReturnValueOnce(pendingConch.promise)

    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, path: '/conches/legacy_oa' })
    const replaceSpy = vi.spyOn(wrapper.router, 'replace')

    await wrapper.router.push('/calls')
    wrapper.unmount()

    pendingConch.resolve(sampleDetail())
    await flushPromises()

    expect(replaceSpy).not.toHaveBeenCalled()
  })

  it('快速切换规则包时，较慢的旧规则包响应不会覆盖新规则包状态', async () => {
    const slowA = deferred<ConchDetail>()
    const fastB = deferred<ConchDetail>()
    crab.conch.mockReturnValueOnce(slowA.promise).mockReturnValueOnce(fastB.promise)

    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'oa_a' }, path: '/conches/oa_a' })
    await wrapper.setProps({ conch: 'oa_b' })

    fastB.resolve(sampleDetail({ conch: 'oa_b', title: '新规则包 B' }))
    await flushPromises()
    expect(wrapper.text()).toContain('新规则包 B')

    slowA.resolve(sampleDetail({ conch: 'oa_a', title: '旧规则包 A' }))
    await flushPromises()
    expect(wrapper.text()).toContain('新规则包 B')
    expect(wrapper.text()).not.toContain('旧规则包 A')
    wrapper.unmount()
  })
})

describe('H4: CredentialDialog 切换账号清理与无法解密可选字段重填', () => {
  const fields = [
    { name: 'password', label: '密码', type: 'secret', description: '必填密码', required: true, options: null },
    { name: 'token', label: '令牌', type: 'secret', description: '可选令牌', required: false, options: null },
    { name: 'note', label: '备注', type: 'string', description: '可选备注', required: false, options: null },
  ]

  it('切换账号时立即清掉旧账号已保存标签，且忽略延迟返回的旧账号凭证名列表', async () => {
    const slowAlice = deferred<{ fields: string[] }>()
    const fastBob = deferred<{ fields: string[] }>()
    crab.credentialNames.mockReturnValueOnce(slowAlice.promise).mockReturnValueOnce(fastBob.promise)

    const wrapper = await mountPage(CredentialDialog, {
      props: { open: false, conch: 'legacy_oa', account: 'alice', fields },
      attach: true,
    })
    await wrapper.setProps({ open: true })
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true, account: 'bob' })

    const tagTexts = () => [...document.body.querySelectorAll('.el-tag')].map((el) => el.textContent?.trim())

    fastBob.resolve({ fields: [] })
    await flushPromises()
    expect(tagTexts()).not.toContain('已保存')

    slowAlice.resolve({ fields: ['password', 'token'] })
    await flushPromises()
    expect(tagTexts()).not.toContain('已保存')
    wrapper.unmount()
  })

  it('无法解密时，已保存过的可选字段标为「需重填」且禁止留空；从未保存的可选字段不标「需重填」', async () => {
    crab.credentialNames.mockResolvedValue({ fields: ['password', 'token'] })
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)

    const wrapper = await mountPage(CredentialDialog, {
      props: { open: false, conch: 'legacy_oa', account: 'zhangsan', fields, unreadable: true },
      attach: true,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()

    const items = [...document.body.querySelectorAll('.el-form-item')]
    const itemOf = (name: string) => items.find((el) => el.textContent?.includes(name))!
    expect(itemOf('password').textContent).toContain('需重填')
    expect(itemOf('token').textContent).toContain('需重填')
    expect(itemOf('note').textContent).not.toContain('需重填')

    // 只填 password、留空曾保存过的可选字段 token：应该拦截提交并提示补填 token
    const passwordInput = itemOf('password').querySelector('input') as HTMLInputElement
    passwordInput.value = 'secret-pass'
    passwordInput.dispatchEvent(new Event('input'))
    await flushPromises()

    const saveBtn = [...document.body.querySelectorAll('button')].find((b) => b.textContent?.trim() === '保存')!
    saveBtn.click()
    await flushPromises()
    expect(warning).toHaveBeenCalled()
    expect(String(warning.mock.calls.at(-1)?.[0])).toContain('令牌（token）')
    expect(crab.writeCredentials).not.toHaveBeenCalled()

    // 补填 token、保持从未保存过的 note 为空：应该成功提交
    const tokenInput = itemOf('token').querySelector('input') as HTMLInputElement
    tokenInput.value = 'secret-token'
    tokenInput.dispatchEvent(new Event('input'))
    await flushPromises()

    saveBtn.click()
    await flushPromises()
    expect(crab.writeCredentials).toHaveBeenCalledWith('legacy_oa', 'zhangsan', {
      password: 'secret-pass',
      token: 'secret-token',
    })
    expect(success).toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('M9: DevTracePanel 空 body 与 step.variables 渲染', () => {
  it('requestBody/responseBody 为 null 时不渲染空白块，存在 variables 时渲染变量清单', async () => {
    const trace = {
      untrusted: true,
      note: '正文已脱敏',
      kind: 'http' as const,
      truncated: false,
      steps: [
        {
          index: 1,
          phase: 'action',
          type: 'request',
          durationMs: 10,
          method: 'GET',
          path: '/items',
          status: 204,
          requestBody: null,
          responseBody: null,
        },
        {
          index: 2,
          phase: 'action',
          type: 'extract',
          durationMs: 2,
          extractors: ['json'],
          variables: [
            { name: 'session_id', summary: 'abc***' },
            { name: 'items', summary: ['a', 'b'] },
          ],
        },
      ],
    }

    const wrapper = await mountPage(DevTracePanel, { props: { trace } })
    await flushPromises()

    const dts = wrapper.findAll('dt').map((dt) => dt.text())
    expect(dts).not.toContain('请求体')
    expect(dts).not.toContain('响应体')
    expect(dts).toContain('变量')

    const vars = wrapper.get('[data-test="step-variables"]')
    expect(vars.text()).toContain('session_id')
    expect(vars.text()).toContain('abc***')
    expect(vars.text()).toContain('items')
    wrapper.unmount()
  })
})

describe('M10: FieldInput 无 Vue 运行时 prop 警告且透传 disabled', () => {
  it('省略 options/type 或传入 options: null 时不产生任何 Vue warn，且透传 disabled', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const w1 = mount(FieldInput, {
      props: { modelValue: 'hello' },
      global: { plugins: [ElementPlus] },
    })
    const w2 = mount(FieldInput, {
      props: { modelValue: '', options: null, disabled: true },
      global: { plugins: [ElementPlus] },
    })
    const w3 = mount(FieldInput, {
      props: { modelValue: 'a', type: 'select', options: ['a', 'b'], disabled: true },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(warnSpy).not.toHaveBeenCalled()
    expect(w2.get('input').attributes('disabled')).toBeDefined()
    expect(w3.findComponent({ name: 'ElSelect' }).props('disabled')).toBe(true)

    w1.unmount()
    w2.unmount()
    w3.unmount()
  })
})

describe('M11: 枚举中文翻译与不可固定 TLS 错误限制', () => {
  it('CatalogView 订阅未立即安装时翻译 SyncReasons 枚举', async () => {
    crab.catalog.mockResolvedValue({
      conches: [
        {
          conch: 'legacy_oa',
          version: '2.0.0',
          title: '协同办公 OA',
          description: '',
          capabilityCount: 1,
          subscribed: false,
          installed: false,
          installedVersion: null,
          disabled: false,
          localConflict: false,
          ruleDisabled: false,
        },
      ],
      syncedAt: '2026-11-22T10:00:00Z',
      configured: true,
    })
    crab.subscribe.mockResolvedValue({
      conch: 'legacy_oa',
      subscribed: true,
      outcome: { conch: 'legacy_oa', version: '2.0.0', kind: 'SKIPPED_MIN_VERSION', reason: 'MIN_CRAB_VERSION' },
    })
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)

    const wrapper = await mountPage(CatalogView)
    await flushPromises()

    const btn = wrapper.findAll('button').find((b) => b.text().trim() === '订阅')!
    await btn.trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledWith('已订阅 legacy_oa，但这次没有安装（Crab 版本过低：需要更高版本的 Crab）')
    wrapper.unmount()
  })

  it('labels.ts 覆盖 CONSOLE_LOGIN 与 if/repeat/ask/mcp_call 中文标签', () => {
    expect(configAuditLabels.CONSOLE_LOGIN?.text).toBe('控制台登录')
    expect(stepTypeLabels.if).toBe('条件判断')
    expect(stepTypeLabels.repeat).toBe('循环')
    expect(stepTypeLabels.ask).toBe('登录输入')
    expect(stepTypeLabels.mcp_call).toBe('MCP 调用')
  })

  it('tls.ts 翻译新增 6 种传输错误，并对 TLS_PROTOCOL 等不可固定错误隐藏信任站点入口', async () => {
    for (const kind of ['TLS_PROTOCOL', 'TLS_CIPHER', 'TLS_CERT_ALGORITHM', 'TLS_SNI', 'NO_RESPONSE', 'HEADER_LIMIT']) {
      expect(transportKindLabels[kind]).toBeTruthy()
      expect(plainFailure({ kind, host: 'oa.internal' })).not.toBe('oa.internal 的连接失败了，详情里有原因。')
    }

    expect(isPinnableTlsKind('TLS_PROTOCOL')).toBe(false)
    expect(isPinnableTlsKind('TLS_CIPHER')).toBe(false)
    expect(isPinnableTlsKind('TLS_CERT_ALGORITHM')).toBe(false)
    expect(isPinnableTlsKind('TLS_SNI')).toBe(false)
    expect(isPinnableTlsKind('TLS_UNTRUSTED')).toBe(true)

    expect(trustLocation({ kind: 'TLS_PROTOCOL', host: 'oa.internal', port: 443 })).toBeNull()
    expect(trustLocation({ kind: 'TLS_UNTRUSTED', host: 'oa.internal', port: 443 })).toEqual({
      path: '/trust',
      query: { host: 'oa.internal', port: '443' },
    })

    crab.tlsFailures.mockResolvedValue({
      failures: [
        sampleFailure({ host: 'old-tls.internal', kind: 'TLS_PROTOCOL' }),
        sampleFailure({ host: 'self-signed.internal', kind: 'TLS_UNTRUSTED' }),
      ],
    })
    const wrapper = await mountPage(TrustView)
    await flushPromises()

    const rows = wrapper.findAll('[data-test="failure-card"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('TLS 协议版本过旧')
    expect(rows[0].find('[data-test="trust-site"]').exists()).toBe(false)
    expect(rows[1].find('[data-test="trust-site"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('M12: CallsView 与 TrustView 竞态和抽屉返回行为', () => {
  it('CallsView 详情加载失败时在抽屉里展示错误和重试，重试成功后恢复，移除 ?trace= 关闭抽屉', async () => {
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)
    crab.calls.mockResolvedValue({
      calls: [
        {
          traceId: 't-ok',
          at: '2026-11-22T10:00:00Z',
          origin: 'REEF',
          channel: 'HTTP',
          conch: 'legacy_oa',
          action: 'list_todo',
          account: 'zhangsan',
          conchVersion: '1.0.0',
          success: true,
          errorCode: null,
          errorMessage: null,
          durationMs: 12,
          apiKeyId: null,
        },
        {
          traceId: 't-fail',
          at: '2026-11-22T10:01:00Z',
          origin: 'REEF',
          channel: 'HTTP',
          conch: 'legacy_oa',
          action: 'approve',
          account: 'zhangsan',
          conchVersion: '1.0.0',
          success: false,
          errorCode: 'UPSTREAM_ERROR',
          errorMessage: 'bad gateway',
          durationMs: 45,
          apiKeyId: null,
        },
      ],
    })
    crab.call.mockRejectedValueOnce(new Error('network blip')).mockResolvedValueOnce({
      traceId: 't-fail',
      at: '2026-11-22T10:01:00Z',
      origin: 'REEF',
      channel: 'HTTP',
      conch: 'legacy_oa',
      action: 'approve',
      account: 'zhangsan',
      conchVersion: '1.0.0',
      success: false,
      errorCode: 'UPSTREAM_ERROR',
      errorMessage: 'bad gateway',
      durationMs: 45,
      apiKeyId: null,
      steps: [],
      devTrace: null,
    })

    const wrapper = await mountPage(CallsView, { path: '/calls?trace=t-fail&failed=1', attach: true })
    await flushPromises()

    expect(errorSpy).toHaveBeenCalled()
    const errAlert = document.body.querySelector('[data-test="call-drawer-error"]') as HTMLElement
    expect(errAlert).not.toBeNull()
    expect(errAlert.textContent).toContain('调用详情读取失败')
    expect(errAlert.textContent).toContain('network blip')

    ;(errAlert.querySelector('button') as HTMLButtonElement).click()
    await flushPromises()
    expect(document.body.querySelector('[data-test="call-drawer-error"]')).toBeNull()
    expect(document.body.textContent).toContain('legacy_oa.approve')

    // 浏览器后退移除 ?trace= 与 ?failed=1 时，抽屉关闭且恢复显示成功记录
    await wrapper.router.push('/calls')
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('modelValue')).toBe(false)
    expect(wrapper.text()).toContain('list_todo')
    wrapper.unmount()
  })

  it('TrustView 切换探测目标时，较慢的旧站点探测结果不会覆盖新站点弹窗', async () => {
    const slowA = deferred<TlsProbeResult>()
    const fastB = deferred<TlsProbeResult>()
    crab.tlsFailures.mockResolvedValue({
      failures: [
        sampleFailure({ host: 'a.internal', port: 443 }),
        sampleFailure({ host: 'b.internal', port: 443 }),
      ],
    })
    crab.probeTls.mockReturnValueOnce(slowA.promise).mockReturnValueOnce(fastB.promise)

    const wrapper = await mountPage(TrustView, { attach: true })
    await flushPromises()

    const buttons = wrapper.findAll('[data-test="trust-site"]')
    await buttons[0].trigger('click')
    await flushPromises()

    // 关闭 A 的弹窗，改为探测 B
    const closeBtn = document.body.querySelector('.el-dialog__headerbtn') as HTMLElement
    closeBtn.click()
    await flushPromises()

    await buttons[1].trigger('click')
    fastB.resolve(sampleProbe({ host: 'b.internal', chain: [{ ...sampleProbe().chain[0], subject: 'CN=b.internal' }] }))
    await flushPromises()
    expect(document.body.textContent).toContain('CN=b.internal')

    slowA.resolve(sampleProbe({ host: 'a.internal', chain: [{ ...sampleProbe().chain[0], subject: 'CN=a.internal' }] }))
    await flushPromises()
    expect(document.body.textContent).toContain('CN=b.internal')
    expect(document.body.textContent).not.toContain('CN=a.internal')
    wrapper.unmount()
  })
})

describe('M13 & M14: 错误与空态分离、ReportsView、AccountsPanel 与 OverviewView', () => {
  it('ReportsView 上报返回 status=0 时提示无法连接到 Reef 而不是 HTTP 0，刷新同时重载设置', async () => {
    crab.sendReport.mockResolvedValue({ attempted: true, accepted: false, status: 0 })
    const errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)

    const wrapper = await mountPage(ReportsView)
    await flushPromises()
    expect(crab.reportPreview).toHaveBeenCalledTimes(1)
    expect(crab.reportSettings).toHaveBeenCalledTimes(1)

    const sendBtn = wrapper.findAll('button').find((b) => b.text().includes('立即上报'))!
    await sendBtn.trigger('click')
    await flushPromises()

    expect(errorSpy).toHaveBeenCalledWith('无法连接到 Reef，上报未送达')
    expect(String(errorSpy.mock.calls.at(-1)?.[0])).not.toContain('HTTP 0')

    const refreshBtn = wrapper.findAll('button').find((b) => b.text().trim() === '刷新')!
    await refreshBtn.trigger('click')
    await flushPromises()
    expect(crab.reportSettings).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('ConchesView / FilesView / CatalogView / CallsView 加载失败时显示错误与重试，不误显空态', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)

    crab.conches.mockRejectedValueOnce(new Error('conch err'))
    const wConches = await mountPage(ConchesView)
    await flushPromises()
    expect(wConches.find('[data-test="conches-load-failed"]').exists()).toBe(true)
    expect(wConches.text()).not.toContain('还没有规则包')
    wConches.unmount()

    crab.files.mockRejectedValueOnce(new Error('file err'))
    const wFiles = await mountPage(FilesView)
    await flushPromises()
    expect(wFiles.find('[data-test="files-load-failed"]').exists()).toBe(true)
    expect(wFiles.text()).not.toContain('暂存区里没有文件')
    wFiles.unmount()

    crab.catalog.mockRejectedValueOnce(new Error('catalog err'))
    const wCatalog = await mountPage(CatalogView)
    await flushPromises()
    expect(wCatalog.find('[data-test="catalog-load-failed"]').exists()).toBe(true)
    expect(wCatalog.text()).not.toContain('Reef 上还没有已发布的规则包')
    wCatalog.unmount()

    crab.calls.mockRejectedValueOnce(new Error('calls err'))
    const wCalls = await mountPage(CallsView)
    await flushPromises()
    expect(wCalls.find('[data-test="calls-load-failed"]').exists()).toBe(true)
    expect(wCalls.text()).not.toContain('还没有调用记录')
    wCalls.unmount()
  })

  it('AccountsPanel 测试登录失败带 requestId 时显示服务端 requestNote 或准确兜底文案', async () => {
    crab.testLogin.mockRejectedValueOnce(
      new ApiError(502, 'UPSTREAM_ERROR', '登录失败', null, null, 'req-123', null),
    )

    const wrapper = await mountPage(AccountsPanel, {
      props: { conch: 'legacy_oa', accounts: [sampleAccount()], credentials: [] },
      attach: true,
    })
    await flushPromises()

    const testBtn = wrapper.findAll('button').find((b) => b.text().trim() === '测试登录')!
    await testBtn.trigger('click')
    await flushPromises()

    const bodyText = document.body.textContent ?? ''
    expect(bodyText).toContain('req-123')
    expect(bodyText).toContain('测试登录不记录调用追踪。')
    expect(bodyText).not.toContain('执行前就被拒绝了，没有追踪可查。')
    wrapper.unmount()
  })

  it('AccountsPanel 开发者模式下测试登录失败带 traceId 时展示可跳转与可复制的开发追踪编号', async () => {
    crab.testLogin.mockRejectedValueOnce(
      new ApiError(422, 'AUTH_FAILED', '登录失败', null, 'trace-login-1', null, null),
    )

    const wrapper = await mountPage(AccountsPanel, {
      props: { conch: 'local_oa', accounts: [sampleAccount()], credentials: [] },
      attach: true,
    })
    await flushPromises()

    const testBtn = wrapper.findAll('button').find((b) => b.text().trim() === '测试登录')!
    await testBtn.trigger('click')
    await flushPromises()

    const traceBox = document.body.querySelector('[data-test="test-login-trace"]')
    expect(traceBox).not.toBeNull()
    expect(traceBox?.textContent).toContain('trace-login-1')
    expect(traceBox?.textContent).toContain('debug_trace')
    expect(traceBox?.querySelector('a')?.getAttribute('href')).toContain('trace-login-1')
    wrapper.unmount()
  })

  it('OverviewView 同步结果缺少版本号时不显示悬空的 v', async () => {
    crab.syncStatus.mockResolvedValue({
      report: {
        notModified: false,
        error: null,
        latestCrab: null,
        removed: [],
        credentialChanges: [],
        conches: [
          { conch: 'legacy_oa', version: null, kind: 'FAILED', reason: 'DOWNLOAD_FAILED' },
        ],
      },
      latestCrab: null,
      intervalSeconds: 300,
    })

    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    const itemText = wrapper.get('.outcomes li .mono').text()
    expect(itemText).toBe('legacy_oa')
    wrapper.unmount()
  })
})
