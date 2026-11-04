import type { IssuedAgentToken } from './api'

/** 与 Crab `POST /console/api/reef/bootstrap` 约定的格式，字段名不能随意改。 */
export interface CrabBootstrap {
  kind: 'hermit-crab-bootstrap'
  version: 1
  reef: { url: string; token: string; publicKey: string }
  owner: string
  tokenName: string
  createdAt: string
}

export function normalizeReefUrl(value: string): string | null {
  const trimmed = value.trim().replace(/\/+$/, '')
  try {
    const url = new URL(trimmed)
    return url.protocol === 'http:' || url.protocol === 'https:' ? trimmed : null
  } catch {
    return null
  }
}

export function buildBootstrap(issued: IssuedAgentToken, url: string): CrabBootstrap {
  return {
    kind: 'hermit-crab-bootstrap',
    version: 1,
    reef: { url, token: issued.secret, publicKey: issued.publicKey },
    owner: issued.token.owner,
    tokenName: issued.token.name,
    createdAt: issued.token.createdAt ?? new Date().toISOString(),
  }
}

export function bootstrapFileName(bootstrap: CrabBootstrap): string {
  const safe = bootstrap.tokenName.replace(/[^\w\u4e00-\u9fa5-]+/g, '-').replace(/^-+|-+$/g, '') || 'crab'
  return `hermit-crab-${bootstrap.owner}-${safe}.json`
}

export function downloadJson(fileName: string, value: unknown): void {
  const blob = new Blob([JSON.stringify(value, null, 2) + '\n'], { type: 'application/json' })
  const href = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = href
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(href)
}
