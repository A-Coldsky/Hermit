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

  it('详情抽屉不超过视口宽度', async () => {
    crab.calls.mockResolvedValue({ calls: [] })
    const wrapper = await mountPage(CallsView)
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElDrawer' }).props('size')).toBe('min(560px, 100vw)')
  })
})

describe('Crab API Keys', () => {
  it('列表读取失败时显示错误和重试，不写「还没有 API Key」', async () => {
    crab.apiKeys.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage(ApiKeysView)
    await flushPromises()
    expect(wrapper.get('[data-test="keys-load-failed"]').text()).toContain('API Key 列表读取失败')
    expect(wrapper.text()).not.toContain('还没有 API Key')

    crab.apiKeys.mockResolvedValue({ apiKeys: [] })
    await wrapper.get('[data-test="keys-load-failed"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="keys-load-failed"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('还没有 API Key')
  })

  it('本机没有规则包时，创建框提示先订阅或导入', async () => {
    crab.apiKeys.mockResolvedValue({ apiKeys: [] })
    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('创建 API Key'))!.trigger('click')
    await flushPromises()
    const hint = document.body.querySelector('[data-test="no-conches"]') as HTMLElement
    expect(hint.textContent).toContain('本机还没有规则包')
    expect(hint.querySelector('a[href="/catalog"]')).not.toBeNull()
    expect(hint.querySelector('a[href="/conches"]')).not.toBeNull()
    wrapper.unmount()
  })

  it('明文弹窗用右上角关闭时先确认，选「返回复制」明文还在', async () => {
    crab.apiKeys.mockResolvedValue({ apiKeys: [] })
    crab.createApiKey.mockResolvedValue({ id: 'k1', name: 'ops', prefix: 'hk_a', plaintext: 'hk_a_secret', scope: { all: true, readOnly: true, conches: [], actions: [] }, createdAt: '2026-11-04T00:00:00Z' })
    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('创建 API Key'))!.trigger('click')
    await flushPromises()
    const form = document.body.querySelector('.el-dialog') as HTMLElement
    const input = form.querySelector('input[placeholder^="用途说明"]') as HTMLInputElement
    input.value = 'ops'
    input.dispatchEvent(new Event('input'))
    ;([...form.querySelectorAll('.el-radio')] as HTMLElement[]).find((r) => r.textContent?.includes('全部权限'))!.click()
    await flushPromises()
    ;[...form.querySelectorAll('button')].find((b) => b.textContent?.trim() === '创建')!.click()
    await flushPromises()

    const created = () => document.body.querySelector('[data-test="created-dialog"]') as HTMLElement
    expect(created().textContent).toContain('hk_a_secret')
    ;(created().querySelector('.el-dialog__headerbtn') as HTMLElement).click()
    await flushPromises()
    const box = document.body.querySelector('.el-message-box') as HTMLElement
    expect(box.textContent).toContain('已经保存 API Key 了吗')
    ;([...box.querySelectorAll('button')] as HTMLElement[]).find((b) => b.textContent?.includes('返回复制'))!.click()
    await flushPromises()
    expect(created().textContent).toContain('hk_a_secret')
    wrapper.unmount()
  })

  it('页面上有可复制的 Agent 接入提示词，不含 Key 明文', async () => {
    crab.apiKeys.mockResolvedValue({ apiKeys: [] })
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()

    const pre = wrapper.get('[data-test="connect-prompt"] pre')
    expect(pre.isVisible()).toBe(false)
    await wrapper.get('[data-test="toggle-connect"]').trigger('click')
    expect(pre.isVisible()).toBe(true)
    const text = pre.text()
    expect(text).toContain(`${window.location.origin}/mcp`)
    expect(text).toContain('Authorization: Bearer <API Key>')
    expect(text).toContain('不要向我索要 Key，也不要让我把 Key 发到对话里')
    expect(text).toContain('占位符 <API Key> 在其中的哪个位置')
    expect(text).toContain('Crab 控制台的「API Key」页')
    expect(text).toContain('tools/list')
    expect(text).toContain('list_systems')
    expect(text).not.toMatch(/hk_[A-Za-z0-9]/)

    await wrapper.find('[data-test="copy-connect"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText.mock.calls[0][0]).toContain('/mcp')
    wrapper.unmount()
  })

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

