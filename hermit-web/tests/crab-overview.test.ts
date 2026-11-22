import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  calls: vi.fn(),
  debug: vi.fn(),
  setDebug: vi.fn(),
  syncStatus: vi.fn(),
  sync: vi.fn(),
  apiKeys: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import OverviewView from '../apps/crab-console/src/views/OverviewView.vue'

function summary(overrides: Record<string, unknown>) {
  return {
    conch: 'legacy_oa',
    version: '1.0.0',
    title: '协同办公 OA',
    capabilityCount: 3,
    accountCount: 1,
    needsConfiguration: false,
    loginPaused: false,
    ...overrides,
  }
}

function report(overrides: Record<string, unknown>) {
  return {
    notModified: true,
    conches: [],
    removed: [],
    credentialChanges: [],
    latestCrab: null,
    error: null,
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  crab.conches.mockResolvedValue({ conches: [] })
  crab.calls.mockResolvedValue({ calls: [] })
  crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
  crab.syncStatus.mockResolvedValue({ report: null, latestCrab: null })
  crab.apiKeys.mockResolvedValue({ apiKeys: [] })
})

describe('Crab 概览', () => {
  it('接入 Agent 卡片给出地址、Key 数和复制按钮', async () => {
    crab.conches.mockResolvedValue({ conches: [summary({})] })
    crab.apiKeys.mockResolvedValue({ apiKeys: [{ id: 'a', revokedAt: null }, { id: 'b', revokedAt: '2026-01-01T00:00:00Z' }] })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    const card = wrapper.get('[data-test="connect-card"]')
    expect(card.get('[data-test="connect-endpoint"]').text()).toBe(`${window.location.origin}/mcp`)
    expect(card.get('[data-test="connect-hint"]').text()).toContain('有 1 把可用的 API Key')
    expect(card.find('[data-test="connect-nokey"]').exists()).toBe(false)
    expect(card.get('[data-test="connect-keys"]').attributes('href')).toContain('/apikeys')

    await card.get('[data-test="copy-connect"]').trigger('click')
    await flushPromises()
    const copied = writeText.mock.calls[0][0] as string
    expect(copied).toContain(`地址是 ${window.location.origin}/mcp`)
    expect(copied).toContain('list_systems')
  })

  it('没有规则包时先引导取得规则包，不让人先去建 Key', async () => {
    const wrapper = await mountPage(OverviewView)
    await flushPromises()
    const first = wrapper.get('[data-test="connect-noconch"]')
    expect(first.html()).toContain('/catalog')
    expect(first.html()).toContain('/conches')
    expect(wrapper.find('[data-test="connect-nokey"]').exists()).toBe(false)
  })

  it('整包停用的规则包不算进可调用的能力', async () => {
    crab.conches.mockResolvedValue({
      conches: [summary({}), summary({ conch: 'stock', capabilityCount: 5, switchedOff: true })],
    })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()
    expect(wrapper.text()).toContain('3 个可调用的能力')
  })

  it('没有 Key 时提示先创建，读不到 Key 时不显示数量', async () => {
    crab.conches.mockResolvedValue({ conches: [summary({})] })
    const empty = await mountPage(OverviewView)
    await flushPromises()
    expect(empty.get('[data-test="connect-nokey"]').text()).toContain('还没有 API Key')

    crab.apiKeys.mockRejectedValue(new Error('boom'))
    const unknown = await mountPage(OverviewView)
    await flushPromises()
    expect(unknown.find('[data-test="connect-nokey"]').exists()).toBe(false)
    expect(unknown.get('[data-test="connect-hint"]').text()).not.toContain('把可用的')
    expect(unknown.text()).toContain('暂无调用')
  })

  it('加载中不显示 0 和「暂无调用」', async () => {
    const calls = deferred<{ calls: unknown[] }>()
    crab.calls.mockReturnValue(calls.promise)
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('暂无调用')
    expect(wrapper.findAll('.stat .value').map((v) => v.text())).not.toContain('0')

    calls.resolve({ calls: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('暂无调用')
  })

  it('只是账号暂停时不说缺凭证或配置', async () => {
    crab.conches.mockResolvedValue({ conches: [summary({ needsConfiguration: true, loginPaused: true })] })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('1 个规则包有账号暂停了自动登录')
    expect(wrapper.text()).not.toContain('还缺凭证或配置')
    expect(wrapper.text()).not.toContain('还没有账号，或还缺凭证、配置')
  })

  it('没有账号的规则包计入待配置，不说成账号暂停', async () => {
    crab.conches.mockResolvedValue({
      conches: [summary({ accountCount: 0, needsConfiguration: true, loginPaused: false })],
    })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('1 个规则包还没有账号，或还缺凭证、配置')
    expect(wrapper.text()).toContain('legacy_oa')
    expect(wrapper.text()).not.toContain('有账号暂停了自动登录')
  })

  it('有停用的规则包时同步卡不显示「已是最新」，也不重复列为已移除', async () => {
    crab.syncStatus.mockResolvedValue({
      report: report({
        conches: [{ conch: 'legacy_oa', version: '1.0.0', kind: 'DISABLED', reason: '维护中' }],
        removed: ['legacy_oa'],
      }),
      latestCrab: null,
    })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('已是最新')
    expect(text).toContain('1 个规则包 Reef 已停用')
    expect(text).toContain('本机保留的版本仍可调用')
    expect(text).toContain('Reef 已停用，不再更新')
    expect(text).not.toContain('已失效')
    expect(text).not.toContain('不能调用')
    expect(text).not.toContain('已移除')
  })

  it('没有停用项且没有变化时显示「已是最新」', async () => {
    crab.syncStatus.mockResolvedValue({ report: report({}), latestCrab: null })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('已是最新')
  })

  it('目录 304 但有包可更新时不显示「已是最新」，并列出可更新的包', async () => {
    crab.conches.mockResolvedValue({
      conches: [summary({ updateAvailable: true, latestVersion: '1.2.0' }), summary({ conch: 'crm', updateAvailable: false })],
    })
    crab.syncStatus.mockResolvedValue({ report: report({ notModified: true }), latestCrab: null })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('已是最新')
    expect(wrapper.get('[data-test="updatable"]').text()).toContain('1 个规则包有新版本，需要手动更新')
    expect(wrapper.get('[data-test="updatable"]').text()).toContain('legacy_oa v1.2.0')
  })

  it('开发者模式可以拨，不再标明暂未生效', async () => {
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('暂未生效')
    expect(wrapper.get('[data-test="debug-switch"]').classes()).not.toContain('is-disabled')
    expect(wrapper.text()).toContain('只对本机本地规则包记录脱敏后的请求与响应')
  })

  it('需要修复时不显示「已是最新」，列出原因和修复按钮', async () => {
    crab.syncStatus.mockResolvedValue({
      report: report({ conches: [{ conch: 'legacy_oa', version: '1.0.0', kind: 'REPAIR_REQUIRED', reason: 'POINTER_MISSING' }] }),
      latestCrab: null,
    })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('已是最新')
    expect(text).not.toContain('REPAIR_REQUIRED')
    const box = wrapper.get('[data-test="repairs"]')
    expect(box.text()).toContain('1 个规则包需要修复')
    expect(box.text()).toContain('本机版本指针丢失，同步不会重新下载')
    expect(box.findAll('button').map((b) => b.text())).toContain('修复')
  })

  it('只有 http 或 https 的下载地址才是链接', async () => {
    crab.syncStatus.mockResolvedValue({
      report: null,
      latestCrab: { latestVersion: '9.9.9', downloadUrl: 'javascript:alert(1)' },
    })
    const blocked = await mountPage(OverviewView)
    await flushPromises()
    const isDownloadLink = (blocked.vm as unknown as { isDownloadLink: (url: string) => boolean }).isDownloadLink
    expect(isDownloadLink('javascript:alert(1)')).toBe(false)
    expect(isDownloadLink('https://example.invalid/crab.jar')).toBe(true)
    expect(isDownloadLink('http://127.0.0.1:9/crab.jar')).toBe(true)

    expect(blocked.text()).toContain('javascript:alert(1)')
    expect(blocked.html()).not.toMatch(/href\s*=\s*["']\s*javascript:/i)

    crab.syncStatus.mockResolvedValue({
      report: null,
      latestCrab: { latestVersion: '9.9.9', downloadUrl: 'https://example.invalid/crab.jar' },
    })
    const linked = await mountPage(OverviewView)
    await flushPromises()
    expect(linked.get('a[href="https://example.invalid/crab.jar"]').text()).toBe('https://example.invalid/crab.jar')
  })

  it('换壳动画只在同步进行中出现', async () => {
    const pending = deferred<ReturnType<typeof report>>()
    crab.sync.mockReturnValue(pending.promise)
    const wrapper = await mountPage(OverviewView)
    await flushPromises()
    expect(wrapper.find('[data-test="shell-swap"]').exists()).toBe(false)

    await wrapper.findAll('button').find((b) => b.text().includes('立即同步'))!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="shell-swap"]').text()).toContain('正在从 Reef 拉取规则包')

    pending.resolve(report({}))
    await flushPromises()
    expect(wrapper.find('[data-test="shell-swap"]').exists()).toBe(false)
  })

  it('挑壳的小螃蟹只在开发者模式打开时出现', async () => {
    const off = await mountPage(OverviewView)
    await flushPromises()
    expect(off.find('[data-test="shell-picker"]').exists()).toBe(false)

    crab.debug.mockResolvedValue({ enabled: true, expiresAt: null })
    const on = await mountPage(OverviewView)
    await flushPromises()
    expect(on.find('[data-test="shell-picker"]').exists()).toBe(true)
  })
})
