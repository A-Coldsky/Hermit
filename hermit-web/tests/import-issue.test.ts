import { describe, expect, it } from 'vitest'
import view from '../apps/crab-console/src/views/ConchesView.vue?raw'
import { formatImportIssue } from '../apps/crab-console/src/import-issue'

describe('formatImportIssue', () => {
  it('去掉路径前缀，字段名不再出现三次', () => {
    const shown = formatImportIssue({
      code: 'PARSE',
      path: 'min_crab_version',
      message: 'min_crab_version: 缺少 min_crab_version',
    })
    expect(shown).toEqual({
      code: 'PARSE',
      location: 'min_crab_version',
      reason: '缺少 min_crab_version',
    })
    const joined = `${shown.code} ${shown.location} ${shown.reason}`
    expect(joined.match(/min_crab_version/g)).toHaveLength(2)
    expect(joined).not.toContain('min_crab_version:')
  })

  it('从 message 取出行号，原因里不再重复括号', () => {
    expect(formatImportIssue({
      code: 'PARSE',
      path: '',
      message: 'YAML 无法解析（第 4 行）',
    })).toEqual({
      code: 'PARSE',
      location: '第 4 行',
      reason: 'YAML 无法解析',
    })
  })

  it('使用接口给出的行号', () => {
    expect(formatImportIssue({
      code: 'DRIVER',
      path: 'driver',
      message: 'driver 只允许 http 或 mcp',
      line: 3,
    })).toEqual({
      code: 'DRIVER',
      location: '第 3 行 driver',
      reason: 'driver 只允许 http 或 mcp',
    })
  })

  it('省略 related 的警告保留中文原因，没有行号', () => {
    expect(formatImportIssue({
      code: 'MISSING_RELATED',
      path: 'actions.ping.related',
      message: '缺少 related',
    })).toEqual({
      code: 'MISSING_RELATED',
      location: 'actions.ping.related',
      reason: '缺少 related',
    })
  })

  it('第 0 行不当成位置', () => {
    const shown = formatImportIssue({
      code: 'PARSE',
      path: '',
      message: 'YAML 无法解析（第 0 行）',
      line: 0,
    })
    expect(shown.location).toBe('文档')
    expect(shown.reason).toBe('YAML 无法解析（第 0 行）')
  })
})

describe('导入警告样式', () => {
  it('深色警告色走 --hm-warning，不写死 #f7b13c', () => {
    const start = view.indexOf('.issues.warn')
    const body = view.slice(start, view.indexOf('}', start))
    expect(body).toContain('color: var(--hm-warning)')
    expect(body).toContain('border-left-color: var(--hm-warning)')
    expect(body).not.toContain('#f7b13c')
    expect(body).not.toContain('--el-color-warning-dark-2')
    expect(view).toContain('data-test="import-warning"')
  })
})
