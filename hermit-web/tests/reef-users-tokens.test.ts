import { flushPromises } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { mountPage } from './helpers'
import { session, setRole } from '../apps/reef-admin/src/auth'
import { buildBootstrap, normalizeReefUrl } from '../apps/reef-admin/src/bootstrap'

const reef = vi.hoisted(() => ({
  conches: vi.fn(),
  alerts: vi.fn(),
  ackAlert: vi.fn(),
  tokens: vi.fn(),
  crabs: vi.fn(),
  createToken: vi.fn(),
  revokeToken: vi.fn(),
  voidConfigToken: vi.fn(),
  users: vi.fn(),
  updateUser: vi.fn(),
  resetPassword: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/alerts', () => ({ refreshOpenAlerts: vi.fn() }))

import AlertsView from '../apps/reef-admin/src/views/AlertsView.vue'
import ConchesView from '../apps/reef-admin/src/views/ConchesView.vue'
import TokensView from '../apps/reef-admin/src/views/TokensView.vue'
import UsersView from '../apps/reef-admin/src/views/UsersView.vue'

const issued = {
  token: {
    id: 7,
    owner: 'alice',
    name: '办公室 PC',
    prefix: 'hat_abcdefgh',
    createdAt: '2026-11-04T04:00:00Z',
    lastUsedAt: null,
    revokedAt: null,
    source: 'DATABASE' as const,
  },
  secret: 'hat_abcdefghSECRET',
  publicKey: '-----BEGIN PUBLIC KEY-----\nMCo=\n-----END PUBLIC KEY-----\n',
}

beforeEach(() => {
  vi.resetAllMocks()
  reef.tokens.mockResolvedValue([])
  reef.crabs.mockResolvedValue([])
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('初始化 JSON', () => {
  it('按 Crab 约定的字段生成，地址去掉末尾斜杠', () => {
    const url = normalizeReefUrl(' https://reef.example.com/ ')
    expect(url).toBe('https://reef.example.com')
    expect(buildBootstrap(issued, url!)).toEqual({
      kind: 'hermit-crab-bootstrap',
      version: 1,
      reef: { url: 'https://reef.example.com', token: issued.secret, publicKey: issued.publicKey },
      owner: 'alice',
      tokenName: '办公室 PC',
      createdAt: '2026-11-04T04:00:00Z',
    })
  })

  it('拒绝非 http(s) 地址', () => {
    expect(normalizeReefUrl('ftp://reef')).toBeNull()
    expect(normalizeReefUrl('reef.example.com')).toBeNull()
  })
})

describe('我的令牌', () => {
  it('生成后只在弹窗里显示一次明文，配置令牌不能吊销', async () => {
    setRole('USER')
    reef.tokens.mockResolvedValue([
      { ...issued.token, id: null, name: 'HERMIT_REEF_AGENT_TOKEN', prefix: null, source: 'CONFIG' },
    ])
    reef.createToken.mockResolvedValue(issued)
    const wrapper = await mountPage(TokensView, { attach: true })
    await flushPromises()
    expect(wrapper.text()).toContain('配置令牌')
    expect(wrapper.findAll('button').filter((b) => b.text() === '吊销')).toHaveLength(0)
    expect(wrapper.find('[data-test="show-all"]').exists()).toBe(false)

    await wrapper.get('[data-test="create-token"]').trigger('click')
    await flushPromises()
    const name = document.body.querySelector('input[data-test="token-name"], [data-test="token-name"] input') as HTMLInputElement
    name.value = '办公室 PC'
    name.dispatchEvent(new Event('input'))
    ;(document.body.querySelector('[data-test="submit-token"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(reef.createToken).toHaveBeenCalledWith('办公室 PC')
    expect(document.body.querySelector('[data-test="secret"]')?.textContent).toBe(issued.secret)
    expect(document.body.textContent).toContain('唯一一次')
    wrapper.unmount()
  })
})

describe('普通用户只读', () => {
  it('目录不显示上传，告警不显示确认', async () => {
    setRole('USER')
    reef.conches.mockResolvedValue([])
    const conches = await mountPage(ConchesView)
    await flushPromises()
    expect(conches.text()).not.toContain('上传规则包')
    expect(conches.text()).toContain('等管理员上传')

    reef.alerts.mockResolvedValue({
      alerts: [
        { id: 1, kind: 'FAILURE_RATE', conch: 'legacy_oa', action: 'list', message: 'x', createdAt: '2026-11-04T00:00:00Z', acknowledgedAt: null, acknowledgedBy: null },
      ],
    })
    const alerts = await mountPage(AlertsView)
    await flushPromises()
    expect(alerts.findAll('button').filter((b) => b.text() === '确认')).toHaveLength(0)
    expect(alerts.text()).toContain('待管理员确认')
  })
})

describe('用户管理', () => {
  it('自己那一行不能禁用，最后一个管理员的报错翻成中文', async () => {
    setRole('ADMIN')
    session.user = 'root'
    reef.users.mockResolvedValue([
      { username: 'root', role: 'ADMIN', disabled: false, createdAt: '2026-11-01T00:00:00Z', tokenCount: 1 },
      { username: 'bob', role: 'ADMIN', disabled: false, createdAt: '2026-11-02T00:00:00Z', tokenCount: 0 },
    ])
    reef.updateUser.mockRejectedValue(new ApiError(409, 'CONFLICT', 'at least one enabled administrator is required'))
    const error = vi.spyOn(ElMessage, 'error')
    const { ElMessageBox } = await import('element-plus')
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = await mountPage(UsersView)
    await flushPromises()
    const disable = wrapper.findAll('tr button').filter((b) => b.text() === '禁用')
    expect(disable.map((b) => b.element.closest('tr')?.textContent?.startsWith('bob'))).toEqual([true])

    await disable[0].trigger('click')
    await flushPromises()
    expect(reef.updateUser).toHaveBeenCalledWith('bob', { disabled: true })
    expect(error).toHaveBeenCalledWith('至少要保留一个启用中的管理员')
    confirm.mockRestore()
    error.mockRestore()
  })

  it('禁用确认写明令牌会吊销、启用不恢复，配置令牌归属人点名配置令牌', async () => {
    setRole('ADMIN')
    session.user = 'bob'
    reef.users.mockResolvedValue([
      { username: 'root', role: 'ADMIN', disabled: false, createdAt: '2026-11-01T00:00:00Z', tokenCount: 2 },
      { username: 'bob', role: 'ADMIN', disabled: false, createdAt: '2026-11-02T00:00:00Z', tokenCount: 0 },
    ])
    reef.tokens.mockResolvedValue([
      { ...issued.token, id: null, owner: 'root', name: 'HERMIT_REEF_AGENT_TOKEN', prefix: null, source: 'CONFIG' },
    ])
    reef.updateUser.mockResolvedValue({})
    const { ElMessageBox } = await import('element-plus')
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = await mountPage(UsersView)
    await flushPromises()
    await wrapper.findAll('tr button').filter((b) => b.text() === '禁用')[0].trigger('click')
    await flushPromises()

    const message = String(confirm.mock.calls[0][0])
    expect(message).not.toContain('之后可以重新启用')
    expect(message).toContain('重新启用账号也不会恢复')
    expect(message).toContain('HERMIT_REEF_AGENT_TOKEN')
    expect(reef.tokens).toHaveBeenCalledWith(true)
    expect(reef.updateUser).toHaveBeenCalledWith('root', { disabled: true })
    confirm.mockRestore()
  })

  it('启用成功提示旧令牌不会回来', async () => {
    setRole('ADMIN')
    session.user = 'root'
    reef.users.mockResolvedValue([
      { username: 'root', role: 'ADMIN', disabled: false, createdAt: '2026-11-01T00:00:00Z', tokenCount: 0 },
      { username: 'alice', role: 'USER', disabled: true, createdAt: '2026-11-02T00:00:00Z', tokenCount: 0 },
    ])
    reef.updateUser.mockResolvedValue({})
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = await mountPage(UsersView)
    await flushPromises()
    await wrapper.findAll('tr button').filter((b) => b.text() === '启用')[0].trigger('click')
    await flushPromises()
    expect(reef.updateUser).toHaveBeenCalledWith('alice', { disabled: false })
    expect(String(success.mock.calls[0][0])).toContain('不会恢复')
    success.mockRestore()
  })

  it('锁定用户显示已锁定，重置密码需二次确认并提示同步解除锁定、刷新列表', async () => {
    setRole('ADMIN')
    session.user = 'root'
    reef.users
      .mockResolvedValueOnce([
        { username: 'root', role: 'ADMIN', disabled: false, locked: false, createdAt: '2026-11-01T00:00:00Z', tokenCount: 0 },
        { username: 'alice', role: 'USER', disabled: false, locked: true, createdAt: '2026-11-02T00:00:00Z', tokenCount: 0 },
      ])
      .mockResolvedValueOnce([
        { username: 'root', role: 'ADMIN', disabled: false, locked: false, createdAt: '2026-11-01T00:00:00Z', tokenCount: 0 },
        { username: 'alice', role: 'USER', disabled: false, locked: false, createdAt: '2026-11-02T00:00:00Z', tokenCount: 0 },
      ])
    reef.resetPassword.mockResolvedValue({
      username: 'alice',
      role: 'USER',
      disabled: false,
      locked: false,
      createdAt: '2026-11-02T00:00:00Z',
      tokenCount: 0,
    })
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = await mountPage(UsersView, { attach: true })
    await flushPromises()
    expect(wrapper.text()).toContain('已锁定')

    await wrapper.findAll('tr button').filter((b) => b.text() === '重置密码')[0].trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('同时解除锁定')

    const pwd = document.body.querySelector('input[data-test="reset-password"], [data-test="reset-password"] input') as HTMLInputElement
    const confirm = document.body.querySelector('input[data-test="reset-confirm"], [data-test="reset-confirm"] input') as HTMLInputElement
    const submit = document.body.querySelector('[data-test="submit-reset"]') as HTMLButtonElement

    pwd.value = 'NewPass-1234'
    pwd.dispatchEvent(new Event('input'))
    confirm.value = 'Mismatch-1234'
    confirm.dispatchEvent(new Event('input'))
    await flushPromises()
    submit.click()
    await flushPromises()
    expect(reef.resetPassword).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('两次输入的新密码不一致')

    confirm.value = 'NewPass-1234'
    confirm.dispatchEvent(new Event('input'))
    await flushPromises()
    submit.click()
    await flushPromises()
    expect(reef.resetPassword).toHaveBeenCalledWith('alice', 'NewPass-1234')
    expect(String(success.mock.calls[0][0])).toContain('解除登录锁定')
    expect(reef.users).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('已锁定')
    success.mockRestore()
    wrapper.unmount()
  })

  it('重置已禁用用户密码时提示仍需先启用才能登录', async () => {
    setRole('ADMIN')
    session.user = 'root'
    reef.users.mockResolvedValue([
      { username: 'root', role: 'ADMIN', disabled: false, locked: false, createdAt: '2026-11-01T00:00:00Z', tokenCount: 0 },
      { username: 'alice', role: 'USER', disabled: true, locked: false, createdAt: '2026-11-02T00:00:00Z', tokenCount: 0 },
    ])
    reef.resetPassword.mockResolvedValue({})
    const success = vi.spyOn(ElMessage, 'success')
    const wrapper = await mountPage(UsersView, { attach: true })
    await flushPromises()

    await wrapper.findAll('tr button').filter((b) => b.text() === '重置密码')[0].trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('已禁用')

    const pwd = document.body.querySelector('input[data-test="reset-password"], [data-test="reset-password"] input') as HTMLInputElement
    const confirm = document.body.querySelector('input[data-test="reset-confirm"], [data-test="reset-confirm"] input') as HTMLInputElement
    pwd.value = 'NewPass-1234'
    pwd.dispatchEvent(new Event('input'))
    confirm.value = 'NewPass-1234'
    confirm.dispatchEvent(new Event('input'))
    await flushPromises()
    ;(document.body.querySelector('[data-test="submit-reset"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(reef.resetPassword).toHaveBeenCalledWith('alice', 'NewPass-1234')
    expect(String(success.mock.calls[0][0])).toContain('启用后才能登录')
    success.mockRestore()
    wrapper.unmount()
  })
})

describe('配置令牌作废', () => {
  it('ADMIN 可以作废配置令牌，确认后调用作废接口；普通令牌仍是吊销', async () => {
    setRole('ADMIN')
    reef.tokens.mockResolvedValue([
      { ...issued.token, id: null, owner: 'root', name: 'HERMIT_REEF_AGENT_TOKEN', prefix: null, source: 'CONFIG' },
      issued.token,
    ])
    reef.voidConfigToken.mockResolvedValue({})
    const { ElMessageBox } = await import('element-plus')
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(TokensView)
    await flushPromises()

    expect(wrapper.findAll('button').filter((b) => b.text() === '吊销')).toHaveLength(1)
    await wrapper.get('[data-test="void-config"]').trigger('click')
    await flushPromises()
    expect(String(confirm.mock.calls[0][0])).toContain('HERMIT_REEF_AGENT_TOKEN')
    expect(reef.voidConfigToken).toHaveBeenCalledTimes(1)
    expect(reef.revokeToken).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('已作废的配置令牌显示已作废，没有按钮', async () => {
    setRole('ADMIN')
    reef.tokens.mockResolvedValue([
      {
        ...issued.token,
        id: null,
        owner: 'root',
        name: 'HERMIT_REEF_AGENT_TOKEN',
        prefix: null,
        source: 'CONFIG',
        revokedAt: '2026-11-05T02:00:00Z',
      },
    ])
    const wrapper = await mountPage(TokensView)
    await flushPromises()
    expect(wrapper.text()).toContain('已作废')
    expect(wrapper.find('[data-test="void-config"]').exists()).toBe(false)
  })
})
