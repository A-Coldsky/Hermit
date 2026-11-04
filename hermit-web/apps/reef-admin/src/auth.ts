import { computed, reactive } from 'vue'

const TOKEN_KEY = 'hermit.reef.token'
const EXPIRES_KEY = 'hermit.reef.expiresAt'
const USER_KEY = 'hermit.reef.user'
const ROLE_KEY = 'hermit.reef.role'

export type Role = 'ADMIN' | 'USER'

export const session = reactive({
  token: sessionStorage.getItem(TOKEN_KEY),
  expiresAt: sessionStorage.getItem(EXPIRES_KEY),
  user: sessionStorage.getItem(USER_KEY),
  role: sessionStorage.getItem(ROLE_KEY) as Role | null,
})

/** 角色未知时按普通用户处理，只影响按钮显示；权限以服务端为准。 */
export const isAdmin = computed(() => session.role === 'ADMIN')

export function setRole(role: Role): void {
  session.role = role
  sessionStorage.setItem(ROLE_KEY, role)
}

export function signIn(token: string, expiresAt: string, user: string): void {
  session.token = token
  session.expiresAt = expiresAt
  session.user = user
  sessionStorage.setItem(TOKEN_KEY, token)
  sessionStorage.setItem(EXPIRES_KEY, expiresAt)
  sessionStorage.setItem(USER_KEY, user)
}

export function signOut(): void {
  session.token = null
  session.expiresAt = null
  session.user = null
  session.role = null
  sessionStorage.removeItem(ROLE_KEY)
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(EXPIRES_KEY)
  sessionStorage.removeItem(USER_KEY)
}

export function signedIn(): boolean {
  if (!session.token) {
    return false
  }
  if (session.expiresAt && new Date(session.expiresAt).getTime() <= Date.now()) {
    signOut()
    return false
  }
  return true
}
