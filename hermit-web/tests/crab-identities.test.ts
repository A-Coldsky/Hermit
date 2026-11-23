import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

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

  it('身份名字放在可以折行的元素里', async () => {
    const wrapper = await mountPage(IdentitiesView)
    await flushPromises()
    const title = wrapper.get('[data-test="identity-card"] .hm-card-title .name')
    expect(title.text()).not.toBe('')
  })

  it('添加账号：先检查，登录名不一样时警告，确认后才绑定', async () => {
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
      adding: { conch: string }
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
    await vm.confirmAdd()
    await flushPromises()
    expect(crab.bindIdentityMember).toHaveBeenCalledWith('abc123', 'oa_expense', 'zhangsan')
    wrapper.unmount()
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
