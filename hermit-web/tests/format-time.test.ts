import { describe, expect, it } from 'vitest'
import { formatTime, formatUtc } from '../shared/format'

describe('时间格式', () => {
  it('本地时间后面带时区', () => {
    expect(formatTime('2026-10-30T08:00:00Z')).toMatch(/\d{2}:\d{2}:\d{2} (GMT|UTC)/)
  })

  it('UTC 写法用于悬停提示', () => {
    expect(formatUtc('2026-10-30T08:00:05Z')).toBe('2026-10-30 08:00:05 UTC')
    expect(formatUtc(null)).toBe('')
  })

  it('空值和无法解析的值保持原样', () => {
    expect(formatTime(null)).toBe('—')
    expect(formatTime('not-a-time')).toBe('not-a-time')
  })
})
