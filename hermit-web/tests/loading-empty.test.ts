import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'

const reef = vi.hoisted(() => ({
  shells: vi.fn(),
  tideSummary: vi.fn(),
  alerts: vi.fn(),
  tideRollbacks: vi.fn(),
}))
const crab = vi.hoisted(() => ({ shells: vi.fn() }))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/alerts', () => ({ refreshOpenAlerts: vi.fn() }))
vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ReefShellsView from '../apps/reef-admin/src/views/ShellsView.vue'
import DashboardView from '../apps/reef-admin/src/views/DashboardView.vue'
import CrabShellsView from '../apps/crab-console/src/views/ShellsView.vue'

const reefShell = {
  shell: 'legacy_oa',
  version: '1.0.0',
  title: '协同办公 OA',
  description: '',
  owner: null,
  tags: [],
  capabilityCount: 1,
  publishedAt: null,
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('Reef Shell 目录', () => {
  it('请求返回前不显示空态', async () => {
    const pending = deferred<unknown[]>()
    reef.shells.mockReturnValue(pending.promise)
    const wrapper = mountPage(ReefShellsView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('还没有 Shell')

    pending.resolve([])
    await flushPromises()
    expect(wrapper.text()).toContain('还没有 Shell，先上传一个 YAML')
  })

  it('有数据时不显示空态', async () => {
    reef.shells.mockResolvedValue([reefShell])
    const wrapper = mountPage(ReefShellsView)
    await flushPromises()

    expect(wrapper.text()).toContain('协同办公 OA')
    expect(wrapper.text()).not.toContain('还没有 Shell')
  })
})

describe('Reef 概览', () => {
  it('加载中不显示「一切正常」，加载完且没有告警才显示', async () => {
    const alerts = deferred<{ alerts: unknown[] }>()
    reef.shells.mockResolvedValue([])
    reef.tideSummary.mockResolvedValue({ bucket: 'day', from: '', to: '', p95Note: '', series: [] })
    reef.alerts.mockReturnValue(alerts.promise)
    reef.tideRollbacks.mockResolvedValue({ events: [] })
    const wrapper = mountPage(DashboardView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('一切正常')
    expect(wrapper.text()).not.toContain('7 天内没有 Crab 发生自动回退')
    expect(wrapper.text()).not.toContain('所选范围内没有调用数据')

    alerts.resolve({ alerts: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('一切正常')
    expect(wrapper.text()).toContain('7 天内没有 Crab 发生自动回退')
  })
})

describe('Crab Shell 列表', () => {
  it('请求返回前不显示空态', async () => {
    const pending = deferred<{ shells: unknown[] }>()
    crab.shells.mockReturnValue(pending.promise)
    const wrapper = mountPage(CrabShellsView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('还没有 Shell')

    pending.resolve({ shells: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('还没有 Shell。')
  })
})
