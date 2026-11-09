import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  catalog: vi.fn(),
  sync: vi.fn(),
  syncStatus: vi.fn(),
  subscribe: vi.fn(),
  unsubscribe: vi.fn(),
  update: vi.fn(),
  conches: vi.fn(),
  conch: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
  apiKeys: vi.fn(),
  importLocalConch: vi.fn(),
  localConches: vi.fn(),
  reloadLocalConches: vi.fn(),
  deleteLocalConch: vi.fn(),
  exportLocalConch: vi.fn(),
  debug: vi.fn(),
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
  crab.localConches.mockResolvedValue({ conches: [] })
  crab.syncStatus.mockResolvedValue({ report: null, latestCrab: null })
  crab.accounts.mockResolvedValue({ accounts: [] })
  crab.config.mockResolvedValue({ fields: {} })
  crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
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

  it('订阅结果没有安装结果时不说已安装到本机', async () => {
    crab.catalog.mockResolvedValue({ conches: [entry()], syncedAt: '2026-10-30T00:00:00Z', configured: true })
    crab.subscribe.mockResolvedValue({ conch: 'legacy_oa', subscribed: true, outcome: null })
    const success = vi.spyOn(ElMessage, 'success')
    const warning = vi.spyOn(ElMessage, 'warning')
    const wrapper = await mountPage(CatalogView)
    await flushPromises()

    await buttons(wrapper, '订阅')[0].trigger('click')
    await flushPromises()

    expect(success).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('已订阅 legacy_oa，但这次没有安装')
  })

  it('接口返回没有订上时不说已订阅', async () => {
    crab.catalog.mockResolvedValue({ conches: [entry()], syncedAt: '2026-10-30T00:00:00Z', configured: true })
    crab.subscribe.mockResolvedValue({ conch: 'legacy_oa', subscribed: false, outcome: null })
    const success = vi.spyOn(ElMessage, 'success')
    const warning = vi.spyOn(ElMessage, 'warning')
    const wrapper = await mountPage(CatalogView)
    await flushPromises()

    await buttons(wrapper, '订阅')[0].trigger('click')
    await flushPromises()

    expect(success).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('没有订阅 legacy_oa：Reef 上当前不能订阅这个规则包')
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

  it('没有账号时提示先新建，并标成待配置', async () => {
    crab.conches.mockResolvedValue({
      conches: [
        {
          conch: 'optional_note',
          version: '1.0.0',
          title: '备注',
          capabilityCount: 1,
          subscribed: false,
          accountCount: 0,
          needsConfiguration: true,
          loginPaused: false,
          source: 'LOCAL',
        },
        {
          conch: 'ready_ping',
          version: '1.0.0',
          title: '就绪',
          capabilityCount: 1,
          subscribed: false,
          accountCount: 1,
          needsConfiguration: false,
          loginPaused: false,
          source: 'LOCAL',
        },
      ],
    })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()
    const cards = wrapper.findAll('.conch')
    expect(cards[0].classes()).toContain('warn')
    expect(cards[0].text()).toContain('未建账号：先新建一个账号')
    expect(cards[0].text()).not.toContain('还缺凭证或配置项')
    expect(cards[0].text()).not.toContain('可用')
    expect(cards[1].classes()).not.toContain('warn')
    expect(cards[1].text()).toContain('可用：可以通过 OpenAPI 和 MCP 调用')
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

    expect(crab.importLocalConch).toHaveBeenCalledWith(file, false)
    expect(document.body.textContent).toContain('导入失败')
    expect(document.body.textContent).toContain('缺少 actions')
    wrapper.unmount()
  })

  it('导入失败把代码、位置和中文原因分开，字段名不连成三次', async () => {
    crab.importLocalConch.mockRejectedValue(
      new ApiError(400, 'INVALID_INPUT', '规则包校验失败', {
        errors: [{ code: 'PARSE', path: 'min_crab_version', message: 'min_crab_version: 缺少 min_crab_version' }],
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

    const item = document.body.querySelector('[data-test="import-error"]')
    const text = item?.textContent ?? ''
    expect(text).toContain('PARSE')
    expect(text).toContain('缺少 min_crab_version')
    expect(text).not.toContain('min_crab_version:')
    expect(text.match(/min_crab_version/g)).toHaveLength(2)
    expect(document.body.textContent).toContain('规则包校验失败')
    wrapper.unmount()
  })

  it('能定位到行时在失败位置里写出行号', async () => {
    crab.importLocalConch.mockRejectedValue(
      new ApiError(400, 'INVALID_INPUT', '规则包校验失败', {
        errors: [
          { code: 'PARSE', path: '', message: 'YAML 无法解析（第 4 行）' },
          { code: 'DRIVER', path: 'driver', message: 'driver 只允许 http 或 mcp', line: 3 },
        ],
        warnings: [],
      }),
    )
    const wrapper = await mountPage(ConchesView, { attach: true })
    await flushPromises()
    const input = wrapper.find('[data-test="local-file"]')
    const file = new File([':'], 'bad.yaml', { type: 'application/yaml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()

    const items = [...document.body.querySelectorAll('[data-test="import-error"]')].map((node) => node.textContent ?? '')
    expect(items[0]).toContain('第 4 行')
    expect(items[0]).toContain('YAML 无法解析')
    expect(items[0]).not.toContain('（第 4 行）')
    expect(items[1]).toContain('第 3 行')
    expect(items[1]).toContain('driver 只允许 http 或 mcp')
    wrapper.unmount()
  })

  it('导入成功的警告条目能看出是警告，并写出中文原因', async () => {
    crab.importLocalConch.mockResolvedValue({
      conch: 'local_ping',
      version: '1.0.0',
      replaced: false,
      warnings: [{ code: 'MISSING_RELATED', path: 'actions.ping.related', message: '缺少 related' }],
    })
    const wrapper = await mountPage(ConchesView, { attach: true })
    await flushPromises()
    const input = wrapper.find('[data-test="local-file"]')
    const file = new File(['conch: local_ping'], 'local_ping.yaml', { type: 'application/yaml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()

    const item = document.body.querySelector('[data-test="import-warning"]')
    const text = item?.textContent ?? ''
    expect(text).toContain('MISSING_RELATED')
    expect(text).toContain('actions.ping.related')
    expect(text).toContain('缺少 related')
    expect(item?.closest('.issues')?.classList.contains('warn')).toBe(true)
    expect(item?.querySelector('.el-icon')).toBeTruthy()
    expect(document.body.textContent).toContain('已导入本地规则包 local_ping')
    wrapper.unmount()
  })

  async function chooseFile(wrapper: Awaited<ReturnType<typeof mountPage>>) {
    const input = wrapper.find('[data-test="local-file"]')
    const file = new File(['conch: local_ping'], 'local_ping.yaml', { type: 'application/yaml' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
    return file
  }

  const overwriteConflict = () =>
    new ApiError(409, 'CONFLICT', '将覆盖已有的本地规则包 local_ping。确认后再导入。', { conch: 'local_ping', confirm: true })

  it('同名本地包要确认覆盖，确认后带 confirm 重新导入', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const success = vi.spyOn(ElMessage, 'success')
    crab.importLocalConch
      .mockRejectedValueOnce(overwriteConflict())
      .mockResolvedValueOnce({ conch: 'local_ping', version: '0.2.0', replaced: true, warnings: [] })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()
    const file = await chooseFile(wrapper)

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(confirm.mock.calls[0][0]).toContain('local_ping')
    expect(confirm.mock.calls[0][1]).toBe('已有同名本地规则包')
    expect(crab.importLocalConch.mock.calls).toEqual([
      [file, false],
      [file, true],
    ])
    expect(success).toHaveBeenCalledWith('已覆盖本地规则包 local_ping v0.2.0')
    expect(crab.conches).toHaveBeenCalledTimes(2)
    confirm.mockRestore()
  })

  it('取消覆盖时不重新导入，也不报错', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const error = vi.spyOn(ElMessage, 'error')
    crab.importLocalConch.mockRejectedValue(overwriteConflict())
    const wrapper = await mountPage(ConchesView)
    await flushPromises()
    await chooseFile(wrapper)

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(crab.importLocalConch).toHaveBeenCalledTimes(1)
    expect(error).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('与正式包重名的 409 只显示错误，不询问覆盖', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const error = vi.spyOn(ElMessage, 'error')
    crab.importLocalConch.mockRejectedValue(new ApiError(409, 'INVALID_INPUT', '与已安装的正式规则包重名'))
    const wrapper = await mountPage(ConchesView)
    await flushPromises()
    await chooseFile(wrapper)

    expect(confirm).not.toHaveBeenCalled()
    expect(crab.importLocalConch).toHaveBeenCalledTimes(1)
    expect((error.mock.calls[0][0] as { message: string }).message).toContain('与已安装的正式规则包重名')
    confirm.mockRestore()
  })

  it('坏的本地包标出损坏和原因，不能进入详情，删除后刷新列表', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const reason = '文件 local_ping.yaml 的规则包 local_ping 无法解析：YAML 无法解析（第 1 行）。'
    crab.localConches
      .mockResolvedValueOnce({
        conches: [
          { conch: 'local_ok', file: 'local_ok.yaml', version: '0.1.0', loaded: true, broken: false, reason: null },
          { conch: 'local_ping', file: 'local_ping.yaml', version: null, loaded: false, broken: true, reason },
        ],
      })
      .mockResolvedValue({ conches: [] })
    crab.deleteLocalConch.mockResolvedValue({ conch: 'local_ping', deleted: true })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const cards = wrapper.findAll('[data-test="broken-conch"]')
    expect(cards).toHaveLength(1)
    const card = cards[0]
    expect(card.find('[data-test="broken-tag"]').text()).toBe('文件损坏，无法加载')
    expect(card.find('[data-test="broken-reason"]').text()).toBe(reason)
    expect(card.text()).toContain('不能调用')
    expect(card.text()).not.toContain('可用：')
    expect(card.attributes('role')).toBeUndefined()
    expect(wrapper.text()).not.toContain('还没有规则包')

    await card.find('button').trigger('click')
    await flushPromises()
    expect(confirm.mock.calls[0][1]).toBe('删除本地规则包 local_ping')
    expect(crab.deleteLocalConch).toHaveBeenCalledWith('local_ping')
    expect(wrapper.findAll('[data-test="broken-conch"]')).toHaveLength(0)
    expect(wrapper.text()).toContain('还没有规则包')
    confirm.mockRestore()
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

  it('Reef 停用后详情提示停用但仍可调用，列表带「Reef 已停用」', async () => {
    crab.conch.mockResolvedValue({ ...detail('REEF'), reefDisabled: true, reefDisabledAt: '2026-10-30T00:00:00Z' })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    const alert = page.find('[data-test="reef-disabled"]')
    expect(alert.text()).toContain('Reef 已停用')
    expect(alert.text()).not.toContain('失效')
    expect(alert.text()).toContain('仍可正常调用')
    expect(alert.text()).toContain('不能再更新')

    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'legacy_oa', version: '1.0.0', title: 'OA', capabilityCount: 1, subscribed: true, accountCount: 1, needsConfiguration: false, loginPaused: false, source: 'REEF', reefDisabled: true, reefDisabledAt: '2026-10-30T00:00:00Z' },
      ],
    })
    const list = await mountPage(ConchesView)
    await flushPromises()
    expect(list.find('.reef-off').text()).toBe('Reef 已停用')
    expect(list.text()).toContain('可用：可以通过 OpenAPI 和 MCP 调用')
  })

  it('有新版本时详情横幅用提示色，更新按钮是默认尺寸', async () => {
    crab.conch.mockResolvedValue({ ...detail('REEF'), updateAvailable: true, latestVersion: '1.1.0', newRequiredCredentials: [] })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    const banner = page.findAll('.el-alert').find((a) => a.text().includes('Reef 有新版本 v1.1.0'))!
    expect(banner.classes()).toContain('el-alert--warning')
    const button = banner.get('.update-btn')
    expect(button.text()).toContain('更新到 v1.1.0')
    expect(button.classes()).not.toContain('el-button--small')
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

describe('需要修复的规则包', () => {
  const repairReport = {
    notModified: true,
    conches: [{ conch: 'legacy_oa', version: '1.0.0', kind: 'REPAIR_REQUIRED', reason: 'POINTER_MISSING' }],
    removed: [],
    credentialChanges: [],
    latestCrab: null,
    error: null,
  }

  it('目录标出需要修复，只给修复入口，修复后提示从本机重新载入', async () => {
    crab.catalog.mockResolvedValue({
      configured: true,
      syncedAt: '2026-11-04T00:00:00Z',
      conches: [entry({ subscribed: true, installed: true, installedVersion: '1.0.0', updateAvailable: true, latestVersion: '1.1.0' })],
    })
    crab.syncStatus.mockResolvedValue({ report: repairReport, latestCrab: null })
    crab.update.mockResolvedValue({ outcome: { conch: 'legacy_oa', version: '1.0.0', kind: 'REPAIRED', reason: 'RESTORED' } })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = await mountPage(CatalogView)
    await flushPromises()

    expect(wrapper.find('[data-test="repair-tag"]').text()).toBe('需要修复')
    expect(wrapper.text()).not.toContain('有新版本 v1.1.0')
    expect(buttons(wrapper, '更新')).toHaveLength(0)
    expect(buttons(wrapper, '管理')).toHaveLength(0)

    await buttons(wrapper, '修复')[0].trigger('click')
    await flushPromises()
    expect(crab.update).toHaveBeenCalledWith('legacy_oa')
    expect(success).toHaveBeenCalledWith('legacy_oa 已从本机重新载入 v1.0.0')
  })
})

