import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ApiError } from '../shared/http'
import { session, signIn } from '../apps/reef-admin/src/auth'

const reef = vi.hoisted(() => ({ changePassword: vi.fn() }))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))

import PasswordDialog from '../apps/reef-admin/src/components/PasswordDialog.vue'

async function mountDialog() {
  const blank = { render: () => null }
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: blank },
      { path: '/login', name: 'login', component: blank },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(PasswordDialog, {
    props: { modelValue: true },
    attachTo: document.body,
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

async function fill(oldPassword: string, newPassword: string) {
  const inputs = Array.from(document.body.querySelectorAll<HTMLInputElement>('.el-dialog input'))
  const values = [oldPassword, newPassword, newPassword]
  inputs.forEach((input, index) => {
    input.value = values[index]
    input.dispatchEvent(new Event('input'))
  })
  await flushPromises()
  const save = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.el-dialog button')).find((button) =>
    button.textContent?.includes('保存'),
  )
  save?.click()
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  signIn('old-jwt', '2099-01-01T00:00:00Z', 'admin')
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('Reef 修改密码', () => {
  it('成功后清掉本地令牌并回到登录页：服务端已作废旧令牌', async () => {
    reef.changePassword.mockResolvedValue({ username: 'admin' })
    const { router } = await mountDialog()

    await fill('old-password', 'new-password-1')

    expect(reef.changePassword).toHaveBeenCalledWith('old-password', 'new-password-1')
    expect(session.token).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(document.body.textContent).toContain('请用新密码重新登录')
  })

  it('当前密码不对时留在原页，不退出登录', async () => {
    reef.changePassword.mockRejectedValue(new ApiError(400, 'INVALID_INPUT', '当前密码不正确'))
    const { router } = await mountDialog()

    await fill('bad-password', 'new-password-1')

    expect(session.token).toBe('old-jwt')
    expect(router.currentRoute.value.path).toBe('/')
  })
})
