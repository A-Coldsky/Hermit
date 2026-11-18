import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from '../apps/crab-console/src/api'

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ success: status < 400, data, error: null, traceId: null }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('本地包待确认与历史的路径', () => {
  it('确认提交 sha256，拒绝、历史和回退走文档里的路径', async () => {
    const calls: { url: string; init?: RequestInit }[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
        const path = String(url)
        calls.push({ url: path, init })
        if (path.endsWith('/pending') && (!init?.method || init.method === 'GET')) return json({ pending: [] })
        if (path.endsWith('/history')) return json({ history: [] })
        if (init?.method === 'DELETE') return json({ conch: 'local_ping' })
        return json({ conch: 'local_ping', version: '1.2.0' })
      }),
    )

    await api.pendingLocalOverwrites()
    await api.confirmPendingOverwrite('local_ping', 'abc123')
    await api.rejectPendingOverwrite('local_ping')
    await api.localConchHistory('local_ping')
    await api.restoreLocalConch('local_ping', '20261118080000000-1.1.0')

    expect(calls.map((call) => [call.init?.method ?? 'GET', call.url])).toEqual([
      ['GET', '/console/api/local-conches/pending'],
      ['POST', '/console/api/local-conches/local_ping/pending/confirm'],
      ['DELETE', '/console/api/local-conches/local_ping/pending'],
      ['GET', '/console/api/local-conches/local_ping/history'],
      ['POST', '/console/api/local-conches/local_ping/history/20261118080000000-1.1.0/restore'],
    ])
    expect(calls[1].init?.body).toBe(JSON.stringify({ sha256: 'abc123' }))
    const headers = calls[1].init?.headers as Record<string, string>
    expect(headers['Content-Type']).toBe('application/json')
    expect(headers['X-Hermit-Console']).toBe('1')
  })
})
