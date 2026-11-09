import type { ValidationIssue } from './api'

/** 控制台导入结果里的一条：代码、位置、中文原因各出现一次。 */
export interface ShownImportIssue {
  code: string
  location: string
  reason: string
}

/**
 * 解析失败的 message 常常已经是「路径: 缺少 路径」。位置单独显示路径后，原因里去掉这层前缀，
 * 避免字段名连着出现三次。能从 message 或 `line` 定位到行时，位置写「第 N 行」。
 */
export function formatImportIssue(issue: Partial<ValidationIssue> | null | undefined): ShownImportIssue {
  const code = text(issue?.code) || '校验'
  const path = text(issue?.path)
  const message = text(issue?.message)
  const line = positiveLine(issue?.line) ?? lineIn(message)
  const reason = withoutLocatedLine(stripPathPrefix(path, message), line)
  const location = [line === undefined ? '' : `第 ${line} 行`, path].filter(Boolean).join(' ')
  return {
    code,
    location: location || '文档',
    reason,
  }
}

function text(value: string | null | undefined): string {
  return typeof value === 'string' ? value.trim() : ''
}

function positiveLine(value: number | null | undefined): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : undefined
}

function lineIn(message: string): number | undefined {
  const match = /第\s*(\d+)\s*行/.exec(message)
  if (!match) return undefined
  const line = Number(match[1])
  return line > 0 ? line : undefined
}

function stripPathPrefix(path: string, message: string): string {
  if (!path || !message.startsWith(`${path}:`)) return message
  const rest = message.slice(path.length + 1).trim()
  return rest || message
}

function withoutLocatedLine(reason: string, line: number | undefined): string {
  if (line === undefined) return reason
  const next = reason.replace(new RegExp(`（第\\s*${line}\\s*行）`), '').trim()
  return next || reason
}
