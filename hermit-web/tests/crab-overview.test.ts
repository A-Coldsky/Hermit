import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  calls: vi.fn(),
  debug: vi.fn(),
  setDebug: vi.fn(),
  syncStatus: vi.fn(),
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
})

describe('Crab 概览', () => {
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
})

