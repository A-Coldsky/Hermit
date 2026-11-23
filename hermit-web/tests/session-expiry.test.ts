import ElementPlus from 'element-plus'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DevTracePanel from '../apps/crab-console/src/components/DevTracePanel.vue'
import { importIssueLabels } from '../apps/crab-console/src/import-issue'
import { issueHints } from '../apps/reef-admin/src/labels'
import type { DevTrace } from '../apps/crab-console/src/api'

describe('1.12.0 自定义会话失效前端契约', () => {
  it('Crab 与 Reef 均收录 SESSION_EXPIRY 中文提示', () => {
    expect(importIssueLabels.SESSION_EXPIRY).toBeTruthy()
    expect(issueHints.SESSION_EXPIRY?.title).toBeTruthy()
    expect(issueHints.SESSION_EXPIRY?.hint).toContain('1.12.0')
  })

  it('DevTracePanel 渲染 session_expired 警告并默认展开该步骤', () => {
    const trace: DevTrace = {
      kind: 'http',
      untrusted: true,
      note: '正文已脱敏',
      truncated: false,
      session: {
        loginRan: true,
        loginOk: true,
        aliveCheckRan: true,
        aliveCheckOk: false,
        cookieCount: 1,
        cookieNames: ['SID'],
        stored: true,
      },
      steps: [
        {
          index: 0,
          phase: 'action',
          type: 'request',
          durationMs: 12,
          method: 'GET',
          path: '/api/items',
          status: 200,
          warnings: [
            {
              code: 'session_expired',
              message: '命中 auth.expired_when 第 1 条规则，按会话失效处理',
            },
          ],
        },
        {
          index: 1,
          phase: 'action',
          type: 'assert',
          durationMs: 1,
          assertions: ['not_contains'],
          onFail: 'session_expired',
          passed: false,
          warnings: [
            {
              code: 'session_expired',
              message: '断言未通过（on_fail: session_expired），按会话失效处理',
            },
          ],
        },
      ],
    }

    const wrapper = mount(DevTracePanel, {
      props: { trace },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).toContain('命中 auth.expired_when 第 1 条规则，按会话失效处理')
    expect(wrapper.text()).toContain('断言未通过（on_fail: session_expired），按会话失效处理')
  })
})
