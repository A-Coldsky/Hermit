import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { House, Box } from '@element-plus/icons-vue'
import { markRaw } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ApiError } from '../shared/http'
import { copyText, relativeTime } from '../shared/format'
import AppLayout from '../shared/components/AppLayout.vue'
import { issueHints } from '../apps/reef-admin/src/labels'
import { session, setRole, signIn, signOut } from '../apps/reef-admin/src/auth'
import { deferred, mountPage } from './helpers'

const reef = vi.hoisted(() => ({
  me: vi.fn(),
  conches: vi.fn(),
  conch: vi.fn(),
  versions: vi.fn(),
  diff: vi.fn(),
  disableConch: vi.fn(),
  enableConch: vi.fn(),
  tideSummary: vi.fn(),
  tideFailures: vi.fn(),
  tideCompare: vi.fn(),
  tideRollbacks: vi.fn(),
  alerts: vi.fn(),
  tokens: vi.fn(),
  crabs: vi.fn(),
  createToken: vi.fn(),
  users: vi.fn(),
  audit: vi.fn(),
  signingKey: vi.fn(),
}))

const alertsMock = vi.hoisted(() => ({
  refreshOpenAlerts: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/alerts', async () => {
  const { ref } = await import('vue')
  return { openAlerts: ref(0), refreshOpenAlerts: alertsMock.refreshOpenAlerts }
})

import { router as reefRouter } from '../apps/reef-admin/src/router'
import ReefApp from '../apps/reef-admin/src/App.vue'
import DashboardView from '../apps/reef-admin/src/views/DashboardView.vue'
import ConchesView from '../apps/reef-admin/src/views/ConchesView.vue'
import ConchDetailView from '../apps/reef-admin/src/views/ConchDetailView.vue'
import TokensView from '../apps/reef-admin/src/views/TokensView.vue'
import UsersView from '../apps/reef-admin/src/views/UsersView.vue'
import AuditView from '../apps/reef-admin/src/views/AuditView.vue'
import SettingsView from '../apps/reef-admin/src/views/SettingsView.vue'
import TideView from '../apps/reef-admin/src/views/TideView.vue'
import VersionPanel from '../apps/reef-admin/src/components/VersionPanel.vue'
import FailureGroups from '../apps/reef-admin/src/components/FailureGroups.vue'
import ConchStats from '../apps/reef-admin/src/components/ConchStats.vue'
import type { ConchVersion } from '../apps/reef-admin/src/api'

const FUTURE = '2099-01-01T00:00:00.000Z'

function makeVersion(overrides: Partial<ConchVersion> = {}): ConchVersion {
  return {
    version: '1.0.0',
    status: 'PUBLISHED',
    uploadedBy: 'admin',
    uploadedAt: '2026-10-26T02:00:00Z',
    publishedBy: 'admin',
    publishedAt: '2026-10-26T02:00:00Z',
    current: true,
    warningCount: 0,
    warnings: [],
    pullCount: 0,
    ...overrides,
  }
}

const baseDetail = {
  conch: 'legacy_oa',
  version: '1.0.0',
  published: true,
  title: '协同办公 OA',
  description: '',
  driver: 'http',
  minCrabVersion: '1.0.0',
  baseUrl: null,
  owner: null,
  tags: [],
  changelog: null,
  credentials: {},
  config: {},
  actions: {},
  disabled: false,
  disabledAt: null,
  disabledBy: null,
  disabledReason: null,
  pullCount: 0,
  upstream: null,
  snapshot: null,
  tools: null,
}

const issuedToken = {
  token: {
    id: 7,
    owner: 'alice',
    name: '办公室 PC',
    prefix: 'hat_abcdefgh',
    createdAt: '2026-11-04T04:00:00Z',
    lastUsedAt: null,
    revokedAt: null,
    source: 'DATABASE' as const,
  },
  secret: 'hat_abcdefghSECRET',
  publicKey: '-----BEGIN PUBLIC KEY-----\nMCo=\n-----END PUBLIC KEY-----\n',
}

beforeEach(() => {
  vi.clearAllMocks()
  signOut()
  setRole('ADMIN')
  localStorage.removeItem('hermit.reef.conchesView')
  localStorage.removeItem('hermit.sidebar.collapsed')
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  signOut()
  document.body.innerHTML = ''
})

describe('H1：Reef 校验文案准确', () => {
  it('MIN_CRAB_VERSION 提示把版本调高而不是降低', () => {
    expect(issueHints.MIN_CRAB_VERSION.title).toContain('不满足')
    expect(issueHints.MIN_CRAB_VERSION.hint).toContain('调到对应版本或以上')
    expect(issueHints.MIN_CRAB_VERSION.hint).not.toContain('降低')
  })

  it('DRIVER、UNSUPPORTED_STEP、REQUEST_BODY 覆盖现行能力', () => {
    expect(issueHints.DRIVER.hint).toContain('driver: http')
    expect(issueHints.DRIVER.hint).toContain('driver: mcp')
    expect(issueHints.UNSUPPORTED_STEP.hint).toContain('if')
    expect(issueHints.UNSUPPORTED_STEP.hint).toContain('repeat')
    expect(issueHints.UNSUPPORTED_STEP.hint).toContain('ask')
    expect(issueHints.REQUEST_BODY.hint).toContain('multipart')
    expect(issueHints.REQUEST_BODY.hint).toContain('1.8.0')
  })
})

describe('H2 & M7：剪贴板失败不误标已保存，且防重复提交生成令牌', () => {
  it('copyText 在剪贴板写入失败时返回 false，成功时返回 true', async () => {
    const writeText = vi.fn().mockRejectedValueOnce(new Error('denied')).mockResolvedValueOnce(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    const ok = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)

    expect(await copyText('secret', '已复制')).toBe(false)
    expect(warn).toHaveBeenCalled()
    expect(await copyText('secret', '已复制')).toBe(true)
    expect(ok).toHaveBeenCalledWith('已复制')
  })

  it('复制初始化 JSON 失败时关闭弹窗仍弹出未保存确认', async () => {
    reef.tokens.mockResolvedValue([])
    reef.crabs.mockResolvedValue([])
    reef.createToken.mockResolvedValue(issuedToken)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      configurable: true,
    })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')

    const wrapper = await mountPage(TokensView, { attach: true })
    await flushPromises()
    await wrapper.get('[data-test="create-token"]').trigger('click')
    await flushPromises()

    const name = document.body.querySelector('input[data-test="token-name"], [data-test="token-name"] input') as HTMLInputElement
    name.value = '办公室 PC'
    name.dispatchEvent(new Event('input'))
    ;(document.body.querySelector('[data-test="submit-token"]') as HTMLButtonElement).click()
    await flushPromises()

    const copyBtn = [...document.body.querySelectorAll('.el-dialog button')].find((b) =>
      b.textContent?.includes('复制 JSON'),
    ) as HTMLButtonElement
    copyBtn.click()
    await flushPromises()

    const closeBtn = [...document.body.querySelectorAll('.el-dialog__headerbtn')].pop() as HTMLButtonElement
    closeBtn.click()
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('还没有下载或复制初始化 JSON'),
      '确定关闭？',
      expect.anything(),
    )
    wrapper.unmount()
  })

  it('提交中再次按回车不会重复创建令牌', async () => {
    reef.tokens.mockResolvedValue([])
    reef.crabs.mockResolvedValue([])
    const pending = deferred<typeof issuedToken>()
    reef.createToken.mockReturnValue(pending.promise)

    const wrapper = await mountPage(TokensView, { attach: true })
    await flushPromises()
    await wrapper.get('[data-test="create-token"]').trigger('click')
    await flushPromises()

    const input = document.body.querySelector('input[data-test="token-name"], [data-test="token-name"] input') as HTMLInputElement
    input.value = '财务电脑'
    input.dispatchEvent(new Event('input'))
    await flushPromises()

    const form = input.closest('form')!
    form.dispatchEvent(new Event('submit', { cancelable: true }))
    form.dispatchEvent(new Event('submit', { cancelable: true }))
    await flushPromises()

    expect(reef.createToken).toHaveBeenCalledTimes(1)
    expect(input.disabled).toBe(true)

    pending.resolve(issuedToken)
    await flushPromises()
    wrapper.unmount()
  })
})

describe('M1 & M5：VersionPanel 权限隐藏与最旧版本对比', () => {
  it('普通用户在规则包停用时看不到置灰的「发布」占位，管理员仍能看到', async () => {
    const props = {
      conch: 'legacy_oa',
      disabled: true,
      versions: [
        makeVersion({ version: '1.1.0', status: 'UPLOADED', current: false, publishedAt: null, publishedBy: null }),
        makeVersion({ version: '1.0.0', status: 'PUBLISHED', current: true }),
      ],
    }
    setRole('USER')
    const userView = await mountPage(VersionPanel, { props })
    await flushPromises()
    const headers = userView.findAll('.el-table__header th').map((th) => th.text())
    const actionCol = headers.indexOf('操作')
    const userActionCell = userView.findAll('.el-table__body tr')[0].findAll('td')[actionCol]
    expect(userView.find('.off').exists()).toBe(false)
    expect(userActionCell.text()).not.toContain('发布')

    setRole('ADMIN')
    const adminView = await mountPage(VersionPanel, { props })
    await flushPromises()
    expect(adminView.get('.off').text()).toBe('发布')
  })

  it('最旧的当前版本点「对比」时与相邻新版本对比，而不是自己比自己', async () => {
    reef.diff.mockResolvedValue({
      addedActions: [],
      removedActions: [],
      changedActions: [],
      addedCredentials: [],
      removedCredentials: [],
      credentialRequiredChanges: [],
      addedConfig: [],
      removedConfig: [],
      configDefaultChanges: [],
      newRequiredCredentials: [],
      toolChanges: [],
      addedScripts: [],
      removedScripts: [],
      changedScripts: [],
    })
    const wrapper = await mountPage(VersionPanel, {
      attach: true,
      props: {
        conch: 'legacy_oa',
        versions: [
          makeVersion({ version: '1.1.0', status: 'UPLOADED', current: false, publishedAt: null, publishedBy: null }),
          makeVersion({ version: '1.0.0', status: 'PUBLISHED', current: true }),
        ],
      },
    })
    await flushPromises()
    const rows = wrapper.findAll('.el-table__body tr')
    const oldestDiffBtn = rows[1].findAll('button').find((b) => b.text() === '对比')!
    await oldestDiffBtn.trigger('click')
    await flushPromises()

    expect(reef.diff).toHaveBeenCalledWith('legacy_oa', '1.0.0', '1.1.0')
    wrapper.unmount()
  })
})

describe('M2：RBAC 路由守卫 fail-closed 与降权踢出', () => {
  it('api.me() 非 401 失败时阻止进入管理员路由并回到概览', async () => {
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    signIn('tok', FUTURE, 'alice')
    session.role = null
    reef.me.mockRejectedValueOnce(new ApiError(500, 'INTERNAL', 'boom'))

    await reefRouter.push('/users')
    expect(reefRouter.currentRoute.value.name).toBe('dashboard')
    expect(warn).toHaveBeenCalled()
  })

  it('api.me() 401 清会话后中止导航，不覆盖登录跳转也不弹权限警告', async () => {
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    signIn('tok', FUTURE, 'alice')
    await reefRouter.push('/settings')
    session.role = null
    reef.me.mockImplementationOnce(async () => {
      signOut()
      throw new ApiError(401, 'UNAUTHORIZED', 'expired')
    })

    await reefRouter.push('/audit')
    expect(reefRouter.currentRoute.value.name).toBe('settings')
    expect(warn).not.toHaveBeenCalled()
  })

  it('App 挂载发现已降权且停在管理员页时提示并回概览；未登录失败时不拉取告警', async () => {
    const warn = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    signIn('tok', FUTURE, 'alice')
    setRole('ADMIN')
    reef.me.mockResolvedValueOnce({ username: 'alice', role: 'USER' })

    const testRouter = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'dashboard', component: { render: () => null } },
        { path: '/users', name: 'users', meta: { admin: true }, component: { render: () => null } },
        { path: '/:pathMatch(.*)*', component: { render: () => null } },
      ],
    })
    await testRouter.push('/users')
    await testRouter.isReady()

    const wrapper = mount(ReefApp, { global: { plugins: [ElementPlus, testRouter] } })
    await flushPromises()

    expect(testRouter.currentRoute.value.name).toBe('dashboard')
    expect(warn).toHaveBeenCalledWith(expect.objectContaining({ message: '没有权限执行这个操作' }))
    wrapper.unmount()

    vi.clearAllMocks()
    signOut()
    reef.me.mockRejectedValueOnce(new ApiError(401, 'UNAUTHORIZED', 'expired'))
    const wrapper2 = mount(ReefApp, { global: { plugins: [ElementPlus, testRouter] } })
    await flushPromises()
    expect(alertsMock.refreshOpenAlerts).not.toHaveBeenCalled()
    wrapper2.unmount()
  })
})

describe('M3 & M5：ConchDetailView 原因控制字符清理与毫秒级时间比较', () => {
  it('停用原因里的换行和制表符会被折叠成空格，纯换行被拦住', async () => {
    reef.versions.mockResolvedValue([makeVersion()])
    reef.conch.mockResolvedValue(baseDetail)
    reef.disableConch.mockResolvedValue({ conch: 'legacy_oa', disabled: true })

    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, attach: true })
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '停用')!.trigger('click')
    await flushPromises()

    const textarea = document.querySelector('.el-dialog textarea') as HTMLTextAreaElement
    const submit = () =>
      [...document.querySelectorAll('.el-dialog button')].find((b) => b.textContent?.trim() === '停用') as HTMLButtonElement

    textarea.value = '\n\r\t  '
    textarea.dispatchEvent(new Event('input'))
    await flushPromises()
    submit().click()
    await flushPromises()
    expect(reef.disableConch).not.toHaveBeenCalled()

    textarea.value = '上游维护\n预计周五恢复\t请等待'
    textarea.dispatchEvent(new Event('input'))
    await flushPromises()
    submit().click()
    await flushPromises()
    expect(reef.disableConch).toHaveBeenCalledWith('legacy_oa', '上游维护 预计周五恢复 请等待')
    wrapper.unmount()
  })

  it('带毫秒的 ISO 时间正确识别为晚于整秒版本', async () => {
    reef.versions.mockResolvedValue([
      makeVersion({
        version: '1.1.0',
        status: 'UPLOADED',
        current: false,
        uploadedAt: '2026-10-26T02:00:00.123Z',
        publishedAt: null,
        publishedBy: null,
      }),
      makeVersion({
        version: '1.0.0',
        status: 'PUBLISHED',
        current: true,
        uploadedAt: '2026-10-26T02:00:00Z',
      }),
    ])
    reef.conch.mockResolvedValue(baseDetail)

    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(wrapper.text()).toContain('有 1 个新上传的版本还没发布')
    expect(wrapper.text()).toContain('v1.1.0')
  })
})

describe('M4：各页面加载失败不冒充空态或健康态', () => {
  it('DashboardView 加载失败时不显示「一切正常」，提供重试', async () => {
    reef.conches.mockRejectedValueOnce(new Error('network'))
    reef.tideSummary.mockRejectedValueOnce(new Error('network'))
    reef.alerts.mockRejectedValueOnce(new Error('network'))
    reef.tideRollbacks.mockRejectedValueOnce(new Error('network'))

    const wrapper = await mountPage(DashboardView)
    await flushPromises()

    expect(wrapper.get('[data-test="dashboard-load-failed"]').text()).toContain('读取失败')
    expect(wrapper.text()).not.toContain('一切正常')
    expect(wrapper.text()).not.toContain('7 天内没有 Crab 切换版本')

    reef.conches.mockResolvedValueOnce([])
    reef.tideSummary.mockResolvedValueOnce({ bucket: 'day', from: '', to: '', p95Note: '', series: [] })
    reef.alerts.mockResolvedValueOnce({ alerts: [] })
    reef.tideRollbacks.mockResolvedValueOnce({ events: [] })
    await wrapper.get('[data-test="dashboard-load-failed"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="dashboard-load-failed"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('一切正常')
  })

  it('ConchesView 首次加载失败时隐藏「共 0 个」', async () => {
    reef.conches.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    expect(wrapper.find('[data-test="conch-count"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('共 0 个')
  })

  it('ConchDetailView 非 404 错误时不误报「还没有发布版本」且不显示空白页签', async () => {
    reef.versions.mockResolvedValueOnce([makeVersion()])
    reef.conch.mockRejectedValueOnce(new ApiError(500, 'INTERNAL', 'db down'))

    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.get('[data-test="conch-load-failed"]').text()).toContain('读取失败')
    expect(wrapper.text()).not.toContain('这个规则包还没有发布版本')
    expect(wrapper.find('.el-tabs').exists()).toBe(false)

    reef.versions.mockResolvedValueOnce([makeVersion()])
    reef.conch.mockResolvedValueOnce(baseDetail)
    await wrapper.get('[data-test="conch-load-failed"] button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="conch-load-failed"]').exists()).toBe(false)
    expect(wrapper.find('.el-tabs').exists()).toBe(true)
  })

  it('TokensView、UsersView、AuditView、SettingsView 失败时显示错误重试并隐藏误导空文案', async () => {
    reef.tokens.mockRejectedValueOnce(new Error('err'))
    reef.crabs.mockRejectedValueOnce(new Error('err'))
    const tokens = await mountPage(TokensView)
    await flushPromises()
    expect(tokens.get('[data-test="tokens-load-failed"]').text()).toContain('读取失败')
    expect(tokens.text()).not.toContain('还没有令牌')
    expect(tokens.text()).not.toContain('还没有 Crab')

    reef.users.mockRejectedValueOnce(new Error('err'))
    const users = await mountPage(UsersView)
    await flushPromises()
    expect(users.get('[data-test="users-load-failed"]').text()).toContain('读取失败')
    expect(users.text()).not.toContain('没有用户')

    reef.audit.mockRejectedValueOnce(new Error('err'))
    const audit = await mountPage(AuditView)
    await flushPromises()
    expect(audit.get('[data-test="audit-load-failed"]').text()).toContain('读取失败')
    expect(audit.text()).not.toContain('没有记录')

    reef.signingKey.mockRejectedValueOnce(new Error('err'))
    const settings = await mountPage(SettingsView)
    await flushPromises()
    expect(settings.get('[data-test="signing-key-load-failed"]').text()).toContain('读取失败')
  })
})

describe('M8：FailureGroups 归属列与 Tide/ConchStats 竞态保护', () => {
  it('FailureGroups 渲染归属列，缺失归属显示破折号', async () => {
    const wrapper = await mountPage(FailureGroups, {
      props: {
        groups: [
          {
            conch: 'legacy_oa',
            conchVersion: '1.0.0',
            action: 'list_todo',
            stepIndex: 0,
            stepType: 'request',
            errorCode: 'UPSTREAM_ERROR',
            owner: 'alice',
            count: 3,
            recent: [],
          },
          {
            conch: 'legacy_oa',
            conchVersion: '1.0.0',
            action: 'approve',
            stepIndex: null,
            stepType: null,
            errorCode: 'TIMEOUT',
            owner: null,
            count: 1,
            recent: [],
          },
        ],
      },
    })
    await flushPromises()

    const headers = wrapper.findAll('.el-table__header th').map((th) => th.text())
    const ownerCol = headers.indexOf('归属')
    expect(ownerCol).toBeGreaterThan(-1)
    const rows = wrapper.findAll('.el-table__body tr')
    expect(rows[0].findAll('td')[ownerCol].text()).toBe('alice')
    expect(rows[1].findAll('td')[ownerCol].text()).toBe('—')
  })

  it('TideView 丢弃晚返回的旧筛选响应，不被旧结果覆盖', async () => {
    const firstSummary = deferred<{ bucket: string; from: string; to: string; p95Note: string; series: unknown[] }>()
    const firstFailures = deferred<{ groups: unknown[] }>()
    const firstRollbacks = deferred<{ events: unknown[] }>()

    reef.conches.mockResolvedValue([])
    reef.tideSummary.mockReturnValueOnce(firstSummary.promise).mockResolvedValueOnce({
      bucket: 'hour',
      from: '',
      to: '',
      p95Note: '',
      series: [{ conch: 'legacy_oa', action: 'fast_action', bucketStart: '2026-11-01T00:00:00Z', success: 9, failure: 0, failureRate: 0, avgMillis: 10, p95Millis: 20 }],
    })
    reef.tideFailures.mockReturnValueOnce(firstFailures.promise).mockResolvedValueOnce({ groups: [] })
    reef.tideRollbacks.mockReturnValueOnce(firstRollbacks.promise).mockResolvedValueOnce({ events: [] })

    const wrapper = await mountPage(TideView)
    await flushPromises()

    const range24h = wrapper.findAll('.el-radio-button').find((r) => r.text() === '24 小时')!
    await range24h.find('input').setValue(true)
    await flushPromises()
    expect(wrapper.text()).toContain('fast_action')

    firstSummary.resolve({
      bucket: 'day',
      from: '',
      to: '',
      p95Note: '',
      series: [{ conch: 'legacy_oa', action: 'stale_action', bucketStart: '2026-11-01T00:00:00Z', success: 1, failure: 0, failureRate: 0, avgMillis: 10, p95Millis: 20 }],
    })
    firstFailures.resolve({ groups: [] })
    firstRollbacks.resolve({ events: [] })
    await flushPromises()

    expect(wrapper.text()).toContain('fast_action')
    expect(wrapper.text()).not.toContain('stale_action')
  })

  it('ConchStats 加载失败时显示重试且不误报空态', async () => {
    reef.tideSummary.mockRejectedValueOnce(new Error('boom'))
    reef.tideFailures.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage(ConchStats, { props: { conch: 'legacy_oa', versions: [] } })
    await flushPromises()

    expect(wrapper.get('[data-test="stats-load-failed"]').text()).toContain('读取失败')
    expect(wrapper.text()).not.toContain('所选范围内没有调用')
    expect(wrapper.text()).not.toContain('还没有已发布版本')
  })

  it('ConchDetailView 在 ?tab=stats 下不重复拉取 Tide，404 时不请求 Tide，并同步 query.tab', async () => {
    reef.versions.mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', 'missing'))
    const missingWrap = await mountPage(ConchDetailView, { props: { conch: 'nope' }, path: '/?tab=stats' })
    await flushPromises()
    expect(reef.tideSummary).not.toHaveBeenCalled()
    missingWrap.unmount()

    vi.clearAllMocks()
    reef.versions.mockResolvedValue([makeVersion()])
    reef.conch.mockResolvedValue(baseDetail)
    reef.tideSummary.mockResolvedValue({ bucket: 'day', from: '', to: '', p95Note: '', series: [] })
    reef.tideFailures.mockResolvedValue({ groups: [] })
    reef.tideCompare.mockResolvedValue({ conch: 'legacy_oa', versions: [] })

    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, path: '/?tab=stats' })
    await flushPromises()
    expect(reef.tideSummary).toHaveBeenCalledTimes(1)

    await wrapper.router.push('/?tab=versions')
    await flushPromises()
    expect(wrapper.find('.el-tabs__item.is-active').text()).toContain('版本')
    wrapper.unmount()
  })
})

describe('M14 & 共享组件打磨', () => {
  it('relativeTime 遇到略超前的未来时间返回「刚刚」而不是负数分钟', () => {
    const twoMinutesInFuture = new Date(Date.now() + 2 * 60_000).toISOString()
    expect(relativeTime(twoMinutesInFuture)).toBe('刚刚')
    expect(relativeTime(twoMinutesInFuture)).not.toContain('-')
  })

  it('AppLayout 在 404 路由不高亮概览，并在窄屏下用抽屉状态更新按钮且支持 Escape 关闭', async () => {
    const mql = {
      matches: true,
      media: '(max-width: 900px)',
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }
    vi.stubGlobal('matchMedia', vi.fn(() => mql))

    const layout = await mountPage(AppLayout, {
      path: '/unknown-404-page',
      props: {
        product: 'Reef',
        mark: 'reef',
        subtitle: 'Hermit 管理端',
        items: [
          { to: '/', label: '概览', icon: markRaw(House) },
          { to: '/conches', label: '规则包目录', icon: markRaw(Box) },
        ],
      },
    })
    await flushPromises()

    expect(layout.find('.nav-item.active').exists()).toBe(false)

    const toggle = layout.get('.topbar .icon-btn')
    expect(toggle.attributes('aria-label')).toBe('展开侧栏')
    await toggle.trigger('click')
    expect(layout.find('.layout.drawer-open').exists()).toBe(true)
    expect(toggle.attributes('aria-label')).toBe('收起侧栏')

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()
    expect(layout.find('.layout.drawer-open').exists()).toBe(false)
    expect(toggle.attributes('aria-label')).toBe('展开侧栏')
    layout.unmount()
  })
})
