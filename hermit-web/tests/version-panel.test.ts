import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConchVersion } from '../apps/reef-admin/src/api'
import { mountPage } from './helpers'

vi.mock('../apps/reef-admin/src/api', () => ({ api: {} }))

import VersionPanel from '../apps/reef-admin/src/components/VersionPanel.vue'

function version(overrides: Partial<ConchVersion>): ConchVersion {
  return {
    version: '1.0.0',
    status: 'PUBLISHED',
    uploadedBy: 'admin',
    uploadedAt: '2026-10-12T12:00:00Z',
    publishedBy: 'admin',
    publishedAt: '2026-10-12T12:00:00Z',
    current: true,
    warningCount: 0,
    warnings: [],
    ...overrides,
  }
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('版本表警告列', () => {
  it('警告数可点，弹出逐条说明', async () => {
    const wrapper = await mountPage(VersionPanel, {
      attach: true,
      props: {
        conch: 'legacy_oa',
        versions: [
          version({
            warningCount: 2,
            warnings: [
              { code: 'UNKNOWN_RELATED', path: 'actions.list_todo.related[0]', message: 'related 指向不存在的能力 approve' },
              { code: 'UNKNOWN_RELATED', path: 'actions.list_todo.related[1]', message: 'related 指向不存在的能力 list_done' },
            ],
          }),
        ],
      },
    })
    await flushPromises()
    const button = wrapper.get('button[aria-label="查看 v1.0.0 的 2 条警告"]')
    expect(button.text()).toBe('2')

    await button.trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('v1.0.0 的校验警告')
    expect(document.body.textContent).toContain('related 指向不存在的能力 approve')
    expect(document.body.textContent).toContain('related 指向不存在的能力 list_done')
    wrapper.unmount()
  })

  it('没有警告时不可点', async () => {
    const wrapper = await mountPage(VersionPanel, { props: { conch: 'legacy_oa', versions: [version({})] } })
    await flushPromises()
    expect(wrapper.text()).toContain('v1.0.0')
    expect(wrapper.find('button[aria-label*="条警告"]').exists()).toBe(false)
  })

  it('操作列排在上传、发布记录之前，窄屏不用滑到最右，也不固定盖住警告列', async () => {
    const wrapper = await mountPage(VersionPanel, { props: { conch: 'legacy_oa', versions: [version({})] } })
    await flushPromises()
    expect(wrapper.text()).toContain('YAML')
    expect(wrapper.find('.el-table-fixed-column--right').exists()).toBe(false)
    const headers = wrapper.findAll('.el-table__header th').map((th) => th.text())
    expect(headers.indexOf('操作')).toBeGreaterThan(headers.indexOf('警告'))
    expect(headers.indexOf('操作')).toBeLessThan(headers.indexOf('上传'))
  })
})
