import { flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  recorderStatus: vi.fn(),
  recorderStart: vi.fn(),
  recorderStop: vi.fn(),
  recorderEntries: vi.fn(),
  recorderEntry: vi.fn(),
  recorderImportHar: vi.fn(),
  recorderClearSession: vi.fn(),
  recorderSnapshots: vi.fn(),
  recorderCreateSnapshot: vi.fn(),
  recorderSnapshot: vi.fn(),
  recorderDownloadHar: vi.fn(),
  recorderDeleteSnapshot: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))

import RecorderView from '../apps/crab-console/src/views/RecorderView.vue'

function statusBase(over: Record<string, unknown> = {}) {
  return {
    devMode: true,
    state: 'IDLE',
    sessionId: null,
    mode: null,
    source: null,
    startUrl: null,
    startedAt: null,
    stoppedAt: null,
    totalEntries: 0,
    businessEntries: 0,
    browserConnected: false,
    disconnectReason: null,
    defaultRemoteDebuggingDetected: false,
    browsers: [
      { id: 'chrome', name: 'Google Chrome', path: '/usr/bin/google-chrome' },
    ],
    snapshots: [],
    ...over,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.recorderStatus.mockResolvedValue(statusBase())
  crab.recorderEntries.mockResolvedValue({ entries: [] })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Crab 浏览器录制页面', () => {
  it('开发者模式关闭时展示提示并隐藏录制控制区', async () => {
    crab.recorderStatus.mockResolvedValue(statusBase({ devMode: false }))
    const wrapper = await mountPage(RecorderView)
    await flushPromises()

    expect(wrapper.get('[data-test="recorder-dev-closed"]').text()).toContain('开发者模式未开启')
    expect(wrapper.find('[data-test="recorder-controls"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('开启开发者模式后可以启动独立 Chrome 录制、停止、生成脱敏快照并删除', async () => {
    const sampleEntries = [
      {
        id: 'e1',
        index: 1,
        startedAt: '2026-11-24T12:00:00Z',
        durationMs: 25,
        method: 'POST',
        url: 'https://sso.corp.local/login',
        origin: 'https://sso.corp.local',
        path: '/login',
        resourceType: 'Document',
        staticAsset: false,
        status: 302,
        statusText: 'Found',
        mimeType: 'text/html',
        redirectTo: 'https://oa.corp.local/api/todos',
        binary: false,
        contentDisposition: null,
        responseBytes: 0,
        errorText: null,
        hasRequestBody: true,
        hasResponseBody: false,
      },
      {
        id: 'e2',
        index: 2,
        startedAt: '2026-11-24T12:00:01Z',
        durationMs: 14,
        method: 'GET',
        url: 'https://oa.corp.local/api/todos',
        origin: 'https://oa.corp.local',
        path: '/api/todos',
        resourceType: 'XHR',
        staticAsset: false,
        status: 200,
        statusText: 'OK',
        mimeType: 'application/json',
        redirectTo: null,
        binary: false,
        contentDisposition: null,
        responseBytes: 120,
        errorText: null,
        hasRequestBody: false,
        hasResponseBody: true,
      },
    ]

    crab.recorderStart.mockResolvedValue(
      statusBase({
        state: 'RECORDING',
        sessionId: 'rec_11223344',
        mode: 'launch',
        source: 'chrome_launch',
        browserConnected: true,
      }),
    )
    crab.recorderStop.mockResolvedValue(
      statusBase({
        state: 'STOPPED',
        sessionId: 'rec_11223344',
        mode: 'launch',
        source: 'chrome_launch',
        totalEntries: 3,
        businessEntries: 2,
      }),
    )
    crab.recorderEntries.mockResolvedValue({ entries: sampleEntries })

    const createdSnap = {
      id: 'cap_a1b2c3d4',
      title: 'OA 待办',
      createdAt: '2026-11-24T12:01:00Z',
      source: 'chrome_launch',
      origins: ['https://sso.corp.local', 'https://oa.corp.local'],
      cookieNames: ['OA_SESSION'],
      hasRedirects: true,
      hasDownloads: false,
      entryCount: 2,
      entries: sampleEntries,
    }
    crab.recorderCreateSnapshot.mockResolvedValue(createdSnap)

    const wrapper = await mountPage(RecorderView)
    await flushPromises()

    await wrapper.get('[data-test="start-url"]').setValue('https://sso.corp.local/login')
    await wrapper.get('[data-test="start-recording"]').trigger('click')
    await flushPromises()

    expect(crab.recorderStart).toHaveBeenCalledWith({
      mode: 'launch',
      startUrl: 'https://sso.corp.local/login',
      browserPath: '/usr/bin/google-chrome',
      debugEndpoint: undefined,
    })
    expect(wrapper.get('[data-test="recording-badge"]').text()).toContain('录制中')

    await wrapper.get('[data-test="stop-recording"]').trigger('click')
    await flushPromises()

    expect(crab.recorderStop).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('/api/todos')

    // Fill snapshot title & extraSecrets and confirm snapshot creation.
    crab.recorderStatus.mockResolvedValue(statusBase({ state: 'STOPPED', snapshots: [createdSnap] }))
    await wrapper.get('[data-test="snapshot-title"]').setValue('OA 待办')
    await wrapper.get('[data-test="extra-secrets"]').setValue('MySecretPass1!\n13800138000')
    await wrapper.get('[data-test="create-snapshot"]').trigger('click')
    await flushPromises()

    expect(crab.recorderCreateSnapshot).toHaveBeenCalledWith({
      title: 'OA 待办',
      entryIds: ['e1', 'e2'],
      extraSecrets: ['MySecretPass1!', '13800138000'],
    })

    const snapCard = wrapper.get('[data-test="snapshot-cap_a1b2c3d4"]')
    expect(snapCard.text()).toContain('cap_a1b2c3d4')
    expect(snapCard.text()).toContain('OA_SESSION')
    expect(snapCard.text()).toContain('https://sso.corp.local')

    // Copy AI prompt button works without error.
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    await wrapper.get('[data-test="copy-ai-cap_a1b2c3d4"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledTimes(1)
    const copiedPrompt = String(writeText.mock.calls[0][0])
    expect(copiedPrompt).toContain('cap_a1b2c3d4')
    expect(copiedPrompt).toContain('conch_capture_list')
    expect(copiedPrompt).toContain('conch_capture_get')

    // Delete snapshot asks for confirmation and calls API.
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    crab.recorderDeleteSnapshot.mockResolvedValue({ id: 'cap_a1b2c3d4', deleted: true })
    crab.recorderStatus.mockResolvedValue(statusBase({ state: 'STOPPED', snapshots: [] }))
    await wrapper.get('[data-test="delete-snap-cap_a1b2c3d4"]').trigger('click')
    await flushPromises()
    expect(crab.recorderDeleteSnapshot).toHaveBeenCalledWith('cap_a1b2c3d4')

    wrapper.unmount()
  })

  it('未检测到本机浏览器时提示为可选项、允许导入 HAR，填入自定义路径后可启用录制', async () => {
    crab.recorderStatus.mockResolvedValue(statusBase({ browsers: [] }))
    const wrapper = await mountPage(RecorderView)
    await flushPromises()

    expect(wrapper.get('[data-test="no-browser-notice"]').text()).toContain('浏览器录制为可选项')
    expect(wrapper.get('[data-test="start-recording"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="import-har"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-test="custom-browser-path"]').setValue('/opt/chrome/chrome')
    await flushPromises()
    expect(wrapper.get('[data-test="start-recording"]').attributes('disabled')).toBeUndefined()

    wrapper.unmount()
  })
})
