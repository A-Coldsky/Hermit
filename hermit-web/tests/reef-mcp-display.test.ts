import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'
import { setRole } from '../apps/reef-admin/src/auth'

const reef = vi.hoisted(() => ({
  conches: vi.fn(),
  versions: vi.fn(),
  conch: vi.fn(),
  tideSummary: vi.fn(),
  tideFailures: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))

import ConchesView from '../apps/reef-admin/src/views/ConchesView.vue'
import ConchDetailView from '../apps/reef-admin/src/views/ConchDetailView.vue'
import DiffSummary from '../apps/reef-admin/src/components/DiffSummary.vue'

const tool = {
  name: 'search_issues',
  upstreamName: 'search',
  readOnly: true,
  destructive: false,
  upstreamAnnotations: { readOnlyHint: true },
  schemaSha256: 'abcdef0123456789abcdef',
}

const mcpSummary = {
  conch: 'tracker',
  version: '1.0.0',
  title: '缺陷跟踪',
  description: '源站 MCP',
  owner: null,
  tags: [],
  capabilityCount: 2,
  publishedAt: null,
  disabled: false,
  pullCount: 0,
  driver: 'mcp',
  upstream: { url: 'https://tracker.intra.example:8443/mcp', transport: 'streamable_http', auth: { type: 'oauth' }, allowedHosts: ['tracker.intra.example'] },
  snapshot: { fetchedAt: '2026-11-04T08:00:00Z', protocolVersion: '2025-11-25', server: { name: 'tracker-mcp', version: '2.1.0' }, toolsSha256: '0123456789abcdef0123' },
  tools: [tool, { ...tool, name: 'close_issue', upstreamName: 'close_issue', readOnly: false, destructive: true, upstreamAnnotations: {} }],
}

const httpSummary = {
  ...mcpSummary,
  conch: 'legacy_oa',
  title: '协同办公 OA',
  description: '',
  driver: 'http',
  upstream: null,
  snapshot: null,
  tools: null,
}

const detailBase = {
  minCrabVersion: '1.1.0',
  baseUrl: null,
  changelog: null,
  credentials: {},
  config: {},
  actions: { search_issues: {}, close_issue: {} },
  disabledAt: null,
  disabledBy: null,
  disabledReason: null,
}

beforeEach(() => {
  setRole('ADMIN')
  vi.clearAllMocks()
  reef.versions.mockResolvedValue([{ version: '1.0.0', status: 'PUBLISHED', current: true, uploadedAt: '2026-11-04T08:00:00Z', warningCount: 0, warnings: [] }])
})

describe('Reef 原生 MCP 展示：目录', () => {
  it('两种类型用不同标识，MCP 包显示上游主机', async () => {
    reef.conches.mockResolvedValue([mcpSummary, httpSummary])
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const badges = wrapper.findAll('[data-test="driver-badge"]').map((b) => b.text())
    expect(badges).toEqual(['原生 MCP · 源站提供', 'HTTP 规则 · Hermit 编写'])
    expect(wrapper.get('[data-test="upstream-host"]').text()).toBe('tracker.intra.example')
    expect(wrapper.text()).toContain('2 个工具')
  })

  it('可以只看原生 MCP', async () => {
    reef.conches.mockResolvedValue([mcpSummary, httpSummary])
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const mcpOption = wrapper.findAll('[data-test="kind-filter"] label').find((l) => l.text() === '原生 MCP')!
    await mcpOption.find('input').setValue(true)
    await flushPromises()

    expect(wrapper.text()).toContain('缺陷跟踪')
    expect(wrapper.text()).not.toContain('协同办公 OA')
    expect(wrapper.get('[data-test="conch-count"]').text()).toBe('共 1 个')
  })
})

describe('Reef 原生 MCP 展示：详情', () => {
  it('说明工具来自源站，列出上游信息，快照时间标明是写进包的时间', async () => {
    reef.conch.mockResolvedValue({ ...mcpSummary, ...detailBase })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'tracker' } })
    await flushPromises()

    expect(wrapper.get('[data-test="mcp-notice"]').text()).toContain('Hermit 只做权限管理和转发')
    const upstream = wrapper.get('[data-test="mcp-upstream"]').text()
    expect(upstream).toContain('https://tracker.intra.example:8443/mcp')
    expect(upstream).toContain('Streamable HTTP')
    expect(upstream).toContain('OAuth 登录')
    expect(upstream).toContain('tracker-mcp')
    expect(upstream).toContain('写进规则包的时间')
    expect(upstream).toContain('0123456789ab')
    expect(wrapper.text()).not.toContain('老系统地址')
    expect(wrapper.text()).toContain('工具（2）')
  })

  it('工具页是工具表：上游名称、只读、敏感', async () => {
    reef.conch.mockResolvedValue({ ...mcpSummary, ...detailBase })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'tracker' }, path: '/?tab=capabilities' })
    await flushPromises()

    const table = wrapper.get('[data-test="mcp-tools"]').text()
    expect(table).toContain('search_issues')
    expect(table).toContain('search')
    expect(table).toContain('只读')
    expect(table).toContain('敏感')
    expect(table).not.toContain('需确认')
    expect(table).toContain('abcdef012345')
  })

  it('HTTP 包不显示上游 MCP 卡片和说明', async () => {
    reef.conch.mockResolvedValue({ ...httpSummary, ...detailBase, actions: {}, minCrabVersion: '1.0.0' })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    expect(wrapper.find('[data-test="mcp-notice"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="mcp-upstream"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('HTTP 规则 · Hermit 编写')
    expect(wrapper.text()).toContain('老系统地址')
    expect(wrapper.find('[data-test="auth-origins"]').exists()).toBe(false)
  })

  it('HTTP 包写了认证地址时列出来，配置引用写成由 Crab 端配置', async () => {
    reef.conch.mockResolvedValue({
      ...httpSummary,
      ...detailBase,
      actions: {},
      minCrabVersion: '1.11.0',
      baseUrl: 'https://oa.corp.com',
      authOrigins: ['https://sso.corp.com', '{{ config.portal_url }}'],
    })
    const wrapper = await mountPage(ConchDetailView, { props: { conch: 'legacy_oa' } })
    await flushPromises()

    const origins = wrapper.get('[data-test="auth-origins"]').text()
    expect(origins).toContain('https://sso.corp.com、由 Crab 端配置（portal_url）')
    expect(origins).toContain('账号密码会提交到那里')
  })
})

describe('Reef 原生 MCP 展示：版本对比', () => {
  it('列出新增、移除和变更的工具，新增敏感工具时提醒核对', () => {
    const change = (over: Record<string, unknown>) => ({
      name: 'x',
      kind: 'changed',
      previousUpstreamName: 'x',
      upstreamName: 'x',
      previousSchemaSha256: 'a',
      schemaSha256: 'a',
      previousDescription: 'd',
      description: 'd',
      previousReadOnly: true,
      readOnly: true,
      previousDestructive: false,
      destructive: false,
      ...over,
    })
    const wrapper = mount(DiffSummary, {
      props: {
        diff: {
          toolChanges: [
            change({ name: 'close_issue', kind: 'added', destructive: true, previousDestructive: null }),
            change({ name: 'old_tool', kind: 'removed' }),
            change({ name: 'search_issues', schemaSha256: 'b', previousReadOnly: true, readOnly: false }),
          ],
        },
      },
      global: { plugins: [ElementPlus] },
    })

    const text = wrapper.get('[data-test="tool-changes"]').text()
    expect(text).toContain('+ close_issue')
    expect(text).toContain('− old_tool')
    expect(text).toContain('~ search_issues')
    expect(text).toContain('参数')
    expect(text).toContain('改为写入')
    expect(text).toContain('有新的敏感工具')
    expect(wrapper.text()).not.toContain('没有结构变化')
  })

  it('列出新增、删除和改过源码的脚本库，只有这些变化时也不算没有结构变化', () => {
    const wrapper = mount(DiffSummary, {
      props: {
        diff: {
          addedScripts: ['fresh'],
          removedScripts: ['old_lib'],
          changedScripts: ['pack'],
          changedActions: [{ name: 'run', input: false, output: false, steps: true, method: false, mcp: false }],
        },
      },
      global: { plugins: [ElementPlus] },
    })

    const text = wrapper.get('[data-test="script-changes"]').text()
    expect(text).toContain('+ fresh')
    expect(text).toContain('− old_lib')
    expect(text).toContain('~ pack')
    expect(text).toContain('源码')
    expect(wrapper.text()).toContain('~ run')
    expect(wrapper.text()).toContain('步骤')
    expect(wrapper.text()).not.toContain('没有结构变化')
    expect(wrapper.text()).not.toContain('function')

    const scriptsOnly = mount(DiffSummary, {
      props: { diff: { changedScripts: ['pack'] } },
      global: { plugins: [ElementPlus] },
    })
    expect(scriptsOnly.get('[data-test="script-changes"]').text()).toContain('~ pack')
    expect(scriptsOnly.text()).not.toContain('没有结构变化')
  })
})
