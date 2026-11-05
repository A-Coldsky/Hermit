import { reactive } from 'vue'
import type { RouteLocationNormalized, RouteLocationRaw } from 'vue-router'
import { api } from './api'

/** 后端对「没带或带了过期 Cookie」和「口令错误」都回 401 `AUTH_FAILED`，只能靠文案区分。 */
export const LOGIN_REQUIRED = '需要控制台登录。'

export const consoleSession = reactive({ checked: false, required: false, authenticated: false })

export async function refreshSession(): Promise<void> {
  const state = await api.session()
  consoleSession.required = state.required
  consoleSession.authenticated = state.authenticated
  consoleSession.checked = true
}

export function markSignedIn(): void {
  consoleSession.required = true
  consoleSession.authenticated = true
  consoleSession.checked = true
}

/** 服务端说要登录，就说明已经设置了口令，即使启动时读到的是未设置。 */
export function markSignedOut(): void {
  consoleSession.required = true
  consoleSession.authenticated = false
  consoleSession.checked = true
}

export function safeRedirect(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/'
}

/** 没设置口令时不出现登录页；读不到会话状态时放行，由各页面自己的请求报错。 */
export async function sessionGuard(to: RouteLocationNormalized): Promise<true | RouteLocationRaw> {
  if (!consoleSession.checked) {
    try {
      await refreshSession()
    } catch {
      return true
    }
  }
  const signedIn = !consoleSession.required || consoleSession.authenticated
  if (to.name === 'login') {
    return signedIn ? safeRedirect(to.query.redirect) : true
  }
  if (!signedIn) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  return true
}
