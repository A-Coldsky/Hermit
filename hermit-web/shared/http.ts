export interface Envelope<T> {
  success: boolean
  data: T | null
  error: { code: string; message: string } | null
  traceId: string | null
  /** Present when the failure did not store a trace. Do not open it as a call. */
  requestId?: string | null
  requestNote?: string | null
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly data: unknown = null,
    readonly traceId: string | null = null,
    readonly requestId: string | null = null,
    readonly requestNote: string | null = null,
  ) {
    super(message)
  }
}

/** 请求没有到达服务端，或者连接中途断开。不是 ApiError：没有状态码，也没有信封。 */
export class NetworkError extends Error {
  constructor(readonly cause: unknown) {
    super('连不上服务：服务可能已停止，或者网络断开。请确认服务在运行后重试。')
  }
}

async function send(input: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(input, init)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new NetworkError(error)
  }
}

export interface ClientOptions {
  headers?: () => Record<string, string>
  onUnauthorized?: (error: ApiError) => void
}

export type Query = Record<string, string | number | boolean | null | undefined>

export function createClient(options: ClientOptions = {}) {
  async function request<T>(method: string, path: string, body?: unknown, query?: Query): Promise<T> {
    return (await requestEnvelope<T>(method, path, body, query)).data as T
  }

  async function text(path: string): Promise<string> {
    const response = await send(path, { headers: options.headers?.() ?? {} })
    const body = await response.text()
    if (!response.ok) {
      throw fail(response.status, parseEnvelope(body))
    }
    return body
  }

  /** 二进制响应。不走 JSON 信封；失败时仍按信封解析错误。`cache: no-store` 避免浏览器留下图片。 */
  async function blob(path: string, query?: Query): Promise<Blob> {
    const response = await send(withQuery(path, query), {
      headers: options.headers?.() ?? {},
      cache: 'no-store',
    })
    if (!response.ok) {
      throw fail(response.status, parseEnvelope(await response.text()))
    }
    return response.blob()
  }

  function fail(status: number, parsed: Envelope<unknown> | null): ApiError {
    const error = new ApiError(
      status,
      parsed?.error?.code ?? 'INTERNAL',
      parsed?.error?.message ?? `请求失败（HTTP ${status}）`,
      parsed?.data ?? null,
      parsed?.traceId ?? null,
      parsed?.requestId ?? null,
      parsed?.requestNote ?? null,
    )
    if (status === 401) {
      options.onUnauthorized?.(error)
    }
    return error
  }

  async function requestEnvelope<T>(method: string, path: string, body?: unknown, query?: Query): Promise<Envelope<T>> {
    const headers: Record<string, string> = { Accept: 'application/json', ...(options.headers?.() ?? {}) }
    let payload: BodyInit | undefined
    if (body instanceof FormData) {
      payload = body
    } else if (body instanceof Blob) {
      // 请求体就是文件内容。
      headers['Content-Type'] = body.type || 'application/octet-stream'
      payload = body
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
      payload = JSON.stringify(body)
    }
    const response = await send(withQuery(path, query), { method, headers, body: payload })
    const parsed = parseEnvelope<T>(await response.text())
    if (!response.ok || !parsed || !parsed.success) {
      throw fail(response.status, parsed)
    }
    return parsed
  }

  return {
    get: <T>(path: string, query?: Query) => request<T>('GET', path, undefined, query),
    post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
    postEnvelope: <T>(path: string, body?: unknown) => requestEnvelope<T>('POST', path, body ?? {}),
    put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
    patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body ?? {}),
    del: <T>(path: string) => request<T>('DELETE', path),
    upload: <T>(path: string, form: FormData) => request<T>('POST', path, form),
    raw: <T>(path: string, file: Blob, query?: Query) => request<T>('POST', path, file, query),
    text,
    blob,
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
