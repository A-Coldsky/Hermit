import type { DevTrace, DevTraceTransport, TlsCertificate } from './api'

/**
 * 与 `POST /console/api/tls/ca` 的 `name` 相同。
 * 服务端拒绝时的 message 是 {@link CA_NAME_ERROR}。
 */
export const CA_NAME = /^(?=.{1,64}$)[A-Za-z0-9_.-]+\.(?:pem|crt|cer)$/i

export const CA_NAME_ERROR =
  '名字只能包含字母、数字、点、下划线和连字符，长度 1 到 64，并且以 .pem、.crt 或 .cer 结尾。'

/** 拼在文件名后面，与服务端 `文件名：…` 一致。 */
export const PRIVATE_KEY_DETAIL = '含有私钥，已跳过整个文件'
export const NOT_CERTIFICATE_DETAIL = '不是证书'
export const TOO_LARGE_DETAIL = '超过 64 KiB，已跳过'
export const EXPIRED_PIN = '这张证书已经过期，不能登记。'
export const NOT_YET_VALID = '这张证书尚未生效，不能登记。'

export function caFileError(name: string, detail: string): string {
  const file = name.trim()
  return file ? `${file}：${detail}` : detail
}

const DAY_MS = 24 * 60 * 60 * 1000

const TLS_KINDS = new Set(['TLS_UNTRUSTED', 'TLS_INTERMEDIATE', 'TLS_HOSTNAME', 'TLS_EXPIRED', 'TLS_OTHER'])

const TLS_PHRASES = [
  '上游证书不受信任',
  '上游没有发送中间证书',
  '上游证书与主机名',
  '上游证书已过期',
  '与上游的 TLS 握手失败',
]

export const transportKindLabels: Record<string, string> = {
  DNS: '无法解析地址',
  REFUSED: '拒绝连接',
  TIMEOUT: '连接超时',
  TLS_UNTRUSTED: '证书不受信任',
  TLS_INTERMEDIATE: '缺少中间证书',
  TLS_HOSTNAME: '证书与地址不符',
  TLS_EXPIRED: '证书已过期',
  TLS_OTHER: '安全连接失败',
  OTHER: '连接失败',
}

export const sourceFallbackLabels: Record<string, string> = {
  JDK: 'JDK',
  OS: '操作系统',
  CA_DIR: 'CA 目录',
}

/** 确认指纹时给用户看的限制。不写 CA、PEM。 */
export const PIN_NOTE = '只信任这一张证书。证书到期或这个站点更换证书后，需要重新确认。'

export function isTlsKind(kind: string | null | undefined): boolean {
  return !!kind && (TLS_KINDS.has(kind) || kind.startsWith('TLS_'))
}

/** 错误码仍是 UPSTREAM_ERROR，只能从种类或消息判断是不是 TLS。 */
export function isTlsFailure(input: { message?: string | null; kind?: string | null }): boolean {
  if (isTlsKind(input.kind)) return true
  const message = input.message ?? ''
  return TLS_PHRASES.some((phrase) => message.includes(phrase))
}

export function endpointFromMessage(message: string): { host: string; port: number } | null {
  const paren = /（\s*([A-Za-z0-9._-]+):(\d{1,5})\s*）/.exec(message)
  if (paren) {
    const port = Number(paren[2])
    if (port >= 1 && port <= 65535) return { host: paren[1], port }
  }
  const named = /主机名\s+([A-Za-z0-9._-]+)/.exec(message)
  if (named) return { host: named[1], port: 443 }
  return null
}

export function trustLocation(input: {
  message?: string | null
  kind?: string | null
  host?: string | null
  port?: number | null
}): { path: '/trust'; query: Record<string, string> } | null {
  if (!isTlsFailure(input)) return null
  const parsed = input.message ? endpointFromMessage(input.message) : null
  const host = (input.host || parsed?.host || '').trim()
  const port = input.port || parsed?.port
  const query: Record<string, string> = {}
  if (host) query.host = host
  if (port && port > 0) query.port = String(port)
  return { path: '/trust', query }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

/** 已合入的 `TransportFailure.fields()`：kind、host、port、cause、chain。 */
export function isTraceTransport(value: unknown): value is DevTraceTransport {
  return isRecord(value) && typeof value.kind === 'string' && typeof value.host === 'string' && Array.isArray(value.chain)
}

/**
 * 步骤或轮次上的 `transport`，或包着它的对象。
 * MCP 连接失败先写在 `error.transport`，入库时再抄到该轮的 `transport`。
 */
export function transportOf(value: unknown): DevTraceTransport | null {
  if (isTraceTransport(value)) return value
  if (!isRecord(value)) return null
  return isTraceTransport(value.transport) ? value.transport : null
}

/** 错误 JSON 里不再重复展开证书链。详情由传输卡片单独折叠。 */
export function errorWithoutTransport(error: unknown): unknown {
  if (!isRecord(error) || !('transport' in error)) return error
  const rest = { ...error }
  delete rest.transport
  return rest
}

export function firstTlsTransport(trace: DevTrace | null | undefined): DevTraceTransport | null {
  if (!trace) return null
  const found = [
    transportOf(trace),
    ...(trace.steps ?? []).map((step) => transportOf(step)),
    ...(trace.exchanges ?? []).map((item) => transportOf(item) ?? transportOf(item.error)),
    transportOf(trace.error),
  ]
  return found.find((item): item is DevTraceTransport => !!item && isTlsKind(item.kind)) ?? null
}

export function plainFailure(input: { kind: string; host: string }): string {
  const host = input.host.trim() || '这个地址'
  switch (input.kind) {
    case 'TLS_UNTRUSTED':
      return `这个站点的证书不被本机信任，常见于公司内网。确认地址是 ${host} 无误后，可以信任它。`
    case 'TLS_INTERMEDIATE':
      return `这个站点没有把完整的证书发过来，本机无法确认它。确认地址是 ${host} 无误后，可以信任它。`
    case 'TLS_HOSTNAME':
      return `这个站点出示的证书和地址 ${host} 对不上。请先确认地址没有填错，再决定要不要信任它。`
    case 'TLS_EXPIRED':
      return `这个站点的证书已经过期。确认地址是 ${host} 无误后，可以看一下到期时间；过期的证书信任了也不会放行。`
    case 'TLS_OTHER':
      return `和 ${host} 建立安全连接没有成功。可以打开详情查看原因。`
    case 'DNS':
      return `找不到地址 ${host}。请检查名称是否写对。这通常不是证书问题。`
    case 'REFUSED':
      return `${host} 拒绝了连接。这通常不是证书问题。`
    case 'TIMEOUT':
      return `连接 ${host} 超时，对方没有及时回应。`
    default:
      return `连接 ${host} 失败。`
  }
}

export function expiryState(notAfter: string | null | undefined, now = Date.now()): 'expired' | 'soon' | 'ok' | 'unknown' {
  if (!notAfter) return 'unknown'
  const at = Date.parse(notAfter)
  if (Number.isNaN(at)) return 'unknown'
  if (at <= now) return 'expired'
  if (at - now <= 30 * DAY_MS) return 'soon'
  return 'ok'
}

/** 服务端拒绝登记时的句子。可以登记时返回 null。 */
export function pinRefusal(
  cert: { notBefore?: string | null; notAfter?: string | null } | null | undefined,
  now = Date.now(),
): string | null {
  if (!cert) return null
  if (expiryState(cert.notAfter, now) === 'expired') return EXPIRED_PIN
  if (cert.notBefore) {
    const at = Date.parse(cert.notBefore)
    if (!Number.isNaN(at) && at > now) return NOT_YET_VALID
  }
  return null
}

/** 链的第一张非 CA 是站点证书。只有 CA 时用第一张。 */
export function leafOf(chain: TlsCertificate[]): TlsCertificate | null {
  if (!chain.length) return null
  return chain.find((cert) => cert.ca === false) ?? chain[0]
}

/** 颁发者证书：主题等于站点证书的颁发者，否则取后面第一张 CA。 */
export function issuerCertificate(chain: TlsCertificate[]): TlsCertificate | null {
  const leaf = leafOf(chain)
  if (!leaf) return null
  return (
    chain.find((cert) => cert !== leaf && cert.subject === leaf.issuer) ??
    chain.find((cert) => cert !== leaf && cert.ca !== false) ??
    null
  )
}

export function hasPrivateKey(pem: string): boolean {
  return /-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/.test(pem)
}

export function hasCertificate(pem: string): boolean {
  return pem.includes('-----BEGIN CERTIFICATE-----')
}

export function suggestCaName(filename: string): string {
  const base = filename.split(/[/\\]/).pop() ?? ''
  const cleaned = base.replace(/[^A-Za-z0-9_.-]+/g, '').slice(0, 64)
  return CA_NAME.test(cleaned) ? cleaned : ''
}

export function certificateFileName(subject: string): string {
  const cn = /(?:^|,\s*)CN=([^,]+)/i.exec(subject)?.[1]?.trim() ?? ''
  const slug = cn.replace(/[^A-Za-z0-9_.-]+/g, '').slice(0, 59)
  const name = slug ? `${slug}.pem` : 'issuer.pem'
  return CA_NAME.test(name) ? name : 'issuer.pem'
}

export function sourceLabel(kind: string, label: string | null | undefined): string {
  const text = label?.trim()
  return text || sourceFallbackLabels[kind] || kind
}
