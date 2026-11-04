export interface Envelope<T> {
  success: boolean
  data: T | null
  error: { code: string; message: string } | null
  traceId: string | null
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly data: unknown = null,
    readonly traceId: string | null = null,
  ) {
    super(message)
  }
}

export interface ClientOptions {
  headers?: () => Record<string, string>
  onUnauthorized?: () => void
}

export type Query = Record<string, string | number | boolean | null | undefined>

export function createClient(options: ClientOptions = {}) {
  async function request<T>(method: string, path: string, body?: unknown, query?: Query): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json', ...(options.headers?.() ?? {}) }
    let payload: BodyInit | undefined
    if (body instanceof FormData) {
      payload = body
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
      payload = JSON.stringify(body)
    }
    const response = await fetch(withQuery(path, query), { method, headers, body: payload })
    if (response.status === 401) {
      options.onUnauthorized?.()
    }
    const text = await response.text()
    let parsed: Envelope<T> | null = null
    try {
      parsed = text ? (JSON.parse(text) as Envelope<T>) : null
    } catch {
      parsed = null
    }
    if (!response.ok || !parsed || !parsed.success) {
      throw new ApiError(
        response.status,
        parsed?.error?.code ?? 'INTERNAL',
        parsed?.error?.message ?? `请求失败（HTTP ${response.status}）`,
        parsed?.data ?? null,
        parsed?.traceId ?? null,
      )
    }
    return parsed.data as T
  }

  async function text(path: string): Promise<string> {
    const response = await fetch(path, { headers: options.headers?.() ?? {} })
    if (response.status === 401) {
      options.onUnauthorized?.()
    }
    if (!response.ok) {
      throw new ApiError(response.status, 'INTERNAL', `请求失败（HTTP ${response.status}）`)
    }
    return response.text()
  }

  return {
    get: <T>(path: string, query?: Query) => request<T>('GET', path, undefined, query),
    post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
    put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
    patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body ?? {}),
    del: <T>(path: string) => request<T>('DELETE', path),
    upload: <T>(path: string, form: FormData) => request<T>('POST', path, form),
    text,
  }
}

function withQuery(path: string, query?: Query): string {
  if (!query) {
    return path
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  }
  const suffix = params.toString()
  return suffix ? `${path}?${suffix}` : path
}

export function seg(value: string): string {
  return encodeURIComponent(value)
}
