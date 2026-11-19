import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppLayout from '../shared/components/AppLayout.vue'
import HermitMark from '../shared/components/HermitMark.vue'
import { mountPage } from './helpers'

describe('品牌图标', () => {
  it('Crab 是寄居蟹，Reef 是珊瑚，两者不共用图形', () => {
    const crab = mount(HermitMark, { props: { variant: 'crab' } })
    const reef = mount(HermitMark, { props: { variant: 'reef' } })

    expect(crab.find('.crab-body').exists()).toBe(true)
    expect(crab.find('.coral').exists()).toBe(false)
    expect(reef.find('.coral').exists()).toBe(true)
    expect(reef.find('.crab-body').exists()).toBe(false)
  })

  it('侧栏按 mark 显示对应产品的图标', async () => {
    const layout = await mountPage(AppLayout, {
      props: { product: 'Reef', mark: 'reef', subtitle: 'Hermit 管理端', items: [] },
    })
    expect(layout.get('.logo').attributes('data-mark')).toBe('reef')
  })
})
