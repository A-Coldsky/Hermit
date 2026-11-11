import { flushPromises } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  conch: vi.fn(),
  calls: vi.fn(),
  call: vi.fn(),
  apiKeys: vi.fn(),
  createApiKey: vi.fn(),
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
          scope: { all: true, readOnly: true, conches: [], actions: [] },
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

  it('列表标出全部权限的 Key', async () => {
    crab.apiKeys.mockResolvedValue({
      apiKeys: [
        { id: 'k1', name: 'ops', prefix: 'hk_a', scope: { all: true, readOnly: false, conches: [], actions: [] }, createdAt: '2026-10-30T00:00:00Z', lastUsedAt: null, revokedAt: null },
        { id: 'k2', name: 'oa', prefix: 'hk_b', scope: { all: false, readOnly: true, conches: ['legacy_oa'], actions: [] }, createdAt: '2026-10-30T00:00:00Z', lastUsedAt: null, revokedAt: null },
      ],
    })
    const wrapper = await mountPage(ApiKeysView)
    await flushPromises()
    expect(wrapper.findAll('[data-test="scope-all"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('全部规则包的全部能力，包括本地规则包')
    expect(wrapper.text()).toContain('legacy_oa')
  })

  it('选中全部权限时提示风险，创建时传 all 且不带范围', async () => {
    crab.apiKeys.mockResolvedValue({ apiKeys: [] })
    crab.createApiKey.mockResolvedValue({ id: 'k1', name: 'ops', prefix: 'hk_a', plaintext: 'hk_a_secret', scope: { all: true, readOnly: false, conches: [], actions: [] }, createdAt: '2026-11-04T00:00:00Z' })
    const warning = vi.spyOn(ElMessage, 'warning')
    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('创建 API Key'))!.trigger('click')
    await flushPromises()

    const dialog = () => document.body.querySelector('.el-dialog') as HTMLElement
    const input = dialog().querySelector('input[placeholder^="用途说明"]') as HTMLInputElement
    input.value = 'ops'
    input.dispatchEvent(new Event('input'))
    const submit = () => [...dialog().querySelectorAll('button')].find((b) => b.textContent?.trim() === '创建')!.click()

    submit()
    await flushPromises()
    expect(crab.createApiKey).not.toHaveBeenCalled()
    expect(warning).toHaveBeenCalledWith('请选择允许的规则包或能力，或改选「全部权限」')

    expect(document.body.querySelector('[data-test="scope-all-risk"]')).toBeNull()
    const radios = [...dialog().querySelectorAll('.el-radio')] as HTMLElement[]
    radios.find((r) => r.textContent?.includes('全部权限'))!.click()
    await flushPromises()
    const risk = document.body.querySelector('[data-test="scope-all-risk"]')
    expect(risk?.textContent).toContain('全部权限风险较高')
    expect(risk?.textContent).toContain('包括本地规则包')
    expect(risk?.textContent).toContain('这把 Key 不能保存本地规则包')
    expect(risk?.textContent).not.toContain('会修改数据的能力也能调用')

    ;(dialog().querySelector('.el-checkbox') as HTMLElement).click()
    await flushPromises()
    expect(document.body.querySelector('[data-test="scope-all-risk"]')?.textContent).toContain('会修改数据的能力也能调用')

    submit()
    await flushPromises()
    expect(crab.createApiKey).toHaveBeenCalledWith({ name: 'ops', readOnly: false, all: true, conches: [], actions: [] })
    const example = document.body.querySelector('[data-test="key-example"]')
    expect(example?.textContent).toContain('Bearer <API Key>')
    expect(example?.textContent).toContain('/mcp')
    expect(example?.textContent).not.toContain('hk_a_secret')
    wrapper.unmount()
  })
})

