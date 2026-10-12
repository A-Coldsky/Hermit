import { reactive } from 'vue'

const TOKEN_KEY = 'hermit.reef.token'
const EXPIRES_KEY = 'hermit.reef.expiresAt'
const USER_KEY = 'hermit.reef.user'

export const session = reactive({
  token: sessionStorage.getItem(TOKEN_KEY),
  expiresAt: sessionStorage.getItem(EXPIRES_KEY),
  user: sessionStorage.getItem(USER_KEY),
})

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
