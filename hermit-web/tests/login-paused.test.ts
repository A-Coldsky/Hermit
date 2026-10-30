import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conch: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
  credentialNames: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ConchDetailView from '../apps/crab-console/src/views/ConchDetailView.vue'
import CredentialDialog from '../apps/crab-console/src/components/CredentialDialog.vue'
import { deferred } from './helpers'

const detail = {
  conch: 'legacy_oa',
  version: '1.0.0',
  title: '协同办公 OA',
  description: '',
  credentials: [{ name: 'password', description: '填原始密码', required: true }],
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
  crab.conch.mockResolvedValue(detail)
  crab.config.mockResolvedValue({ fields: {} })
})

describe('Crab 规则包详情：账号状态', () => {
  it('自动登录暂停时不显示「可以调用」，给出原因和恢复方法', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [account({ loginPaused: true, loginPauseReason: '连续登录失败已达上限，自动登录已暂停' })],
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('配置完整，可以调用')
    expect(text).toContain('默认账号 zhangsan 已暂停自动登录')
    expect(text).toContain('连续登录失败已达上限')
    expect(text).toContain('已暂停')
    expect(text).toContain('测试登录')
    expect(text).not.toContain('/console/api')
  })

  it('凭证无法解密时标出，不显示「已填写」', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [account({ needsConfiguration: true, credentialsUnreadable: true, credentialMessage: '凭证无法解密，需重新配置' })],
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('无法解密')
    expect(text).toContain('凭证无法解密，需全部重新填写：zhangsan')
    expect(text).not.toContain('已填写')
    expect(text).not.toContain('账号缺少凭证')
  })

  it('一切正常时显示可以调用', async () => {
    crab.conch.mockResolvedValue({ ...detail, loginPaused: false })
    crab.accounts.mockResolvedValue({ accounts: [account({})] })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.text()).toContain('配置完整，可以调用')
  })

  it('只有非默认账号暂停时，不说整个规则包不能调用，并点名账号', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [account({}), account({ name: 'lisi', default: false, loginPaused: true, loginPauseReason: '密码错误' })],
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('默认账号可以调用，部分账号需要处理')
    expect(text).toContain('账号 lisi 已暂停自动登录：密码错误')
    expect(text).not.toContain('还不能调用')
    expect(text).not.toContain('修正凭证')
  })

  it('暂停用警告色，无法解密时自动登录列不写「正常」', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [
        account({ loginPaused: true }),
        account({ name: 'lisi', default: false, needsConfiguration: true, credentialsUnreadable: true }),
      ],
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    const paused = wrapper.findAll('.el-tag').find((t) => t.text() === '已暂停')!
    expect(paused.classes()).toContain('el-tag--warning')
    const rows = wrapper.findAll('.el-table__body tr')
    expect(rows[1].text()).toContain('无法登录：凭证无法解密')
    expect(rows[1].text()).not.toContain('正常')
  })

  it('账号操作列固定在右侧', async () => {
    crab.accounts.mockResolvedValue({ accounts: [account({})] })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.find('.el-table-fixed-column--right').exists()).toBe(true)
  })

  it('账号还在加载时不显示空态和「待配置」横幅', async () => {
    const pending = deferred<{ accounts: unknown[] }>()
    crab.accounts.mockReturnValue(pending.promise)
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('还没有账号')
    expect(wrapper.text()).not.toContain('待配置')

    pending.resolve({ accounts: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('还没有账号，调用会返回「待配置」')
  })
})

describe('Crab 凭证对话框', () => {
  const fields = [{ name: 'password', label: '密码', type: 'secret', description: '填原始密码', required: true }]

  it('凭证无法解密时要求全部重填，不提示留空保持不变', async () => {
    crab.credentialNames.mockResolvedValue({ fields: ['password'] })
    const wrapper = await mountPage(CredentialDialog, {
      props: { open: false, conch: 'legacy_oa', account: 'zhangsan', fields, unreadable: true },
      attach: true,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()

    const text = document.body.textContent ?? ''
    expect(text).toContain('需要全部重新填写')
    expect(text).toContain('需重填')
    expect(text).not.toContain('留空表示保持不变')
    expect(document.body.querySelector('input[placeholder="已保存，留空保持不变"]')).toBeNull()
    wrapper.unmount()
  })

  it('凭证正常时已保存字段可以留空', async () => {
    crab.credentialNames.mockResolvedValue({ fields: ['password'] })
    const wrapper = await mountPage(CredentialDialog, {
      props: { open: false, conch: 'legacy_oa', account: 'zhangsan', fields },
      attach: true,
    })
    await wrapper.setProps({ open: true })
    await flushPromises()

    expect(document.body.querySelector('input[placeholder="已保存，留空保持不变"]')).not.toBeNull()
    wrapper.unmount()
  })
})
