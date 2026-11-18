import type { DevTraceStep, DevTraceWire } from './api'

/** 与 `HttpCalls.WIRE_BODY_NOTE` 相同。`truncated` 为 true、但没有 `truncation` 时用这一句。 */
export const WIRE_TRUNCATION = '实际发送的正文超过 64 KiB，已截断。'

/** 请求行：方法加 `target`。`target` 已含查询串，不含 fragment。 */
export function requestLine(wire: Pick<DevTraceWire, 'method' | 'target'> | null | undefined): string {
  if (!wire) return ''
  const method = typeof wire.method === 'string' ? wire.method.trim() : ''
  const target = typeof wire.target === 'string' ? wire.target : ''
  return [method, target].filter((part) => part !== '').join(' ')
}

/** 截断说明。优先用接口给出的 `truncation`，否则在 `truncated` 时用固定文案。 */
export function wireTruncation(wire: DevTraceWire | null | undefined): string {
  if (!wire) return ''
  const note = typeof wire.truncation === 'string' ? wire.truncation.trim() : ''
  if (note) return note
  return wire.truncated ? WIRE_TRUNCATION : ''
}

export function contentTypeText(value: unknown): string {
  return typeof value === 'string' && value.trim() ? value.trim() : '（无）'
}

export function hasMapEntries(value: unknown): boolean {
  return value != null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value as object).length > 0
}

/** 非空的逻辑 query 映射。空对象不占一行。 */
export function hasLogicalQuery(step: Pick<DevTraceStep, 'query'>): boolean {
  return hasMapEntries(step.query)
}
