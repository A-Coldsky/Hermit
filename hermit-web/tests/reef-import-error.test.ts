import { describe, expect, it } from 'vitest'
import { reefImportErrorText } from '../apps/crab-console/src/labels'

describe('引导导入的连接错误', () => {
  it('旧的英文句子仍换成原来的提示', () => {
    expect(reefImportErrorText('cannot reach reef')).toBe(
      '连不上 Reef：确认 JSON 里的地址能从本机访问，Reef 正在运行',
    )
  })

  it('分类后的中文原因原样显示', () => {
    const refused = '上游拒绝连接（127.0.0.1:9）'
    const dns = '无法解析上游地址 reef.internal'
    const handshake = '与上游的 TLS 握手失败：handshake'
    expect(reefImportErrorText(refused)).toBe(refused)
    expect(reefImportErrorText(dns)).toBe(dns)
    expect(reefImportErrorText(handshake)).toBe(handshake)
  })

  it('其它句子继续交给通用错误文案', () => {
    expect(reefImportErrorText('reef request failed')).toBeNull()
  })
})
