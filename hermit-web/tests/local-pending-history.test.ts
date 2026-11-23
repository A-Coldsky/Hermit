import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { formatTime } from '../shared/format'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  conches: vi.fn(),
  localConches: vi.fn(),
  pendingLocalOverwrites: vi.fn(),
  localOverrideGrants: vi.fn(),
  revokeLocalOverrideGrant: vi.fn(),
  confirmPendingOverwrite: vi.fn(),
  rejectPendingOverwrite: vi.fn(),
  localConchHistory: vi.fn(),
  restoreLocalConch: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))
vi.mock('../apps/crab-console/src/status', () => ({ refreshPending: vi.fn() }))

import ConchesView from '../apps/crab-console/src/views/ConchesView.vue'

const sha = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
const baseSha = 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'

const pending = (over: Record<string, unknown> = {}) => ({
  conch: 'local_ping',
  sha256: sha,
  baseSha,
  addedDestinations: ['https://b.example:443', 'https://oauth.example:443'],
  fromVersion: '1.0.0',
  toVersion: '1.2.0',
  keyName: 'agent-dev',
  submittedAt: '2026-11-18T07:10:00.000Z',
  expiresAt: '2026-11-18T07:40:00.000Z',
  ...over,
})

const localConch = (over: Record<string, unknown> = {}) => ({
  conch: 'local_ping',
  version: '1.0.0',
  title: '本地探测',
  capabilityCount: 1,
  subscribed: false,
  accountCount: 1,
  needsConfiguration: false,
  loginPaused: false,
  source: 'LOCAL',
  ...over,
})

function buttons(wrapper: Awaited<ReturnType<typeof mountPage>>, text: string) {
  return wrapper.findAll('button').filter((b) => b.text() === text)
}

beforeEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
  crab.conches.mockResolvedValue({ conches: [] })
  crab.localConches.mockResolvedValue({ conches: [] })
  crab.pendingLocalOverwrites.mockResolvedValue({ pending: [] })
  crab.localOverrideGrants.mockResolvedValue({ grants: [] })
  crab.localConchHistory.mockResolvedValue({ history: [] })
})

describe('待确认覆盖', () => {
  it('卡片列出新增地址、新旧版本、提交的 Key 和到期时间', async () => {
    crab.pendingLocalOverwrites.mockResolvedValue({ pending: [pending()] })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const card = wrapper.get('[data-test="pending-overwrite"]')
    expect(card.text()).toContain('待确认覆盖')
    expect(card.text()).toContain('local_ping')
    expect(card.get('[data-test="pending-versions"]').text()).toContain('当前版本 v1.0.0')
    expect(card.get('[data-test="pending-versions"]').text()).toContain('新版本 v1.2.0')
    expect(card.findAll('[data-test="pending-address"]').map((node) => node.text())).toEqual([
      'https://b.example:443',
      'https://oauth.example:443',
    ])
    expect(card.get('[data-test="pending-key"]').text()).toBe('agent-dev')
    expect(card.get('[data-test="pending-expiry"]').text()).toBe(`${formatTime('2026-11-18T07:40:00.000Z')} 到期`)
    expect(card.text()).toContain('清空这个包的登录会话')
    expect(wrapper.text()).not.toContain('还没有规则包')
  })

  it('旧版本读不出、没有 Key、没有新增地址时照实显示，并标明无法事先确定的地址', async () => {
    crab.pendingLocalOverwrites.mockResolvedValue({
      pending: [pending({
        fromVersion: null,
        keyName: null,
        addedDestinations: [],
        undetermined: true,
        yaml: 'conch: local_ping\n',
      })],
    })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const card = wrapper.get('[data-test="pending-overwrite"]')
    expect(card.get('[data-test="pending-versions"]').text()).toContain('当前版本 读不出来')
    expect(card.get('[data-test="pending-key"]').text()).toBe('—')
    expect(card.get('[data-test="pending-expiry"]').text()).toBe(`${formatTime('2026-11-18T07:40:00.000Z')} 到期`)
    expect(card.text()).toContain('没有新增地址')
    expect(card.get('[data-test="pending-undetermined"]').text()).toBe('还有无法事先确定的地址。')
    expect(card.text()).not.toContain('conch: local_ping')
  })

  it('确认时提交 sha256，成功后卡片消失', async () => {
    const success = vi.spyOn(ElMessage, 'success')
    crab.pendingLocalOverwrites
      .mockResolvedValueOnce({ pending: [pending()] })
      .mockResolvedValue({ pending: [] })
    crab.confirmPendingOverwrite.mockResolvedValue({ conch: 'local_ping', version: '1.2.0' })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="pending-confirm"]').trigger('click')
    await flushPromises()

    expect(crab.confirmPendingOverwrite).toHaveBeenCalledWith('local_ping', sha, false)
    expect(crab.confirmPendingOverwrite.mock.calls[0][1]).not.toBe(baseSha)
    expect(success).toHaveBeenCalledWith('已确认覆盖 local_ping v1.2.0')
    expect(wrapper.find('[data-test="pending-overwrite"]').exists()).toBe(false)
  })

  it('磁盘已变的 409 丢掉卡片，并把 message 留在列表上方', async () => {
    const message = '规则包在等待确认期间被改过，请让 agent 重新保存。'
    const success = vi.spyOn(ElMessage, 'success')
    const error = vi.spyOn(ElMessage, 'error')
    crab.pendingLocalOverwrites
      .mockResolvedValueOnce({ pending: [pending()] })
      .mockResolvedValue({ pending: [] })
    crab.confirmPendingOverwrite.mockRejectedValue(new ApiError(409, 'CONFLICT', message))
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="pending-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="pending-overwrite"]').exists()).toBe(false)
    expect(wrapper.get('[data-test="pending-dropped"]').text()).toBe(message)
    expect(success).not.toHaveBeenCalled()
    expect(error).not.toHaveBeenCalled()

    await buttons(wrapper, '刷新')[0].trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="pending-dropped"]').exists()).toBe(false)
  })

  it('待确认内容变了的 409 保留卡片，再次确认用刷新后的 sha256', async () => {
    const message = '待确认的内容已经变了，请刷新后再确认。'
    const nextSha = 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
    const success = vi.spyOn(ElMessage, 'success')
    crab.pendingLocalOverwrites
      .mockResolvedValueOnce({ pending: [pending()] })
      .mockResolvedValue({ pending: [pending({ sha256: nextSha })] })
    crab.confirmPendingOverwrite
      .mockRejectedValueOnce(new ApiError(409, 'CONFLICT', message))
      .mockResolvedValue({
        conch: 'local_ping',
        version: '1.2.0',
        replaced: true,
        warnings: [],
        previousVersion: '1.0.0',
        backupId: '20261118080000000-1.0.0',
      })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="pending-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-test="pending-conflict"]').text()).toBe(message)
    expect(wrapper.find('[data-test="pending-dropped"]').exists()).toBe(false)
    expect(success).not.toHaveBeenCalled()

    crab.pendingLocalOverwrites.mockResolvedValue({ pending: [] })
    await wrapper.get('[data-test="pending-confirm"]').trigger('click')
    await flushPromises()

    expect(crab.confirmPendingOverwrite.mock.calls.map((call) => call[1])).toEqual([sha, nextSha])
    expect(success).toHaveBeenCalledWith('已确认覆盖 local_ping v1.2.0')
  })

  it('其它失败仍走错误提示，不写成磁盘已变', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    crab.pendingLocalOverwrites.mockResolvedValue({ pending: [pending()] })
    crab.confirmPendingOverwrite.mockRejectedValue(new ApiError(500, 'INTERNAL', '服务器内部错误'))
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="pending-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="pending-conflict"]').exists()).toBe(false)
    expect(error).toHaveBeenCalled()
  })

  it('拒绝调用删除接口，不提交 sha256', async () => {
    const success = vi.spyOn(ElMessage, 'success')
    crab.pendingLocalOverwrites.mockResolvedValueOnce({ pending: [pending()] }).mockResolvedValue({ pending: [] })
    crab.rejectPendingOverwrite.mockResolvedValue({ conch: 'local_ping' })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="pending-reject"]').trigger('click')
    await flushPromises()

    expect(crab.rejectPendingOverwrite).toHaveBeenCalledWith('local_ping')
    expect(crab.confirmPendingOverwrite).not.toHaveBeenCalled()
    expect(success).toHaveBeenCalledWith('已拒绝覆盖 local_ping')
    expect(wrapper.find('[data-test="pending-overwrite"]').exists()).toBe(false)
  })

  it('待确认接口失败时提示错误，不把 404 当成没有待确认', async () => {
    const error = vi.spyOn(ElMessage, 'error')
    crab.pendingLocalOverwrites.mockRejectedValue(new ApiError(404, 'NOT_FOUND', '没有待确认的覆盖。'))
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    expect(wrapper.find('[data-test="pending-overwrite"]').exists()).toBe(false)
    expect(error).toHaveBeenCalled()
    expect(JSON.stringify(error.mock.calls[0][0])).toContain('没有待确认的覆盖。')
  })

  it('勾选本次不再询问后，确认把 true 传给接口', async () => {
    crab.pendingLocalOverwrites.mockResolvedValue({ pending: [pending()] })
    crab.confirmPendingOverwrite.mockResolvedValue({ conch: 'local_ping', version: '1.2.0' })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const box = wrapper.get('[data-test="pending-remember"]')
    expect(box.text()).toContain('本次开发者模式内不再询问')
    await box.get('input[type="checkbox"]').setValue(true)
    await flushPromises()
    await wrapper.get('[data-test="pending-confirm"]').trigger('click')
    await flushPromises()

    expect(crab.confirmPendingOverwrite).toHaveBeenCalledWith('local_ping', sha, true)
  })

  it('列出自动确认，撤销后重新读取', async () => {
    const success = vi.spyOn(ElMessage, 'success')
    crab.localOverrideGrants
      .mockResolvedValueOnce({ grants: [{ conch: 'local_ping', since: '2026-11-19T01:02:03.000Z' }] })
      .mockResolvedValue({ grants: [] })
    crab.revokeLocalOverrideGrant.mockResolvedValue({ conch: 'local_ping', revoked: true })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    expect(wrapper.get('[data-test="override-grants"]').findAll('[data-test="override-grant"]')).toHaveLength(1)
    expect(wrapper.get('[data-test="override-grant-conch"]').text()).toBe('local_ping')
    expect(wrapper.get('[data-test="override-grant-since"]').text()).toContain(formatTime('2026-11-19T01:02:03.000Z'))
    expect(wrapper.get('[data-test="override-grant-since"]').text()).toContain('起不再询问')
    await wrapper.get('[data-test="override-grant-revoke"]').trigger('click')
    await flushPromises()

    expect(crab.revokeLocalOverrideGrant).toHaveBeenCalledWith('local_ping')
    expect(success).toHaveBeenCalledWith('已取消 local_ping 的自动确认')
    expect(wrapper.find('[data-test="override-grant"]').exists()).toBe(false)
  })

  it('没有 sha256 时不能确认', async () => {
    crab.pendingLocalOverwrites.mockResolvedValue({ pending: [pending({ sha256: '' })] })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    expect(wrapper.get('[data-test="pending-confirm"]').attributes('disabled')).toBeDefined()
  })
})

describe('本地包历史', () => {
  const older = { id: '20261118070000000-1.0.0', version: '1.0.0', savedAt: '2026-11-18T07:00:00.000Z' }
  const newer = { id: '20261118080000000-1.1.0', version: '1.1.0', savedAt: null }

  it('每个本地包列出历史，新的在前；正式包不查历史', async () => {
    crab.conches.mockResolvedValue({
      conches: [
        localConch(),
        { ...localConch(), conch: 'legacy_oa', title: 'OA', source: 'REEF' },
      ],
    })
    crab.localConchHistory.mockImplementation(async (conch: string) => {
      if (conch === 'local_ping') return { history: [older, newer] }
      return { history: [] }
    })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    expect(crab.localConchHistory.mock.calls.map((call) => call[0])).toEqual(['local_ping'])
    const lists = wrapper.findAll('[data-test="local-history"]')
    expect(lists).toHaveLength(1)
    const versions = lists[0].findAll('[data-test="history-version"]').map((node) => node.text())
    expect(versions).toEqual(['v1.1.0', 'v1.0.0'])
    expect(lists[0].text()).toContain(formatTime('2026-11-18T07:00:00.000Z'))
    expect(lists[0].text()).toContain(formatTime('2026-11-18T08:00:00.000'))
  })

  it('坏包也列出历史', async () => {
    crab.localConches.mockResolvedValue({
      conches: [
        { conch: 'local_ping', file: 'local_ping.yaml', version: null, loaded: false, broken: true, reason: '无法解析' },
      ],
    })
    crab.localConchHistory.mockResolvedValue({ history: [older] })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    const card = wrapper.get('[data-test="broken-conch"]')
    expect(card.get('[data-test="history-version"]').text()).toBe('v1.0.0')
    expect(crab.localConchHistory).toHaveBeenCalledWith('local_ping')
  })

  it('回退前确认，同意后按 id 调用恢复', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const success = vi.spyOn(ElMessage, 'success')
    crab.conches.mockResolvedValue({ conches: [localConch()] })
    crab.localConchHistory.mockResolvedValue({ history: [newer] })
    crab.restoreLocalConch.mockResolvedValue({ conch: 'local_ping', version: '1.1.0' })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="restore"]').trigger('click')
    await flushPromises()

    expect(confirm.mock.calls[0][0]).toContain('回退 local_ping 到 v1.1.0')
    expect(confirm.mock.calls[0][0]).toContain('回退前会先备份当前版本')
    expect(confirm.mock.calls[0][0]).toContain('地址变化时会清空登录会话')
    expect(confirm.mock.calls[0][1]).toBe('回退 local_ping')
    expect(crab.restoreLocalConch).toHaveBeenCalledWith('local_ping', '20261118080000000-1.1.0')
    expect(success).toHaveBeenCalledWith('已回退 local_ping 到 v1.1.0')
    confirm.mockRestore()
  })

  it('回退后有同网站的规则包时提示一次', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const warning = vi.spyOn(ElMessage, 'warning')
    crab.conches.mockResolvedValue({ conches: [localConch()] })
    crab.localConchHistory.mockResolvedValue({ history: [newer] })
    crab.restoreLocalConch.mockResolvedValue({ conch: 'local_ping', version: '1.1.0', sameSite: ['oa_base'] })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="restore"]').trigger('click')
    await flushPromises()

    expect(warning).toHaveBeenCalledTimes(1)
    expect(JSON.stringify(warning.mock.calls[0][0])).toContain('oa_base')
    confirm.mockRestore()
    warning.mockRestore()
  })

  it('取消回退不调用接口', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    const error = vi.spyOn(ElMessage, 'error')
    crab.conches.mockResolvedValue({ conches: [localConch()] })
    crab.localConchHistory.mockResolvedValue({ history: [older] })
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    await wrapper.get('[data-test="restore"]').trigger('click')
    await flushPromises()

    expect(crab.restoreLocalConch).not.toHaveBeenCalled()
    expect(error).not.toHaveBeenCalled()
    confirm.mockRestore()
  })

  it('历史接口 404 按读失败显示，不说成没有历史', async () => {
    crab.conches.mockResolvedValue({ conches: [localConch()] })
    crab.localConchHistory.mockRejectedValue(new ApiError(404, 'NOT_FOUND', '没有这份历史。'))
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    expect(wrapper.get('[data-test="history-error"]').text()).toBe('没有这份历史。')
    expect(wrapper.get('[data-test="local-history"]').text()).not.toContain('还没有历史版本')
  })

  it('历史接口失败时不说成没有历史', async () => {
    crab.conches.mockResolvedValue({ conches: [localConch()] })
    crab.localConchHistory.mockRejectedValue(new Error('历史读失败'))
    const wrapper = await mountPage(ConchesView)
    await flushPromises()

    expect(wrapper.get('[data-test="history-error"]').text()).toBe('历史读失败')
    expect(wrapper.get('[data-test="local-history"]').text()).not.toContain('还没有历史版本')
  })
})
