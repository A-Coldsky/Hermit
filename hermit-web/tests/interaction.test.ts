import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const reef = vi.hoisted(() => ({ conches: vi.fn() }))
const crab = vi.hoisted(() => ({ conches: vi.fn(), calls: vi.fn(), call: vi.fn() }))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ReefConchesView from '../apps/reef-admin/src/views/ConchesView.vue'
import CallsView from '../apps/crab-console/src/views/CallsView.vue'

function call(index: number, success: boolean) {
  return {
    traceId: `t-${index}`,
    at: '2026-10-15T08:00:00Z',
    origin: 'API',
    conch: 'legacy_oa',
    action: 'list_todo',
    account: 'alice',
    conchVersion: '1.0.0',
    success,
    errorCode: success ? null : 'UPSTREAM_ERROR',
    errorMessage: success ? null : '上游状态码 500',
    durationMs: 100 + index,
  }
}

afterEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
})

describe('Crab 调用记录', () => {
  it('超过一页时分页，汇总按全部记录计算', async () => {
    crab.conches.mockResolvedValue({ conches: [] })
    crab.calls.mockResolvedValue({ calls: Array.from({ length: 45 }, (_, i) => call(i, i % 5 !== 0)) })
    const wrapper = await mountPage(CallsView, { path: '/calls' })
    await flushPromises()

    expect(wrapper.findAll('.el-table__body tr')).toHaveLength(20)
    expect(wrapper.find('.el-pagination').exists()).toBe(true)
    expect(wrapper.get('.summary').text()).toContain('45')
    expect(wrapper.get('.summary').text()).toContain('失败 9')
  })

  it('带 failed=1 进入时只看失败', async () => {
    crab.conches.mockResolvedValue({ conches: [] })
    crab.calls.mockResolvedValue({ calls: [call(1, true), call(2, false), call(3, true)] })
    const wrapper = await mountPage(CallsView, { path: '/calls?failed=1' })
    await flushPromises()

    expect(wrapper.findAll('.el-table__body tr')).toHaveLength(1)
    expect(wrapper.find('.el-pagination').exists()).toBe(false)
  })
})

describe('Reef 规则包目录视图', () => {
  const conch = {
    conch: 'legacy_oa',
    version: '1.0.0',
    title: '协同办公 OA',
    description: '审批与请假',
    owner: '平台组',
    tags: ['审批'],
    capabilityCount: 1,
    publishedAt: null,
  }

  it('默认卡片视图，切到列表后记住选择', async () => {
    reef.conches.mockResolvedValue([conch])
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()

    expect(wrapper.find('.cards .conch').exists()).toBe(true)
    expect(wrapper.find('.el-table').exists()).toBe(false)

    await wrapper.findAll('.el-radio-button input')[1].setValue(true)
    await flushPromises()
    expect(wrapper.find('.el-table').exists()).toBe(true)
    expect(localStorage.getItem('hermit.reef.conchesView')).toBe('table')
  })

  it('筛选没有结果时提示关键词，不说还没有规则包', async () => {
    reef.conches.mockResolvedValue([conch])
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()
    await wrapper.get('input[placeholder^="按名称"]').setValue('不存在')
    await flushPromises()

    expect(wrapper.text()).toContain('没有匹配「不存在」的规则包')
    expect(wrapper.text()).not.toContain('还没有规则包')
  })
})
