import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@shared/http'
import { mountPage } from './helpers'

const reef = vi.hoisted(() => ({ login: vi.fn() }))
const crab = vi.hoisted(() => ({
  shells: vi.fn(),
  calls: vi.fn(),
  call: vi.fn(),
  debug: vi.fn(),
  syncStatus: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/auth', () => ({ signIn: vi.fn() }))
vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import LoginView from '../apps/reef-admin/src/views/LoginView.vue'
import OverviewView from '../apps/crab-console/src/views/OverviewView.vue'
import CallsView from '../apps/crab-console/src/views/CallsView.vue'

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('Crab 调试开关', () => {
  it('写明不影响调用记录，不再自称调试模式', async () => {
    crab.shells.mockResolvedValue({ shells: [] })
    crab.calls.mockResolvedValue({ calls: [] })
    crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
    crab.syncStatus.mockResolvedValue({ report: null, latestCrab: null })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()

    expect(wrapper.text()).toContain('调试开关')
    expect(wrapper.text()).toContain('未接入调用记录')
    expect(wrapper.text()).toContain('不影响调用记录')
    expect(wrapper.text()).not.toContain('调试模式')
    expect(wrapper.text()).not.toContain('调试日志')
  })
})

describe('Reef 登录失败', () => {
  it('显示中文提示，不带错误码', async () => {
    reef.login.mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'authentication failed'))
    const wrapper = await mountPage(LoginView)
    const [username, password] = wrapper.findAll('input')
    await username.setValue('admin')
    await password.setValue('wrong')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('用户名或密码错误')
    expect(wrapper.text()).not.toContain('UNAUTHORIZED')
    expect(wrapper.text()).not.toContain('authentication failed')
  })
})

describe('Crab 调用详情标题', () => {
  function detail(errorCode: string) {
    return {
      traceId: 't-1',
      at: '2026-10-15T08:00:00Z',
      origin: 'API',
      shell: 'legacy_oa',
      action: 'list_todo',
      account: 'default',
      shellVersion: '1.0.0',
      success: false,
      errorCode,
      errorMessage: '登录失败',
      durationMs: 12,
      apiKeyId: null,
      steps: [],
    }
  }

  async function openDetail(errorCode: string) {
    crab.calls.mockResolvedValue({ calls: [] })
    crab.shells.mockResolvedValue({ shells: [] })
    crab.call.mockResolvedValue(detail(errorCode))
    const wrapper = await mountPage(CallsView, { attach: true, path: '/calls?trace=t-1' })
    await flushPromises()
    return wrapper
  }

  it('认证失败只显示中文说明', async () => {
    const wrapper = await openDetail('AUTH_FAILED')
    const title = document.body.querySelector('.el-drawer .el-alert__title')?.textContent ?? ''
    expect(title).toBe('认证失败')
    wrapper.unmount()
  })

  it('其他错误仍附带错误码，便于对照日志', async () => {
    const wrapper = await openDetail('TIMEOUT')
    const title = document.body.querySelector('.el-drawer .el-alert__title')?.textContent ?? ''
    expect(title).toBe('超时（TIMEOUT）')
    wrapper.unmount()
  })
})
