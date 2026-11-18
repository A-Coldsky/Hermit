import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conch: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
  debug: vi.fn(),
  loginInput: vi.fn(),
  loginInputImage: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ConchDetailView from '../apps/crab-console/src/views/ConchDetailView.vue'
import type { Account, ConchDetail } from '../apps/crab-console/src/api'

function detail(over: Partial<ConchDetail> = {}): ConchDetail {
  return {
    conch: 'legacy_oa',
    version: '1.0.0',
    title: '协同办公 OA',
    description: '待办和审批',
    credentials: [{ name: 'password', type: 'secret', label: '密码', required: true, description: '填原始密码', options: null }],
    config: [],
    capabilities: [
      { action: 'list_todo', title: '待办列表', description: '', method: 'GET', readOnly: true, disabled: false },
      { action: 'approve', title: '审批', description: '', method: 'POST', readOnly: false, disabled: false },
    ],
    subscribed: true,
    needsConfiguration: false,
    loginPaused: false,
    source: 'REEF',
    driver: 'http',
    reefDisabled: false,
    reefDisabledAt: null,
    updateAvailable: false,
    latestVersion: null,
    newRequiredCredentials: [],
    ...over,
  }
}

function account(over: Partial<Account> = {}): Account {
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
    loginInputRequired: false,
    loginInputExpiresAt: null,
    oauth: null,
    ...over,
  }
}

async function mountDetail(path = '/', conch = 'legacy_oa', attach = false) {
  const wrapper = await mountPage(ConchDetailView, { props: { conch }, path, attach })
  await flushPromises()
  return wrapper
}

function tabLabels(wrapper: Awaited<ReturnType<typeof mountDetail>>): string[] {
  return wrapper.findAll('[role="tab"]').map((tab) => tab.text().replace(/\s+/g, ''))
}

function activeTab(wrapper: Awaited<ReturnType<typeof mountDetail>>): string {
  return wrapper.get('[role="tab"][aria-selected="true"]').text().replace(/\s+/g, '')
}

function queryTab(wrapper: Awaited<ReturnType<typeof mountDetail>>): unknown {
  return wrapper.vm.$route.query.tab
}

/** 沿着父节点看有没有被 v-show 收起来。传送到 body 的弹窗不该被标签页挡住。 */
function isShown(el: Element | null): boolean {
  let node = el instanceof HTMLElement ? el : null
  while (node) {
    if (getComputedStyle(node).display === 'none') return false
    node = node.parentElement
  }
  return el !== null
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.conch.mockResolvedValue(detail())
  crab.accounts.mockResolvedValue({ accounts: [account()] })
  crab.config.mockResolvedValue({ fields: {} })
  crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
  crab.loginInput.mockResolvedValue({
    kind: 'code',
    label: '短信验证码',
    hint: '输入短信里的 6 位数字',
    expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    hasImage: false,
    canResend: false,
    resendAfter: 0,
  })
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('Crab 规则包详情标签', () => {
  it('默认打开能力，数量写在标签上，并同步到地址', async () => {
    const wrapper = await mountDetail()

    expect(tabLabels(wrapper)).toEqual(['能力（2）', '账号', '配置'])
    expect(activeTab(wrapper)).toBe('能力（2）')
    expect(wrapper.find('[data-test="accounts-dot"]').exists()).toBe(false)
    expect(queryTab(wrapper)).toBe('capabilities')
    expect(wrapper.get('#pane-capabilities').attributes('aria-hidden')).toBe('false')
    expect(wrapper.text()).toContain('list_todo')
    wrapper.unmount()
  })

  it('MCP 包把第一个标签写成工具，地址仍是 capabilities', async () => {
    crab.conch.mockResolvedValue(detail({
      driver: 'mcp',
      capabilities: [{ action: 'search', title: '搜索', description: '', method: 'POST', readOnly: true, disabled: false }],
    }))
    const wrapper = await mountDetail()

    expect(tabLabels(wrapper)[0]).toBe('工具（1）')
    expect(activeTab(wrapper)).toBe('工具（1）')
    expect(queryTab(wrapper)).toBe('capabilities')
    wrapper.unmount()
  })

  it('本地包在配置后面加开发者', async () => {
    crab.conch.mockResolvedValue(detail({ source: 'LOCAL', subscribed: false, credentials: [] }))
    crab.accounts.mockResolvedValue({ accounts: [account()] })
    const wrapper = await mountDetail()

    expect(tabLabels(wrapper)).toEqual(['能力（2）', '账号', '配置', '开发者'])
    expect(activeTab(wrapper)).toBe('能力（2）')
    expect(wrapper.find('[data-test="developer-hint"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('声明的必填凭证还没填时默认打开账号，并在标签上显示红点', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [account({ needsConfiguration: true, missingFields: ['password'], missingLabels: ['密码（password）'] })],
    })
    const wrapper = await mountDetail()

    expect(activeTab(wrapper)).toBe('账号')
    expect(wrapper.find('[data-test="accounts-dot"]').exists()).toBe(true)
    expect(queryTab(wrapper)).toBe('accounts')
    expect(wrapper.text()).toContain('账号缺少凭证')
    const alert = wrapper.get('.el-alert').element
    const tabs = wrapper.get('[data-test="detail-tabs"]').element
    expect(alert.compareDocumentPosition(tabs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    wrapper.unmount()
  })

  it('有必填凭证但还没有账号时也打开账号', async () => {
    crab.accounts.mockResolvedValue({ accounts: [] })
    const wrapper = await mountDetail()

    expect(activeTab(wrapper)).toBe('账号')
    expect(wrapper.find('[data-test="accounts-dot"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('还没有账号')
    wrapper.unmount()
  })

  it('只缺可选凭证、或凭证无法解密时不显示红点，仍从能力开始', async () => {
    crab.conch.mockResolvedValue(detail({
      credentials: [{ name: 'note', type: 'string', label: '备注', required: false, description: null, options: null }],
    }))
    crab.accounts.mockResolvedValue({ accounts: [] })
    const optional = await mountDetail()
    expect(activeTab(optional)).toBe('能力（2）')
    expect(optional.find('[data-test="accounts-dot"]').exists()).toBe(false)
    optional.unmount()

    crab.conch.mockResolvedValue(detail())
    crab.accounts.mockResolvedValue({
      accounts: [account({ needsConfiguration: true, credentialsUnreadable: true, credentialMessage: '凭证无法解密，需重新配置' })],
    })
    const unreadable = await mountDetail()
    expect(activeTab(unreadable)).toBe('能力（2）')
    expect(unreadable.find('[data-test="accounts-dot"]').exists()).toBe(false)
    expect(unreadable.text()).toContain('凭证无法解密')
    unreadable.unmount()
  })

  it('等验证码或自动登录暂停时默认打开账号并显示红点', async () => {
    crab.accounts.mockResolvedValue({ accounts: [account({ loginInputRequired: true })] })
    const waiting = await mountDetail()
    expect(activeTab(waiting)).toBe('账号')
    expect(waiting.find('[data-test="accounts-dot"]').exists()).toBe(true)
    expect(queryTab(waiting)).toBe('accounts')
    waiting.unmount()

    crab.accounts.mockResolvedValue({
      accounts: [account({ loginPaused: true, loginPauseReason: '连续登录失败已达上限，自动登录已暂停' })],
    })
    const paused = await mountDetail()
    expect(activeTab(paused)).toBe('账号')
    expect(paused.find('[data-test="accounts-dot"]').exists()).toBe(true)
    expect(paused.text()).toContain('已暂停自动登录')
    const alert = paused.get('.el-alert').element
    const tabs = paused.get('[data-test="detail-tabs"]').element
    expect(alert.compareDocumentPosition(tabs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    paused.unmount()
  })

  it('只缺配置时仍从能力开始，不给账号红点', async () => {
    crab.config.mockResolvedValue({
      fields: {
        base_url: {
          type: 'string',
          label: '地址',
          required: true,
          description: null,
          options: null,
          value: null,
          defaultValue: null,
          missing: true,
        },
      },
    })
    const wrapper = await mountDetail()

    expect(activeTab(wrapper)).toBe('能力（2）')
    expect(wrapper.find('[data-test="accounts-dot"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('缺少必填配置')
    wrapper.unmount()
  })

  it('地址里的标签刷新后还在，点别的标签会改地址，无效值回到默认', async () => {
    const opened = await mountDetail('/?from=list&tab=config')
    expect(activeTab(opened)).toBe('配置')
    expect(opened.vm.$route.query.from).toBe('list')
    expect(queryTab(opened)).toBe('config')

    await opened.get('#tab-accounts').trigger('click')
    await flushPromises()
    expect(activeTab(opened)).toBe('账号')
    expect(queryTab(opened)).toBe('accounts')
    expect(opened.vm.$route.query.from).toBe('list')
    opened.unmount()

    crab.accounts.mockResolvedValue({
      accounts: [account({ loginPaused: true })],
    })
    const kept = await mountDetail('/?tab=capabilities')
    expect(activeTab(kept)).toBe('能力（2）')
    expect(kept.find('[data-test="accounts-dot"]').exists()).toBe(true)
    expect(queryTab(kept)).toBe('capabilities')
    kept.unmount()

    const invalid = await mountDetail('/?tab=versions')
    expect(activeTab(invalid)).toBe('账号')
    expect(queryTab(invalid)).toBe('accounts')
    invalid.unmount()

    crab.accounts.mockResolvedValue({ accounts: [account()] })
    const fallback = await mountDetail('/?tab=developer')
    expect(activeTab(fallback)).toBe('能力（2）')
    expect(queryTab(fallback)).toBe('capabilities')
    fallback.unmount()
  })

  it('人在能力标签时，验证码弹窗仍然弹出', async () => {
    crab.accounts.mockResolvedValue({
      accounts: [account({ loginInputRequired: true, loginInputExpiresAt: '2026-11-17T08:05:00Z' })],
    })
    const wrapper = await mountDetail('/?tab=capabilities', 'legacy_oa', true)

    expect(activeTab(wrapper)).toBe('能力（2）')
    expect(wrapper.get('#pane-accounts').attributes('aria-hidden')).toBe('true')
    expect(getComputedStyle(wrapper.get('#pane-accounts').element).display).toBe('none')
    expect(wrapper.find('[data-test="accounts-dot"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="login-input-required"]').exists()).toBe(true)
    const dialog = document.body.querySelector('[data-test="login-input"]')
    expect(dialog?.textContent).toContain('短信验证码')
    expect(wrapper.get('#pane-accounts').element.contains(dialog)).toBe(false)
    expect(isShown(dialog)).toBe(true)
    expect(crab.loginInput).toHaveBeenCalledWith('legacy_oa', 'zhangsan')
    expect(queryTab(wrapper)).toBe('capabilities')
    wrapper.unmount()
  })
})
