import { describe, expect, it } from 'vitest'
import { errorText } from '../shared/format'
import { ApiError } from '../shared/http'

describe('错误码标签', () => {
  it('停用能力有自己的中文标签', () => {
    const error = new ApiError(404, 'CAPABILITY_DISABLED', '该能力已在本机停用，调用前需要重新启用。')
    expect(errorText(error)).toBe('能力已停用：该能力已在本机停用，调用前需要重新启用。')
  })

  it('message 已经表达错误码时不再加前缀', () => {
    expect(errorText(new ApiError(403, 'FORBIDDEN', '没有权限执行这个操作'))).toBe('没有权限执行这个操作')
    expect(errorText(new ApiError(404, 'NOT_FOUND', '资源不存在'))).toBe('资源不存在')
    expect(errorText(new ApiError(404, 'NOT_FOUND', '规则包不存在'))).toBe('规则包不存在')
    expect(errorText(new ApiError(500, 'INTERNAL', '服务器内部错误'))).toBe('服务器内部错误')
    expect(errorText(new ApiError(500, 'INTERNAL', '内部错误'))).toBe('内部错误')
    expect(errorText(new ApiError(409, 'SESSION_EXPIRED', '会话已失效'))).toBe('会话已失效')
    expect(errorText(new ApiError(504, 'TIMEOUT', '执行超时'))).toBe('执行超时')
    expect(errorText(new ApiError(400, 'INVALID_INPUT', '输入无效'))).toBe('输入无效')
  })

  it('message 另有说明时仍保留短标签', () => {
    expect(errorText(new ApiError(403, 'FORBIDDEN', 'console access denied'))).toBe('无权限：console access denied')
    expect(errorText(new ApiError(404, 'NOT_FOUND', 'not found'))).toBe('不存在：not found')
    expect(errorText(new ApiError(409, 'CONFLICT', '该规则包版本已存在'))).toBe('状态冲突：该规则包版本已存在')
    expect(errorText(new ApiError(409, 'NOT_CONFIGURED', '上报未开启，或尚未连接 Reef。'))).toBe(
      '待配置：上报未开启，或尚未连接 Reef。',
    )
    expect(errorText(new ApiError(409, 'CONCH_DISABLED', 'Reef 已停用该规则包，不再下载。'))).toBe(
      '规则包已停用：Reef 已停用该规则包，不再下载。',
    )
    expect(errorText(new ApiError(409, 'LOGIN_INPUT_REQUIRED', '请到控制台输入'))).toBe(
      '需要验证码：请到控制台输入',
    )
  })

  it('没有对应标签或不是接口错误时用原文', () => {
    expect(errorText(new ApiError(401, 'UNAUTHORIZED', '未登录或登录已过期'))).toBe('未登录或登录已过期')
    expect(errorText(new Error('网络断开'))).toBe('网络断开')
    expect(errorText(null)).toBe('操作失败')
  })
})
