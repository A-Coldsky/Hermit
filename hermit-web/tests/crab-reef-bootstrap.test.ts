import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  reef: vi.fn(),
  importReefBootstrap: vi.fn(),
  reportPreview: vi.fn(),
  reportSettings: vi.fn(),
  updateReportSettings: vi.fn(),
  sendReport: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ReefView from '../apps/crab-console/src/views/ReefView.vue'
import ReportsView from '../apps/crab-console/src/views/ReportsView.vue'

const instanceId = '0f8fad5b-d9cb-469f-a165-70867728950e'
const notConnected = {
  configured: false,
  source: null,
  url: null,
  owner: null,
  tokenName: null,
  tokenPrefix: null,
  publicKeyFingerprint: null,
  instanceId,
  importedAt: null,
}
const bootstrap = {
  kind: 'hermit-crab-bootstrap',
  version: 1,
  reef: { url: 'https://reef.example.com', token: 'hat_secret', publicKey: '-----BEGIN PUBLIC KEY-----\nMCo=\n-----END PUBLIC KEY-----\n' },
  owner: 'alice',
  tokenName: '办公室 PC',
  createdAt: '2026-11-04T04:00:00Z',
}

async function paste(wrapper: Awaited<ReturnType<typeof mountPage>>, value: string) {
  await wrapper.get('textarea[data-test="bootstrap-text"], [data-test="bootstrap-text"] textarea').setValue(value)
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  crab.reef.mockResolvedValue(notConnected)
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('连接 Reef', () => {
  it('未连接时提示导入，非初始化 JSON 不能提交', async () => {
    const wrapper = await mountPage(ReefView)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有连接 Reef')
    expect(wrapper.text()).toContain(instanceId)

    await paste(wrapper, '{"hello":1}')
    expect(wrapper.get('[data-test="bootstrap-error"]').text()).toContain('不是 Reef')
    expect(wrapper.get('[data-test="bootstrap-submit"]').attributes('disabled')).toBeDefined()

    await paste(wrapper, '{oops')
    expect(wrapper.get('[data-test="bootstrap-error"]').text()).toContain('不是有效的 JSON')
  })

  it('整份提交解析后的 JSON，成功后显示连接信息且不显示明文令牌', async () => {
    crab.importReefBootstrap.mockResolvedValue({
      configured: true,
      source: 'BOOTSTRAP',
      url: 'https://reef.example.com',
      owner: 'alice',
      tokenName: '办公室 PC',
      tokenPrefix: 'hat_secret',
      publicKeyFingerprint: 'ab'.repeat(32),
      instanceId,
      importedAt: '2026-11-04T05:00:00Z',
    })
    const wrapper = await mountPage(ReefView)
    await flushPromises()
    await paste(wrapper, JSON.stringify(bootstrap))
    expect(wrapper.get('[data-test="bootstrap-preview"]').text()).toContain('alice')

    await wrapper.get('[data-test="bootstrap-submit"]').trigger('click')
    await flushPromises()
    expect(crab.importReefBootstrap).toHaveBeenCalledWith(bootstrap)
    const connection = wrapper.get('[data-test="connection"]').text()
    expect(connection).toContain('https://reef.example.com')
    expect(connection).toContain('办公室 PC')
    expect(wrapper.text()).toContain('初始化 JSON 导入')
  })

  it('已连接时先确认替换，令牌被拒时给出中文原因', async () => {
    crab.reef.mockResolvedValue({ ...notConnected, configured: true, source: 'CONFIG', url: 'http://old' })
    crab.importReefBootstrap.mockRejectedValue(new ApiError(400, 'INVALID_INPUT', 'reef rejected the token'))
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const error = vi.spyOn(ElMessage, 'error')
    const wrapper = await mountPage(ReefView)
    await flushPromises()
    await paste(wrapper, JSON.stringify(bootstrap))
    await wrapper.get('[data-test="bootstrap-submit"]').trigger('click')
    await flushPromises()

    expect(confirm.mock.calls[0][0]).toContain('config.yml')
    const message = (error.mock.calls[0][0] as { message: string }).message
    expect(message).toContain('Reef 拒绝了这把令牌')
    confirm.mockRestore()
    error.mockRestore()
  })
})

describe('上报设置', () => {
  it('关闭上报后保存，并说明关闭状态', async () => {
    crab.reportPreview.mockRejectedValue(new ApiError(409, 'NOT_CONFIGURED', 'reporting is disabled'))
    crab.reportSettings.mockResolvedValue({ enabled: true, intervalSeconds: 300 })
    crab.updateReportSettings.mockResolvedValue({ enabled: false, intervalSeconds: 300 })
    const wrapper = await mountPage(ReportsView)
    await flushPromises()
    expect(wrapper.get('[data-test="report-save"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-test="report-enabled"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-test="report-save"]').trigger('click')
    await flushPromises()

    expect(crab.updateReportSettings).toHaveBeenCalledWith({ enabled: false, intervalSeconds: 300 })
    expect(wrapper.text()).toContain('上报已关闭')
  })
})
