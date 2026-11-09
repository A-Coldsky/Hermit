import { ApiError } from '@shared/http'
import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ConchVersion } from '../apps/reef-admin/src/api'
import { auditActionLabels } from '../apps/reef-admin/src/labels'
import { setRole, signIn } from '../apps/reef-admin/src/auth'
import { deferred, mountPage } from './helpers'

const reef = vi.hoisted(() => ({
  upload: vi.fn(),
  publish: vi.fn(),
  discardVersion: vi.fn(),
  diff: vi.fn(),
  versions: vi.fn(),
  conch: vi.fn(),
  conches: vi.fn(),
  tideSummary: vi.fn(),
  tideFailures: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))

import VersionPanel from '../apps/reef-admin/src/components/VersionPanel.vue'
import ConchDetailView from '../apps/reef-admin/src/views/ConchDetailView.vue'
import ConchesView from '../apps/reef-admin/src/views/ConchesView.vue'
import UploadView from '../apps/reef-admin/src/views/UploadView.vue'

const uploaded: ConchVersion = {
  version: '1.1.0',
  status: 'UPLOADED',
  uploadedBy: 'admin',
  uploadedAt: '2026-11-05T02:00:00Z',
  publishedBy: null,
  publishedAt: null,
  current: false,
  warningCount: 0,
  warnings: [],
  pullCount: 0,
}

const published: ConchVersion = {
  ...uploaded,
  version: '1.0.0',
  status: 'PUBLISHED',
  uploadedAt: '2026-11-04T02:00:00Z',
  publishedBy: 'admin',
  publishedAt: '2026-11-04T02:00:00Z',
  current: true,
}

const detail = {
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
  pullCount: 0,
  published: true,
}

async function chooseFile(wrapper: Awaited<ReturnType<typeof mountPage>>) {
  const input = wrapper.get('input[type="file"]')
  const file = new File(['conch: legacy_oa\n'], 'legacy_oa.yaml', { type: 'application/x-yaml' })
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

beforeEach(() => {
  setRole('ADMIN')
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('上传规则包', () => {
  it('选好文件后先停在本地，确认后才上传，取消不发请求', async () => {
    reef.upload.mockResolvedValue({ conch: 'legacy_oa', version: '1.1.0', warnings: [], diff: null })
    const wrapper = await mountPage(UploadView)
    await chooseFile(wrapper)

    expect(reef.upload).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="pending"]').text()).toContain('legacy_oa.yaml')

    await wrapper.findAll('button').find((b) => b.text() === '取消')!.trigger('click')
    expect(wrapper.find('[data-test="pending"]').exists()).toBe(false)
    expect(reef.upload).not.toHaveBeenCalled()

    await chooseFile(wrapper)
    await wrapper.get('[data-test="confirm-upload"]').trigger('click')
    await flushPromises()
    expect(reef.upload).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('待发布')
  })

  it('待发布版本可以丢弃，确认后调用丢弃接口', async () => {
    reef.upload.mockResolvedValue({ conch: 'legacy_oa', version: '1.1.0', warnings: [], diff: null })
    reef.discardVersion.mockResolvedValue({ conch: 'legacy_oa', version: '1.1.0' })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(UploadView)
    await chooseFile(wrapper)
    await wrapper.get('[data-test="confirm-upload"]').trigger('click')
    await flushPromises()

    await wrapper.get('[data-test="discard-upload"]').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(reef.discardVersion).toHaveBeenCalledWith('legacy_oa', '1.1.0')
    expect(wrapper.find('[data-test="discard-upload"]').exists()).toBe(false)
  })
})

describe('版本表丢弃', () => {
  it('只有未发布版本有丢弃，确认后通知刷新', async () => {
    reef.discardVersion.mockResolvedValue({ conch: 'legacy_oa', version: '1.1.0' })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(VersionPanel, { props: { conch: 'legacy_oa', versions: [uploaded, published] } })
    await flushPromises()

    const buttons = wrapper.findAll('[data-test="discard"]')
    expect(buttons).toHaveLength(1)
    await buttons[0].trigger('click')
    await flushPromises()
    expect(reef.discardVersion).toHaveBeenCalledWith('legacy_oa', '1.1.0')
    expect(wrapper.emitted('changed')).toHaveLength(1)
  })

  it('USER 看不到丢弃', async () => {
    setRole('USER')
    const wrapper = await mountPage(VersionPanel, { props: { conch: 'legacy_oa', versions: [uploaded, published] } })
    await flushPromises()
    expect(wrapper.find('[data-test="discard"]').exists()).toBe(false)
  })

  it('审计动作 DISCARD 显示为丢弃', () => {
    expect(auditActionLabels.DISCARD).toBe('丢弃')
  })
})

describe('USER 打开管理员页面', () => {
  it('送回概览时给出没有权限的提示', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    signIn('token', '2099-01-01T00:00:00Z', 'alice')
    setRole('USER')
    const { router } = await import('../apps/reef-admin/src/router')
    await router.push('/conches/upload')
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(warning).toHaveBeenCalledWith(expect.objectContaining({ message: '没有权限执行这个操作' }))
  })
})

describe('USER 看到的规则包', () => {
  it('详情页有未发布版本时不显示「去发布」', async () => {
    setRole('USER')
    reef.versions.mockResolvedValue([uploaded, published])
    reef.conch.mockResolvedValue(detail)
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(wrapper.text()).toContain('还没发布')
    expect(wrapper.find('[data-test="go-publish"]').exists()).toBe(false)
  })

  it('ADMIN 仍有「去发布」', async () => {
    reef.versions.mockResolvedValue([uploaded, published])
    reef.conch.mockResolvedValue(detail)
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(wrapper.find('[data-test="go-publish"]').exists()).toBe(true)
  })

  it('管理员打开从未发布的包时标明未发布，并展示最高已上传版本', async () => {
    reef.versions.mockResolvedValue([
      { ...uploaded, version: '1.0.0', uploadedAt: '2026-11-04T02:00:00Z' },
      uploaded,
    ])
    reef.conch.mockResolvedValue({
      ...detail,
      version: '1.1.0',
      published: false,
      description: '只覆盖待办查询。',
      changelog: '先看这一版再决定发不发布。',
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.get('[data-test="unpublished-badge"]').text()).toBe('未发布')
    const notice = wrapper.get('[data-test="unpublished-notice"]').text()
    expect(notice).toContain('还未发布')
    expect(notice).toContain('v1.1.0')
    expect(wrapper.get('[data-test="go-publish"]').text()).toContain('去发布')
    expect(wrapper.text()).toContain('尚未发布')
    expect(wrapper.text()).toContain('最高已上传版本')
    expect(wrapper.text()).toContain('只覆盖待办查询。')
    expect(wrapper.text()).toContain('先看这一版再决定发不发布。')
    expect(wrapper.text()).not.toContain('当前发布版本')

    await wrapper.get('[data-test="go-publish"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('v1.1.0')
  })

  it('普通用户直接打开从未发布的包时显示不存在', async () => {
    setRole('USER')
    reef.versions.mockRejectedValue(new ApiError(404, 'NOT_FOUND', '资源不存在'))
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.text()).toContain('规则包不存在')
    expect(wrapper.find('[data-test="unpublished-badge"]').exists()).toBe(false)
    expect(reef.conch).not.toHaveBeenCalled()
  })

  it('目录加载中不显示「共 0 个」', async () => {
    const pending = deferred<never[]>()
    reef.conches.mockReturnValue(pending.promise)
    const wrapper = await mountPage(ConchesView)
    await flushPromises()
    expect(wrapper.find('[data-test="conch-count"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('共 0 个')

    pending.resolve([])
    await flushPromises()
    expect(wrapper.get('[data-test="conch-count"]').text()).toBe('共 0 个')
  })
})
