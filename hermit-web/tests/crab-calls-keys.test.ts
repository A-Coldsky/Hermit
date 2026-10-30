import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  conch: vi.fn(),
  calls: vi.fn(),
  call: vi.fn(),
  apiKeys: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ApiKeysView from '../apps/crab-console/src/views/ApiKeysView.vue'
import CallsView from '../apps/crab-console/src/views/CallsView.vue'

beforeEach(() => {
  vi.clearAllMocks()
  crab.conches.mockResolvedValue({ conches: [] })
})

describe('Crab 调用记录', () => {
  it('错误标签和错误说明分开显示', async () => {
    crab.calls.mockResolvedValue({
      calls: [
        {
          traceId: 't-1',
          at: '2026-10-30T00:00:00Z',
          origin: 'http',
          conch: 'legacy_oa',
          action: 'list_todo',
          account: 'zhangsan',
          conchVersion: '1.0.0',
          success: false,
          errorCode: 'UPSTREAM_ERROR',
          errorMessage: '上游返回 502',
          durationMs: 120,
          apiKeyId: null,
        },
      ],
    })
    const wrapper = await mountPage(CallsView)
    await flushPromises()

    const message = wrapper.find('.err-msg')
    expect(message.exists()).toBe(true)
    expect(message.text()).toBe('上游返回 502')
    expect(wrapper.find('.err').text()).not.toContain('上游返回 502')
  })
})

describe('Crab API Keys', () => {
  it('操作列固定在右侧，创建前就提示 Key 只显示一次', async () => {
    crab.apiKeys.mockResolvedValue({
      apiKeys: [
        {
          id: 'k1',
          name: 'ops',
          prefix: 'hk_abcd',
          scope: { readOnly: true, conches: [], actions: [] },
          createdAt: '2026-10-30T00:00:00Z',
          lastUsedAt: null,
          revokedAt: null,
        },
      ],
    })
    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    expect(wrapper.find('.el-table-fixed-column--right').exists()).toBe(true)

    await wrapper.findAll('button').find((b) => b.text().includes('创建 API Key'))!.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('完整的 Key 只显示一次')
    wrapper.unmount()
  })
})
