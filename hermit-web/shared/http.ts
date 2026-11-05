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
  onUnauthorized?: (error: ApiError) => void
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
    const parsed = parseEnvelope<T>(await response.text())
    if (!response.ok || !parsed || !parsed.success) {
      throw fail(response.status, parsed)
    }
    return parsed.data as T
  }

  async function text(path: string): Promise<string> {
    const response = await fetch(path, { headers: options.headers?.() ?? {} })
    const body = await response.text()
    if (!response.ok) {
      throw fail(response.status, parseEnvelope(body))
    }
    return body
  }

  function fail(status: number, parsed: Envelope<unknown> | null): ApiError {
    const error = new ApiError(
      status,
      parsed?.error?.code ?? 'INTERNAL',
      parsed?.error?.message ?? `请求失败（HTTP ${status}）`,
      parsed?.data ?? null,
      parsed?.traceId ?? null,
    )
    if (status === 401) {
      options.onUnauthorized?.(error)
    }
    return error
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

function parseEnvelope<T>(text: string): Envelope<T> | null {
  try {
    return text ? (JSON.parse(text) as Envelope<T>) : null
  } catch {
    return null
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
