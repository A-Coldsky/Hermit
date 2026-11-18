import { describe, expect, it } from 'vitest'
import view from '../apps/crab-console/src/views/ConchesView.vue?raw'
import { formatImportIssue, importIssueLabels } from '../apps/crab-console/src/import-issue'

describe('formatImportIssue', () => {
  it('原因里已经写出字段名时不再单独显示 path', () => {
    const shown = formatImportIssue({
      code: 'PARSE',
      path: 'min_crab_version',
      message: 'min_crab_version: 缺少 min_crab_version',
    })
    expect(shown).toEqual({
      code: 'PARSE',
      label: '解析失败',
      location: '',
      reason: '缺少 min_crab_version',
    })
    const joined = [shown.code, shown.label, shown.location, shown.reason].filter(Boolean).join(' ')
    expect(joined).toBe('PARSE 解析失败 缺少 min_crab_version')
    expect(joined.match(/min_crab_version/g)).toHaveLength(1)
    expect(joined).not.toContain('min_crab_version:')
    expect(joined).not.toMatch(/第\s*\d+\s*行/)
  })

  it('从 message 取出行号，原因里不再重复括号', () => {
    expect(formatImportIssue({
      code: 'PARSE',
      path: '',
      message: 'YAML 无法解析（第 4 行）',
    })).toEqual({
      code: 'PARSE',
      label: '解析失败',
      location: '第 4 行',
      reason: 'YAML 无法解析',
    })
  })

  it('使用接口给出的行号，原因里已有字段名时不重复 path', () => {
    expect(formatImportIssue({
      code: 'DRIVER',
      path: 'driver',
      message: 'driver 只允许 http 或 mcp',
      line: 3,
    })).toEqual({
      code: 'DRIVER',
      label: '驱动无效',
      location: '第 3 行',
      reason: 'driver 只允许 http 或 mcp',
    })
  })

  it('原因里没有 path 时位置仍写出字段', () => {
    expect(formatImportIssue({
      code: 'PARSE',
      path: 'credentials',
      message: 'credentials: 必须是映射',
    })).toEqual({
      code: 'PARSE',
      label: '解析失败',
      location: 'credentials',
      reason: '必须是映射',
    })
  })

  it('已有行号在去掉重复 path 后仍然保留', () => {
    expect(formatImportIssue({
      code: 'PARSE',
      path: 'version',
      message: 'version: 缺少 version（第 2 行）',
    })).toEqual({
      code: 'PARSE',
      label: '解析失败',
      location: '第 2 行',
      reason: '缺少 version',
    })
  })

  it('更长字段名里的一段不算已经写出 path', () => {
    expect(formatImportIssue({
      code: 'PARSE',
      path: 'version',
      message: 'min_crab_version 与 min_agent_version 的值不一致',
    })).toMatchObject({
      label: '解析失败',
      location: 'version',
      reason: 'min_crab_version 与 min_agent_version 的值不一致',
    })
  })

  it('其它代码也查表给人话，未知代码不加', () => {
    expect(importIssueLabels.PARSE).toBe('解析失败')
    expect(importIssueLabels.LITERAL_CREDENTIAL).toBe('明文凭证')
    expect(formatImportIssue({ code: 'DRIVER', path: 'x', message: 'y' }).label).toBe(importIssueLabels.DRIVER)
    expect(formatImportIssue({ code: 'MISSING_RELATED', path: 'x', message: 'y' }).label).toBe(importIssueLabels.MISSING_RELATED)
    expect(formatImportIssue({ code: 'IGNORED_REQUEST_FIELD', path: 'x', message: 'y' }).label).toBe('字段不会发送')
    expect(formatImportIssue({ code: 'DOUBLE_ENCODE', path: 'x', message: 'y' }).label).toBe('会被编码两次')
    expect(formatImportIssue({ code: 'PERCENT_ENCODED', path: 'x', message: 'y' }).label).toBe('会被再编码')
    expect(formatImportIssue({ code: 'FRAGMENT_DROPPED', path: 'x', message: 'y' }).label).toBe('片段不会发出去')
    expect(formatImportIssue({ code: 'QUERY_STYLE', path: 'x', message: 'y' }).label).toBe('query_style 无效')
    expect(importIssueLabels.DRIVER).not.toBe(importIssueLabels.PARSE)
    expect(formatImportIssue({
      code: 'REQUIRED',
      path: '/actions',
      message: '缺少 actions',
    })).toEqual({
      code: 'REQUIRED',
      label: '',
      location: '/actions',
      reason: '缺少 actions',
    })
  })

  it('省略 related 的警告保留中文原因和路径，没有行号', () => {
    expect(formatImportIssue({
      code: 'MISSING_RELATED',
      path: 'actions.ping.related',
      message: '缺少 related',
    })).toEqual({
      code: 'MISSING_RELATED',
      label: '缺少相关能力',
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
    expect(shown.label).toBe('解析失败')
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
    expect(view).toContain('class="gloss"')
  })
})
