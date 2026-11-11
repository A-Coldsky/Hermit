import { describe, expect, it } from 'vitest'
import { canRollback, previousBlockedNote } from '../apps/crab-console/src/previous'

describe('上一版退回', () => {
  it('字段缺失时不显示退回', () => {
    expect(canRollback({ previousVersion: '1.0.1' })).toBe(false)
    expect(canRollback({ previousVersion: '1.0.1', previousDisabled: false })).toBe(false)
    expect(canRollback({ previousVersion: '1.0.1', previousPresent: true })).toBe(false)
    expect(previousBlockedNote({ previousVersion: '1.0.1' })).toBeNull()
  })

  it('上一版已停用时说明原因，不给出退回', () => {
    const row = { previousVersion: '1.0.1', previousDisabled: true, previousPresent: true }
    expect(canRollback(row)).toBe(false)
    expect(previousBlockedNote(row)).toBe('上一版 v1.0.1（已停用）也不符合当前规则，无法退回，请等发布方出新版')
  })

  it('文件不在时不说成规则不合规', () => {
    const row = { previousVersion: '1.0.1', previousDisabled: false, previousPresent: false }
    expect(canRollback(row)).toBe(false)
    expect(previousBlockedNote(row)).toBe('上一版 v1.0.1 的文件已经不在本机，无法退回。')
    expect(previousBlockedNote(row)).not.toContain('不符合')
    expect(previousBlockedNote(row)).not.toContain('已停用')
  })

  it('上一版合规且文件还在时可以退回', () => {
    const row = { previousVersion: '1.0.0', previousDisabled: false, previousPresent: true }
    expect(canRollback(row)).toBe(true)
    expect(previousBlockedNote(row)).toBeNull()
  })
})
