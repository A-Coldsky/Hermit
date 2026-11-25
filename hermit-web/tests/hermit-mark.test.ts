import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import crabHtml from '../apps/crab-console/index.html?raw'
import reefHtml from '../apps/reef-admin/index.html?raw'
import AppLayout from '../shared/components/AppLayout.vue'
import HermitDuet from '../shared/components/HermitDuet.vue'
import HermitMark from '../shared/components/HermitMark.vue'
import { mountPage } from './helpers'

describe('品牌图标', () => {
  it('Crab 是寄居蟹，Reef 是珊瑚，两者不共用图形', () => {
    const crab = mount(HermitMark, { props: { variant: 'crab' } })
    const reef = mount(HermitMark, { props: { variant: 'reef' } })

    expect(crab.find('.crab-body').exists()).toBe(true)
    expect(crab.find('.hm-shell').exists()).toBe(true)
    expect(crab.find('.coral').exists()).toBe(false)
    expect(reef.find('.coral').exists()).toBe(true)
    expect(reef.find('.hm-reef').exists()).toBe(true)
    expect(reef.find('.crab-body').exists()).toBe(false)
  })

  it('HermitDuet 同时包含 Reef 与 Crab，并支持场景态和 1:1 方章', () => {
    const scene = mount(HermitDuet, { props: { variant: 'scene', status: 'connected' } })
    expect(scene.attributes('data-duet')).toBe('scene')
    expect(scene.classes()).toContain('status-connected')
    expect(scene.find('.hm-reef').exists()).toBe(true)
    expect(scene.find('.hm-shell').exists()).toBe(true)
    expect(scene.find('.crab-body').exists()).toBe(true)
    expect(scene.find('.link-dots.ok').exists()).toBe(true)

    const errorScene = mount(HermitDuet, { props: { variant: 'scene', status: 'error' } })
    expect(errorScene.find('.link-dots.err').exists()).toBe(true)

    const badge = mount(HermitDuet, { props: { variant: 'badge', size: 128 } })
    expect(badge.attributes('data-duet')).toBe('badge')
    expect(badge.attributes('viewBox')).toBe('0 0 128 128')
    expect(badge.find('.hm-reef').exists()).toBe(true)
    expect(badge.find('.crab-body').exists()).toBe(true)
  })

  it('两个应用的 favicon 已替换为新版图标，无旧版残留', () => {
    // 新版 Crab 使用奶油珍珠壳色 #fff2df，不再出现旧版青绿堆叠椭圆壳
    expect(crabHtml).toContain('%23fff2df')
    expect(crabHtml).not.toContain('rx=%2720.5%27')
    // 新版 Reef 使用外展海扇珊瑚三色与左下嵌螺，不再出现旧版竖条矩形珊瑚
    expect(reefHtml).toContain('%23fbbf24')
    expect(reefHtml).toContain('%23fb7185')
    expect(reefHtml).toContain('%23fff2df')
    expect(reefHtml).not.toContain('width=%2712%27 height=%2741%27')
  })

  it('侧栏按 mark 显示对应产品的图标', async () => {
    const layout = await mountPage(AppLayout, {
      props: { product: 'Reef', mark: 'reef', subtitle: 'Hermit 管理端', items: [] },
    })
    expect(layout.get('.logo').attributes('data-mark')).toBe('reef')
  })
})
