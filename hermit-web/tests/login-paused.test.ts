import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  shell: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ShellDetailView from '../apps/crab-console/src/views/ShellDetailView.vue'

const detail = {
  shell: 'legacy_oa',
  version: '1.0.0',
  title: '协同办公 OA',
  description: '',
  credentials: [{ name: 'password', description: 'OA 登录密码', required: true }],
  config: [],
  capabilities: [],
  loginPaused: true,
}

function account(overrides: Record<string, unknown>) {
  return {
    name: 'zhangsan',
    default: true,
    needsConfiguration: false,
    missingFields: [],
    credentialsUnreadable: false,
    credentialMessage: null,
    loginPaused: false,
    loginPauseReason: null,
    loginPauseRecovery: null,
    ...overrides,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  crab.shell.mockResolvedValue(detail)
  crab.config.mockResolvedValue({ fields: {} })
})

describe('Crab Shell 详情：账号状态', () => {
  it('自动登录暂停时不显示「可以调用」，给出原因和恢复方法', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [account({ loginPaused: true, loginPauseReason: '连续登录失败已达上限，自动登录已暂停' })],
    })
    const wrapper = await mountPage(ShellDetailView, { props: { shell: 'legacy_oa' } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('配置完整，可以调用')
    expect(text).toContain('自动登录已暂停，修正后才能调用')
    expect(text).toContain('连续登录失败已达上限')
    expect(text).toContain('已暂停')
    expect(text).toContain('测试登录')
    expect(text).not.toContain('/console/api')
  })

  it('凭证无法解密时标出，不显示「已填写」', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [account({ needsConfiguration: true, credentialsUnreadable: true, credentialMessage: '凭证无法解密，需重新配置' })],
    })
    const wrapper = await mountPage(ShellDetailView, { props: { shell: 'legacy_oa' } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('无法解密')
    expect(text).toContain('凭证无法解密，需重新填写：zhangsan')
    expect(text).not.toContain('已填写')
    expect(text).not.toContain('账号缺少凭证')
  })

  it('一切正常时显示可以调用', async () => {
    crab.shell.mockResolvedValue({ ...detail, loginPaused: false })
    crab.accounts.mockResolvedValue({ accounts: [account({})] })
    const wrapper = await mountPage(ShellDetailView, { props: { shell: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.text()).toContain('配置完整，可以调用')
  })
})
