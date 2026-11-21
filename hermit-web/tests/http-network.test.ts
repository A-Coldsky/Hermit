import { afterEach, describe, expect, it, vi } from 'vitest'
import { errorText } from '@shared/format'
import { ApiError, createClient, NetworkError } from '@shared/http'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('请求没有到达服务端', () => {
  it('把浏览器的 Failed to fetch 换成能看懂的中文', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const client = createClient()

    for (const call of [() => client.get('/api/x'), () => client.post('/api/x', {}), () => client.text('/x'), () => client.blob('/x')]) {
      const error = await call().catch((caught: unknown) => caught)
      expect(error).toBeInstanceOf(NetworkError)
      expect(error).not.toBeInstanceOf(ApiError)
      expect(errorText(error)).toContain('连不上服务')
      expect(errorText(error)).not.toContain('Failed to fetch')
    }
  })

  it('不触发登录失效处理', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    const onUnauthorized = vi.fn()
    await createClient({ onUnauthorized }).get('/api/x').catch(() => undefined)
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})
