import { ApiError } from '@shared/http'
import { flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  identities: vi.fn(),
  conches: vi.fn(),
  authenticators: vi.fn(),
  checkAuthBinding: vi.fn(),
  bindAuthBinding: vi.fn(),
  unbindAuthBinding: vi.fn(),
  testLogin: vi.fn(),
  loginInput: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))

import AccountsPanel from '../apps/crab-console/src/components/AccountsPanel.vue'
import IdentitiesView from '../apps/crab-console/src/views/IdentitiesView.vue'

const sso = {
  conch: 'corp_sso',
  version: '1.2.0',
  title: '公司统一登录',
  description: '',
  source: 'REEF',
  origins: ['https://sso.corp.com'],
  allowHosts: [],
  exports: ['access_token'],
  accounts: [
    { name: 'zhangsan', consumers: [{ origin: 'OFFICIAL', conch: 'corp_oa', account: 'default' }] },
    { name: 'lisi', consumers: [] },
  ],
}

const authUse = { conch: 'corp_sso', minVersion: '1.2.0', installed: true, version: '1.2.0', source: 'REEF' }

function account(extra: Record<string, unknown> = {}) {
  return {
    name: 'default',
    default: true,
    needsConfiguration: false,
    missingFields: [],
    loginPaused: false,
    loginInputRequired: false,
    loginInputExpiresAt: null,
    oauth: null,
    authBinding: null,
    ...extra,
  }
}

const bound = {
  origin: 'OFFICIAL',
  conch: 'corp_sso',
  account: 'zhangsan',
  baseUrl: 'https://oa.corp.com',
  boundAt: '2026-11-26T02:00:00Z',
  loginInputRequired: false,
  loginPaused: false,
}

describe('授权器', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
    crab.identities.mockResolvedValue({ identities: [] })
    crab.conches.mockResolvedValue({ conches: [] })
    crab.authenticators.mockResolvedValue({ authenticators: [sso] })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('「授权与身份」列出授权器、账号和绑到它的规则包', async () => {
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    const card = wrapper.find('[data-test="authenticators"]')
    expect(card.text()).toContain('corp_sso')
    expect(card.text()).toContain('https://sso.corp.com')
    const rows = card.findAll('[data-test="authenticator-account"]')
    expect(rows[0].text()).toContain('corp_oa / default')
    expect(rows[1].text()).toContain('还没有规则包绑定')
  })

  it('没有授权器时不显示这一块；读取失败也不影响身份', async () => {
    crab.authenticators.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    expect(wrapper.find('[data-test="authenticators"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="identity-empty"]').exists()).toBe(true)
  })

  it('业务包：未绑定的账号提示绑定，不出现「填写凭证」', async () => {
    const wrapper = await mountPage(AccountsPanel, {
      props: { conch: 'corp_oa', accounts: [account()], credentials: [], authUse },
      attach: true,
    })
    await flushPromises()
    expect(wrapper.find('[data-test="auth-use-hint"]').text()).toContain('corp_sso')
    expect(wrapper.find('[data-test="auth-unbound"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('填写凭证')
    expect(wrapper.find('[data-test="auth-bind"]').text()).toContain('绑定授权器')
  })

  it('绑定要先检查并确认地址，提交时带上检查时看到的地址', async () => {
    crab.checkAuthBinding.mockResolvedValue({
      allowed: true,
      problem: null,
      authAccount: { origin: 'OFFICIAL', conch: 'corp_sso', account: 'zhangsan' },
      baseUrl: 'https://oa.corp.com',
      warnings: ['授权器是本地包'],
    })
    crab.bindAuthBinding.mockResolvedValue({ allowed: true, problem: null, authAccount: null, baseUrl: 'https://oa.corp.com', warnings: [] })
    crab.authenticators.mockResolvedValue({ authenticators: [{ ...sso, accounts: [{ name: 'zhangsan', consumers: [] }] }] })
    const wrapper = await mountPage(AccountsPanel, {
      props: { conch: 'corp_oa', accounts: [account()], credentials: [], authUse },
      attach: true,
    })
    await flushPromises()
    await wrapper.find('[data-test="auth-bind"]').trigger('click')
    await flushPromises()
    // 只有一个授权器账号时直接选中并检查。
    expect(crab.checkAuthBinding).toHaveBeenCalledWith('corp_oa', 'default', 'zhangsan')
    expect(document.body.querySelector('[data-test="auth-bind-url"]')?.textContent).toContain('https://oa.corp.com')
    expect(document.body.textContent).toContain('授权器是本地包')
    ;(document.body.querySelector('[data-test="auth-bind-confirm"]') as HTMLButtonElement).click()
    await flushPromises()
    expect(crab.bindAuthBinding).toHaveBeenCalledWith('corp_oa', 'default', 'zhangsan', 'https://oa.corp.com')
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('检查不通过时显示原因，不能确认', async () => {
    crab.checkAuthBinding.mockResolvedValue({ allowed: false, problem: '授权器不允许用在这个地址', authAccount: null, baseUrl: null, warnings: [] })
    crab.authenticators.mockResolvedValue({ authenticators: [{ ...sso, accounts: [{ name: 'zhangsan', consumers: [] }] }] })
    const wrapper = await mountPage(AccountsPanel, {
      props: { conch: 'corp_oa', accounts: [account()], credentials: [], authUse },
      attach: true,
    })
    await flushPromises()
    await wrapper.find('[data-test="auth-bind"]').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-test="auth-bind-problem"]')?.textContent).toContain('授权器不允许用在这个地址')
    expect((document.body.querySelector('[data-test="auth-bind-confirm"]') as HTMLButtonElement).disabled).toBe(true)
    expect(crab.bindAuthBinding).not.toHaveBeenCalled()
  })

  it('已绑定的账号显示授权器账号和确认过的地址；授权器在等验证码时指到授权器页面', async () => {
    const wrapper = await mountPage(AccountsPanel, {
      props: {
        conch: 'corp_oa',
        accounts: [account({ authBinding: { ...bound, loginInputRequired: true } })],
        credentials: [],
        authUse,
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-test="auth-binding"]').text()).toContain('corp_sso / zhangsan')
    expect(wrapper.text()).toContain('https://oa.corp.com')
    expect(wrapper.find('[data-test="auth-binding-input"]').text()).toContain('到授权器的账号页输入')
  })

  it('测试登录遇到授权器在等验证码：不在这个包的账号下开验证码框，指到授权器账号', async () => {
    crab.testLogin.mockRejectedValue(new ApiError(409, 'LOGIN_INPUT_REQUIRED', '需要验证码'))
    const wrapper = await mountPage(AccountsPanel, {
      attach: true,
      props: { conch: 'corp_oa', accounts: [account({ authBinding: bound })], credentials: [], authUse },
    })
    await flushPromises()
    await wrapper.find('[data-test="test-login"]').trigger('click')
    await flushPromises()
    expect(crab.loginInput).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('授权器 corp_sso 的账号 zhangsan 在等验证码')
    wrapper.unmount()
  })

  it('解除绑定要确认', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    crab.unbindAuthBinding.mockResolvedValue({ unbound: true })
    const wrapper = await mountPage(AccountsPanel, {
      attach: true,
      props: { conch: 'corp_oa', accounts: [account({ authBinding: bound })], credentials: [], authUse },
    })
    await flushPromises()
    await wrapper.find('[data-test="auth-unbind"]').trigger('click')
    await flushPromises()
    expect(crab.unbindAuthBinding).toHaveBeenCalledWith('corp_oa', 'default')
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('授权器自己的账号页列出绑到它的账号，删除前提示会作废业务包绑定', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = await mountPage(AccountsPanel, {
      attach: true,
      props: {
        conch: 'corp_sso',
        accounts: [account({ name: 'zhangsan', consumers: [{ origin: 'OFFICIAL', conch: 'corp_oa', account: 'default' }] })],
        credentials: [],
        isAuthenticator: true,
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-test="authenticator-hint"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="auth-consumer"]').text()).toContain('corp_oa / default')
    const deleteBtn = wrapper.findAll('button').find((b) => b.text() === '删除')
    expect(deleteBtn).toBeDefined()
    await deleteBtn!.trigger('click')
    await flushPromises()
    expect(String(confirm.mock.calls[0][0])).toContain('corp_oa/default')
    expect(String(confirm.mock.calls[0][0])).toContain('作废')
    wrapper.unmount()
  })

  it('业务包绑定的授权器账号未就绪时显示授权器原因，不说业务包缺少凭证', async () => {
    const wrapper = await mountPage(AccountsPanel, {
      props: {
        conch: 'corp_oa',
        accounts: [
          account({
            needsConfiguration: true,
            authBinding: { ...bound, ready: false, problem: '授权器账号缺少凭证' },
          }),
        ],
        credentials: [],
        authUse,
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-test="auth-binding-unready"]').text()).toContain('无法登录：授权器账号缺少凭证')
    expect(wrapper.text()).not.toContain('无法登录：缺少凭证')
  })

  it('引用授权器的业务包不会出现在共享身份的候选列表里', async () => {
    crab.identities.mockResolvedValue({
      identities: [{ id: 'id_1', name: '张三', createdAt: '2026-11-26T00:00:00Z', members: [], lastCleared: null }],
    })
    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'plain_oa', title: '普通 OA', driver: 'http', source: 'REEF', usesAuthenticator: false },
        { conch: 'sso_oa', title: 'SSO OA', driver: 'http', source: 'REEF', usesAuthenticator: true },
      ],
    })
    const wrapper = await mountPage(IdentitiesView, { attach: true })
    await flushPromises()
    const addBtn = wrapper.findAll('button').find((b) => b.text().includes('添加账号'))
    expect(addBtn).toBeDefined()
    await addBtn!.trigger('click')
    await flushPromises()
    const text = document.body.textContent ?? ''
    expect(text).toContain('plain_oa')
    expect(text).not.toContain('sso_oa')
    wrapper.unmount()
  })

  it('授权器测试登录成功后显示探活通过与已验证的导出变量名，MCP 包不显示测试登录按钮', async () => {
    crab.testLogin.mockResolvedValue({
      ok: true,
      aliveCheckPassed: true,
      exported: ['access_token', 'csrf_token'],
      traceId: 'tr-auth-1',
    })
    const authWrapper = await mountPage(AccountsPanel, {
      attach: true,
      props: {
        conch: 'corp_sso',
        driver: 'auth',
        accounts: [account({ name: 'zhangsan' })],
        credentials: [],
        isAuthenticator: true,
      },
    })
    await flushPromises()
    await authWrapper.get('[data-test="test-login"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('登录与探活通过，会话已缓存')
    const exported = document.body.querySelector('[data-test="test-login-exported"]')?.textContent ?? ''
    expect(exported).toContain('access_token')
    expect(exported).toContain('csrf_token')
    authWrapper.unmount()

    const mcpWrapper = await mountPage(AccountsPanel, {
      props: {
        conch: 'vendor_mcp',
        driver: 'mcp',
        accounts: [account({ name: 'alice' })],
        credentials: [],
      },
    })
    await flushPromises()
    expect(mcpWrapper.find('[data-test="test-login"]').exists()).toBe(false)
    expect(mcpWrapper.text()).not.toContain('自动登录')
    expect(mcpWrapper.find('[data-test="mcp-account-hint"]').exists()).toBe(true)
    mcpWrapper.unmount()
  })
})
