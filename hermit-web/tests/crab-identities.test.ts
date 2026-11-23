import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deferred, mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  identities: vi.fn(),
  conches: vi.fn(),
  accounts: vi.fn(),
  createIdentity: vi.fn(),
  renameIdentity: vi.fn(),
  deleteIdentity: vi.fn(),
  checkIdentityMember: vi.fn(),
  bindIdentityMember: vi.fn(),
  unbindIdentityMember: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))

import IdentitiesView from '../apps/crab-console/src/views/IdentitiesView.vue'
import { auditFieldText, configAuditLabels } from '../apps/crab-console/src/labels'

const identity = {
  id: 'abc123',
  name: 'OA · 张三',
  createdAt: '2026-11-23T02:00:00Z',
  members: [
    {
      origin: 'OFFICIAL',
      conch: 'oa_leave',
      account: 'default',
      title: '请假',
      installed: true,
      active: true,
      addresses: ['https://oa.corp.com', 'https://sso.corp.com'],
    },
  ],
  lastCleared: null,
}

describe('身份页面', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    document.body.innerHTML = ''
    crab.identities.mockResolvedValue({ identities: [identity] })
    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'oa_leave', title: '请假', driver: 'http', source: 'REEF' },
        { conch: 'oa_expense', title: '报销', driver: 'http', source: 'REEF' },
        { conch: 'wiki_mcp', title: '知识库', driver: 'mcp', source: 'REEF' },
      ],
    })
    crab.accounts.mockResolvedValue({ accounts: [{ name: 'zhangsan', default: true }] })
    crab.createIdentity.mockResolvedValue({ ...identity, id: 'new', name: '李四', members: [] })
    crab.bindIdentityMember.mockResolvedValue({ allowed: true, problem: null, peers: [], warnings: [] })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('列出身份、成员和它们访问的地址', async () => {
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    const card = wrapper.find('[data-test="identity-card"]')
    expect(card.text()).toContain('OA · 张三')
    expect(card.text()).toContain('oa_leave')
    expect(card.text()).toContain('https://sso.corp.com')
    expect(wrapper.find('[data-test="identity-cleared"]').exists()).toBe(false)
  })

  it('清空重来过的身份显示是哪个账号引起的', async () => {
    crab.identities.mockResolvedValue({
      identities: [{ ...identity, lastCleared: { at: '2026-11-23T03:00:00Z', conch: 'oa_expense', account: 'zhangsan' } }],
    })
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    expect(wrapper.find('[data-test="identity-cleared"]').text()).toContain('oa_expense')
  })

  it('新建身份后重新读取列表', async () => {
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    await wrapper.find('input[data-test="identity-name"]').setValue('  李四 ')
    await wrapper.find('[data-test="identity-create"]').trigger('click')
    await flushPromises()
    expect(crab.createIdentity).toHaveBeenCalledWith('李四')
    expect(crab.identities).toHaveBeenCalledTimes(2)
  })

  it('新建时名字超过 64 个字符：不截断，说明原因，不能新建', async () => {
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    const input = wrapper.find('input[data-test="identity-name"]')
    expect(input.attributes('maxlength')).toBeUndefined()
    expect(wrapper.find('[data-test="identity-name-problem"]').exists()).toBe(false)

    await input.setValue('W'.repeat(65))
    expect((input.element as HTMLInputElement).value).toHaveLength(65)
    expect(wrapper.get('[data-test="identity-name-problem"]').text()).toBe('名字最多 64 个字符，现在是 65 个')
    expect(wrapper.get('[data-test="identity-create"]').attributes('disabled')).toBeDefined()
    await input.trigger('keyup.enter')
    await flushPromises()
    expect(crab.createIdentity).not.toHaveBeenCalled()

    // 首尾空白不算长度，和 Crab 的检查一致。
    await input.setValue(` ${'W'.repeat(64)} `)
    expect(wrapper.find('[data-test="identity-name-problem"]').exists()).toBe(false)
    await wrapper.find('[data-test="identity-create"]').trigger('click')
    await flushPromises()
    expect(crab.createIdentity).toHaveBeenCalledWith('W'.repeat(64))
  })

  it('新建进行中连续按两次回车不会重复创建', async () => {
    const pending = deferred<typeof identity>()
    crab.createIdentity.mockReturnValueOnce(pending.promise)
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    const input = wrapper.find('input[data-test="identity-name"]')
    await input.setValue('张三')
    await input.trigger('keyup.enter')
    await input.trigger('keyup.enter')
    await flushPromises()
    expect(crab.createIdentity).toHaveBeenCalledTimes(1)
    pending.resolve({ ...identity, id: 'n1', name: '张三', members: [] })
    await flushPromises()
  })

  it('身份名字放在可以折行的元素里', async () => {
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    const title = wrapper.get('[data-test="identity-card"] .hm-card-title .name')
    expect(title.text()).not.toBe('')
  })

  it('添加账号：登录名不一样时需要勾选确认且按钮变 warning，确认后才绑定', async () => {
    crab.checkIdentityMember.mockResolvedValue({
      allowed: true,
      problem: null,
      peers: [
        { origin: 'OFFICIAL', conch: 'oa_leave', account: 'default', addresses: ['https://sso.corp.com'], credentials: 'DIFFERENT' },
      ],
      warnings: ['两边填的登录名不一样，确认是同一个人再绑定。'],
    })
    const wrapper = await mountPage(IdentitiesView, { attach: true })
    await flushPromises()
    await wrapper.find('[data-test="identity-add"]').trigger('click')
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      adding: { conch: string; confirmedDifferent: boolean }
      candidates: { conch: string }[]
      pickConch: () => Promise<void>
      confirmAdd: () => Promise<void>
    }
    // 已经在身份里的包和 driver: mcp 的包不在可选项里。
    expect(vm.candidates.map((conch) => conch.conch)).toEqual(['oa_expense'])
    vm.adding.conch = 'oa_expense'
    await vm.pickConch()
    await flushPromises()
    // 只有一个账号时直接选中并检查。
    expect(crab.checkIdentityMember).toHaveBeenCalledWith('abc123', 'oa_expense', 'zhangsan')
    expect(crab.bindIdentityMember).not.toHaveBeenCalled()
    const body = document.body.textContent ?? ''
    expect(body).toContain('登录名不一样')
    expect(body).toContain('两边填的登录名不一样')

    const confirmBtn = document.body.querySelector('[data-test="identity-confirm"]') as HTMLButtonElement
    expect(confirmBtn.disabled).toBe(true)
    expect(confirmBtn.className).toContain('el-button--warning')

    // 未勾选确认时直接调 confirmAdd 也不会绑定
    await vm.confirmAdd()
    expect(crab.bindIdentityMember).not.toHaveBeenCalled()

    // 勾选后才可绑定
    const checkbox = document.body.querySelector('[data-test="identity-confirm-different"] input') as HTMLInputElement
    expect(checkbox).not.toBeNull()
    checkbox.click()
    await flushPromises()
    expect(confirmBtn.disabled).toBe(false)

    await vm.confirmAdd()
    await flushPromises()
    expect(crab.bindIdentityMember).toHaveBeenCalledWith('abc123', 'oa_expense', 'zhangsan')
    wrapper.unmount()
  })

  it('H7: 快速切换规则包与关闭弹窗时忽略过期响应，检查进行中禁用确认按钮', async () => {
    const slowAccountsA = deferred<{ accounts: { name: string; default: boolean }[] }>()
    const fastAccountsB = deferred<{ accounts: { name: string; default: boolean }[] }>()
    const slowCheckB = deferred<{ allowed: boolean; problem: null; peers: []; warnings: [] }>()
    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'oa_a', title: '包 A', driver: 'http', source: 'REEF' },
        { conch: 'oa_b', title: '包 B', driver: 'http', source: 'REEF' },
      ],
    })
    crab.accounts.mockReturnValueOnce(slowAccountsA.promise).mockReturnValueOnce(fastAccountsB.promise)
    crab.checkIdentityMember.mockReturnValueOnce(slowCheckB.promise)

    const wrapper = await mountPage(IdentitiesView, { attach: true })
    await flushPromises()
    await wrapper.find('[data-test="identity-add"]').trigger('click')
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      adding: { open: boolean; conch: string; account: string; accounts: { name: string }[]; checking: boolean; check: unknown }
      pickConch: () => Promise<void>
    }

    vm.adding.conch = 'oa_a'
    const pickA = vm.pickConch()
    vm.adding.conch = 'oa_b'
    const pickB = vm.pickConch()

    fastAccountsB.resolve({ accounts: [{ name: 'bob', default: true }] })
    await flushPromises()
    // 正在检查 B 时确认按钮应禁用
    expect(vm.adding.checking).toBe(true)
    const confirmBtn = document.body.querySelector('[data-test="identity-confirm"]') as HTMLButtonElement
    expect(confirmBtn.disabled).toBe(true)

    // 迟到的 A 响应不能覆盖 B 的账号列表或触发检查
    slowAccountsA.resolve({ accounts: [{ name: 'alice', default: true }] })
    await pickA
    await flushPromises()
    expect(vm.adding.accounts.map((a) => a.name)).toEqual(['bob'])
    expect(crab.checkIdentityMember).toHaveBeenCalledTimes(1)

    // 关闭弹窗后迟到的检查结果应被忽略
    vm.adding.open = false
    await flushPromises()
    slowCheckB.resolve({ allowed: true, problem: null, peers: [], warnings: [] })
    await pickB
    await flushPromises()
    expect(vm.adding.check).toBeNull()
    expect(vm.adding.checking).toBe(false)
    wrapper.unmount()
  })

  it('H9: 身份列表加载失败显示错误和重试而不冒充空态；conches 失败不丢弃已加载身份', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as never)
    crab.identities.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    expect(wrapper.get('[data-test="identities-load-failed"]').text()).toContain('身份列表读取失败')
    expect(wrapper.find('[data-test="identity-empty"]').exists()).toBe(false)

    // 点重试：identities 成功但 conches 失败，已加载的身份应保留展示
    crab.identities.mockResolvedValueOnce({ identities: [identity] })
    crab.conches.mockRejectedValueOnce(new Error('conches boom'))
    await wrapper.get('[data-test="identities-load-failed"] button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="identities-load-failed"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-test="identity-card"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('OA · 张三')
  })

  it('H9 & M14: 过滤停用包、无标题不显示空括号、展示本地徽章、混绑警告不重复、间接关联无悬空冒号', async () => {
    crab.conches.mockResolvedValue({
      conches: [
        { conch: 'oa_disabled', title: '停用包', driver: 'http', source: 'REEF', ruleDisabled: true },
        { conch: 'oa_notitle', title: '', driver: 'http', source: 'LOCAL' },
      ],
    })
    crab.checkIdentityMember.mockResolvedValue({
      allowed: true,
      problem: null,
      peers: [
        { origin: 'OFFICIAL', conch: 'oa_leave', account: 'default', addresses: ['https://sso.corp.com'], credentials: 'SAME' },
        { origin: 'LOCAL', conch: 'oa_portal', account: 'u1', addresses: [], credentials: 'UNKNOWN' },
      ],
      warnings: [],
    })

    const wrapper = await mountPage(IdentitiesView, { attach: true })
    await flushPromises()
    await wrapper.find('[data-test="identity-add"]').trigger('click')
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      adding: { conch: string }
      candidates: { conch: string }[]
      candidateLabel: (c: { conch: string; title: string }) => string
      pickConch: () => Promise<void>
    }
    expect(vm.candidates.map((c) => c.conch)).toEqual(['oa_notitle'])
    expect(vm.candidateLabel({ conch: 'oa_notitle', title: '' })).toBe('oa_notitle')

    vm.adding.conch = 'oa_notitle'
    await vm.pickConch()
    await flushPromises()

    const peers = [...document.body.querySelectorAll('[data-test="identity-peer"]')]
    expect(peers).toHaveLength(2)
    expect(peers[0].textContent).toContain('相同的地址：https://sso.corp.com')
    expect(peers[1].querySelector('[data-test="peer-local-tag"]')?.textContent).toContain('本地')
    expect(peers[1].textContent).toContain('通过身份里的其他成员间接关联')
    expect(peers[1].textContent).not.toContain('相同的地址：')
    // 后端未给混绑警告时，前端兜底给出本地+正式混绑提示
    expect(document.body.textContent).toContain('同时包含本地规则包（未签名）和正式规则包')

    // 若后端已给出本地+正式混绑警告，前端不重复叠加
    crab.checkIdentityMember.mockResolvedValueOnce({
      allowed: true,
      problem: null,
      peers: [
        { origin: 'OFFICIAL', conch: 'oa_leave', account: 'default', addresses: ['https://sso.corp.com'], credentials: 'SAME' },
      ],
      warnings: ['身份将混用本地未签名包与正式包，请注意风险。'],
    })
    await vm.pickConch()
    await flushPromises()
    expect(document.body.textContent).toContain('混用本地未签名包与正式包')
    expect(document.body.textContent).not.toContain('同时包含本地规则包（未签名）和正式规则包')
    wrapper.unmount()
  })

  it('变更操作进行中禁用并发改名/删除/解除', async () => {
    const pendingDel = deferred<void>()
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    crab.deleteIdentity.mockReturnValueOnce(pendingDel.promise)

    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    const delBtn = wrapper.get('[data-test="identity-delete"]')
    await delBtn.trigger('click')
    await flushPromises()
    expect(crab.deleteIdentity).toHaveBeenCalledTimes(1)
    expect(delBtn.attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-test="identity-unbind"]').attributes('disabled')).toBeDefined()

    pendingDel.resolve()
    await flushPromises()
    expect(delBtn.attributes('disabled')).toBeUndefined()
  })

  it('同名的另一份生效时可以绑进同一个身份', async () => {
    // 身份里的是正式包那一份；现在生效的是同名的本地包，它的账号是另一套。
    crab.conches.mockResolvedValue({
      conches: [{ conch: 'oa_leave', title: '请假', driver: 'http', source: 'LOCAL' }],
    })
    const wrapper = await mountPage(IdentitiesView, { attach: true })
    await flushPromises()
    await wrapper.find('[data-test="identity-add"]').trigger('click')
    await flushPromises()
    const vm = wrapper.vm as unknown as { candidates: { conch: string }[] }
    expect(vm.candidates.map((conch) => conch.conch)).toEqual(['oa_leave'])
    wrapper.unmount()
  })

  it('不能绑定时显示原因，确认按钮不执行', async () => {
    crab.checkIdentityMember.mockResolvedValue({
      allowed: false,
      problem: '这个包和身份里的账号没有相同的站点或认证地址',
      peers: [],
      warnings: [],
    })
    const wrapper = await mountPage(IdentitiesView, { attach: true })
    await flushPromises()
    await wrapper.find('[data-test="identity-add"]').trigger('click')
    const vm = wrapper.vm as unknown as {
      adding: { conch: string }
      pickConch: () => Promise<void>
      confirmAdd: () => Promise<void>
    }
    vm.adding.conch = 'oa_expense'
    await vm.pickConch()
    await flushPromises()
    expect(document.body.textContent).toContain('没有相同的站点或认证地址')
    await vm.confirmAdd()
    expect(crab.bindIdentityMember).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('身份的审计文字', () => {
  it('五种操作都有中文名，自动解除的原因换成中文', () => {
    for (const kind of ['IDENTITY_CREATED', 'IDENTITY_RENAMED', 'IDENTITY_DELETED', 'IDENTITY_BOUND', 'IDENTITY_UNBOUND']) {
      expect(configAuditLabels[kind]?.text).toBeTruthy()
    }
    expect(auditFieldText('IDENTITY_UNBOUND', 'address_changed')).toContain('地址变了')
    expect(auditFieldText('IDENTITY_UNBOUND', 'OA · 张三')).toBe('OA · 张三')
    expect(auditFieldText('CONFIG_WRITTEN', 'address_changed')).toBe('address_changed')
  })
})
