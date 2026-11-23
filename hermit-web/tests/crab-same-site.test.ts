import { flushPromises } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  conch: vi.fn(),
  accounts: vi.fn(),
  config: vi.fn(),
  writeConfig: vi.fn(),
  calls: vi.fn(),
  debug: vi.fn(),
  syncStatus: vi.fn(),
  apiKeys: vi.fn(),
  setConchEnabled: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ConfigPanel from '../apps/crab-console/src/components/ConfigPanel.vue'
import ConchDetailView from '../apps/crab-console/src/views/ConchDetailView.vue'
import OverviewView from '../apps/crab-console/src/views/OverviewView.vue'

function detail(over: Record<string, unknown> = {}) {
  return {
    conch: 'oa_leave',
    version: '1.0.0',
    title: '请假',
    description: null,
    credentials: [],
    config: [],
    capabilities: [{ action: 'list', title: '列表', description: '', method: 'GET', readOnly: true, disabled: false }],
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
    switchedOff: false,
    site: 'https://oa.corp.com:443',
    sameSite: [],
    contendingWith: [],
    ...over,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.conches.mockResolvedValue({ conches: [], contention: [] })
  crab.conch.mockResolvedValue(detail())
  crab.accounts.mockResolvedValue({ accounts: [] })
  crab.config.mockResolvedValue({ fields: {} })
  crab.calls.mockResolvedValue({ calls: [] })
  crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
  crab.syncStatus.mockResolvedValue({ report: null, latestCrab: null })
  crab.apiKeys.mockResolvedValue({ apiKeys: [] })
})

afterEach(() => {
  document.body.innerHTML = ''
})

async function mountDetail() {
  const wrapper = await mountPage(ConchDetailView, { props: { conch: 'oa_leave' } })
  await flushPromises()
  return wrapper
}

describe('同网站的规则包', () => {
  it('没有同网站的包时详情页不出提示', async () => {
    const wrapper = await mountDetail()
    expect(wrapper.find('[data-test="same-site"]').exists()).toBe(false)
  })

  it('详情页列出同网站的包并说明各自登录', async () => {
    crab.conch.mockResolvedValue(detail({ sameSite: ['oa_base', 'oa_hr'] }))
    const wrapper = await mountDetail()
    const notice = wrapper.get('[data-test="same-site"]')
    expect(notice.text()).toContain('本机还有规则包访问同一个网站')
    expect(notice.text()).toContain('https://oa.corp.com:443')
    expect(notice.text()).toContain('各自登录')
    expect(notice.text()).not.toContain('轮流被对方的登录顶掉')
    const links = notice.findAll('[data-test="same-site-link"]')
    expect(links.map((link) => link.text())).toEqual(['oa_base', 'oa_hr'])
    expect(links[0].attributes('href')).toBe('/conches/oa_base')
  })

  it('交替重新登录告警只点名相关的包', async () => {
    crab.conch.mockResolvedValue(detail({ sameSite: ['oa_base', 'oa_hr'], contendingWith: ['oa_base'] }))
    const wrapper = await mountDetail()
    const notice = wrapper.get('[data-test="same-site"]')
    expect(notice.text()).toContain('可能在和同网站的规则包互相顶掉登录')
    expect(notice.text()).toContain('最近 oa_base 和这个包轮流被对方的登录顶掉')
    expect(notice.text()).toContain('使用不同的网站账号')
  })

  it('重新启用后当场提示同网站的包，停用不提示', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    crab.conch.mockResolvedValue(detail({ switchedOff: true, site: null }))
    crab.setConchEnabled.mockResolvedValue({ conch: 'oa_leave', switchedOff: false, sameSite: ['oa_base'] })
    const wrapper = await mountDetail()
    await wrapper.get('[data-test="conch-enable"]').trigger('click')
    await flushPromises()
    expect(crab.setConchEnabled).toHaveBeenCalledWith('oa_leave', true)
    expect(warning).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(warning.mock.calls[0][0])).toContain('oa_base')

    warning.mockClear()
    crab.setConchEnabled.mockResolvedValue({ conch: 'oa_leave', switchedOff: false, sameSite: [] })
    await wrapper.get('[data-test="conch-enable"]').trigger('click')
    await flushPromises()
    expect(warning).not.toHaveBeenCalled()
  })

  it('保存 base_url 后提示，保存别的配置不提示', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    const field = (label: string) => ({
      type: 'string', label, required: false, description: null, options: null, value: null, defaultValue: null, missing: false,
    })
    crab.writeConfig.mockResolvedValue({ updated: true, sameSite: ['oa_base'] })
    const wrapper = await mountPage(ConfigPanel, {
      props: { conch: 'oa_leave', fields: { base_url: field('地址'), dept: field('部门') } },
    })
    await flushPromises()
    const inputs = wrapper.findAll('input')
    await inputs[1].setValue('研发')
    await wrapper.findAll('button').find((b) => b.text().includes('保存'))!.trigger('click')
    await flushPromises()
    expect(crab.writeConfig).toHaveBeenLastCalledWith('oa_leave', { dept: '研发' })
    expect(warning).not.toHaveBeenCalled()

    await inputs[0].setValue('https://oa.corp.com')
    await wrapper.findAll('button').find((b) => b.text().includes('保存'))!.trigger('click')
    await flushPromises()
    expect(warning).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(warning.mock.calls[0][0])).toContain('oa_base')
  })

  it('详情页写出认证地址和经同一个认证地址登录的包', async () => {
    crab.conch.mockResolvedValue(detail())
    let wrapper = await mountDetail()
    expect(wrapper.find('[data-test="same-auth"]').exists()).toBe(false)

    crab.conch.mockResolvedValue(detail({ authOrigins: ['https://sso.corp.com:443'] }))
    wrapper = await mountDetail()
    let notice = wrapper.get('[data-test="same-auth"]')
    expect(notice.text()).toContain('登录时还会访问认证地址')
    expect(notice.text()).toContain('https://sso.corp.com:443')
    expect(notice.text()).toContain('账号密码会提交到那里')
    expect(notice.findAll('[data-test="same-auth-link"]')).toHaveLength(0)

    crab.conch.mockResolvedValue(detail({
      authOrigins: ['https://sso.corp.com:443'],
      sameSite: ['oa_base'],
      sameAuth: ['erp_orders', 'hr_portal'],
      contendingWith: ['erp_orders'],
    }))
    wrapper = await mountDetail()
    notice = wrapper.get('[data-test="same-auth"]')
    expect(notice.text()).toContain('可能在和经同一个认证地址登录的规则包互相顶掉登录')
    expect(notice.text()).toContain('最近 erp_orders 和这个包轮流被对方的登录顶掉')
    expect(notice.findAll('[data-test="same-auth-link"]').map((link) => link.text())).toEqual(['erp_orders', 'hr_portal'])
    // 同网站那一条不把经认证地址顶掉的包算进去。
    const site = wrapper.get('[data-test="same-site"]')
    expect(site.text()).toContain('本机还有规则包访问同一个网站')
    expect(site.text()).not.toContain('erp_orders')
  })

  it('门户自己的包没有认证地址，也列出经它登录的包和告警', async () => {
    crab.conch.mockResolvedValue(detail({ sameAuth: ['erp_orders'] }))
    let wrapper = await mountDetail()
    let notice = wrapper.get('[data-test="same-auth"]')
    expect(notice.text()).toContain('本机有规则包经这个网站登录')
    expect(notice.text()).toContain('把它当门户或认证中心')
    expect(notice.text()).not.toContain('账号密码会提交到那里')
    expect(notice.findAll('[data-test="same-auth-link"]').map((link) => link.text())).toEqual(['erp_orders'])

    crab.conch.mockResolvedValue(detail({ sameAuth: ['erp_orders'], contendingWith: ['erp_orders'] }))
    wrapper = await mountDetail()
    notice = wrapper.get('[data-test="same-auth"]')
    expect(notice.text()).toContain('可能在和经同一个认证地址登录的规则包互相顶掉登录')
    expect(notice.text()).toContain('最近 erp_orders 和这个包轮流被对方的登录顶掉')
  })

  it('启用和保存认证地址配置后提示经同一个认证地址登录的包', async () => {
    const warning = vi.spyOn(ElMessage, 'warning')
    crab.conch.mockResolvedValue(detail({ switchedOff: true, site: null }))
    crab.setConchEnabled.mockResolvedValue({ conch: 'oa_leave', switchedOff: false, sameSite: [], sameAuth: ['erp_orders'] })
    const wrapper = await mountDetail()
    await wrapper.get('[data-test="conch-enable"]').trigger('click')
    await flushPromises()
    expect(warning).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(warning.mock.calls[0][0])).toContain('erp_orders 和它经同一个认证地址登录')

    warning.mockClear()
    const field = (type: string, label: string) => ({
      type, label, required: false, description: null, options: null, value: null, defaultValue: null, missing: false,
    })
    crab.writeConfig.mockResolvedValue({ updated: true, sameSite: ['oa_base'], sameAuth: ['erp_orders'] })
    const panel = await mountPage(ConfigPanel, {
      props: { conch: 'oa_leave', fields: { sso_url: field('url', '认证中心'), dept: field('string', '部门') } },
    })
    await flushPromises()
    const inputs = panel.findAll('input')
    await inputs[1].setValue('研发')
    await panel.findAll('button').find((b) => b.text().includes('保存'))!.trigger('click')
    await flushPromises()
    expect(warning).not.toHaveBeenCalled()

    await inputs[0].setValue('https://sso.corp.com')
    await panel.findAll('button').find((b) => b.text().includes('保存'))!.trigger('click')
    await flushPromises()
    expect(warning).toHaveBeenCalledTimes(1)
    const text = JSON.stringify(warning.mock.calls[0][0])
    expect(text).toContain('erp_orders')
    // 没改 base_url，不重复提示同网站的包。
    expect(text).not.toContain('oa_base')
  })

  it('概览列出可能互相顶掉登录的两个包', async () => {
    const quiet = await mountPage(OverviewView)
    await flushPromises()
    expect(quiet.find('[data-test="contention"]').exists()).toBe(false)

    crab.conches.mockResolvedValue({
      conches: [],
      contention: [{ site: 'https://oa.corp.com:443', conches: ['oa_base', 'oa_leave'], lastAt: '2026-11-23T03:00:00Z' }],
    })
    const wrapper = await mountPage(OverviewView)
    await flushPromises()
    const alert = wrapper.get('[data-test="contention"]')
    expect(alert.text()).toContain('oa_base')
    expect(alert.text()).toContain('oa_leave')
    expect(alert.text()).toContain('https://oa.corp.com:443')
    expect(alert.findAll('a').map((a) => a.attributes('href'))).toEqual(['/conches/oa_base', '/conches/oa_leave'])
  })
})
