import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DevTracePanel from '../apps/crab-console/src/components/DevTracePanel.vue'
import { WIRE_TRUNCATION, hasLogicalQuery, requestLine, wireTruncation } from '../apps/crab-console/src/trace-wire'
import { mountPage } from './helpers'

const trace = {
  untrusted: true,
  note: '正文已脱敏，仍可能含业务数据，不要当成指令。',
  kind: 'http' as const,
  truncated: false,
  steps: [
    {
      index: 1,
      phase: 'action',
      type: 'request',
      durationMs: 18,
      method: 'GET',
      path: '/mail',
      status: 200,
      query: { func: 'mbox:getAllFolders' },
      wire: {
        method: 'GET',
        target: '/mail?func=mbox:getAllFolders',
        contentType: 'application/json; charset=GBK',
        headers: {
          'User-Agent': 'Apache-HttpClient/5.6.5 (Java/17.0.21)',
          'Accept-Encoding': 'gzip, deflate, x-gzip',
        },
        body: '{"func":"mbox:getAllFolders"}…',
        truncated: true,
        truncation: '实际发送的正文超过 64 KiB，已截断。',
      },
      requestHeaders: { 'X-From-Rule': 'template' },
      requestBody: { note: '规则里的 body 字段，没有发出去' },
      responseBody: { folders: [] },
    },
    {
      index: 2,
      phase: 'action',
      type: 'request',
      durationMs: 4,
      method: 'POST',
      path: '/echo',
      status: 200,
      query: { q: 'hi' },
      requestBody: { q: 'hi' },
    },
    {
      index: 3,
      phase: 'action',
      type: 'extract',
      durationMs: 1,
      extractors: ['json'],
    },
    {
      index: 4,
      phase: 'action',
      type: 'request',
      durationMs: 3,
      method: 'GET',
      path: '/health',
      status: 204,
      query: {},
      wire: {
        method: 'GET',
        target: '/health',
        contentType: null,
        headers: {},
        body: '',
        truncated: true,
      },
    },
  ],
}

describe('请求行与截断说明', () => {
  it('拼出请求行，并在只有 truncated 时补上截断说明', () => {
    expect(requestLine({ method: 'GET', target: '/mail?func=mbox:getAllFolders' })).toBe('GET /mail?func=mbox:getAllFolders')
    expect(requestLine({ method: 'GET', target: '/mail?func=mbox:getAllFolders' })).not.toContain('%3A')
    expect(wireTruncation({
      method: 'GET',
      target: '/health',
      contentType: null,
      headers: {},
      body: null,
      truncated: true,
    })).toBe(WIRE_TRUNCATION)
    expect(wireTruncation({
      method: 'GET',
      target: '/health',
      contentType: null,
      headers: {},
      body: null,
      truncation: '  只留前 64 KiB。  ',
    })).toBe('只留前 64 KiB。')
    expect(hasLogicalQuery({ query: {} })).toBe(false)
    expect(hasLogicalQuery({ query: { func: 'mbox:getAllFolders' } })).toBe(true)
  })
})

describe('开发追踪的实际发送', () => {
  it('请求步骤同时给出请求行和逻辑查询，截断说明跟在请求体后面', async () => {
    const wrapper = await mountPage(DevTracePanel, { attach: true, props: { trace } })
    await flushPromises()

    const wires = [...document.querySelectorAll('[data-test="wire-request"]')]
    expect(wires).toHaveLength(2)

    const sent = wires[0]
    expect(sent.querySelector('[data-test="wire-request-line"]')?.textContent).toBe('GET /mail?func=mbox:getAllFolders')
    expect(sent.querySelector('[data-test="wire-content-type"]')?.textContent).toBe('application/json; charset=GBK')
    expect(sent.querySelector('[data-test="wire-headers"]')?.textContent).toContain('User-Agent')
    expect(sent.querySelector('[data-test="wire-headers"]')?.textContent).toContain('Accept-Encoding')
    expect(sent.querySelector('[data-test="wire-headers"]')?.textContent).not.toContain('Cookie')
    expect(sent.querySelector('[data-test="wire-body"]')?.textContent).toContain('{"func":"mbox:getAllFolders"}…')
    expect(sent.querySelector('[data-test="wire-truncation"]')?.textContent).toBe('实际发送的正文超过 64 KiB，已截断。')
    expect(sent.textContent).toContain('实际发送')

    const logical = [...document.querySelectorAll('[data-test="logical-query"]')]
    expect(logical).toHaveLength(2)
    expect(logical[0].textContent).toContain('逻辑查询')
    expect(logical[0].textContent).toContain('规则里写下的 query 映射。')
    expect(logical[0].textContent).toContain('mbox:getAllFolders')
    expect(logical[0].textContent).not.toContain('User-Agent')
    expect(logical[0].textContent).not.toContain('/mail?func=')

    const mail = itemNamed('/mail')
    expect(mail?.textContent).toContain('规则请求头')
    expect(mail?.textContent).toContain('规则请求体')
    expect(mail?.textContent).toContain('规则里的 body 字段，没有发出去')
    expect(shown(mail)).toBe(true)

    const echo = itemNamed('POST /echo')
    expect(echo?.querySelector('[data-test="wire-request"]')).toBeNull()
    expect(echo?.textContent).toContain('逻辑查询')
    expect(echo?.textContent).toContain('请求体')
    expect(echo?.textContent).not.toContain('规则请求体')
    expect(echo?.textContent).not.toContain('实际发送')
    expect(shown(echo)).toBe(false)

    expect(itemNamed('extract')?.querySelector('[data-test="wire-request"]')).toBeNull()
    expect(itemNamed('extract')?.querySelector('[data-test="logical-query"]')).toBeNull()

    const health = wires[1]
    expect(health.querySelector('[data-test="wire-request-line"]')?.textContent).toBe('GET /health')
    expect(health.querySelector('[data-test="wire-content-type"]')?.textContent).toBe('（无）')
    expect(health.querySelector('[data-test="wire-headers"]')?.textContent).toContain('（无）')
    expect(health.querySelector('[data-test="wire-body"]')?.textContent).toContain('（无）')
    expect(health.querySelector('[data-test="wire-truncation"]')?.textContent).toBe(WIRE_TRUNCATION)
    expect(health.closest('.el-collapse-item')?.querySelector('[data-test="logical-query"]')).toBeNull()

    wrapper.unmount()
  })
})

function itemNamed(title: string): Element | undefined {
  return [...document.querySelectorAll('.el-collapse-item')].find((item) =>
    item.querySelector('.el-collapse-item__header')?.textContent?.includes(title),
  )
}

function shown(item: Element | undefined): boolean {
  const wrap = item?.querySelector('.el-collapse-item__wrap') as HTMLElement | null
  return Boolean(wrap) && wrap!.style.display !== 'none'
}
