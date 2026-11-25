import { flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { mountPage } from './helpers'
import { session, setRole } from '../apps/reef-admin/src/auth'

const reef = vi.hoisted(() => ({
  conches: vi.fn(),
  tideSummary: vi.fn(),
  alerts: vi.fn(),
  tideRollbacks: vi.fn(),
  announcements: vi.fn(),
  createAnnouncement: vi.fn(),
  updateAnnouncement: vi.fn(),
  deleteAnnouncement: vi.fn(),
  crabDownloadInfo: vi.fn(),
  downloadCrabJar: vi.fn(),
  submissions: vi.fn(),
  pendingSubmissionsCount: vi.fn(),
  submission: vi.fn(),
  submissionYaml: vi.fn(),
  submitConch: vi.fn(),
  withdrawSubmission: vi.fn(),
  approveSubmission: vi.fn(),
  rejectSubmission: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/alerts', () => ({ refreshOpenAlerts: vi.fn() }))
vi.mock('../apps/reef-admin/src/submissions', () => ({
  pendingSubmissions: { value: 0 },
  refreshPendingSubmissions: vi.fn(),
}))

import CrabDownloadView from '../apps/reef-admin/src/views/CrabDownloadView.vue'
import DashboardView from '../apps/reef-admin/src/views/DashboardView.vue'
import GuideView from '../apps/reef-admin/src/views/GuideView.vue'
import SubmissionsView from '../apps/reef-admin/src/views/SubmissionsView.vue'

beforeEach(() => {
  vi.resetAllMocks()
  session.user = 'admin'
  setRole('ADMIN')
  reef.conches.mockResolvedValue([])
  reef.tideSummary.mockResolvedValue({ bucket: 'day', from: '2026-11-17T00:00:00Z', to: '2026-11-24T00:00:00Z', p95Note: '', series: [] })
  reef.alerts.mockResolvedValue({ alerts: [] })
  reef.tideRollbacks.mockResolvedValue({ events: [] })
  reef.announcements.mockResolvedValue([])
  reef.submissions.mockResolvedValue([])
  reef.pendingSubmissionsCount.mockResolvedValue({ count: 0 })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('DashboardView 公共公告', () => {
  it('普通用户只读公告，空公告时隐藏公告卡片', async () => {
    setRole('USER')
    session.user = 'alice'
    reef.announcements.mockResolvedValueOnce([])
    const emptyWrap = await mountPage(DashboardView)
    await flushPromises()
    expect(emptyWrap.find('[data-test="announcements-card"]').exists()).toBe(false)
    emptyWrap.unmount()

    reef.announcements.mockResolvedValueOnce([
      {
        id: 1,
        title: 'Crab 新版已就绪',
        body: '请下载独立 JAR 包运行',
        level: 'IMPORTANT',
        pinned: true,
        createdBy: 'admin',
        createdAt: '2026-11-24T00:00:00Z',
        updatedBy: 'admin',
        updatedAt: '2026-11-24T00:00:00Z',
      },
    ])
    const wrap = await mountPage(DashboardView)
    await flushPromises()
    expect(wrap.find('[data-test="announcements-card"]').exists()).toBe(true)
    expect(wrap.text()).toContain('Crab 新版已就绪')
    expect(wrap.text()).toContain('请下载独立 JAR 包运行')
    expect(wrap.find('[data-test="create-notice-btn"]').exists()).toBe(false)
    expect(wrap.find('[data-test="edit-notice-btn"]').exists()).toBe(false)
    wrap.unmount()
  })

  it('管理员发布公告时会拦截空标题/空正文，并能正常提交', async () => {
    reef.createAnnouncement.mockResolvedValue({
      id: 2,
      title: '维护通知',
      body: '今晚更新',
      level: 'INFO',
      pinned: false,
      createdBy: 'admin',
      createdAt: '2026-11-24T00:00:00Z',
      updatedBy: 'admin',
      updatedAt: '2026-11-24T00:00:00Z',
    })
    const wrap = await mountPage(DashboardView, { attach: true })
    await flushPromises()

    await wrap.get('[data-test="create-notice-btn"]').trigger('click')
    await flushPromises()

    // 空表单拦截，不发请求
    ;(document.body.querySelector('[data-test="save-notice-btn"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reef.createAnnouncement).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请填写公告标题')

    const titleInput = document.body.querySelector('[data-test="notice-title-input"] input, input[data-test="notice-title-input"]') as HTMLInputElement
    const bodyInput = document.body.querySelector('[data-test="notice-body-input"] textarea, textarea[data-test="notice-body-input"]') as HTMLTextAreaElement
    titleInput.value = '维护通知'
    titleInput.dispatchEvent(new Event('input'))
    bodyInput.value = '今晚更新'
    bodyInput.dispatchEvent(new Event('input'))
    await flushPromises()

    reef.announcements.mockResolvedValueOnce([
      {
        id: 2,
        title: '维护通知',
        body: '今晚更新',
        level: 'INFO',
        pinned: false,
        createdBy: 'admin',
        createdAt: '2026-11-24T00:00:00Z',
        updatedBy: 'admin',
        updatedAt: '2026-11-24T00:00:00Z',
      },
    ])
    ;(document.body.querySelector('[data-test="save-notice-btn"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(reef.createAnnouncement).toHaveBeenCalledWith({
      title: '维护通知',
      body: '今晚更新',
      level: 'INFO',
      pinned: false,
    })
    wrap.unmount()
  })
})

describe('CrabDownloadView 独立 JAR 下载', () => {
  it('本地 JAR 存在时展示文件元数据、SHA-256、JDK 17 启动命令并支持流式下载', async () => {
    reef.crabDownloadInfo.mockResolvedValue({
      available: true,
      source: 'LOCAL',
      fileName: 'hermit-crab-0.1.0-SNAPSHOT-all.jar',
      sizeBytes: 21 * 1024 * 1024,
      sha256: 'a'.repeat(64),
      updatedAt: '2026-11-24T08:00:00Z',
      latestCrabVersion: '1.12.0',
      downloadUrl: '/api/admin/crab/download',
      externalUrl: null,
    })
    reef.downloadCrabJar.mockResolvedValue(new Blob(['jar']))
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test-jar')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    const wrap = await mountPage(CrabDownloadView)
    await flushPromises()

    expect(wrap.get('[data-test="jar-filename"]').text()).toBe('hermit-crab-0.1.0-SNAPSHOT-all.jar')
    expect(wrap.get('[data-test="jar-sha256"]').text()).toBe('a'.repeat(64))
    expect(wrap.text()).toContain('java -jar hermit-crab-0.1.0-SNAPSHOT-all.jar serve')
    expect(wrap.text()).toContain('$env:HERMIT_HOME=".\\crab-data"; java -jar .\\hermit-crab-0.1.0-SNAPSHOT-all.jar serve')
    expect(wrap.text()).toContain('选择「保留」并核对上方 SHA-256 校验和')
    expect(wrap.text()).toContain('http://127.0.0.1:7777/console/')
    expect(wrap.text()).not.toContain('--port')

    await wrap.get('[data-test="download-jar-btn"]').trigger('click')
    await flushPromises()
    expect(reef.downloadCrabJar).toHaveBeenCalledTimes(1)
    expect(createUrl).toHaveBeenCalledTimes(1)
    expect(clickSpy).toHaveBeenCalledTimes(1)
    wrap.unmount()
  })

  it('未放置本地 JAR 且无外链时管理员看到 downloads/ 放置指引', async () => {
    setRole('ADMIN')
    reef.crabDownloadInfo.mockResolvedValue({
      available: false,
      source: 'NONE',
      fileName: null,
      sizeBytes: null,
      sha256: null,
      updatedAt: null,
      latestCrabVersion: null,
      downloadUrl: null,
      externalUrl: null,
    })
    const wrap = await mountPage(CrabDownloadView)
    await flushPromises()
    expect(wrap.find('[data-test="no-jar-state"]').exists()).toBe(true)
    expect(wrap.text()).toContain('downloads/')
    expect(wrap.text()).toContain('hermit-crab-*-all.jar')
    wrap.unmount()
  })
})

describe('SubmissionsView 规则提交与审批', () => {
  const samplePending = {
    id: 11,
    conch: 'demo_oa',
    version: '1.1.0',
    sha256: 'b'.repeat(64),
    minCrabVersion: '1.0.0',
    driver: 'http',
    title: '协同办公 OA',
    status: 'PENDING' as const,
    submitNote: '新增已办查询',
    submittedBy: 'alice',
    submittedAt: '2026-11-24T08:00:00Z',
    reviewedBy: null,
    reviewedAt: null,
    reviewComment: null,
    warningCount: 1,
  }

  const sampleDetail = {
    ...samplePending,
    warnings: [{ code: 'MISSING_RELATED', path: 'actions.list_done.related', message: '缺少 related' }],
    diff: { addedActions: ['list_done'] },
    currentPublishedVersion: '1.0.0',
    conchDisabled: false,
    versionConflict: false,
  }

  it('管理员可查看详情、加载 YAML、空驳回原因被拦截，确认后可批准并发布', async () => {
    setRole('ADMIN')
    session.user = 'admin'
    reef.submissions.mockResolvedValue([samplePending])
    reef.submission.mockResolvedValue(sampleDetail)
    reef.submissionYaml.mockResolvedValue('conch: demo_oa\nversion: 1.1.0\n')
    reef.approveSubmission.mockResolvedValue({
      ...sampleDetail,
      status: 'APPROVED',
      reviewedBy: 'admin',
      reviewedAt: '2026-11-24T09:00:00Z',
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrap = await mountPage(SubmissionsView, { attach: true })
    await flushPromises()
    expect(wrap.text()).toContain('规则审批')
    expect(wrap.text()).toContain('demo_oa')

    await wrap.get('[data-test="view-submission-btn"]').trigger('click')
    await flushPromises()
    expect(reef.submission).toHaveBeenCalledWith(11)
    expect(document.body.textContent).toContain('list_done')

    // 加载 YAML
    ;(document.body.querySelector('[data-test="toggle-yaml-btn"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reef.submissionYaml).toHaveBeenCalledWith(11)
    expect(document.body.querySelector('[data-test="submission-yaml"]')?.textContent).toContain('conch: demo_oa')

    // 空原因驳回被拦截
    ;(document.body.querySelector('[data-test="reject-btn"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reef.rejectSubmission).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('驳回提交时请填写驳回原因')

    // 批准并发布
    ;(document.body.querySelector('[data-test="approve-btn"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reef.approveSubmission).toHaveBeenCalledWith(11, undefined)
    wrap.unmount()
  })

  it('普通用户看到规则提交标题，能撤回自己待审批的提交且看不到管理员审批按钮', async () => {
    setRole('USER')
    session.user = 'alice'
    reef.submissions.mockResolvedValue([samplePending])
    reef.submission.mockResolvedValue(sampleDetail)
    reef.withdrawSubmission.mockResolvedValue({ ...sampleDetail, status: 'WITHDRAWN' })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrap = await mountPage(SubmissionsView, { attach: true })
    await flushPromises()
    expect(wrap.text()).toContain('规则提交')

    await wrap.get('[data-test="view-submission-btn"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-test="approve-btn"]')).toBeNull()
    expect(document.body.querySelector('[data-test="reject-btn"]')).toBeNull()

    ;(document.body.querySelector('[data-test="withdraw-drawer-btn"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reef.withdrawSubmission).toHaveBeenCalledWith(11)
    wrap.unmount()
  })

  it('提交校验失败 422 时展示结构化错误列表', async () => {
    setRole('USER')
    session.user = 'alice'
    reef.submitConch.mockRejectedValue(
      new ApiError(422, 'INVALID_INPUT', '规则包校验失败', {
        errors: [{ code: 'CONCH_NAME', path: 'conch', message: '规则包名不合法' }],
        warnings: [],
      }),
    )

    const wrap = await mountPage(SubmissionsView, { attach: true })
    await flushPromises()
    await wrap.get('[data-test="open-submit-btn"]').trigger('click')
    await flushPromises()

    // 未选文件直接提交被拦截
    ;(document.body.querySelector('[data-test="confirm-submit-btn"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reef.submitConch).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('请先选择要提交的规则包文件')
    wrap.unmount()
  })
})

describe('GuideView 图文使用教程', () => {
  it('展示 4 个场景切换、内联 SVG 架构图、脱敏截图与 JDK 17 启动命令', async () => {
    const wrap = await mountPage(GuideView)
    await flushPromises()

    expect(wrap.findAll('[data-test="guide-tabs"] button')).toHaveLength(4)
    expect(wrap.find('[data-test="guide-panel-quickstart"] svg').exists()).toBe(true)
    expect(wrap.text()).toContain('java -jar hermit-crab-0.1.0-SNAPSHOT-all.jar serve')

    await wrap.get('[data-test="guide-tab-authoring"]').trigger('click')
    expect(wrap.get('[data-test="guide-panel-authoring"]').isVisible()).toBe(true)
    expect(wrap.text()).toContain('浏览器录制')
    expect(wrap.text()).toContain('批准并签名发布')

    await wrap.get('[data-test="guide-tab-intranet"]').trigger('click')
    expect(wrap.get('[data-test="guide-panel-intranet"]').isVisible()).toBe(true)
    expect(wrap.text()).toContain('auth.origins')
    expect(wrap.text()).toContain('auth.expired_when')
    wrap.unmount()
  })
})
