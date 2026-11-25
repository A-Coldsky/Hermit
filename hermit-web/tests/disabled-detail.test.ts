import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  authenticators: vi.fn(async () => ({ authenticators: [] })),
  conch: vi.fn(),
  setConchEnabled: vi.fn(),
  setConchSource: vi.fn(),
  conches: vi.fn(),
  localConches: vi.fn(),
  pendingLocalOverwrites: vi.fn(async () => ({ pending: [] })),
  localOverrideGrants: vi.fn(async () => ({ grants: [] })),
  localConchHistory: vi.fn(async () => ({ history: [] })),
  accounts: vi.fn(),
  config: vi.fn(),
  debug: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ConchDetailView from '../apps/crab-console/src/views/ConchDetailView.vue'
import ConchesView from '../apps/crab-console/src/views/ConchesView.vue'

const account = { name: 'alice', default: true, needsConfiguration: false, credentialsUnreadable: false, loginPaused: false }

function detail(ruleDisabled: boolean) {
  return {
    conch: 'legacy_oa',
    version: '1.0.1',
    title: '协同办公 OA',
    description: '',
    credentials: [{ name: 'password', type: 'secret', required: true }],
    config: [],
    capabilities: [],
    loginPaused: false,
    source: 'REEF',
    subscribed: true,
    needsConfiguration: false,
    ruleDisabled,
    declarationsLoaded: true,
    message: ruleDisabled ? '已停用。这版规则暂时不能调用。能力不能引用登录步骤里的变量。第 12 行。' : null,
    fixHint: ruleDisabled ? '更新到合规的新版本' : null,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.accounts.mockResolvedValue({ accounts: [account] })
  crab.config.mockResolvedValue({ fields: {} })
  crab.debug.mockResolvedValue({ enabled: false, expiresAt: null })
})

describe('停用状态说真话', () => {
  it('停用包的详情不写「可以调用」，也不给测试登录', async () => {
    crab.conch.mockResolvedValue(detail(true))
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(page.text()).toContain('这版已停用，所有账号暂时都不能调用')
    expect(page.text()).not.toContain('可以调用，部分账号需要处理')
    expect(page.find('[data-test="ready"]').exists()).toBe(false)
    expect(page.find('[data-test="test-login"]').exists()).toBe(false)
  })

  it('正常包照常给测试登录，横幅只写配置完整', async () => {
    crab.conch.mockResolvedValue(detail(false))
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(page.find('[data-test="ready"]').text()).toContain('配置完整')
    expect(page.text()).not.toContain('已停用')
    expect(page.find('[data-test="test-login"]').exists()).toBe(true)
  })

  it('本机停用的包显示提示和「启用规则包」，点了就调启用接口', async () => {
    crab.conch.mockResolvedValue({ ...detail(false), switchedOff: true })
    crab.setConchEnabled.mockResolvedValue({ conch: 'legacy_oa', switchedOff: false })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(page.get('[data-test="switched-off"]').text()).toContain('所有能力都不能调用')
    expect(page.find('[data-test="conch-disable"]').exists()).toBe(false)
    await page.get('[data-test="conch-enable"]').trigger('click')
    await flushPromises()
    expect(crab.setConchEnabled).toHaveBeenCalledWith('legacy_oa', true)
  })

  it('本机停用且还缺账号时，横幅先说已停用，不写调用结果', async () => {
    crab.conch.mockResolvedValue({ ...detail(false), switchedOff: true, needsConfiguration: true })
    crab.accounts.mockResolvedValue({ accounts: [] })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    const banner = page.get('[data-test="setup-banner"]').text()
    expect(banner).toContain('规则包已停用')
    expect(banner).not.toContain('可以调用')
    expect(banner).not.toContain('调用会返回')
  })

  it('未停用的包只有「停用规则包」', async () => {
    crab.conch.mockResolvedValue({ ...detail(false), switchedOff: false })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(page.find('[data-test="switched-off"]').exists()).toBe(false)
    expect(page.find('[data-test="conch-enable"]').exists()).toBe(false)
    expect(page.find('[data-test="conch-disable"]').exists()).toBe(true)
  })

  it('只有一份时不出现来源切换', async () => {
    crab.conch.mockResolvedValue({ ...detail(false), localVersion: null, reefVersion: '1.0.1' })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()
    expect(page.find('[data-test="two-sources"]').exists()).toBe(false)
  })

  it('同名两份时切到正式包：默认不复制，取消不切换', async () => {
    crab.conch.mockResolvedValue({ ...detail(false), source: 'LOCAL', localVersion: '9.9.9', reefVersion: '1.0.1' })
    crab.setConchSource.mockResolvedValue({ conch: 'legacy_oa', source: 'REEF', copied: false, copiedAccounts: 0, copiedConfigKeys: 0 })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, attach: true })
    await flushPromises()

    expect(page.get('[data-test="two-sources"]').text()).toContain('本地包 v9.9.9')
    expect(page.get('[data-test="two-sources"]').text()).toContain('正式包 v1.0.1')
    expect(page.find('[data-test="source-to-local"]').exists()).toBe(false)
    await page.get('[data-test="source-to-reef"]').trigger('click')
    await flushPromises()
    expect(crab.setConchSource).not.toHaveBeenCalled()
    const confirm = () => document.querySelector('[data-test="to-reef-confirm"]') as HTMLElement
    expect(confirm().textContent?.trim()).toBe('切换')
    ;(document.querySelector('[data-test="to-reef-cancel"]') as HTMLElement).click()
    await flushPromises()
    expect(crab.setConchSource).not.toHaveBeenCalled()

    await page.get('[data-test="source-to-reef"]').trigger('click')
    await flushPromises()
    confirm().click()
    await flushPromises()
    expect(crab.setConchSource).toHaveBeenCalledWith('legacy_oa', 'REEF', false)
    page.unmount()
  })

  it('勾选「同时复制」才带 copy，再次打开时回到不勾选', async () => {
    crab.conch.mockResolvedValue({ ...detail(false), source: 'LOCAL', localVersion: '9.9.9', reefVersion: '1.0.1' })
    crab.setConchSource.mockResolvedValue({ conch: 'legacy_oa', source: 'REEF', copied: true, copiedAccounts: 1, copiedConfigKeys: 1 })
    const page = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' }, attach: true })
    await flushPromises()
    await page.get('[data-test="source-to-reef"]').trigger('click')
    await flushPromises()
    const box = () => document.querySelector('[data-test="to-reef-copy"] input') as HTMLInputElement
    box().click()
    await flushPromises()
    const confirm = document.querySelector('[data-test="to-reef-confirm"]') as HTMLElement
    expect(confirm.textContent?.trim()).toBe('复制并切换')
    confirm.click()
    await flushPromises()
    expect(crab.setConchSource).toHaveBeenCalledWith('legacy_oa', 'REEF', true)

    await page.get('[data-test="source-to-reef"]').trigger('click')
    await flushPromises()
    expect(box().checked).toBe(false)
    page.unmount()
  })

  it('本机停用的卡片不写「可用」', async () => {
    crab.conches.mockResolvedValue({
      conches: [
        {
          conch: 'legacy_oa',
          version: '1.0.1',
          title: '协同办公 OA',
          capabilityCount: 2,
          subscribed: true,
          accountCount: 1,
          needsConfiguration: false,
          loginPaused: false,
          source: 'REEF',
          reefDisabled: false,
          reefDisabledAt: null,
          updateAvailable: false,
          latestVersion: null,
          newRequiredCredentials: [],
          switchedOff: true,
        },
      ],
    })
    crab.localConches.mockResolvedValue({ conches: [] })
    const page = await mountPage(ConchesView)
    await flushPromises()

    expect(page.get('[data-test="switched-off-tag"]').text()).toBe('本机已停用')
    expect(page.get('[data-test="switched-off-status"]').text()).toContain('不能调用')
    expect(page.text()).not.toContain('可以通过 OpenAPI 和 MCP 调用')
  })

  it('停用卡片上「已停用」只出现一次，原因段只写原因', async () => {
    crab.conches.mockResolvedValue({
      conches: [
        {
          conch: 'legacy_oa',
          version: '1.0.1',
          title: '协同办公 OA',
          capabilityCount: 2,
          subscribed: true,
          accountCount: 1,
          needsConfiguration: false,
          loginPaused: false,
          source: 'REEF',
          reefDisabled: false,
          reefDisabledAt: null,
          updateAvailable: false,
          latestVersion: null,
          newRequiredCredentials: [],
          ruleDisabled: true,
          message: '已停用。这版规则暂时不能调用。能力不能引用登录步骤里的变量。第 12 行。',
        },
      ],
    })
    crab.localConches.mockResolvedValue({ conches: [] })
    const page = await mountPage(ConchesView)
    await flushPromises()

    expect(page.text().split('已停用').length - 1).toBe(1)
    expect(page.get('[data-test="rule-message"]').text()).toBe('能力不能引用登录步骤里的变量。第 12 行。')
  })
})
