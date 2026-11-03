import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  catalog: vi.fn(),
  sync: vi.fn(),
  subscribe: vi.fn(),
  unsubscribe: vi.fn(),
  update: vi.fn(),
  conches: vi.fn(),
  conch: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
  apiKeys: vi.fn(),
  importLocalConch: vi.fn(),
  reloadLocalConches: vi.fn(),
  deleteLocalConch: vi.fn(),
  exportLocalConch: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ApiKeysView from '../apps/crab-console/src/views/ApiKeysView.vue'
import CatalogView from '../apps/crab-console/src/views/CatalogView.vue'
import ConchDetailView from '../apps/crab-console/src/views/ConchDetailView.vue'
import ConchesView from '../apps/crab-console/src/views/ConchesView.vue'

const entry = (over: Record<string, unknown> = {}) => ({
  conch: 'legacy_oa',
  version: '1.1.0',
  title: '协同办公 OA',
  description: '待办审批',
  capabilityCount: 2,
  subscribed: false,
  installed: false,
  installedVersion: null,
  disabled: false,
  localConflict: false,
  ...over,
})

const detail = (source: 'LOCAL' | 'REEF') => ({
  conch: 'legacy_oa',
  version: '1.0.0',
  title: '协同办公 OA',
  description: '',
  credentials: [],
  config: [],
  capabilities: [],
  loginPaused: false,
  source,
})

function buttons(wrapper: Awaited<ReturnType<typeof mountPage>>, text: string) {
  return wrapper.findAll('button').filter((b) => b.text() === text)
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.conches.mockResolvedValue({ conches: [] })
  crab.accounts.mockResolvedValue({ accounts: [] })
  crab.config.mockResolvedValue({ fields: {} })
})

describe('可订阅规则包', () => {
  it('未配置 Reef 时说明原因，不显示表格', async () => {
    crab.catalog.mockResolvedValue({ conches: [], syncedAt: null, configured: false })
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有连接 Reef')
    expect(wrapper.find('.el-table').exists()).toBe(false)
  })

  it('订阅后调用接口并刷新目录', async () => {
    crab.catalog
      .mockResolvedValueOnce({ conches: [entry()], syncedAt: '2026-10-30T00:00:00Z', configured: true })
      .mockResolvedValue({
        conches: [entry({ subscribed: true, installed: true, installedVersion: '1.1.0' })],
        syncedAt: '2026-10-30T00:00:00Z',
        configured: true,
      })
    crab.subscribe.mockResolvedValue({
      conch: 'legacy_oa',
      subscribed: true,
      outcome: { conch: 'legacy_oa', version: '1.1.0', kind: 'INSTALLED', reason: null },
    })
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    expect(wrapper.text()).toContain('未订阅')

    await buttons(wrapper, '订阅')[0].trigger('click')
    await flushPromises()

    expect(crab.subscribe).toHaveBeenCalledWith('legacy_oa')
    expect(crab.catalog).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已订阅')
    expect(wrapper.text()).toContain('已安装 v1.1.0')
    expect(buttons(wrapper, '取消订阅')).toHaveLength(1)
  })

  it('与本地包同名时不能订阅', async () => {
    crab.catalog.mockResolvedValue({
      conches: [entry({ localConflict: true, installed: true, installedVersion: '0.1.0' })],
      syncedAt: '2026-10-30T00:00:00Z',
      configured: true,
    })
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    expect(wrapper.text()).toContain('与本地规则包同名')
    expect(wrapper.text()).not.toContain('已安装 v0.1.0')
    const subscribe = buttons(wrapper, '订阅')
    expect(subscribe).toHaveLength(1)
    expect(subscribe[0].attributes('disabled')).toBeDefined()
  })
})

describe('本地规则包', () => {
  it('列表给本地包加「本地」标签', async () => {
    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'mine', version: '0.1.0', title: '我的', capabilityCount: 1, subscribed: false, accountCount: 0, needsConfiguration: false, loginPaused: false, source: 'LOCAL' },
        { conch: 'legacy_oa', version: '1.0.0', title: 'OA', capabilityCount: 1, subscribed: true, accountCount: 0, needsConfiguration: false, loginPaused: false, source: 'REEF' },
      ],
    })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()
    const cards = wrapper.findAll('.conch')
    expect(cards[0].find('.local').text()).toBe('本地')
    expect(cards[1].find('.local').exists()).toBe(false)
  })

  it('导入校验失败时列出错误', async () => {
    crab.importLocalConch.mockRejectedValue(
      new ApiError(400, 'INVALID_INPUT', 'conch is invalid', {
        errors: [{ code: 'REQUIRED', path: '/actions', message: '缺少 actions' }],
        warnings: [],
      }),
    )
    const wrapper = await mountPage(ConchesView, { attach: true })
    await flushPromises()
    const input = wrapper.find('[data-test="local-file"]')
    const file = new File(['conch: mine'], 'mine.yaml', { type: 'application/yaml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()

    expect(crab.importLocalConch).toHaveBeenCalledWith(file)
    expect(document.body.textContent).toContain('导入失败')
    expect(document.body.textContent).toContain('缺少 actions')
    wrapper.unmount()
  })

  it('本地包详情可以导出和删除，没有取消订阅', async () => {
    crab.conch.mockResolvedValue(detail('LOCAL'))
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(wrapper.text()).toContain('本地规则包：未经 Reef 签名')
    expect(buttons(wrapper, '导出 YAML')).toHaveLength(1)
    expect(buttons(wrapper, '删除')).toHaveLength(1)
    expect(buttons(wrapper, '取消订阅')).toHaveLength(0)
  })

  it('Reef 停用后详情提示失效但仍可调用，列表带「已失效」', async () => {
    crab.conch.mockResolvedValue({ ...detail('REEF'), reefDisabled: true, reefDisabledAt: '2026-10-30T00:00:00Z' })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    const alert = page.find('[data-test="reef-disabled"]')
    expect(alert.text()).toContain('已失效')
    expect(alert.text()).toContain('仍可正常调用')
    expect(alert.text()).toContain('不能再更新')

    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'legacy_oa', version: '1.0.0', title: 'OA', capabilityCount: 1, subscribed: true, accountCount: 1, needsConfiguration: false, loginPaused: false, source: 'REEF', reefDisabled: true, reefDisabledAt: '2026-10-30T00:00:00Z' },
      ],
    })
    const list = await mountPage(ConchesView)
    await flushPromises()
    expect(list.find('.reef-off').text()).toBe('已失效')
    expect(list.text()).toContain('可用：可以通过 OpenAPI 和 MCP 调用')
  })

  it('Reef 包详情只有取消订阅', async () => {
    crab.conch.mockResolvedValue(detail('REEF'))
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(buttons(wrapper, '导出 YAML')).toHaveLength(0)
    expect(buttons(wrapper, '取消订阅')).toHaveLength(1)
  })

  it('创建 Key 时提示本地包需要明确选中', async () => {
    crab.apiKeys.mockResolvedValue({ apiKeys: [] })
    crab.conches.mockResolvedValue({
      conches: [{ conch: 'mine', version: '0.1.0', title: '', capabilityCount: 0, subscribed: false, accountCount: 0, needsConfiguration: false, loginPaused: false, source: 'LOCAL' }],
    })
    crab.conch.mockResolvedValue({ ...detail('LOCAL'), conch: 'mine' })
    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    await buttons(wrapper, '创建 API Key')[0].trigger('click')
    await flushPromises()
    const hint = document.body.querySelector('[data-test="local-hint"]')
    expect(hint?.textContent).toContain('mine')
    wrapper.unmount()
  })
})

describe('手动更新', () => {
  const updatable = {
    ...detail('REEF'),
    updateAvailable: true,
    latestVersion: '1.1.0',
    newRequiredCredentials: ['token'],
  }

  it('详情提示有新版本和新增凭证，确认后调用更新', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    crab.conch.mockResolvedValue(updatable)
    crab.update.mockResolvedValue({
      conch: 'legacy_oa',
      outcome: { conch: 'legacy_oa', version: '1.1.0', kind: 'INSTALLED', reason: null },
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    const alert = wrapper.find('[data-test="update-available"]')
    expect(alert.text()).toContain('v1.1.0')
    expect(alert.text()).toContain('token')

    await buttons(wrapper, '更新到 v1.1.0')[0].trigger('click')
    await flushPromises()
    expect(confirm.mock.calls[0][0]).toContain('不能退回旧版本')
    expect(crab.update).toHaveBeenCalledWith('legacy_oa')
    expect(crab.conch).toHaveBeenCalledTimes(2)
    confirm.mockRestore()
  })

  it('缺新凭证时说明要补哪些，不当成普通错误', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const warning = vi.spyOn(ElMessage, 'warning')
    const error = vi.spyOn(ElMessage, 'error')
    crab.conch.mockResolvedValue(updatable)
    crab.update.mockRejectedValue(new ApiError(409, 'CONFLICT', 'missing credentials for update: token, cookie'))
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    await buttons(wrapper, '更新到 v1.1.0')[0].trigger('click')
    await flushPromises()
    const message = (warning.mock.calls[0][0] as { message: string }).message
    expect(message).toContain('token、cookie')
    expect(error).not.toHaveBeenCalled()
    confirm.mockRestore()
    warning.mockRestore()
    error.mockRestore()
  })

  it('目录和列表标出有新版本，目录可以直接更新', async () => {
    crab.catalog.mockResolvedValue({
      conches: [
        entry({ subscribed: true, installed: true, installedVersion: '1.0.0', updateAvailable: true, latestVersion: '1.1.0', newRequiredCredentials: [] }),
      ],
      syncedAt: '2026-10-30T00:00:00Z',
      configured: true,
    })
    const catalog = await mountPage(CatalogView)
    await flushPromises()
    expect(catalog.text()).toContain('有新版本 v1.1.0')
    expect(buttons(catalog, '更新')).toHaveLength(1)

    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'legacy_oa', version: '1.0.0', title: 'OA', capabilityCount: 1, subscribed: true, accountCount: 1, needsConfiguration: false, loginPaused: false, source: 'REEF', reefDisabled: false, reefDisabledAt: null, updateAvailable: true, latestVersion: '1.1.0', newRequiredCredentials: ['token'] },
      ],
    })
    const list = await mountPage(ConchesView)
    await flushPromises()
    expect(list.text()).toContain('有新版本 v1.1.0')
    expect(list.find('.update-note').text()).toContain('token')
  })
})
