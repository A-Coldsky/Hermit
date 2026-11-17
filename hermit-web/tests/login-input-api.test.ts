import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../apps/crab-console/src/api'
import { ApiError } from '../shared/http'

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: status < 400, data, error: null, traceId: null }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('验证码控制台接口路径', () => {
  it('接在现有账号路径下，图片带时间戳并且不进缓存', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-17T08:00:00Z'))
    const calls: { url: string; init?: RequestInit }[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
        const path = String(url)
        calls.push({ url: path, init })
        if (path.includes('/login-input/image')) {
          return new Response(Uint8Array.from([137, 80, 78, 71]), {
            status: 200,
            headers: { 'Content-Type': 'image/png' },
          })
        }
        if (init?.method === 'DELETE') return json({ abandoned: true })
        if (init?.method === 'POST' && path.endsWith('/login-input/resend')) return json({})
        if (init?.method === 'POST') return json({ loggedIn: true })
        return json({
          kind: 'image',
          label: '图片验证码',
          hint: null,
          expiresAt: '2026-11-17T08:05:00Z',
          hasImage: true,
          canResend: true,
          resendAfter: 2,
        })
      }),
    )

    await api.loginInput('legacy_oa', 'alice')
    const image = await api.loginInputImage('legacy_oa', 'alice')
    await api.submitLoginInput('legacy_oa', 'alice', ' ab ')
    await api.resendLoginInput('legacy_oa', 'alice')
    await api.abandonLoginInput('legacy_oa', 'alice')

    expect(image.size).toBe(4)
    expect(image.type).toBe('image/png')
    expect(calls.map((call) => call.url)).toEqual([
      '/console/api/conches/legacy_oa/accounts/alice/login-input',
      `/console/api/conches/legacy_oa/accounts/alice/login-input/image?t=${Date.now()}`,
      '/console/api/conches/legacy_oa/accounts/alice/login-input',
      '/console/api/conches/legacy_oa/accounts/alice/login-input/resend',
      '/console/api/conches/legacy_oa/accounts/alice/login-input',
    ])
    expect(calls[0].init?.method).toBe('GET')
    expect(calls[0].init?.headers).toMatchObject({ 'X-Hermit-Console': '1' })
    expect(calls[1].init?.cache).toBe('no-store')
    expect(calls[1].init?.headers).toMatchObject({ 'X-Hermit-Console': '1' })
    expect(calls[2].init?.method).toBe('POST')
    expect(JSON.parse(String(calls[2].init?.body))).toEqual({ value: ' ab ' })
    expect(calls[3].init?.method).toBe('POST')
    expect(calls[4].init?.method).toBe('DELETE')
  })

  it('图片接口的错误信封会变成 ApiError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            success: false,
            data: null,
            error: { code: 'NOT_FOUND', message: '没有待输入的登录' },
            traceId: null,
          }),
          { status: 404 },
        ),
      ),
    )

    await expect(api.loginInputImage('legacy_oa', 'alice')).rejects.toMatchObject({
      status: 404,
      code: 'NOT_FOUND',
      message: '没有待输入的登录',
    })
    await expect(api.loginInputImage('legacy_oa', 'alice')).rejects.toBeInstanceOf(ApiError)
  })
})
