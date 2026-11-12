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
    const row = { previousVersion: '1.0.1', previousDisabled: true, previousPresent: true, ruleDisabled: true }
    expect(canRollback(row)).toBe(false)
    expect(previousBlockedNote(row)).toBe('上一版 v1.0.1（已停用）也不符合当前规则，无法退回，请等发布方出新版')
    expect(previousBlockedNote({ ...row, updateAvailable: true, latestVersion: '1.0.2' })).toBe(
      '上一版 v1.0.1（已停用）也不符合当前规则，无法退回，请更新到 v1.0.2',
    )
  })

  it('当前版已经修好时，不再写「请等发布方出新版」', () => {
    const row = { version: '1.0.2', previousVersion: '1.0.1', previousDisabled: true, previousPresent: true, ruleDisabled: false }
    expect(canRollback(row)).toBe(false)
    expect(previousBlockedNote(row)).toBe('上一版 v1.0.1（已停用）不符合当前规则，不能退回到它。')
  })

  it('上一版等于当前版时没有退回，也没有说明', () => {
    const row = { version: '1.0.1', previousVersion: '1.0.1', previousDisabled: false, previousPresent: true }
    expect(canRollback(row)).toBe(false)
    expect(previousBlockedNote(row)).toBeNull()
  })

  it('退回之后「更新到」和「退回」不指向同一个版本', () => {
    const row = {
      version: '1.0.0',
      previousVersion: '1.0.1',
      previousDisabled: false,
      previousPresent: true,
      updateAvailable: true,
      latestVersion: '1.0.1',
    }
    expect(canRollback(row)).toBe(false)
    expect(canRollback({ ...row, latestVersion: '1.0.2' })).toBe(true)
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
