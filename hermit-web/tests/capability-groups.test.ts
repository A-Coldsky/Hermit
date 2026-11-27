import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { buildGroupTree, flattenGroups, groupDisplayTitle, groupMatches, type GroupDef } from '@shared/groups'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  conch: vi.fn(),
  apiKeys: vi.fn(),
  createApiKey: vi.fn(),
  setCapability: vi.fn(),
  setGroup: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import CapabilityToggles from '../apps/crab-console/src/components/CapabilityToggles.vue'
import ApiKeysView from '../apps/crab-console/src/views/ApiKeysView.vue'
import CapabilityList from '../apps/reef-admin/src/components/CapabilityList.vue'
import McpToolTable from '../apps/reef-admin/src/components/McpToolTable.vue'
import DiffSummary from '../apps/reef-admin/src/components/DiffSummary.vue'

const SAMPLE_GROUPS: GroupDef[] = [
  {
    name: 'approval',
    title: '审批管理',
    description: '待办与各类审批',
    children: [
      { name: 'leave', title: '请假审批', description: '请假单处理' },
      { name: 'expense', title: '报销审批', description: null },
    ],
  },
  {
    name: 'hr',
    title: '人事档案',
    description: null,
    children: [],
  },
]

beforeEach(() => {
  vi.clearAllMocks()
})

describe('shared/groups 工具函数', () => {
  it('按一级与二级目录组织能力，未分组能力保持在顶层', () => {
    const items = [
      { name: 'get_me', group: null },
      { name: 'list_all', group: 'approval' },
      { name: 'list_leave', group: 'approval/leave' },
      { name: 'list_expense', group: 'approval/expense' },
      { name: 'get_emp', group: 'hr' },
    ]
    const tree = buildGroupTree(SAMPLE_GROUPS, items, (i) => i.group)
    expect(tree.hasGroups).toBe(true)
    expect(tree.topLevel.map((i) => i.name)).toEqual(['get_me'])
    expect(tree.groups.map((g) => g.path)).toEqual(['approval', 'hr'])
    expect(tree.groups[0].direct.map((i) => i.name)).toEqual(['list_all'])
    expect(tree.groups[0].children.map((c) => c.path)).toEqual(['approval/leave', 'approval/expense'])
    expect(tree.groups[0].all.map((i) => i.name)).toEqual(['list_all', 'list_leave', 'list_expense'])

    expect(groupMatches('approval/leave', 'approval')).toBe(true)
    expect(groupMatches('approval_v2', 'approval')).toBe(false)
    expect(groupDisplayTitle(SAMPLE_GROUPS, 'approval/leave')).toBe('审批管理 / 请假审批')
    expect(flattenGroups(SAMPLE_GROUPS).map((g) => g.path)).toEqual([
      'approval',
      'approval/leave',
      'approval/expense',
      'hr',
    ])
  })
})

describe('Crab 能力按分组开关', () => {
  it('无分组规则包保持平铺表格，不出现分组容器', async () => {
    const wrapper = await mountPage(CapabilityToggles, {
      props: {
        conch: 'flat_oa',
        capabilities: [
          { action: 'list_todo', title: '查待办', description: '', method: 'GET', readOnly: true, disabled: false },
        ],
      },
    })
    await flushPromises()
    expect(wrapper.find('[data-test="grouped-capabilities"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('list_todo')
  })

  it('有分组规则包直接展示顶层能力与一/二级分组，并支持按分组整批启停', async () => {
    crab.setGroup.mockResolvedValueOnce({
      conch: 'sample_oa',
      group: 'approval',
      disabled: true,
      actions: [
        { action: 'list_all', disabled: true },
        { action: 'list_leave', disabled: true },
      ],
    })
    crab.setGroup.mockResolvedValueOnce({
      conch: 'sample_oa',
      group: 'approval/leave',
      disabled: false,
      actions: [{ action: 'list_leave', disabled: false }],
    })

    const capabilities = [
      { action: 'get_me', title: '当前用户', description: '', method: 'GET', readOnly: true, disabled: false, group: null },
      { action: 'list_all', title: '全部待办', description: '', method: 'GET', readOnly: true, disabled: false, group: 'approval' },
      { action: 'list_leave', title: '请假待办', description: '', method: 'GET', readOnly: true, disabled: false, group: 'approval/leave' },
      { action: 'get_emp', title: '员工信息', description: '', method: 'GET', readOnly: true, disabled: false, group: 'hr' },
    ]
    const wrapper = await mountPage(CapabilityToggles, {
      props: { conch: 'sample_oa', groups: SAMPLE_GROUPS, capabilities },
    })
    await flushPromises()

    expect(wrapper.find('[data-test="grouped-capabilities"]').exists()).toBe(true)
    expect(wrapper.get('[data-test="top-level-capabilities"]').text()).toContain('get_me')
    expect(wrapper.get('[data-test="group-status-approval"]').text()).toContain('已启用 2/2')

    // 停用一级分组 approval：连带子组一起停用
    await wrapper.get('[data-test="group-switch-approval"]').trigger('click')
    await flushPromises()
    expect(crab.setGroup).toHaveBeenCalledWith('sample_oa', 'approval', false)
    expect(wrapper.get('[data-test="group-status-approval"]').text()).toContain('已全部停用')
    expect(wrapper.get('[data-test="group-status-approval/leave"]').text()).toContain('已全部停用')

    // 单独重新启用二级子组 approval/leave：父组变为「部分启用」
    await wrapper.get('[data-test="group-switch-approval/leave"]').trigger('click')
    await flushPromises()
    expect(crab.setGroup).toHaveBeenCalledWith('sample_oa', 'approval/leave', true)
    expect(wrapper.get('[data-test="group-status-approval/leave"]').text()).toContain('已启用 1/1')
    expect(wrapper.get('[data-test="group-status-approval"]').text()).toContain('部分启用 (1/2)')
  })
})

describe('Crab API Key 分组范围适配', () => {
  it('列表正确展示分组范围，并在仅按分组授权时不误写「所选规则包的全部能力」', async () => {
    crab.conches.mockResolvedValue({ conches: [] })
    crab.apiKeys.mockResolvedValue({
      apiKeys: [
        {
          id: 'k-group',
          name: 'approval-bot',
          prefix: 'hk_grp',
          scope: { all: false, readOnly: true, conches: ['sample_oa'], groups: ['sample_oa:approval'], actions: [] },
          createdAt: '2026-11-22T00:00:00Z',
          lastUsedAt: null,
          revokedAt: null,
        },
      ],
    })
    const wrapper = await mountPage(ApiKeysView)
    await flushPromises()
    expect(wrapper.get('[data-test="scope-groups"]').text()).toContain('sample_oa:approval')
    expect(wrapper.text()).toContain('按上述分组')
    expect(wrapper.text()).not.toContain('所选规则包的全部能力')
  })

  it('创建弹窗支持按分组授权、自动去除被一级覆盖的二级子组，并提示补选本地包', async () => {
    crab.apiKeys.mockResolvedValue({ apiKeys: [] })
    crab.conches.mockResolvedValue({ conches: [{ conch: 'official_oa' }, { conch: 'local_oa' }] })
    const baseDetail = {
      version: '1.0.0',
      description: '',
      driver: 'http',
      credentials: [],
      config: [],
      groups: SAMPLE_GROUPS,
      capabilities: [
        { action: 'get_me', title: '当前用户', description: '', method: 'GET', readOnly: true, disabled: false, group: null },
        { action: 'list_leave', title: '请假待办', description: '', method: 'GET', readOnly: true, disabled: false, group: 'approval/leave' },
        { action: 'get_emp', title: '员工信息', description: '', method: 'GET', readOnly: true, disabled: false, group: 'hr' },
      ],
      subscribed: false,
      needsConfiguration: false,
      loginPaused: false,
      reefDisabled: false,
      reefDisabledAt: null,
      updateAvailable: false,
      latestVersion: null,
      newRequiredCredentials: [],
    }
    crab.conch.mockImplementation(async (name: string) => ({
      ...baseDetail,
      conch: name,
      title: name === 'local_oa' ? '本地 OA' : '正式 OA',
      source: name === 'local_oa' ? 'LOCAL' : 'REEF',
    }))
    crab.createApiKey.mockResolvedValue({
      id: 'k2',
      name: 'bot',
      prefix: 'hk_b',
      plaintext: 'hk_b_secret',
      scope: { all: false, readOnly: true, conches: ['official_oa', 'local_oa'], groups: ['official_oa:hr', 'local_oa:approval'], actions: ['local_oa.get_me'] },
      createdAt: '2026-11-22T00:00:00Z',
    })

    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('创建 API Key'))!.trigger('click')
    await flushPromises()

    expect(document.body.querySelector('[data-test="groups-field"]')).not.toBeNull()
    const vm = wrapper.vm as unknown as {
      form: { name: string; groups: string[]; actions: string[]; conches: string[] }
    }
    vm.form.name = 'bot'
    vm.form.groups = ['official_oa:hr', 'local_oa:approval', 'local_oa:approval/leave']
    vm.form.actions = ['local_oa.get_me']
    await flushPromises()

    // 选了本地包与正式包的分组但未在允许的规则包点名时，一键补齐应同时保留已引用的正式包与本地包
    const addBtn = document.body.querySelector('[data-test="add-missing-locals"]') as HTMLElement
    expect(addBtn).not.toBeNull()
    addBtn.click()
    await flushPromises()
    expect(vm.form.conches).toEqual(['official_oa', 'local_oa'])

    const dialog = document.body.querySelector('.el-dialog') as HTMLElement
    ;[...dialog.querySelectorAll('button')].find((b) => b.textContent?.trim() === '创建')!.click()
    await flushPromises()

    expect(crab.createApiKey).toHaveBeenCalledWith({
      name: 'bot',
      readOnly: true,
      all: false,
      conches: ['official_oa', 'local_oa'],
      groups: ['official_oa:hr', 'local_oa:approval'],
      actions: ['local_oa.get_me'],
    })
    wrapper.unmount()
  })
})

describe('Reef 分组展示与版本差异', () => {
  it('CapabilityList 与 McpToolTable 按一/二级分组展示，DiffSummary 展示分组变化', async () => {
    const cap = (name: string, group: string | null) => ({
      name,
      title: `标题 ${name}`,
      description: `说明 ${name}`,
      group,
      effectiveMethod: 'GET',
      inputJsonSchema: {},
      outputJsonSchema: {},
      examples: [],
      notes: null,
      related: [],
      mcp: { expose: true, readOnly: true, destructive: false },
    })
    const capsWrapper = await mountPage(CapabilityList, {
      props: {
        groups: SAMPLE_GROUPS,
        actions: {
          get_me: cap('get_me', null),
          list_leave: cap('list_leave', 'approval/leave'),
          get_emp: cap('get_emp', 'hr'),
        },
      },
    })
    expect(capsWrapper.find('[data-test="reef-grouped-capabilities"]').exists()).toBe(true)
    expect(capsWrapper.get('[data-test="reef-top-level"]').text()).toContain('get_me')
    expect(capsWrapper.get('[data-test="reef-group-approval/leave"]').text()).toContain('list_leave')

    const toolsWrapper = await mountPage(McpToolTable, {
      props: {
        groups: SAMPLE_GROUPS,
        tools: [
          {
            name: 'list_leave',
            upstreamName: 'listLeave',
            group: 'approval/leave',
            readOnly: true,
            destructive: false,
            upstreamAnnotations: {},
            schemaSha256: '0123456789abcdef0123456789abcdef',
          },
          {
            name: 'purge_leave',
            upstreamName: 'purgeLeave',
            group: 'approval/leave',
            readOnly: true,
            destructive: true,
            upstreamAnnotations: {},
            schemaSha256: 'fedcba9876543210fedcba9876543210',
          },
        ],
      },
    })
    await flushPromises()
    expect(toolsWrapper.text()).toContain('审批管理 / 请假审批')
    // 同时标了只读和敏感的工具按敏感算，只显示「敏感」
    const tags = toolsWrapper.findAll('.el-tag').map((tag) => tag.text())
    expect(tags.filter((text) => text === '只读')).toHaveLength(1)
    expect(tags.filter((text) => text === '敏感')).toHaveLength(1)
    expect(tags).not.toContain('写入')

    const diffWrapper = await mountPage(DiffSummary, {
      props: {
        diff: {
          addedGroups: ['finance'],
          removedGroups: ['legacy'],
          changedGroups: ['hr'],
          actionGroupChanges: [{ name: 'get_emp', before: null, after: 'hr' }],
        },
      },
    })
    const section = diffWrapper.get('[data-test="group-changes"]')
    expect(section.text()).toContain('+ finance')
    expect(section.text()).toContain('− legacy')
    expect(section.text()).toContain('~ hr')
    expect(section.text()).toContain('（顶层） → hr')
  })
})
