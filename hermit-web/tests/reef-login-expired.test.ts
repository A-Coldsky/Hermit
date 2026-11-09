import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../shared/http'
import { api } from '../apps/reef-admin/src/api'
import {
  consumeExpiredRedirect,
  markExpiredRedirect,
  session,
  signIn,
  signOut,
} from '../apps/reef-admin/src/auth'
import { router } from '../apps/reef-admin/src/router'
import LoginView from '../apps/reef-admin/src/views/LoginView.vue'
import { mountPage } from './helpers'

const PAST = '2000-01-01T00:00:00.000Z'
const FUTURE = '2099-01-01T00:00:00.000Z'
const SIGNED_OUT = '请先登录。使用部署时配置的管理员账号'
const EXPIRED = '会话已过期'

function storageEntries(): string[] {
  return Object.keys(sessionStorage).sort().map((key) => `${key}=${sessionStorage.getItem(key)}`)
}

function unauthorized(): Response {
  return new Response(
    JSON.stringify({
      success: false,
      data: null,
      error: { code: 'UNAUTHORIZED', message: '登录已失效' },
      traceId: 't-1',
    }),
    { status: 401, headers: { 'Content-Type': 'application/json' } },
  )
}

interface LoginPage {
  lead: string
  warning: string | null
}

async function openLogin(path = '/login'): Promise<LoginPage> {
  const wrapper = await mountPage(LoginView, { path })
  const alert = wrapper.find('.expired')
  const page: LoginPage = {
    lead: wrapper.get('.lead').text(),
    warning: alert.exists() ? alert.get('.el-alert__title').text() : null,
  }
  wrapper.unmount()
  return page
}

beforeEach(() => {
  consumeExpiredRedirect()
  signOut()
})

afterEach(() => {
  consumeExpiredRedirect()
  signOut()
  vi.unstubAllGlobals()
})

describe('Reef 登录页文案', () => {
  it('直接打开登录页仍是请先登录', async () => {
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })

  it('地址栏带 expired 也不会固定成过期文案', async () => {
    expect(await openLogin('/login?expired=1')).toEqual({ lead: SIGNED_OUT, warning: null })
  })

  it('过期提示在表单上方，用 warning 样式和图标', async () => {
    markExpiredRedirect()
    const wrapper = await mountPage(LoginView)
    const alert = wrapper.get('.panel-stack .el-alert')
    const form = wrapper.get('form')

    expect(alert.classes()).toContain('el-alert--warning')
    expect(alert.classes()).toContain('is-light')
    expect(alert.attributes('role')).toBe('alert')
    expect(alert.find('.el-alert__icon svg').exists()).toBe(true)
    expect(alert.find('.el-alert__close-btn').exists()).toBe(false)
    expect(alert.get('.el-alert__title').text()).toBe(EXPIRED)
    expect(wrapper.get('.lead').text()).toBe(SIGNED_OUT)
    expect(form.text()).not.toContain(EXPIRED)
    expect(form.element.contains(alert.element)).toBe(false)
    expect(alert.element.compareDocumentPosition(form.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(wrapper.get('.expired').get('.el-alert').element).toBe(alert.element)
    wrapper.unmount()
  })

  it('过期跳转只影响这一次挂载，再进登录页回到请先登录', async () => {
    markExpiredRedirect()
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: EXPIRED })

    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
    expect(await openLogin('/login?expired=1')).toEqual({ lead: SIGNED_OUT, warning: null })
  })

  it('过期原因不写入 sessionStorage', () => {
    signIn('token', FUTURE, 'admin')
    const before = storageEntries()
    markExpiredRedirect()
    expect(storageEntries()).toEqual(before)
    expect(before.join('\n')).not.toContain('expired')
  })

  it('主动退出后仍是请先登录', async () => {
    signIn('token', FUTURE, 'admin')
    signOut()
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })
})

describe('Reef 路由守卫：本地 expiresAt', () => {
  it('从未登录时去登录页，不标记过期', async () => {
    await router.push('/tide')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query).toEqual({ redirect: '/tide' })
    expect(consumeExpiredRedirect()).toBe(false)
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })

  it('过点后清会话并让这一次登录页显示会话已过期', async () => {
    signIn('token', PAST, 'admin')
    await router.push('/conches')

    expect(session.token).toBeNull()
    expect(sessionStorage.getItem('hermit.reef.token')).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query).toEqual({ redirect: '/conches' })
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: EXPIRED })
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })

  it('从首页过期跳转也不把原因写进 query', async () => {
    signIn('token', PAST, 'admin')
    await router.push('/')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query).toEqual({})
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: EXPIRED })
  })

  it('未过期的会话留在原页面', async () => {
    signIn('token', FUTURE, 'admin')
    await router.push('/settings')

    expect(router.currentRoute.value.name).toBe('settings')
    expect(session.token).toBe('token')
    expect(consumeExpiredRedirect()).toBe(false)
  })

  it('自己打开登录页时，即使本地令牌已过期也不改文案', async () => {
    signIn('token', FUTURE, 'admin')
    await router.push('/settings')
    session.expiresAt = PAST
    sessionStorage.setItem('hermit.reef.expiresAt', PAST)

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('login')
    expect(session.token).toBeNull()
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })

  it('删掉令牌后再打开页面仍是请先登录', async () => {
    signIn('token', FUTURE, 'admin')
    session.token = null
    sessionStorage.removeItem('hermit.reef.token')

    await router.push('/tide')

    expect(session.token).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query).toEqual({ redirect: '/tide' })
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })
})

describe('Reef 401：仍持有令牌', () => {
  it('未处理告警 401 清会话并让登录页显示会话已过期', async () => {
    signIn('token', FUTURE, 'admin')
    await router.push('/alerts')
    vi.stubGlobal('fetch', vi.fn(async () => unauthorized()))

    await expect(api.alerts('open')).rejects.toBeInstanceOf(ApiError)
    await flushPromises()

    expect(session.token).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query).toEqual({ redirect: '/alerts' })
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: EXPIRED })
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })

  it('没有令牌时的 401 留在登录页，文案不变', async () => {
    await router.push('/login')
    vi.stubGlobal('fetch', vi.fn(async () => unauthorized()))

    await expect(api.login('admin', 'wrong')).rejects.toBeInstanceOf(ApiError)
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('login')
    expect(consumeExpiredRedirect()).toBe(false)
    expect(await openLogin()).toEqual({ lead: SIGNED_OUT, warning: null })
  })
})
