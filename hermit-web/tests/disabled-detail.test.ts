import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conch: vi.fn(),
  conches: vi.fn(),
  localConches: vi.fn(),
  pendingLocalOverwrites: vi.fn(async () => ({ pending: [] })),
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
