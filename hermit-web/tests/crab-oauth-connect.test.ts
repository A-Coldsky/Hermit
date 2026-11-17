import { flushPromises } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  startOAuth: vi.fn(),
  disconnectOAuth: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))

import AccountsPanel from '../apps/crab-console/src/components/AccountsPanel.vue'
import type { Account } from '../apps/crab-console/src/api'

function account(name: string, oauth: Account['oauth'], extra: Partial<Account> = {}): Account {
  return {
    name,
    default: name === 'alice',
    needsConfiguration: false,
    missingFields: [],
    credentialsUnreadable: false,
    credentialMessage: null,
    loginPaused: false,
    loginPauseReason: null,
    loginPauseRecovery: null,
    loginInputRequired: false,
    loginInputExpiresAt: null,
    oauth,
    ...extra,
  } as Account
}

function mountPanel(accounts: Account[]) {
  return mountPage(AccountsPanel, {
    attach: true,
    props: { conch: 'vendor_crm', accounts, credentials: [] },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('Crab 控制台连接上游', () => {
  it('HTTP 包不显示上游连接列和按钮', async () => {
    const wrapper = await mountPanel([account('alice', null)])
    await flushPromises()
    expect(wrapper.text()).not.toContain('上游连接')
    expect(wrapper.find('[data-test="oauth-connect"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('显示未连接、已连接和到期时间，不显示令牌', async () => {
    const wrapper = await mountPanel([
      account('alice', { connected: false, expiresAt: null }),
      account('bob', { connected: true, expiresAt: '2999-01-01T00:00:00Z' }),
      account('carol', { connected: true, expiresAt: '2000-01-01T00:00:00Z' }),
    ])
    await flushPromises()
    const status = wrapper.findAll('[data-test="oauth-status"]').map((s) => s.text())
    expect(status[0]).toContain('未连接')
    expect(status[1]).toContain('已连接')
    expect(status[1]).toContain('访问令牌到期')
    expect(status[2]).toContain('令牌已过期')
    const buttons = wrapper.findAll('[data-test="oauth-connect"]').map((b) => b.text())
    expect(buttons).toEqual(['连接上游', '重新连接', '重新连接'])
    expect(wrapper.findAll('[data-test="oauth-disconnect"]')).toHaveLength(2)
    expect(wrapper.text()).not.toMatch(/token|Bearer/i)
    wrapper.unmount()
  })

  it('连接上游先开新窗口再跳到授权地址，回来后刷新', async () => {
    const popup = { opener: {} as unknown, location: { href: '' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockReturnValue(popup as unknown as Window)
    crab.startOAuth.mockResolvedValue({ authorizationUrl: 'https://auth.example.com/authorize?state=s' })
    const wrapper = await mountPanel([account('alice', { connected: false, expiresAt: null })])
    await flushPromises()

    await wrapper.get('[data-test="oauth-connect"]').trigger('click')
    await flushPromises()
    expect(window.open).toHaveBeenCalledWith('', '_blank')
    expect(crab.startOAuth).toHaveBeenCalledWith('vendor_crm', 'alice')
    expect(popup.opener).toBeNull()
    expect(popup.location.href).toBe('https://auth.example.com/authorize?state=s')
    expect(document.body.textContent).toContain('已在新窗口打开上游的登录页')

    window.dispatchEvent(new Event('focus'))
    expect(wrapper.emitted('changed')).toHaveLength(1)

    const done = Array.from(document.body.querySelectorAll('button')).find((b) => b.textContent?.includes('我已完成授权'))!
    done.click()
    await flushPromises()
    expect(wrapper.emitted('changed')).toHaveLength(2)
    wrapper.unmount()
  })

  it('开始失败时关掉空窗口并报错', async () => {
    const popup = { opener: {}, location: { href: '' }, close: vi.fn() }
    vi.spyOn(window, 'open').mockReturnValue(popup as unknown as Window)
    crab.startOAuth.mockRejectedValue(new Error('上游主机不在允许列表中。'))
    const wrapper = await mountPanel([account('alice', { connected: false, expiresAt: null })])
    await flushPromises()

    await wrapper.get('[data-test="oauth-connect"]').trigger('click')
    await flushPromises()
    expect(popup.close).toHaveBeenCalled()
    expect(popup.location.href).toBe('')
    expect(document.body.textContent).not.toContain('已在新窗口打开上游的登录页')
    wrapper.unmount()
  })

  it('断开要确认，确认后调用接口并刷新', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    crab.disconnectOAuth.mockResolvedValue({ oauth: { connected: false, expiresAt: null } })
    const wrapper = await mountPanel([account('alice', { connected: true, expiresAt: null })])
    await flushPromises()

    expect(wrapper.get('[data-test="oauth-status"]').text()).toContain('上游没有给出到期时间')
    await wrapper.get('[data-test="oauth-disconnect"]').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalled()
    expect(crab.disconnectOAuth).toHaveBeenCalledWith('vendor_crm', 'alice')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    wrapper.unmount()
  })
})
