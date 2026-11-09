import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'
import { setRole } from '../apps/reef-admin/src/auth'

const reef = vi.hoisted(() => ({
  versions: vi.fn(),
  conch: vi.fn(),
  conches: vi.fn(),
  disableConch: vi.fn(),
  enableConch: vi.fn(),
  tideSummary: vi.fn(),
  tideFailures: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))

import ConchDetailView from '../apps/reef-admin/src/views/ConchDetailView.vue'
import ConchesView from '../apps/reef-admin/src/views/ConchesView.vue'

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
  setRole('ADMIN')
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
    const banner = wrapper.findAll('.el-alert').find((a) => a.text().includes('这个规则包已停用'))!
    expect(banner.classes()).toContain('el-alert--warning')
    expect(banner.classes()).not.toContain('el-alert--error')
    const badge = wrapper.findAll('.el-tag').find((t) => t.text() === '已停用')!
    expect(badge.classes()).toContain('el-tag--warning')
    expect(badge.classes()).not.toContain('el-tag--danger')
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

  it('停用时版本号不用绿色，启用弹窗事先标出原因必填', async () => {
    reef.conch.mockResolvedValue({ ...base, disabled: true, disabledAt: '2026-10-26T03:00:00Z', disabledBy: 'admin', disabledReason: '下线' })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, attach: true })
    await flushPromises()

    const version = wrapper.findAll('.el-tag').find((t) => t.text() === 'v1.0.0')!
    expect(version.classes()).not.toContain('el-tag--success')

    await wrapper.findAll('button').find((b) => b.text() === '启用')!.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('.required-label .star')).not.toBeNull()
    expect(document.body.textContent).toContain('必填，会记入审计日志')
    wrapper.unmount()
  })

  it('重新启用后停用横幅消失', async () => {
    reef.conch
      .mockResolvedValueOnce({
        ...base,
        disabled: true,
        disabledAt: '2026-10-26T03:00:00Z',
        disabledBy: 'admin',
        disabledReason: '老系统下线',
      })
      .mockResolvedValue(base)
    reef.enableConch.mockResolvedValue({ conch: 'legacy_oa', disabled: false })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, attach: true })
    await flushPromises()
    expect(wrapper.text()).toContain('这个规则包已停用')

    await wrapper.findAll('button').find((b) => b.text() === '启用')!.trigger('click')
    await flushPromises()
    const textarea = document.querySelector('.el-dialog textarea') as HTMLTextAreaElement
    textarea.value = '恢复上线'
    textarea.dispatchEvent(new Event('input'))
    await flushPromises()
    const confirm = [...document.querySelectorAll('.el-dialog button')].find((b) => b.textContent?.trim() === '启用') as HTMLButtonElement
    confirm.click()
    await flushPromises()

    expect(reef.enableConch).toHaveBeenCalledWith('legacy_oa', '恢复上线')
    expect(wrapper.text()).not.toContain('这个规则包已停用')
    expect(wrapper.findAll('.el-tag').some((t) => t.text() === '已停用')).toBe(false)
    wrapper.unmount()
  })

  it('目录卡片和表格的已停用徽标用警告色', async () => {
    localStorage.setItem('hermit.reef.conchesView', 'cards')
    reef.conches.mockResolvedValue([
      {
        conch: 'legacy_oa',
        version: '1.0.0',
        title: '协同办公 OA',
        description: '',
        owner: null,
        tags: [],
        capabilityCount: 1,
        publishedAt: null,
        disabled: true,
        disabledAt: '2026-10-26T03:00:00Z',
        disabledBy: 'admin',
        disabledReason: '老系统下线',
        pullCount: 0,
        driver: 'http',
        upstream: null,
        snapshot: null,
        tools: null,
      },
    ])
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const card = wrapper.findAll('.el-tag').find((t) => t.text() === '已停用')!
    expect(card.classes()).toContain('el-tag--warning')
    expect(card.classes()).not.toContain('el-tag--danger')

    await wrapper.get('[aria-label="列表视图"] input').setValue(true)
    await flushPromises()
    const row = wrapper.findAll('.el-tag').find((t) => t.text() === '已停用')!
    expect(row.classes()).toContain('el-tag--warning')
    expect(row.classes()).not.toContain('el-tag--danger')
    localStorage.removeItem('hermit.reef.conchesView')
  })
})

