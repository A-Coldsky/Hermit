import { ElMessage } from 'element-plus'
import { ApiError } from './http'

const dateTime = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : dateTime.format(date)
}

export function relativeTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const diff = Date.now() - new Date(value).getTime()
  if (Number.isNaN(diff)) {
    return value
  }
  const minutes = Math.round(diff / 60000)
  if (Math.abs(minutes) < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.round(minutes / 60)
  if (hours < 48) {
    return `${hours} 小时前`
  }
  return `${Math.round(hours / 24)} 天前`
}

export function percent(value: number | string | null | undefined, digits = 1): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const number = typeof value === 'string' ? Number(value) : value
  return Number.isFinite(number) ? `${(number * 100).toFixed(digits)}%` : '—'
}

export function millis(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const number = typeof value === 'string' ? Number(value) : value
  if (!Number.isFinite(number)) {
    return '—'
  }
  return number >= 1000 ? `${(number / 1000).toFixed(2)} s` : `${Math.round(number)} ms`
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    return error.code && error.code !== 'INTERNAL' ? `${error.message}（${error.code}）` : error.message
  }
  if (error instanceof Error) {
    return error.message
  }
  return '操作失败'
}

export function notifyError(error: unknown): void {
  ElMessage.error({ message: errorText(error), grouping: true })
}

export async function copyText(value: string, label = '已复制'): Promise<void> {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(label)
  } catch {
    ElMessage.warning('浏览器不允许写入剪贴板，请手动复制')
  }
}

export const errorCodeLabels: Record<string, string> = {
  INVALID_INPUT: '输入无效',
  AUTH_FAILED: '认证失败',
  SESSION_EXPIRED: '会话失效',
  UPSTREAM_ERROR: '老系统错误',
  RULE_MISMATCH: '规则不匹配',
  TIMEOUT: '超时',
  NOT_CONFIGURED: '待配置',
  CONCH_DISABLED: '规则包已停用',
  ACCOUNT_NOT_FOUND: '账号不存在',
  FORBIDDEN: '无权限',
  NOT_FOUND: '不存在',
  INTERNAL: '内部错误',
}
