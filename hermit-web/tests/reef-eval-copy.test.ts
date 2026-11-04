import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountPage } from './helpers'

const reef = vi.hoisted(() => ({
  login: vi.fn(),
  tideSummary: vi.fn(),
  tideFailures: vi.fn(),
  tideCompare: vi.fn(),
}))

vi.mock('../apps/reef-admin/src/api', () => ({ api: reef }))
vi.mock('../apps/reef-admin/src/auth', () => ({ signIn: vi.fn(), setRole: vi.fn() }))

import LoginView from '../apps/reef-admin/src/views/LoginView.vue'
import ConchStats from '../apps/reef-admin/src/components/ConchStats.vue'
import { versionChangeReasons } from '../apps/reef-admin/src/labels'

function point(success: number, failure: number) {
  return { conch: 'legacy_oa', action: 'list', bucketStart: '2026-11-01T00:00:00Z', success, failure, failureRate: failure / (success + failure), avgMillis: 10, p95Millis: 20 }
}

async function failureCard(failure: number) {
  reef.tideSummary.mockResolvedValue({ series: [point(5, failure)], from: null, to: null })
  const wrapper = await mountPage(ConchStats, { props: { conch: 'legacy_oa', versions: [] } })
  await flushPromises()
  return wrapper.findAll('.stat').find((s) => s.text().includes('失败次数'))!
}

beforeEach(() => {
  vi.clearAllMocks()
  reef.tideFailures.mockResolvedValue({ groups: [] })
  reef.tideCompare.mockResolvedValue({ conch: 'legacy_oa', versions: [] })
})

describe('Reef 只向前之后的文案', () => {
  it('登录页不再承诺自动切回旧版', async () => {
    const wrapper = await mountPage(LoginView)
    expect(wrapper.text()).not.toContain('自动切回')
    expect(wrapper.text()).toContain('手动更新')
  })

  it('版本变化原因不再写自动回退', () => {
    for (const label of Object.values(versionChangeReasons)) {
      expect(label).not.toContain('自动回退')
    }
  })
})

describe('失败次数统计卡', () => {
  it('没有失败时用中性色，不用成功绿', async () => {
    const card = await failureCard(0)
    expect(card.classes()).toContain('neutral')
    expect(card.classes()).not.toContain('ok')
  })

  it('有失败时用危险色', async () => {
    const card = await failureCard(2)
    expect(card.classes()).toContain('danger')
  })
})

describe('Reef 用户管理错误', () => {
  it('中文 message 原样显示，旧版英文 message 仍翻译', async () => {
    const { userErrorText } = await import('../apps/reef-admin/src/labels')
    expect(userErrorText('用户名已存在')).toBe('用户名已存在')
    expect(userErrorText('username already exists')).toBe('用户名已存在')
    expect(userErrorText('something else')).toBeNull()
  })
})
