import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  catalog: vi.fn(),
  sync: vi.fn(),
  syncStatus: vi.fn(),
  subscribe: vi.fn(),
  unsubscribe: vi.fn(),
  update: vi.fn(),
  conches: vi.fn(),
  authenticators: vi.fn(),
  localConches: vi.fn(),
  pendingLocalOverwrites: vi.fn(async () => ({ pending: [] })),
  localOverrideGrants: vi.fn(async () => ({ grants: [] })),
  localConchHistory: vi.fn(async () => ({ history: [] })),
}))

const reef = vi.hoisted(() => ({ conches: vi.fn() }))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))
vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))

import KindShell from '../shared/components/KindShell.vue'
import KindBadge from '../shared/components/KindBadge.vue'
import ConchAvatar from '../shared/components/ConchAvatar.vue'
import CatalogView from '../apps/crab-console/src/views/CatalogView.vue'
import CrabConchesView from '../apps/crab-console/src/views/ConchesView.vue'
import ReefConchesView from '../apps/reef-admin/src/views/ConchesView.vue'

const entry = (over: Record<string, unknown> = {}) => ({
  conch: 'corp_oa',
  version: '1.0.0',
  title: 'OA',
  description: '',
  capabilityCount: 2,
  subscribed: false,
  installed: false,
  installedVersion: null,
  disabled: false,
  localConflict: false,
  updateAvailable: false,
  latestVersion: null,
  newRequiredCredentials: [],
  repairRequired: false,
  repairReason: null,
  message: null,
  ruleDisabled: false,
  driver: 'http',
  authUse: null,
  ...over,
})

const sso = (over: Record<string, unknown> = {}) =>
  entry({ conch: 'corp_sso', title: '统一认证', capabilityCount: 0, driver: 'auth', ...over })

const authenticator = (over: Record<string, unknown> = {}) => ({
  conch: 'corp_sso',
  version: '1.0.0',
  title: '统一认证',
  description: '',
  source: 'REEF',
  origins: ['https://sso.example'],
  allowHosts: [],
  exports: ['access_token'],
  subscribed: true,
  updateAvailable: false,
  latestVersion: null,
  newRequiredCredentials: [],
  accounts: [{ name: 'zhang', consumers: [{ origin: 'OFFICIAL', conch: 'corp_oa', account: 'default' }] }],
  ...over,
})

function button(wrapper: Awaited<ReturnType<typeof mountPage>>, text: string) {
  return wrapper.findAll('button').filter((b) => b.text() === text)
}

async function pick(wrapper: Awaited<ReturnType<typeof mountPage>>, value: string) {
  await wrapper.find(`[data-test="kind-tabs"] input[value="${value}"]`).setValue(true)
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.syncStatus.mockResolvedValue({ report: null, latestCrab: null })
  crab.conches.mockResolvedValue({ conches: [] })
  crab.localConches.mockResolvedValue({ conches: [] })
  crab.authenticators.mockResolvedValue({ authenticators: [] })
})

describe('三种螺壳', () => {
  it('按类型画不同的形状，不认识的类型当作 HTTP', () => {
    const kinds = ['http', 'mcp', 'auth', 'other'].map((kind) => mount(KindShell, { props: { kind } }))
    expect(kinds.map((w) => w.attributes('data-kind'))).toEqual(['http', 'mcp', 'auth', 'http'])
    expect(kinds[1].find('.m-socket').exists()).toBe(true)
    expect(kinds[1].find('.m-hole').exists()).toBe(true)
    expect(kinds[2].find('.a-hole').exists()).toBe(true)
    expect(kinds[0].find('.hm-shell').exists()).toBe(true)
  })

  it('小尺寸去掉细线，特征还在', () => {
    const small = mount(KindShell, { props: { kind: 'mcp', size: 20 } })
    const large = mount(KindShell, { props: { kind: 'mcp', size: 48 } })
    expect(large.find('.m-ridge').exists()).toBe(true)
    expect(small.find('.m-ridge').exists()).toBe(false)
    expect(small.find('.m-hole').exists()).toBe(true)
  })

  it('单色版三种都有，HTTP 不用彩色的螺壳', () => {
    const kinds = ['http', 'mcp', 'auth'].map((kind) => mount(KindShell, { props: { kind, size: 13, mono: true } }))
    expect(kinds.every((w) => w.classes().includes('mono'))).toBe(true)
    expect(kinds[0].find('.hm-shell').exists()).toBe(false)
    expect(kinds[0].find('.cut').exists()).toBe(true)
    expect(kinds[1].find('.m-hole').exists()).toBe(true)
    expect(kinds[2].find('.a-hole').exists()).toBe(true)
  })

  it('头像只有字母，不带螺壳', () => {
    const avatar = mount(ConchAvatar, { props: { name: 'corp_sso', title: '统一认证' } })
    expect(avatar.text()).toBe('统')
    expect(avatar.find('svg').exists()).toBe(false)
  })

  it('类型徽标：默认单色小螺壳，large 是彩色螺壳', () => {
    const small = mount(KindBadge, { props: { kind: 'auth' } })
    expect(small.text()).toBe('授权器')
    expect(small.attributes('data-kind')).toBe('auth')
    expect(small.find('svg').classes()).toContain('mono')
    const large = mount(KindBadge, { props: { kind: 'mcp', large: true } })
    expect(large.text()).toBe('原生 MCP')
    expect(large.find('svg').classes()).not.toContain('mono')
    expect(mount(KindBadge, { props: { kind: null } }).attributes('data-kind')).toBe('http')
  })
})

describe('Crab 可订阅规则包：规则包 / 授权器页签', () => {
  it('授权器只在授权器页签里，业务包标出需要的授权器', async () => {
    crab.catalog.mockResolvedValue({
      conches: [entry({ authUse: 'corp_sso' }), sso()],
      syncedAt: '2026-11-01T00:00:00Z',
      configured: true,
    })
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    expect(wrapper.text()).toContain('corp_oa')
    expect(wrapper.find('[data-test="needs-authenticator"]').text()).toContain('corp_sso')
    expect(wrapper.find('[data-test="needs-authenticator"]').text()).toContain('本机还没有')
    expect(wrapper.findAll('.el-table__row')).toHaveLength(1)
    expect(wrapper.find('[data-test="kind-tabs"] svg').exists()).toBe(false)
    expect(wrapper.find('.el-table__row [data-test="kind-badge"]').attributes('data-kind')).toBe('http')

    await pick(wrapper, 'authenticators')
    expect(wrapper.findAll('.el-table__row')).toHaveLength(1)
    expect(wrapper.find('[data-test="used-by"]').text()).toContain('corp_oa')
    expect(wrapper.find('[data-test="needs-authenticator"]').exists()).toBe(false)
  })

  it('订阅业务包时本机没有授权器：确认后先订阅授权器再订阅业务包', async () => {
    crab.catalog.mockResolvedValue({
      conches: [entry({ authUse: 'corp_sso' }), sso()],
      syncedAt: '2026-11-01T00:00:00Z',
      configured: true,
    })
    crab.subscribe.mockImplementation(async (conch: string) => ({
      conch,
      subscribed: true,
      outcome: { conch, version: '1.0.0', kind: 'INSTALLED', reason: null },
    }))
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(CatalogView)
    await flushPromises()

    await button(wrapper, '订阅')[0].trigger('click')
    await flushPromises()

    expect(confirm.mock.calls[0][0]).toContain('corp_sso')
    expect(crab.subscribe.mock.calls.map((c) => c[0])).toEqual(['corp_sso', 'corp_oa'])
  })

  it('不确认就什么都不订阅；授权器已经在本机时不询问', async () => {
    crab.catalog.mockResolvedValue({
      conches: [entry({ authUse: 'corp_sso' }), sso()],
      syncedAt: '2026-11-01T00:00:00Z',
      configured: true,
    })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    await button(wrapper, '订阅')[0].trigger('click')
    await flushPromises()
    expect(crab.subscribe).not.toHaveBeenCalled()

    confirm.mockClear()
    crab.catalog.mockResolvedValue({
      conches: [entry({ authUse: 'corp_sso' }), sso({ subscribed: true, installed: true, installedVersion: '1.0.0' })],
      syncedAt: '2026-11-01T00:00:00Z',
      configured: true,
    })
    crab.subscribe.mockResolvedValue({ conch: 'corp_oa', subscribed: true, outcome: null })
    const second = await mountPage(CatalogView)
    await flushPromises()
    await button(second, '订阅')[0].trigger('click')
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    expect(crab.subscribe.mock.calls.map((c) => c[0])).toEqual(['corp_oa'])
  })

  it('授权器订阅了但没装上：不接着订阅业务包', async () => {
    crab.catalog.mockResolvedValue({
      conches: [entry({ authUse: 'corp_sso' }), sso()],
      syncedAt: '2026-11-01T00:00:00Z',
      configured: true,
    })
    crab.subscribe.mockResolvedValue({
      conch: 'corp_sso',
      subscribed: true,
      outcome: { conch: 'corp_sso', version: '1.0.0', kind: 'SKIPPED_MIN_VERSION', reason: null },
    })
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    await button(wrapper, '订阅')[0].trigger('click')
    await flushPromises()
    expect(crab.subscribe.mock.calls.map((c) => c[0])).toEqual(['corp_sso'])
  })

  it('本机有同名的本地授权器在生效时不询问', async () => {
    crab.catalog.mockResolvedValue({
      conches: [entry({ authUse: 'corp_sso' }), sso()],
      syncedAt: '2026-11-01T00:00:00Z',
      configured: true,
    })
    crab.authenticators.mockResolvedValue({ authenticators: [authenticator({ source: 'LOCAL', subscribed: false })] })
    crab.subscribe.mockResolvedValue({ conch: 'corp_oa', subscribed: true, outcome: null })
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    await button(wrapper, '订阅')[0].trigger('click')
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    expect(crab.subscribe.mock.calls.map((c) => c[0])).toEqual(['corp_oa'])
  })

  it('退订授权器前列出绑在它上面的账号，并说明绑定会保留', async () => {
    crab.catalog.mockResolvedValue({
      conches: [sso({ subscribed: true, installed: true, installedVersion: '1.0.0' })],
      syncedAt: '2026-11-01T00:00:00Z',
      configured: true,
    })
    crab.authenticators.mockResolvedValue({ authenticators: [authenticator()] })
    crab.unsubscribe.mockResolvedValue({})
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = await mountPage(CatalogView)
    await flushPromises()
    await pick(wrapper, 'authenticators')

    await button(wrapper, '取消订阅')[0].trigger('click')
    await flushPromises()

    const message = String(confirm.mock.calls[0][0])
    expect(message).toContain('corp_oa/default')
    expect(message).toContain('绑定都会保留')
    expect(crab.unsubscribe).toHaveBeenCalledWith('corp_sso')
  })
})

describe('Crab 规则包与账号：先分订阅 / 本地，再分类型', () => {
  it('同名的本地包和订阅包并存时两个页签都显示，并标出哪一份在生效', async () => {
    const row = (over: Record<string, unknown>) => ({
      conch: 'oa', version: '1.0.0', title: 'OA', capabilityCount: 1, subscribed: true, accountCount: 0,
      needsConfiguration: false, loginPaused: false, source: 'REEF', driver: 'http', ...over,
    })
    crab.conches.mockResolvedValue({
      conches: [
        row({}),
        row({ conch: 'both', source: 'LOCAL', localVersion: '0.2.0', reefVersion: '1.0.0' }),
        row({ conch: 'tools', source: 'LOCAL', driver: 'mcp', subscribed: false }),
      ],
    })
    crab.authenticators.mockResolvedValue({ authenticators: [authenticator({})] })
    const wrapper = await mountPage(CrabConchesView)
    await flushPromises()
    const names = () => wrapper.findAll('.hm-card.conch .name .mono').map((node) => node.text().split(' ')[0])

    expect(wrapper.find('[data-test="source-tabs"]').text()).toMatch(/订阅 3\s*本地 2/)
    expect(names()).toEqual(['oa', 'both', 'corp_sso'])
    const both = wrapper.findAll('.hm-card.conch')[1]
    expect(both.find('[data-test="local-tag"]').exists()).toBe(true)
    expect(both.find('[data-test="two-sources-tag"]').text()).toBe('本地这份在生效')

    await pick(wrapper, 'http')
    expect(names()).toEqual(['oa', 'both'])
    await pick(wrapper, 'mcp')
    expect(names()).toEqual([])
    expect(wrapper.find('[data-test="conches-empty"]').text()).toContain('原生 MCP')

    await wrapper.find('[data-test="source-tabs"] input[value="local"]').setValue(true)
    await flushPromises()
    expect(names()).toEqual(['tools'])
    await pick(wrapper, 'all')
    expect(names()).toEqual(['both', 'tools'])
    expect(wrapper.find('[data-test="local-tag"]').exists()).toBe(false)
    await pick(wrapper, 'auth')
    expect(wrapper.find('[data-test="authenticators-empty"]').exists()).toBe(true)
  })

  it('列出授权器、谁在用，有新版本时能更新', async () => {
    crab.authenticators.mockResolvedValue({
      authenticators: [authenticator({ updateAvailable: true, latestVersion: '1.1.0' })],
    })
    crab.update.mockResolvedValue({ outcome: { kind: 'INSTALLED' } })
    const wrapper = await mountPage(CrabConchesView)
    await flushPromises()
    expect(wrapper.find('[data-test="authenticator-update-dot"]').exists()).toBe(true)
    await pick(wrapper, 'http')
    expect(wrapper.find('[data-test="authenticator-card"]').exists()).toBe(false)

    await pick(wrapper, 'auth')
    const card = wrapper.find('[data-test="authenticator-card"]')
    expect(card.text()).toContain('corp_sso')
    expect(card.find('[data-test="authenticator-used-by"]').text()).toContain('corp_oa')
    expect(card.text()).toContain('有新版本 v1.1.0')
    expect(card.find('[data-test="authenticator-update"]').exists()).toBe(true)
    // 名字单独一行并带全名的 title；类型徽标和标签在名字下面的一行里，不跟在名字后面。
    const title = card.find('.name strong')
    expect(title.attributes('title')).toBe(title.text())
    expect(title.find('.el-tag, [data-test="kind-badge"]').exists()).toBe(false)
    expect(card.find('.marks [data-test="kind-badge"]').attributes('data-kind')).toBe('auth')
    expect(card.find('.marks').text()).toContain('有新版本 v1.1.0')
  })

  it('双来源授权器、停用本地授权器计数，以及引用授权器业务包的待配置文案', async () => {
    crab.conches.mockResolvedValue({
      conches: [
        {
          conch: 'corp_oa',
          version: '1.0.0',
          title: 'OA',
          capabilityCount: 1,
          subscribed: true,
          accountCount: 1,
          needsConfiguration: true,
          usesAuthenticator: true,
          loginPaused: false,
          source: 'REEF',
          driver: 'http',
          newRequiredCredentials: [],
        },
      ],
    })
    crab.authenticators.mockResolvedValue({
      authenticators: [
        authenticator({
          source: 'LOCAL',
          subscribed: true,
          localVersion: '1.2.0',
          reefVersion: '1.0.0',
          needsConfiguration: true,
          accountCount: 1,
        }),
      ],
    })
    crab.localConches.mockResolvedValue({
      conches: [
        {
          conch: 'bad_sso',
          file: 'bad_sso.yaml',
          broken: true,
          driver: 'auth',
          ruleDisabled: true,
          error: 'missing alive_check',
        },
      ],
    })
    const wrapper = await mountPage(CrabConchesView)
    await flushPromises()

    expect(wrapper.text()).toContain('待配置：账号未绑定授权器或缺少配置')
    expect(wrapper.find('[data-test="authenticator-card"] [data-test="two-sources-tag"]').text()).toBe('本地这份在生效')
    expect(wrapper.find('[data-test="authenticator-card"]').text()).toContain('待配置：还缺凭证或配置项')

    await wrapper.find('[data-test="source-tabs"] input[value="local"]').setValue(true)
    await flushPromises()
    expect(wrapper.find('[data-test="kind-tabs"]').text()).toContain('授权器 2')
    await pick(wrapper, 'auth')
    expect(wrapper.findAll('[data-test="authenticator-card"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="broken-conch"]')).toHaveLength(1)
  })
})

describe('Reef 规则包目录：授权器页签', () => {
  const summary = (over: Record<string, unknown>) => ({
    conch: 'corp_oa',
    version: '1.0.0',
    title: 'OA',
    description: '',
    owner: null,
    tags: [],
    capabilityCount: 2,
    publishedAt: null,
    disabled: false,
    pullCount: 0,
    driver: 'http',
    upstream: null,
    snapshot: null,
    tools: [],
    authUse: null,
    ...over,
  })

  it('授权器不混在规则包里，类型筛选只在规则包页签', async () => {
    reef.conches.mockResolvedValue([
      summary({ authUse: 'corp_sso' }),
      summary({ conch: 'corp_sso', title: '统一认证', driver: 'auth', capabilityCount: 0 }),
    ])
    const wrapper = await mountPage(ReefConchesView)
    await flushPromises()
    expect(wrapper.find('[data-test="conch-count"]').text()).toBe('共 1 个')
    expect(wrapper.find('[data-test="kind-filter"]').exists()).toBe(true)

    expect(wrapper.find('[data-test="kind-tabs"] svg').exists()).toBe(false)
    expect(wrapper.find('.hm-card.conch > .hm-kind-water').attributes('data-kind')).toBe('http')
    expect(wrapper.find('.hm-card.conch [data-test="driver-badge"]').attributes('data-kind')).toBe('http')

    await pick(wrapper, 'authenticators')
    expect(wrapper.find('.hm-card.conch > .hm-kind-water').attributes('data-kind')).toBe('auth')
    expect(wrapper.find('[data-test="conch-count"]').text()).toBe('共 1 个')
    expect(wrapper.find('[data-test="kind-filter"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="used-by"]').text()).toContain('corp_oa')
  })
})
