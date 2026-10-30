import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  calls: vi.fn(),
  debug: vi.fn(),
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
    expect(text).toContain('1 个规则包已在 Reef 停用')
    expect(text).toContain('已停用')
    expect(text).not.toContain('已移除')
  })

  it('没有停用项且没有变化时显示「已是最新」', async () => {
    crab.syncStatus.mockResolvedValue({ report: report({}), latestCrab: null })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('已是最新')
  })
})
