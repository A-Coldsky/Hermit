import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'
import { setRole } from '../apps/reef-admin/src/auth'

const reef = vi.hoisted(() => ({
  conches: vi.fn(),
  tideSummary: vi.fn(),
  alerts: vi.fn(),
  ackAlert: vi.fn(),
  tideRollbacks: vi.fn(),
}))
const crab = vi.hoisted(() => ({ conches: vi.fn(), localConches: vi.fn(async () => ({ conches: [] })) }))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/alerts', () => ({ refreshOpenAlerts: vi.fn() }))
vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ReefConchesView from '../apps/reef-admin/src/views/ConchesView.vue'
import DashboardView from '../apps/reef-admin/src/views/DashboardView.vue'
import AlertsView from '../apps/reef-admin/src/views/AlertsView.vue'
import CrabConchesView from '../apps/crab-console/src/views/ConchesView.vue'

const reefConch = {
  conch: 'legacy_oa',
  version: '1.0.0',
  title: '协同办公 OA',
  description: '',
  owner: null,
  tags: [],
  capabilityCount: 1,
  publishedAt: null,
}

beforeEach(() => {
  setRole('ADMIN')
  vi.clearAllMocks()
  localStorage.removeItem('hermit.reef.conchesView')
})

describe('Reef 规则包目录', () => {
  it('请求返回前不显示空态', async () => {
    const pending = deferred<unknown[]>()
    reef.conches.mockReturnValue(pending.promise)
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('还没有规则包')

    pending.resolve([])
    await flushPromises()
    expect(wrapper.text()).toContain('还没有规则包，先上传一个 YAML')
  })

  it('有数据时不显示空态', async () => {
    reef.conches.mockResolvedValue([reefConch])
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()

    expect(wrapper.text()).toContain('协同办公 OA')
    expect(wrapper.text()).not.toContain('还没有规则包')
  })

  it('默认卡片视图在请求返回前显示加载中和骨架，返回后再显示卡片', async () => {
    const pending = deferred<unknown[]>()
    reef.conches.mockReturnValue(pending.promise)
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()

    expect(localStorage.getItem('hermit.reef.conchesView')).toBeNull()
    expect(wrapper.get('[data-test="catalog-loading"]').text()).toContain('加载中')
    expect(wrapper.find('.cards .el-skeleton').exists()).toBe(true)
    expect(wrapper.find('.cards .conch').exists()).toBe(false)
    expect(wrapper.find('.el-table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('还没有规则包')
    expect(wrapper.text()).not.toContain('共 0 个')

    pending.resolve([reefConch])
    await flushPromises()
    expect(wrapper.find('[data-test="catalog-loading"]').exists()).toBe(false)
    expect(wrapper.find('.cards .conch').exists()).toBe(true)
    expect(wrapper.find('.el-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('协同办公 OA')
    expect(wrapper.text()).not.toContain('加载中')
    expect(wrapper.text()).not.toContain('还没有规则包')
  })

  it('默认卡片视图在目录为空时，加载结束后才显示上传提示', async () => {
    const pending = deferred<unknown[]>()
    reef.conches.mockReturnValue(pending.promise)
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()

    expect(wrapper.get('[data-test="catalog-loading"]').text()).toContain('加载中')
    expect(wrapper.find('.el-skeleton').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('还没有规则包')

    pending.resolve([])
    await flushPromises()
    expect(wrapper.find('[data-test="catalog-loading"]').exists()).toBe(false)
    expect(wrapper.find('.el-table').exists()).toBe(false)
    expect(wrapper.text()).toContain('还没有规则包，先上传一个 YAML')
    expect(wrapper.text()).not.toContain('加载中')
  })

  it('默认卡片视图在已有规则包时刷新，卡片还在，不换成加载中', async () => {
    reef.conches.mockResolvedValue([reefConch])
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()
    expect(wrapper.find('.cards .conch').exists()).toBe(true)

    const pending = deferred<unknown[]>()
    reef.conches.mockReturnValue(pending.promise)
    await wrapper.findAll('button').find((b) => b.text() === '刷新')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="catalog-loading"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('加载中')
    expect(wrapper.find('.cards .conch').exists()).toBe(true)
    expect(wrapper.text()).toContain('协同办公 OA')

    pending.resolve([reefConch])
    await flushPromises()
    expect(wrapper.find('.cards .conch').exists()).toBe(true)
    expect(wrapper.text()).toContain('协同办公 OA')
    expect(wrapper.text()).not.toContain('加载中')
  })

  it('列表视图在请求返回前显示加载中，返回后再显示表格', async () => {
    localStorage.setItem('hermit.reef.conchesView', 'table')
    const pending = deferred<unknown[]>()
    reef.conches.mockReturnValue(pending.promise)
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()

    expect(wrapper.get('[data-test="catalog-loading"]').text()).toContain('加载中')
    expect(wrapper.find('.el-skeleton').exists()).toBe(true)
    expect(wrapper.find('.el-table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('还没有规则包')

    pending.resolve([reefConch])
    await flushPromises()
    expect(wrapper.find('[data-test="catalog-loading"]').exists()).toBe(false)
    expect(wrapper.find('.el-table').exists()).toBe(true)
    expect(wrapper.text()).toContain('协同办公 OA')
    expect(wrapper.text()).not.toContain('加载中')
    expect(wrapper.text()).not.toContain('还没有规则包')
  })

  it('列表视图在目录为空时，加载结束后才显示上传提示', async () => {
    localStorage.setItem('hermit.reef.conchesView', 'table')
    const pending = deferred<unknown[]>()
    reef.conches.mockReturnValue(pending.promise)
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()

    expect(wrapper.text()).toContain('加载中')
    expect(wrapper.text()).not.toContain('还没有规则包')

    pending.resolve([])
    await flushPromises()
    expect(wrapper.find('.el-table').exists()).toBe(true)
    expect(wrapper.text()).toContain('还没有规则包，先上传一个 YAML')
    expect(wrapper.text()).not.toContain('加载中')
  })
})

describe('Reef 概览', () => {
  it('加载中不显示「一切正常」，加载完且没有告警才显示', async () => {
    const alerts = deferred<{ alerts: unknown[] }>()
    reef.conches.mockResolvedValue([])
    reef.tideSummary.mockResolvedValue({ bucket: 'day', from: '', to: '', p95Note: '', series: [] })
    reef.alerts.mockReturnValue(alerts.promise)
    reef.tideRollbacks.mockResolvedValue({ events: [] })
    const wrapper = await mountPage(DashboardView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('一切正常')
    expect(wrapper.text()).not.toContain('7 天内没有 Crab 切换版本')
    expect(wrapper.text()).not.toContain('所选范围内没有调用数据')

    alerts.resolve({ alerts: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('一切正常')
    expect(wrapper.text()).toContain('7 天内没有 Crab 切换版本')
  })
})

const openAlert = {
  id: 7,
  createdAt: '2026-10-20T00:00:00Z',
  kind: 'FAILURE_SURGE',
  conch: 'legacy_oa',
  conchVersion: '1.0.0',
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

describe('Crab 规则包列表', () => {
  it('请求返回前不显示空态', async () => {
    const pending = deferred<{ conches: unknown[] }>()
    crab.conches.mockReturnValue(pending.promise)
    const wrapper = await mountPage(CrabConchesView)
    await flushPromises()

    expect(wrapper.text()).not.toContain('还没有规则包')

    pending.resolve({ conches: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('还没有规则包。')
  })
})
