import { flushPromises } from '@vue/test-utils'
import { ElMessage, ElMessageBox } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  testLogin: vi.fn(),
  loginInput: vi.fn(),
  loginInputImage: vi.fn(),
  submitLoginInput: vi.fn(),
  resendLoginInput: vi.fn(),
  abandonLoginInput: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab }))

import AccountsPanel from '../apps/crab-console/src/components/AccountsPanel.vue'
import type { Account, LoginInput } from '../apps/crab-console/src/api'

function account(name: string, extra: Partial<Account> = {}): Account {
  return {
    name,
    default: name === 'alice',
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
    ...extra,
  }
}

function prompt(overrides: Partial<LoginInput> = {}): LoginInput {
  return {
    kind: 'image',
    label: '图片验证码',
    hint: '输入图中 4 位字符',
    expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
    hasImage: true,
    canResend: true,
    resendAfter: 0,
    ...overrides,
  }
}

function control(id: string): HTMLElement {
  const found = document.body.querySelector(`[data-test="${id}"]`)
  if (!(found instanceof HTMLElement)) throw new Error(`找不到 ${id}`)
  return found
}

function controls(id: string): HTMLElement[] {
  return [...document.body.querySelectorAll(`[data-test="${id}"]`)].filter((node) => node instanceof HTMLElement)
}

function codeInput(): HTMLInputElement {
  const found = control('login-input-value')
  if (found instanceof HTMLInputElement) return found
  const inner = found.querySelector('input')
  if (inner instanceof HTMLInputElement) return inner
  throw new Error('没有验证码输入框')
}

async function typeCode(value: string) {
  const input = codeInput()
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

async function click(id: string) {
  control(id).click()
  await flushPromises()
}

async function mountPanel(accounts: Account[]) {
  const wrapper = await mountPage(AccountsPanel, {
    attach: true,
    props: { conch: 'legacy_oa', accounts, credentials: [] },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
  vi.setSystemTime(new Date('2026-11-17T08:00:00Z'))
  vi.clearAllMocks()
  crab.loginInput.mockResolvedValue(prompt())
  crab.loginInputImage.mockResolvedValue(new Blob([Uint8Array.from([137, 80, 78, 71])], { type: 'image/png' }))
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:captcha')
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('Crab 账号页验证码', () => {
  it('没有待输入时不显示验证码，也不去取', async () => {
    const wrapper = await mountPanel([account('alice')])

    expect(document.body.textContent).not.toContain('需要验证码')
    expect(crab.loginInput).not.toHaveBeenCalled()
    expect(crab.loginInputImage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('待输入时显示提示、图片、输入框、提交、换一张和过期倒计时', async () => {
    const wrapper = await mountPanel([
      account('alice', { loginInputRequired: true, loginInputExpiresAt: '2026-11-17T08:05:00Z' }),
    ])

    const row = control('login-input-required')
    expect(row.textContent).toContain('需要验证码')
    expect(row.textContent).toContain('过期时间')
    expect(row.textContent).toContain('2026')
    expect(document.body.textContent).toContain('需要验证码 · alice')
    expect(document.body.textContent).toContain('图片验证码')
    expect(document.body.textContent).toContain('输入图中 4 位字符')
    expect(document.body.textContent).toContain('还剩 5 分 00 秒')
    expect(document.body.textContent).toContain('不会写入凭证')
    const image = control('login-input-image')
    expect(image.getAttribute('src')).toBe('blob:captcha')
    expect(image.getAttribute('alt')).toBe('验证码图片')
    expect(codeInput()).toBeInstanceOf(HTMLInputElement)
    expect(control('login-input-submit').textContent).toContain('提交')
    expect(control('login-input-resend').textContent).toContain('换一张')
    expect(control('login-input-abandon').textContent).toContain('放弃')
    expect(crab.loginInput).toHaveBeenCalledWith('legacy_oa', 'alice')
    expect(crab.loginInputImage).toHaveBeenCalledWith('legacy_oa', 'alice')
    expect(URL.createObjectURL).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('短信验证码不取图片，按钮是重新发送', async () => {
    crab.loginInput.mockResolvedValue(
      prompt({ kind: 'code', label: '短信验证码', hint: '输入短信里的 6 位数字', hasImage: false, resendAfter: 60 }),
    )
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    expect(document.body.textContent).toContain('短信验证码')
    expect(document.body.textContent).toContain('输入短信里的 6 位数字')
    expect(document.body.querySelector('[data-test="login-input-image"]')).toBeNull()
    expect(control('login-input-resend').textContent).toContain('重新发送（60 秒）')
    expect((control('login-input-resend') as HTMLButtonElement).disabled).toBe(true)
    expect(crab.loginInputImage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('图片没有加载出来时仍可输入，并提示换一张', async () => {
    crab.loginInputImage.mockRejectedValue(new ApiError(500, 'INTERNAL', '图片读不出来'))
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    expect(document.body.textContent).toContain('验证码图片没有加载出来，可以换一张')
    expect(document.body.querySelector('[data-test="login-input-image"]')).toBeNull()
    expect(codeInput()).toBeInstanceOf(HTMLInputElement)
    wrapper.unmount()
  })

  it('读取失败时不再停在正在读取', async () => {
    crab.loginInput.mockRejectedValue(new ApiError(500, 'INTERNAL', '内部错误'))
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    expect(document.body.textContent).toContain('没有读到验证码，可以稍后再试')
    expect(document.body.textContent).not.toContain('正在读取验证码')
    expect(crab.loginInputImage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('图片步骤没有图片时不发图片请求', async () => {
    crab.loginInput.mockResolvedValue(prompt({ hasImage: false }))
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    expect(document.body.textContent).toContain('没有验证码图片')
    expect(crab.loginInputImage).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('次数用尽时不能换一张', async () => {
    crab.loginInput.mockResolvedValue(prompt({ canResend: false, resendAfter: 0 }))
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    const button = control('login-input-resend') as HTMLButtonElement
    expect(button.disabled).toBe(true)
    expect(button.textContent).toContain('换一张')
    expect(button.textContent).not.toContain('秒')
    button.click()
    await flushPromises()
    expect(crab.resendLoginInput).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('冷却结束后可以换一张，并重新取图', async () => {
    crab.loginInput
      .mockResolvedValueOnce(prompt({ resendAfter: 3 }))
      .mockResolvedValueOnce(prompt({ resendAfter: 0, hint: '新的一张' }))
    crab.resendLoginInput.mockResolvedValue({})
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    const button = () => control('login-input-resend') as HTMLButtonElement
    expect(button().disabled).toBe(true)
    expect(button().textContent).toContain('换一张（3 秒）')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(button().textContent).toContain('换一张（2 秒）')
    expect(button().disabled).toBe(true)

    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(button().disabled).toBe(false)
    expect(button().textContent).not.toContain('秒')

    button().click()
    await flushPromises()
    expect(crab.resendLoginInput).toHaveBeenCalledWith('legacy_oa', 'alice')
    expect(crab.loginInput).toHaveBeenCalledTimes(2)
    expect(crab.loginInputImage).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('新的一张')
    wrapper.unmount()
  })

  it('过期后停止倒计时，不能提交', async () => {
    crab.loginInput.mockResolvedValue(prompt({ expiresAt: new Date(Date.now() + 65_000).toISOString() }))
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    expect(control('login-input-expires').textContent).toContain('还剩 1 分 05 秒')
    await vi.advanceTimersByTimeAsync(65_000)
    await flushPromises()
    expect(control('login-input-expires').textContent).toContain('已过期，请重新登录')
    expect((control('login-input-submit') as HTMLButtonElement).disabled).toBe(true)
    expect((control('login-input-resend') as HTMLButtonElement).disabled).toBe(true)
    wrapper.unmount()
  })

  it('提交不裁剪空格，成功后显示登录结果', async () => {
    crab.submitLoginInput.mockResolvedValue({ loggedIn: true })
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    await typeCode(' ab ')
    await click('login-input-submit')

    expect(crab.submitLoginInput).toHaveBeenCalledWith('legacy_oa', 'alice', ' ab ')
    expect(control('login-input-result').textContent).toContain('登录成功')
    expect(control('login-input-result').textContent).toContain('凭证可用，会话已缓存')
    expect(document.body.querySelector('[data-test="login-input-value"]')).toBeNull()
    expect(wrapper.emitted('changed')).toHaveLength(1)
    wrapper.unmount()
  })

  it('空着、超长或带控制字符时不提交', async () => {
    const warning = vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as never)
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    await click('login-input-submit')
    expect(String(warning.mock.calls.at(-1)?.[0])).toContain('请输入验证码')

    await typeCode('a'.repeat(65))
    await click('login-input-submit')
    expect(String(warning.mock.calls.at(-1)?.[0])).toContain('最长 64')

    await typeCode('ab\u0001cd')
    await click('login-input-submit')
    expect(String(warning.mock.calls.at(-1)?.[0])).toContain('控制字符')

    await typeCode('ab\u009F')
    await click('login-input-submit')
    expect(String(warning.mock.calls.at(-1)?.[0])).toContain('控制字符')

    expect(crab.submitLoginInput).not.toHaveBeenCalled()
    crab.submitLoginInput.mockResolvedValue({})
    await typeCode('b'.repeat(64))
    await click('login-input-submit')
    expect(crab.submitLoginInput).toHaveBeenCalledWith('legacy_oa', 'alice', 'b'.repeat(64))
    wrapper.unmount()
  })

  it('接着登录失败时在输入框里显示结果', async () => {
    crab.submitLoginInput.mockRejectedValue(new ApiError(422, 'AUTH_FAILED', '密码错误', null, null, 'req-9', '请求编号，不可查追踪'))
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    await typeCode('wxyz')
    await click('login-input-submit')

    const result = control('login-input-result').textContent ?? ''
    expect(result).toContain('认证失败')
    expect(result).toContain('密码错误')
    expect(result).toContain('req-9')
    expect(result).toContain('请求编号，不可查追踪')
    expect(document.body.querySelector('[data-test="login-input-value"]')).toBeNull()
    wrapper.unmount()
  })

  it('提交后还要再输入一次时换成新的输入，不显示登录失败', async () => {
    crab.loginInput
      .mockResolvedValueOnce(prompt())
      .mockResolvedValueOnce(prompt({ kind: 'code', label: '短信验证码', hint: '输入短信里的 6 位数字', hasImage: false, resendAfter: 60 }))
    crab.submitLoginInput.mockRejectedValue(
      new ApiError(409, 'LOGIN_INPUT_REQUIRED', '这个账号登录需要输入验证码，请到 Crab 控制台的账号页输入后再试'),
    )
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    await typeCode('abcd')
    await click('login-input-submit')

    expect(document.body.textContent).toContain('短信验证码')
    expect(document.body.textContent).toContain('重新发送（60 秒）')
    expect(document.body.querySelector('[data-test="login-input-result"]')).toBeNull()
    expect(document.body.textContent).not.toContain('登录失败')
    expect(crab.loginInputImage).toHaveBeenCalledTimes(1)
    expect(codeInput().value).toBe('')
    wrapper.unmount()
  })

  it('放弃要确认；取消不调用，确认后关掉这次登录', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce(new Error('cancel'))
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])

    await click('login-input-abandon')
    expect(confirm).toHaveBeenCalled()
    expect(crab.abandonLoginInput).not.toHaveBeenCalled()
    expect(document.body.querySelector('[data-test="login-input-value"]')).not.toBeNull()

    confirm.mockResolvedValue('confirm' as never)
    crab.abandonLoginInput.mockResolvedValue({})
    const success = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as never)
    await click('login-input-abandon')
    expect(crab.abandonLoginInput).toHaveBeenCalledWith('legacy_oa', 'alice')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(success).toHaveBeenCalledWith('已放弃这次登录')
    wrapper.unmount()
  })

  it('稍后再说不放弃，点输入验证码会再打开', async () => {
    const wrapper = await mountPanel([account('alice', { loginInputRequired: true })])
    expect(crab.loginInput).toHaveBeenCalledTimes(1)

    await click('login-input-later')
    expect(crab.abandonLoginInput).not.toHaveBeenCalled()

    controls('login-input-open')[0].click()
    await flushPromises()
    expect(crab.loginInput).toHaveBeenCalledTimes(2)
    expect(document.body.textContent).toContain('图片验证码')
    wrapper.unmount()
  })

  it('测试登录遇到需要验证码时直接打开输入框，不弹失败', async () => {
    crab.testLogin.mockRejectedValue(
      new ApiError(409, 'LOGIN_INPUT_REQUIRED', '这个账号登录需要输入验证码，请到 Crab 控制台的账号页输入后再试'),
    )
    const wrapper = await mountPanel([account('alice')])

    await wrapper.get('[data-test="test-login"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('需要验证码 · alice')
    expect(document.body.textContent).toContain('图片验证码')
    expect(document.body.textContent).not.toContain('测试登录 ·')
    expect(crab.loginInput).toHaveBeenCalledWith('legacy_oa', 'alice')
    expect(document.activeElement).toBe(codeInput())
    expect(wrapper.emitted('changed')).toHaveLength(1)
    wrapper.unmount()
  })

  it('测试登录的其它错误仍显示失败，不打开验证码', async () => {
    crab.testLogin.mockRejectedValue(new ApiError(422, 'AUTH_FAILED', '密码错误'))
    const wrapper = await mountPanel([account('alice')])

    await wrapper.get('[data-test="test-login"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('测试登录 · alice')
    expect(document.body.textContent).toContain('认证失败')
    expect(document.body.textContent).toContain('密码错误')
    expect(document.body.querySelector('[data-test="login-input"]')).toBeNull()
    expect(crab.loginInput).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('测试登录成功仍只显示成功，不取验证码', async () => {
    crab.testLogin.mockResolvedValue({ ok: true })
    const wrapper = await mountPanel([account('alice')])

    await wrapper.get('[data-test="test-login"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('登录成功')
    expect(document.body.textContent).not.toContain('需要验证码')
    expect(crab.loginInput).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('多个账号先打开第一个，再点另一个', async () => {
    const wrapper = await mountPanel([
      account('alice', { loginInputRequired: true }),
      account('bob', { loginInputRequired: true, default: false }),
    ])

    expect(document.body.textContent).toContain('需要验证码 · alice')
    expect(controls('login-input-required')).toHaveLength(2)
    expect(crab.loginInput).toHaveBeenCalledTimes(1)

    controls('login-input-open')[1].click()
    await flushPromises()
    expect(document.body.textContent).toContain('需要验证码 · bob')
    expect(crab.loginInput).toHaveBeenCalledWith('legacy_oa', 'bob')
    wrapper.unmount()
  })
})
