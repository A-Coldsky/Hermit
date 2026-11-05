import { DOMWrapper, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'
import { ApiError, createClient } from '../shared/http'
import { mountPage } from './helpers'

const crab = vi.hoisted(() => ({
  session: vi.fn(),
  login: vi.fn(),
  changePassword: vi.fn(),
}))

vi.mock('../apps/crab-console/src/api', () => ({ api: crab, onUnauthorized: vi.fn() }))

import PasswordDialog from '../apps/crab-console/src/components/PasswordDialog.vue'
import LoginView from '../apps/crab-console/src/views/LoginView.vue'
import { consoleSession, markSignedOut, sessionGuard } from '../apps/crab-console/src/session'

function field(name: string) {
  const element = document.body.querySelector(`[data-test="${name}"]`)
  if (!element) {
    throw new Error(`missing ${name}`)
  }
  return new DOMWrapper(element)
}

function to(fullPath: string, name: string | undefined, query: Record<string, string> = {}) {
  return { fullPath, name, query } as unknown as RouteLocationNormalized
}

beforeEach(() => {
  vi.clearAllMocks()
  Object.assign(consoleSession, { checked: false, required: false, authenticated: false })
  document.body.innerHTML = ''
})

describe('控制台口令：路由守卫', () => {
  it('没有设置口令时直接放行，访问登录页回到首页', async () => {
    crab.session.mockResolvedValue({ required: false, authenticated: true })

    expect(await sessionGuard(to('/calls', 'calls'))).toBe(true)
    expect(await sessionGuard(to('/login', 'login'))).toBe('/')
    expect(crab.session).toHaveBeenCalledTimes(1)
  })

  it('设置了口令但未登录时去登录页，并带上原来的地址', async () => {
    crab.session.mockResolvedValue({ required: true, authenticated: false })

    expect(await sessionGuard(to('/calls?conch=oa', 'calls'))).toEqual({
      name: 'login',
      query: { redirect: '/calls?conch=oa' },
    })
    expect(await sessionGuard(to('/login', 'login'))).toBe(true)
  })

  it('已登录时访问登录页回到 redirect，不接受站外地址', async () => {
    crab.session.mockResolvedValue({ required: true, authenticated: true })

    expect(await sessionGuard(to('/login', 'login', { redirect: '/apikeys' }))).toBe('/apikeys')
    expect(await sessionGuard(to('/login', 'login', { redirect: '//evil.example' }))).toBe('/')
  })

  it('读不到会话状态时放行，交给页面自己的请求报错', async () => {
    crab.session.mockRejectedValue(new Error('offline'))

    expect(await sessionGuard(to('/', 'overview'))).toBe(true)
  })

  it('服务端要求登录后，即使启动时读到未设置口令也去登录页', async () => {
    crab.session.mockResolvedValue({ required: false, authenticated: true })
    await sessionGuard(to('/', 'overview'))

    markSignedOut()

    expect(await sessionGuard(to('/calls', 'calls'))).toEqual({ name: 'login', query: { redirect: '/calls' } })
  })
})

describe('控制台口令：登录页', () => {
  it('只有口令框；口令错误时提示锁定规则并清空输入', async () => {
    crab.login.mockRejectedValue(new ApiError(401, 'AUTH_FAILED', '口令错误'))
    const wrapper = await mountPage(LoginView, { path: '/login' })

    expect(wrapper.findAll('input')).toHaveLength(1)
    await wrapper.get('[data-test="password"]').setValue('wrong-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(crab.login).toHaveBeenCalledWith('wrong-password')
    expect(wrapper.get('[data-test="failure"]').text()).toContain('连续输错 5 次会锁定 15 分钟')
    expect((wrapper.get('[data-test="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('登录成功后回到原来的页面', async () => {
    crab.login.mockResolvedValue({ ok: true })
    const wrapper = await mountPage(LoginView, { path: '/login?redirect=/apikeys' })

    await wrapper.get('[data-test="password"]').setValue('right-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(consoleSession.authenticated).toBe(true)
    expect(wrapper.vm.$router.currentRoute.value.fullPath).toBe('/apikeys')
  })

  it('写明忘了口令怎么重设', async () => {
    const wrapper = await mountPage(LoginView, { path: '/login' })

    expect(wrapper.text()).toContain('crab console password')
  })
})

describe('控制台口令：修改口令', () => {
  it('两次新口令不一致时不发请求', async () => {
    await mountPage(PasswordDialog, { props: { modelValue: true }, attach: true })
    await flushPromises()

    await field('current').setValue('old-password')
    await field('next').setValue('new-password-1')
    await field('repeat').setValue('new-password-2')
    await field('save-password').trigger('click')
    await flushPromises()

    expect(crab.changePassword).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('两次输入的新口令不一致')
  })

  it('修改成功后要求重新登录', async () => {
    crab.changePassword.mockResolvedValue({ ok: true })
    Object.assign(consoleSession, { checked: true, required: true, authenticated: true })
    await mountPage(PasswordDialog, { props: { modelValue: true }, attach: true })
    await flushPromises()

    await field('current').setValue('old-password')
    await field('next').setValue('new-password')
    await field('repeat').setValue('new-password')
    await field('save-password').trigger('click')
    await flushPromises()

    expect(crab.changePassword).toHaveBeenCalledWith('old-password', 'new-password')
    expect(consoleSession.authenticated).toBe(false)
  })

  it('当前口令不对时提示，不退出登录', async () => {
    crab.changePassword.mockRejectedValue(new ApiError(401, 'AUTH_FAILED', '口令错误'))
    Object.assign(consoleSession, { checked: true, required: true, authenticated: true })
    await mountPage(PasswordDialog, { props: { modelValue: true }, attach: true })
    await flushPromises()

    await field('current').setValue('bad-password')
    await field('next').setValue('new-password')
    await field('repeat').setValue('new-password')
    await field('save-password').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('当前口令不正确')
    expect(consoleSession.authenticated).toBe(true)
  })
})

describe('共享 HTTP 客户端的 401 回调', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('把服务端的错误码和文案交给回调', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: false, data: null, error: { code: 'AUTH_FAILED', message: '需要控制台登录。' }, traceId: 't1' }), {
          status: 401,
        }),
      ),
    )
    const seen: ApiError[] = []
    const http = createClient({ onUnauthorized: (error) => seen.push(error) })

    await expect(http.get('/console/api/conches')).rejects.toMatchObject({ status: 401, message: '需要控制台登录。' })
    expect(seen.map((e) => [e.code, e.message])).toEqual([['AUTH_FAILED', '需要控制台登录。']])
  })

  it('文本接口出错时也读出服务端文案', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ success: false, data: null, error: { code: 'NOT_FOUND', message: '没有这个本地规则包' }, traceId: null }), {
          status: 404,
        }),
      ),
    )
    const http = createClient()

    await expect(http.text('/console/api/local-conches/x/export')).rejects.toMatchObject({ status: 404, message: '没有这个本地规则包' })
  })
})
