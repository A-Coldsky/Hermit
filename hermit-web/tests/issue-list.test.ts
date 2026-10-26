import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import IssueList from '../apps/reef-admin/src/components/IssueList.vue'

describe('校验问题列表', () => {
  it('已知的码显示人话标题和改法，保留路径与原始码', () => {
    const wrapper = mount(IssueList, {
      props: {
        tone: 'warning',
        issues: [{ code: 'MISSING_NOTES', path: 'actions.list_todo.notes', message: '缺少 notes' }],
      },
    })
    const text = wrapper.text()
    expect(text).toContain('能力没有写使用注意')
    expect(text).toContain('不写也能发布')
    expect(text).toContain('actions.list_todo.notes')
    expect(text).toContain('MISSING_NOTES')
  })

  it('细节与标题不同时一并显示', () => {
    const wrapper = mount(IssueList, {
      props: {
        tone: 'warning',
        issues: [{ code: 'UNKNOWN_RELATED', path: 'actions.a.related[0]', message: 'related 指向不存在的能力 approve2' }],
      },
    })
    expect(wrapper.text()).toContain('related 指向不存在的能力 approve2')
  })

  it('未收录的码退回服务端 message', () => {
    const wrapper = mount(IssueList, {
      props: { tone: 'error', issues: [{ code: 'NEW_CODE', path: '', message: '某个新问题' }] },
    })
    expect(wrapper.text()).toContain('某个新问题')
    expect(wrapper.text()).toContain('（根）')
  })
})
