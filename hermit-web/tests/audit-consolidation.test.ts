import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account, CapabilityState, ConchDetail, DevTrace } from '../apps/crab-console/src/api'
import type { GroupDef } from '@shared/groups'
import { deferred, mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  conch: vi.fn(),
  apiKeys: vi.fn(),
  createApiKey: vi.fn(),
  credentialNames: vi.fn(),
  writeCredentials: vi.fn(),
  deleteAccount: vi.fn(),
  testLogin: vi.fn(),
  setCapability: vi.fn(),
  setGroup: vi.fn(),
  runLocal: vi.fn(),
  refreshLocalMcp: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ApiKeysView from '../apps/crab-console/src/views/ApiKeysView.vue'
import AccountsPanel from '../apps/crab-console/src/components/AccountsPanel.vue'
import CapabilityToggles from '../apps/crab-console/src/components/CapabilityToggles.vue'
import DeveloperPanel from '../apps/crab-console/src/components/DeveloperPanel.vue'
import DevTracePanel from '../apps/crab-console/src/components/DevTracePanel.vue'
import { issueHints } from '../apps/reef-admin/src/labels'

const GROUPS: GroupDef[] = [
  {
    name: 'approval',
    title: '审批',
    description: null,
    children: [{ name: 'leave', title: '请假', description: null }],
  },
  { name: 'ops', title: '写入操作', description: null, children: [] },
]

function makeDetail(over: Partial<ConchDetail> & { conch: string }): ConchDetail {
  return {
    version: '1.0.0',
    title: '通用 OA',
    description: null,
    credentials: [],
    config: [],
    groups: GROUPS,
    capabilities: [
      { action: 'get_me', title: '当前用户', description: '', method: 'GET', readOnly: true, disabled: false, group: null },
      { action: 'list_leave', title: '请假列表', description: '', method: 'GET', readOnly: true, disabled: false, group: 'approval/leave' },
      { action: 'submit_ops', title: '提交写入', description: '', method: 'POST', readOnly: false, disabled: false, group: 'ops' },
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

function makeAccount(over: Partial<Account> = {}): Account {
  return {
    name: 'zhangsan',
    default: true,
    identity: null,
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

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.apiKeys.mockResolvedValue({ apiKeys: [] })
  crab.conches.mockResolvedValue({ conches: [] })
  crab.credentialNames.mockResolvedValue({ fields: [] })
  crab.writeCredentials.mockResolvedValue({ updated: true })
  crab.deleteAccount.mockResolvedValue({ deleted: true })
  crab.testLogin.mockResolvedValue({ ok: true })
})

afterEach(() => {
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('H8 & M14 & Low: ApiKeysView 过期范围清理、停用包/单包失败容错与唯一分组 key', () => {
  it('切回只读或收窄规则包时自动清理失效的分组/能力，不再触发本地包补齐扩大范围', async () => {
    crab.conches.mockResolvedValue({
      conches: [{ conch: 'official_oa' }, { conch: 'local_oa' }],
    })
    crab.conch.mockImplementation(async (name: string) =>
      makeDetail({
        conch: name,
        title: '相同标题 OA',
        source: name === 'local_oa' ? 'LOCAL' : 'REEF',
      }),
    )
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('创建 API Key'))!.trigger('click')
    await flushPromises()

    // 两个包拥有相同 title，el-option-group 仍无重复 key 警告
    expect(warnSpy).not.toHaveBeenCalled()

    const vm = wrapper.vm as unknown as {
      form: { readOnly: boolean; conches: string[]; groups: string[]; actions: string[] }
      missingLocalConches: string[]
    }

    // 关闭只读后选择本地包的写分组和写能力
    vm.form.readOnly = false
    await flushPromises()
    vm.form.groups = ['local_oa:ops', 'official_oa:approval']
    vm.form.actions = ['local_oa.submit_ops', 'official_oa.get_me']
    await flushPromises()
    expect(vm.missingLocalConches).toEqual(['local_oa'])

    // 重新切回只读：local_oa:ops 和 local_oa.submit_ops 失效并自动清掉，不再提示补齐 local_oa
    vm.form.readOnly = true
    await flushPromises()
    expect(vm.form.groups).toEqual(['official_oa:approval'])
    expect(vm.form.actions).toEqual(['official_oa.get_me'])
    expect(vm.missingLocalConches).toEqual([])
    expect(document.body.querySelector('[data-test="missing-local-alert"]')).toBeNull()

    // 选本地只读能力后再收窄允许的规则包到 official_oa：自动清掉 local_oa 的能力
    vm.form.actions = ['local_oa.get_me', 'official_oa.get_me']
    await flushPromises()
    expect(vm.missingLocalConches).toEqual(['local_oa'])
    vm.form.conches = ['official_oa']
    await flushPromises()
    expect(vm.form.actions).toEqual(['official_oa.get_me'])
    expect(vm.missingLocalConches).toEqual([])
    wrapper.unmount()
  })

  it('loadConches 过滤 ruleDisabled 并使用 allSettled 容忍单个规则包读取失败', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)
    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'disabled_oa', ruleDisabled: true },
        { conch: 'broken_oa', ruleDisabled: false },
        { conch: 'healthy_oa', ruleDisabled: false },
      ],
    })
    crab.conch.mockImplementation(async (name: string) => {
      if (name === 'broken_oa') throw new Error('corrupt')
      return makeDetail({ conch: name, title: '健康包' })
    })

    const wrapper = await mountPage(ApiKeysView, { attach: true })
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('创建 API Key'))!.trigger('click')
    await flushPromises()

    expect(crab.conch).not.toHaveBeenCalledWith('disabled_oa')
    const vm = wrapper.vm as unknown as { conchDetails: ConchDetail[]; conchesLoaded: boolean }
    expect(vm.conchesLoaded).toBe(true)
    expect(vm.conchDetails.map((d) => d.conch)).toEqual(['healthy_oa'])
    wrapper.unmount()
  })
})

describe('M13: AccountsPanel 与 CredentialDialog 身份绑定风险提示', () => {
  const fields = [
    { name: 'password', label: '密码', type: 'secret', required: true, description: null, options: null },
  ]

  it('已绑定身份的账号在填写凭证、删除和测试登录时提示对共用 Cookie / 身份的影响', async () => {
    const bound = makeAccount({ name: 'alice', identity: { id: 'id-1', name: 'OA · 张三' } })
    const wrapper = await mountPage(AccountsPanel, {
      props: { conch: 'oa_leave', accounts: [bound], credentials: fields },
      attach: true,
    })
    await flushPromises()

    // 1. 填写凭证弹窗包含清空身份共用 Cookie 提示
    const editBtn = wrapper.findAll('button').find((b) => b.text().trim() === '填写凭证')!
    await editBtn.trigger('click')
    await flushPromises()
    const credWarn = document.body.querySelector('[data-test="credential-identity-warning"]')
    expect(credWarn).not.toBeNull()
    expect(credWarn?.textContent).toContain('OA · 张三')
    expect(credWarn?.textContent).toContain('清空这个身份共用的 Cookie')

    // 2. 删除账号确认框说明会同时从身份解除绑定
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
    const delBtn = wrapper.findAll('button').find((b) => b.text().trim() === '删除')!
    await delBtn.trigger('click')
    await flushPromises()
    expect(String(confirm.mock.calls[0][0])).toContain('OA · 张三')
    expect(String(confirm.mock.calls[0][0])).toContain('解除绑定')
    expect(crab.deleteAccount).not.toHaveBeenCalled()

    // 3. 测试登录先弹出确认；取消时不调用接口，确认后才调用
    confirm.mockRejectedValueOnce('cancel')
    await wrapper.get('[data-test="test-login"]').trigger('click')
    await flushPromises()
    expect(String(confirm.mock.calls[1][0])).toContain('OA · 张三')
    expect(String(confirm.mock.calls[1][0])).toContain('清空这个身份共用的 Cookie')
    expect(crab.testLogin).not.toHaveBeenCalled()

    confirm.mockResolvedValueOnce('confirm' as never)
    await wrapper.get('[data-test="test-login"]').trigger('click')
    await flushPromises()
    expect(crab.testLogin).toHaveBeenCalledWith('oa_leave', 'alice')
    wrapper.unmount()
  })

  it('未绑定身份的账号测试登录不弹确认框，凭证弹窗也不显示身份提示', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const unbound = makeAccount({ name: 'bob', identity: null })
    const wrapper = await mountPage(AccountsPanel, {
      props: { conch: 'oa_leave', accounts: [unbound], credentials: fields },
      attach: true,
    })
    await flushPromises()

    const editBtn = wrapper.findAll('button').find((b) => b.text().trim() === '填写凭证')!
    await editBtn.trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[data-test="credential-identity-warning"]')).toBeNull()

    await wrapper.get('[data-test="test-login"]').trigger('click')
    await flushPromises()
    expect(confirm).not.toHaveBeenCalled()
    expect(crab.testLogin).toHaveBeenCalledWith('oa_leave', 'bob')
    wrapper.unmount()
  })
})

describe('M15: Reef 8 个校验码说明与 DevTracePanel shared_session_cleared 展示', () => {
  it('issueHints 覆盖 PR #174-#176 的全部 8 个校验码', () => {
    const codes = [
      'SAME_SITE',
      'SAME_AUTH',
      'AUTH_ORIGIN_ADDED',
      'AUTH_ORIGIN',
      'GROUP_NAME',
      'GROUP_DEPTH',
      'UNKNOWN_GROUP',
      'EMPTY_GROUP',
    ]
    for (const code of codes) {
      expect(issueHints[code]?.title).toBeTruthy()
      expect(issueHints[code]?.hint).toBeTruthy()
    }
  })

  it('DevTracePanel 正常展示 index=-1 的 shared_session_cleared 警告并默认展开', async () => {
    const trace: DevTrace = {
      untrusted: true,
      note: '正文已脱敏',
      kind: 'http',
      truncated: false,
      steps: [
        {
          index: -1,
          phase: 'login',
          type: 'request',
          durationMs: 0,
          warnings: [
            {
              code: 'shared_session_cleared',
              message: '带着身份里已有的 Cookie 登录没有成功，已清空这个身份的 Cookie 重新登录。',
            },
          ],
        },
      ],
    }

    const wrapper = await mountPage(DevTracePanel, { props: { trace } })
    await flushPromises()
    expect(wrapper.text()).toContain('登录 · 共享会话已清空重来')
    expect(wrapper.text()).not.toContain('#-1')
    expect(wrapper.get('[data-test="trace-warning"]').text()).toContain('已清空这个身份的 Cookie 重新登录')
    expect(wrapper.get('.el-collapse-item').classes()).toContain('is-active')
    wrapper.unmount()
  })
})

describe('M16: CapabilityToggles 并发开关禁用保护', () => {
  it('单个能力切换进行中禁用所有其他能力开关与分组开关，防止并发竞态', async () => {
    const pendingCap = deferred<{ action: string; disabled: boolean }>()
    crab.setCapability.mockReturnValueOnce(pendingCap.promise)

    const capabilities: CapabilityState[] = [
      { action: 'get_me', title: '当前用户', description: '', method: 'GET', readOnly: true, disabled: false, group: null },
      { action: 'list_leave', title: '请假', description: '', method: 'GET', readOnly: true, disabled: false, group: 'approval/leave' },
    ]
    const wrapper = await mountPage(CapabilityToggles, {
      props: { conch: 'oa_leave', groups: GROUPS, capabilities },
    })
    await flushPromises()

    await wrapper.get('[data-test="cap-switch-get_me"]').trigger('click')
    await flushPromises()

    expect(crab.setCapability).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-test="cap-switch-list_leave"]').classes()).toContain('is-disabled')
    expect(wrapper.get('[data-test="group-switch-approval"]').classes()).toContain('is-disabled')
    expect(wrapper.get('[data-test="group-switch-approval/leave"]').classes()).toContain('is-disabled')

    // 尝试并发点击另一个开关与分组开关，均不触发请求
    await wrapper.get('[data-test="cap-switch-list_leave"]').trigger('click')
    await wrapper.get('[data-test="group-switch-approval"]').trigger('click')
    await flushPromises()
    expect(crab.setCapability).toHaveBeenCalledTimes(1)
    expect(crab.setGroup).not.toHaveBeenCalled()

    pendingCap.resolve({ action: 'get_me', disabled: true })
    await flushPromises()
    expect(wrapper.get('[data-test="cap-switch-list_leave"]').classes()).not.toContain('is-disabled')
    expect(wrapper.get('[data-test="group-switch-approval"]').classes()).not.toContain('is-disabled')
  })
})

describe('Low: DeveloperPanel 切换规则包或能力不可用时重置试跑选择', () => {
  it('props.conch 变更或所选能力被停用/移除时重置 trial.action', async () => {
    const caps: CapabilityState[] = [
      { action: 'a1', title: '能力 1', description: '', method: 'GET', readOnly: true, disabled: false },
      { action: 'a2', title: '能力 2', description: '', method: 'GET', readOnly: true, disabled: false },
    ]
    const wrapper = await mountPage(DeveloperPanel, {
      props: {
        conch: 'local_one',
        driver: 'http',
        debugEnabled: true,
        capabilities: caps,
        accounts: [makeAccount()],
      },
    })
    await flushPromises()

    const vm = wrapper.vm as unknown as { trial: { action: string } }
    vm.trial.action = 'a2'
    await flushPromises()
    expect(vm.trial.action).toBe('a2')

    // 停用 a2 后自动清空 trial.action
    await wrapper.setProps({
      capabilities: [
        { ...caps[0] },
        { ...caps[1], disabled: true },
      ],
    })
    await flushPromises()
    expect(vm.trial.action).toBe('')

    // 切到另一个规则包时也清空 trial.action
    vm.trial.action = 'a1'
    await flushPromises()
    await wrapper.setProps({ conch: 'local_two' })
    await flushPromises()
    expect(vm.trial.action).toBe('')
  })
})
