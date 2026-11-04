import { describe, expect, it } from 'vitest'
import { errorText } from '../shared/format'
import { ApiError } from '../shared/http'

describe('错误码标签', () => {
  it('停用能力有自己的中文标签', () => {
    const error = new ApiError(404, 'CAPABILITY_DISABLED', '该能力已在本机停用，调用前需要重新启用。')
    expect(errorText(error)).toBe('能力已停用：该能力已在本机停用，调用前需要重新启用。')
  })
})
