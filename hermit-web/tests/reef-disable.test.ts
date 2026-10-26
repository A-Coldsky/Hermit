import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const reef = vi.hoisted(() => ({
  versions: vi.fn(),
  conch: vi.fn(),
  disableConch: vi.fn(),
  enableConch: vi.fn(),
  tideSummary: vi.fn(),
  tideFailures: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))

import ConchDetailView from '../apps/reef-admin/src/views/ConchDetailView.vue'

const base = {
  conch: 'legacy_oa',
  version: '1.0.0',
  title: '协同办公 OA',
  description: '',
  driver: 'http',
  minCrabVersion: '1.0.0',
  baseUrl: null,
  owner: null,
  tags: [],
  changelog: null,
  credentials: {},
  config: {},
  actions: {},
  disabled: false,
  disabledAt: null,
  disabledBy: null,
  disabledReason: null,
}

const versions = [
  { version: '1.1.0', status: 'UPLOADED', current: false, uploadedAt: '2026-10-26T02:00:00Z', warningCount: 0, warnings: [] },
  { version: '1.0.0', status: 'PUBLISHED', current: true, uploadedAt: '2026-10-25T02:00:00Z', warningCount: 0, warnings: [] },
]

beforeEach(() => {
  vi.clearAllMocks()
  reef.versions.mockResolvedValue(versions)
})

describe('Reef 规则包停用', () => {
  it('停用时显示原因与操作人，提供启用，发布按钮不可点', async () => {
    reef.conch.mockResolvedValue({
      ...base,
      disabled: true,
      disabledAt: '2026-10-26T03:00:00Z',
      disabledBy: 'admin',
      disabledReason: '老系统下线',
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, path: '/?tab=versions' })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('这个规则包已停用')
    expect(text).toContain('老系统下线')
    expect(text).toContain('admin')
    const buttons = wrapper.findAll('button').map((b) => b.text())
    expect(buttons).toContain('启用')
    expect(buttons).not.toContain('停用')
    expect(buttons).not.toContain('发布')
  })

  it('停用需要填写原因，提交后重新加载', async () => {
    reef.conch.mockResolvedValue(base)
    reef.disableConch.mockResolvedValue({ conch: 'legacy_oa', disabled: true })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, attach: true })
    await flushPromises()

    await wrapper.findAll('button').find((b) => b.text() === '停用')!.trigger('click')
    await flushPromises()
    const confirm = () => [...document.querySelectorAll('.el-dialog button')].find((b) => b.textContent?.trim() === '停用') as HTMLButtonElement
    confirm().click()
    await flushPromises()
    expect(reef.disableConch).not.toHaveBeenCalled()

    const textarea = document.querySelector('.el-dialog textarea') as HTMLTextAreaElement
    textarea.value = '  老系统下线  '
    textarea.dispatchEvent(new Event('input'))
    await flushPromises()
    confirm().click()
    await flushPromises()
    expect(reef.disableConch).toHaveBeenCalledWith('legacy_oa', '老系统下线')
    expect(reef.conch).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
