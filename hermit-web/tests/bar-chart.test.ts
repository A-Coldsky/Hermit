import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import BarChart from '../shared/components/BarChart.vue'

function labels(points: { label: string; success: number; failure: number }[]) {
  return mount(BarChart, { props: { points } })
    .findAll('.grid span')
    .map((node) => node.text())
}

describe('柱状图刻度', () => {
  it('最大值取整到好读的刻度，中线标一半', () => {
    expect(labels([{ label: 'a', success: 37, failure: 4 }])).toEqual(['50', '25'])
  })

  it('最大值为 1 时只标顶部，不重复出现两个 1', () => {
    expect(labels([{ label: 'a', success: 0, failure: 0 }])).toEqual(['1'])
  })

  it('没有数据点时显示空态', () => {
    const wrapper = mount(BarChart, { props: { points: [] } })
    expect(wrapper.text()).toContain('所选范围内没有调用数据')
    expect(wrapper.find('svg').exists()).toBe(false)
  })
})
