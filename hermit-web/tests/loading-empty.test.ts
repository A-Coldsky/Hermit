import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'

const reef = vi.hoisted(() => ({
  shells: vi.fn(),
  tideSummary: vi.fn(),
  alerts: vi.fn(),
  ackAlert: vi.fn(),
  tideRollbacks: vi.fn(),
}))
const crab = vi.hoisted(() => ({ shells: vi.fn() }))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/alerts', () => ({ refreshOpenAlerts: vi.fn() }))
vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ReefShellsView from '../apps/reef-admin/src/views/ShellsView.vue'
import DashboardView from '../apps/reef-admin/src/views/DashboardView.vue'
import AlertsView from '../apps/reef-admin/src/views/AlertsView.vue'
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
    const wrapper = await mountPage(ReefShellsView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('还没有 Shell')

    pending.resolve([])
    await flushPromises()
    expect(wrapper.text()).toContain('还没有 Shell，先上传一个 YAML')
  })

  it('有数据时不显示空态', async () => {
    reef.shells.mockResolvedValue([reefShell])
    const wrapper = await mountPage(ReefShellsView)
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
    const wrapper = await mountPage(DashboardView)
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

const openAlert = {
  id: 7,
  createdAt: '2026-10-20T00:00:00Z',
  kind: 'FAILURE_SURGE',
  shell: 'legacy_oa',
  shellVersion: '1.0.0',
  detail: null,
  acknowledgedAt: null,
  acknowledgedBy: null,
}

describe('Reef 告警', () => {
  it('确认最后一条后直接显示空态，重新拉取期间不进入加载态', async () => {
    reef.alerts.mockResolvedValueOnce({ alerts: [openAlert] })
    const refetch = deferred<{ alerts: unknown[] }>()
    reef.alerts.mockReturnValueOnce(refetch.promise)
    reef.ackAlert.mockResolvedValue({ ...openAlert, acknowledgedAt: '2026-10-20T01:00:00Z', acknowledgedBy: 'admin' })
    const wrapper = await mountPage(AlertsView)
    await flushPromises()
    expect(wrapper.text()).toContain('legacy_oa')

    await wrapper.findAll('button').find((b) => b.text() === '确认')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('没有未处理的告警')

    refetch.resolve({ alerts: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('没有未处理的告警')
  })

  it('加载失败时不声称没有告警', async () => {
    reef.alerts.mockRejectedValue(new Error('network'))
    const wrapper = await mountPage(AlertsView)
    await flushPromises()
    expect(wrapper.text()).not.toContain('没有未处理的告警')
  })
})

describe('Crab Shell 列表', () => {
  it('请求返回前不显示空态', async () => {
    const pending = deferred<{ shells: unknown[] }>()
    crab.shells.mockReturnValue(pending.promise)
    const wrapper = await mountPage(CrabShellsView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('还没有 Shell')

    pending.resolve({ shells: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('还没有 Shell。')
  })
})
