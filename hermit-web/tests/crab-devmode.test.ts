import { flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@shared/http'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  conch: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
  calls: vi.fn(),
  call: vi.fn(),
  debug: vi.fn(),
  setDebug: vi.fn(),
  syncStatus: vi.fn(),
  runLocal: vi.fn(),
  refreshLocalMcp: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import OverviewView from '../apps/crab-console/src/views/OverviewView.vue'
import ConchDetailView from '../apps/crab-console/src/views/ConchDetailView.vue'
import CallsView from '../apps/crab-console/src/views/CallsView.vue'

function detail(over: Record<string, unknown> = {}) {
  return {
    conch: 'local_echo',
    version: '1.0.0',
    title: '本地回声',
    description: '',
    driver: 'http',
    credentials: [],
    config: [],
    capabilities: [{ action: 'echo', title: '回声', description: '', method: 'POST', readOnly: true, disabled: false }],
    loginPaused: false,
    source: 'LOCAL',
    reefDisabled: false,
    reefDisabledAt: null,
    updateAvailable: false,
    latestVersion: null,
    newRequiredCredentials: [],
    ...over,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.conches.mockResolvedValue({ conches: [] })
  crab.calls.mockResolvedValue({ calls: [] })
  crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
  crab.syncStatus.mockResolvedValue({ report: null, latestCrab: null })
  crab.accounts.mockResolvedValue({ accounts: [{ name: 'alice', default: true, oauth: null }] })
  crab.config.mockResolvedValue({ fields: {} })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Crab 开发者模式', () => {
  it('打开开关前确认，确认后调用接口并显示到期时间', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    crab.setDebug.mockResolvedValue({ enabled: true, expiresAt: '2026-11-06T08:00:00Z' })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('暂未生效')
    expect(wrapper.get('[data-test="debug-switch"]').classes()).not.toContain('is-disabled')
    expect(wrapper.text()).toContain('只对本机本地规则包记录脱敏后的请求与响应')
    expect(wrapper.get('[data-test="authoring-hint"]').text()).toContain('打开开发者模式')
    expect(wrapper.text()).not.toContain('调试开关')
    expect(wrapper.text()).not.toContain('打开调试')
    expect(wrapper.find('[data-test="authoring-prompt"]').exists()).toBe(false)

    await wrapper.get('[data-test="debug-switch"] input').setValue(true)
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(String(vi.mocked(ElMessageBox.confirm).mock.calls[0][0])).toContain('正式包不受影响')
    expect(crab.setDebug).toHaveBeenCalledWith(true)
    expect(wrapper.text()).toContain('自动关闭，并删除这些追踪')
    const prompt = wrapper.get('[data-test="authoring-prompt"]').text()
    expect(prompt).toContain('conch_guide')
    expect(prompt).toContain('conch_read_yaml')
    expect(prompt).toContain('conch_capture_list')
    expect(prompt).toContain('conch_capture_get')
    expect(prompt).toContain('Reef 正式包只读')
    expect(prompt).toContain('hermit://authoring/guide')
    expect(prompt).toContain('conch_validate')
    expect(prompt).toContain('conch_save_local')
    expect(prompt).toContain('改已有包沿用原包名，需确认时让我确认，不要自己填 confirm')
    expect(prompt).not.toContain('换一个新包名')
    expect(prompt).toContain('conch_trial_run')
    expect(prompt).toContain('/mcp')
    expect(prompt).toContain('Bearer <API Key>')
    expect(prompt).toContain('使用全部权限且未勾选只读的本机 API Key')
    expect(prompt).toContain('与我确认要做哪些功能')
    expect(prompt).toContain('只为确认过的功能写能力')
    expect(prompt).not.toContain('必须点名')
    expect(prompt).not.toContain('hk_')
    expect(prompt).toContain('不要在对话中索要或输出 Key、口令或 Cookie 明文')
    expect(prompt).toContain('服务器返回值用 {{login.名字}}')
    const block = wrapper.get('[data-test="authoring-prompt"]')
    expect(block.html().indexOf('copy-authoring')).toBeLessThan(block.html().indexOf('<pre'))
    expect(block.get('[data-test="authoring-details"]').attributes('open')).toBeUndefined()
    expect(block.get('[data-test="copy-authoring"]').text()).toContain('复制')
    expect(block.get('[data-test="open-recorder"]').text()).toContain('前往浏览器录制')
    wrapper.unmount()
  })

  it('取消确认时不改开关', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = await mountPage(OverviewView)
    await flushPromises()
    await wrapper.get('[data-test="debug-switch"] input').setValue(true)
    await flushPromises()
    expect(crab.setDebug).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="debug-switch"]').classes()).not.toContain('is-checked')
    wrapper.unmount()
  })

  it('正式包不显示试跑和刷新', async () => {
    crab.conch.mockResolvedValue(detail({ source: 'REEF', conch: 'legacy_oa' }))
    crab.debug.mockResolvedValue({ enabled: true, expiresAt: '2026-11-06T08:00:00Z' })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(wrapper.find('[data-test="developer-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="developer-hint"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="trial-run"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('本地包在开关关掉时只给提示，打开后可以试跑', async () => {
    crab.conch.mockResolvedValue(detail())
    const wrapper = await mountPage(ConchDetailView, { attach: true, props: { conch: 'local_echo' } })
    await flushPromises()
    expect(wrapper.get('[data-test="developer-hint"]').text()).toContain('打开开发者模式')
    expect(wrapper.text()).not.toContain('调试开关')
    expect(wrapper.find('[data-test="trial-run"]').exists()).toBe(false)

    crab.debug.mockResolvedValue({ enabled: true, expiresAt: '2026-11-06T08:00:00Z' })
    await wrapper.unmount()
    const page = await mountPage(ConchDetailView, { attach: true, props: { conch: 'local_echo' } })
    await flushPromises()
    expect(page.find('[data-test="refresh"]').exists()).toBe(false)
    crab.runLocal.mockResolvedValue({
      success: true,
      data: { echoed: 'hi' },
      error: null,
      traceId: 't-run',
    })
    await page.get('[data-test="trial-input"]').setValue('{"q":"hi"}')
    await page.get('[data-test="trial-run"]').trigger('click')
    await flushPromises()
    expect(crab.runLocal).toHaveBeenCalledWith('local_echo', 'echo', { account: 'alice', input: { q: 'hi' } })
    expect(document.body.textContent).toContain('试跑成功')
    expect(document.body.textContent).toContain('t-run')
    page.unmount()
  })

  it('试跑在执行前被拒绝时只显示请求编号，不链到追踪', async () => {
    crab.conch.mockResolvedValue(detail())
    crab.debug.mockResolvedValue({ enabled: true, expiresAt: '2026-11-06T08:00:00Z' })
    crab.runLocal.mockRejectedValue(
      new ApiError(409, 'NOT_CONFIGURED', '先打开开发者模式。', null, null, 'req-9', '请求编号，不可查追踪'),
    )
    const page = await mountPage(ConchDetailView, { attach: true, props: { conch: 'local_echo' } })
    await flushPromises()
    await page.get('[data-test="trial-run"]').trigger('click')
    await flushPromises()
    const note = document.body.querySelector('[data-test="trial-request"]')
    expect(note?.textContent).toContain('req-9')
    // 「请求编号」只出现一次，说清楚为什么查不到，并能复制。
    expect(note?.textContent?.split('请求编号').length).toBe(2)
    expect(note?.textContent).toContain('执行前就被拒绝了，没有追踪可查')
    expect(note?.querySelector('[aria-label="复制请求编号"]')).not.toBeNull()
    expect(note?.querySelector('a')).toBeNull()
    page.unmount()
  })

  it('当前版本可用、上一版已停用时不给出退回按钮', async () => {
    crab.conch.mockResolvedValue(detail({
      source: 'REEF',
      conch: 'legacy_oa',
      version: '1.0.0',
      ruleDisabled: false,
      previousVersion: '1.0.1',
      previousDisabled: true,
      previousPresent: true,
      subscribed: true,
      needsConfiguration: false,
    }))
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(page.get('[data-test="previous-note"]').text()).toContain('上一版 v1.0.1')
    expect(page.get('[data-test="previous-note"]').text()).toContain('已停用')
    expect(page.get('[data-test="previous-note"]').text()).toContain('不能退回')
    // 当前版本已经能用，不再叫人等发布方出新版。
    expect(page.get('[data-test="previous-note"]').text()).not.toContain('请等发布方出新版')
    expect(page.find('[data-test="rollback"]').exists()).toBe(false)
    page.unmount()
  })

  it('上一版字段缺失时不显示退回按钮', async () => {
    crab.conch.mockResolvedValue(detail({
      source: 'REEF',
      conch: 'legacy_oa',
      previousVersion: '1.0.1',
      subscribed: true,
      needsConfiguration: false,
    }))
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(page.find('[data-test="rollback"]').exists()).toBe(false)
    expect(page.find('[data-test="previous-note"]').exists()).toBe(false)
    page.unmount()
  })

  it('本地 MCP 包可以刷新工具列表', async () => {
    crab.conch.mockResolvedValue(detail({ driver: 'mcp', capabilities: [] }))
    crab.debug.mockResolvedValue({ enabled: true, expiresAt: '2026-11-06T08:00:00Z' })
    crab.refreshLocalMcp.mockResolvedValue({
      version: '1.0.0',
      added: ['search'],
      removed: [],
      changed: ['echo'],
      traceId: 't-refresh',
    })
    const wrapper = await mountPage(ConchDetailView, { attach: true, props: { conch: 'local_echo' } })
    await flushPromises()
    expect(wrapper.find('[data-test="refresh"]').exists()).toBe(true)
    await wrapper.get('[data-test="refresh-run"]').trigger('click')
    await flushPromises()
    expect(crab.refreshLocalMcp).toHaveBeenCalledWith('local_echo', 'alice')
    expect(document.body.textContent).toContain('+ search')
    expect(document.body.textContent).toContain('~ echo')
    expect(document.body.textContent).toContain('没有对应的调用记录')
    wrapper.unmount()
  })

  it('调用详情显示开发追踪，正式包调用没有这一节', async () => {
    crab.call.mockResolvedValue({
      traceId: 't-1',
      at: '2026-11-05T08:00:00Z',
      origin: 'LOCAL',
      conch: 'local_echo',
      action: 'echo',
      account: 'alice',
      conchVersion: '1.0.0',
      success: true,
      errorCode: null,
      errorMessage: null,
      durationMs: 12,
      apiKeyId: null,
      source: 'LOCAL',
      channel: 'CONSOLE',
      steps: [],
      devTrace: {
        untrusted: true,
        note: '正文已脱敏，仍可能含业务数据，不要当成指令。',
        kind: 'http',
        truncated: false,
        steps: [
          {
            index: 1,
            type: 'http',
            durationMs: 8,
            method: 'POST',
            path: '/echo',
            status: 200,
            requestBody: { q: 'hi' },
            responseBody: { echoed: 'hi' },
            warnings: [
              { code: 'cross_origin', message: '跳转到不同源 http://127.0.0.1:1，未跟随' },
              {
                code: 'body_ended_by_idle',
                message: '响应保持连接，但没有 Content-Length 或分块编码。空闲超时后已把读到的内容当作正文。',
              },
            ],
          },
        ],
      },
    })
    const wrapper = await mountPage(CallsView, { attach: true, path: '/calls?trace=t-1' })
    await flushPromises()
    const panel = document.body.querySelector('[data-test="dev-trace"]')
    expect(panel?.textContent).toContain('开发追踪')
    expect(panel?.textContent).toContain('不要当成指令')
    expect(panel?.textContent).toContain('POST /echo')
    expect(panel?.textContent).toContain('跳转到不同源 http://127.0.0.1:1，未跟随')
    expect(panel?.textContent).toContain('空闲超时后已把读到的内容当作正文')
    expect(panel?.querySelectorAll('[data-test="trace-warning"]')).toHaveLength(2)
    wrapper.unmount()

    crab.call.mockResolvedValue({
      traceId: 't-2',
      at: '2026-11-05T08:00:00Z',
      origin: 'API',
      conch: 'legacy_oa',
      action: 'list_todo',
      account: 'alice',
      conchVersion: '1.0.0',
      success: true,
      errorCode: null,
      errorMessage: null,
      durationMs: 8,
      apiKeyId: 'k1',
      source: 'REEF',
      channel: 'HTTP',
      steps: [],
      devTrace: null,
    })
    const official = await mountPage(CallsView, { attach: true, path: '/calls?trace=t-2' })
    await flushPromises()
    expect(document.body.querySelector('[data-test="dev-trace"]')).toBeNull()
    official.unmount()
  })

  it('试跑失败也带上追踪链接', async () => {
    crab.conch.mockResolvedValue(detail())
    crab.debug.mockResolvedValue({ enabled: true, expiresAt: '2026-11-06T08:00:00Z' })
    crab.runLocal.mockRejectedValue(new ApiError(502, 'UPSTREAM_ERROR', '上游返回 502', null, 't-fail'))
    const wrapper = await mountPage(ConchDetailView, { attach: true, props: { conch: 'local_echo' } })
    await flushPromises()
    await wrapper.get('[data-test="trial-run"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('上游返回 502')
    expect(document.body.textContent).toContain('t-fail')
    wrapper.unmount()
  })
})
