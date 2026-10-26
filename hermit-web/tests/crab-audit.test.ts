import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'

const crab = vi.hoisted(() => ({ audit: vi.fn() }))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))

import AuditView from '../apps/crab-console/src/views/AuditView.vue'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('Crab 配置审计', () => {
  it('请求返回前不显示空态，失败时也不显示', async () => {
    const pending = deferred<unknown>()
    crab.audit.mockReturnValueOnce(pending.promise)
    const wrapper = await mountPage(AuditView)
    await flushPromises()
    expect(wrapper.text()).not.toContain('没有记录')

    pending.resolve({ entries: [], page: 1, limit: 50, total: 0 })
    await flushPromises()
    expect(wrapper.text()).toContain('没有记录')
  })

  it('显示操作名、来源、Shell/账号和字段名', async () => {
    crab.audit.mockResolvedValue({
      entries: [
        {
          id: 3,
          at: '2026-10-26T08:00:00Z',
          actor: 'console',
          action: 'CREDENTIALS_WRITTEN',
          origin: 'OFFICIAL',
          shell: 'legacy_oa',
          account: 'zhangsan',
          fields: ['password', 'username'],
        },
      ],
      page: 1,
      limit: 50,
      total: 1,
    })
    const wrapper = await mountPage(AuditView)
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('修改凭证')
    expect(text).toContain('控制台')
    expect(text).toContain('legacy_oa')
    expect(text).toContain('zhangsan')
    expect(text).toContain('password, username')
  })

  it('按 Shell 和账号筛选时从第一页查', async () => {
    crab.audit.mockResolvedValue({ entries: [], page: 1, limit: 50, total: 0 })
    const wrapper = await mountPage(AuditView)
    await flushPromises()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('legacy_oa')
    await inputs[1].setValue('zhangsan')
    await wrapper.findAll('button').find((b) => b.text() === '查询')!.trigger('click')
    await flushPromises()
    expect(crab.audit).toHaveBeenLastCalledWith({ shell: 'legacy_oa', account: 'zhangsan', page: 1, limit: 50 })
  })
})
