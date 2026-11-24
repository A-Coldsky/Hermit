import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import NotFoundView from '../shared/components/NotFoundView.vue'

describe('应用内 404', () => {
  it('未知地址显示页面不存在和返回入口，不跳回首页', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div>首页</div>' } },
        { path: '/:pathMatch(.*)*', component: NotFoundView, props: { home: '/', homeLabel: '返回概览' } },
      ],
    })
    await router.push('/nope/deep')
    await router.isReady()
    const wrapper = mount(RouterView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('页面不存在')
    expect(wrapper.text()).toContain('/nope/deep')
    expect(wrapper.text()).not.toContain('首页')

    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('按 mark 属性显示对应应用的品牌图标（Reef 显示珊瑚，Crab 显示寄居蟹）', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/:pathMatch(.*)*', component: NotFoundView, props: { home: '/', homeLabel: '返回概览', mark: 'reef' } },
      ],
    })
    await router.push('/missing')
    await router.isReady()
    const wrapper = mount(RouterView, { global: { plugins: [ElementPlus, router] } })
    await flushPromises()

    expect(wrapper.get('[data-mark]').attributes('data-mark')).toBe('reef')
    expect(wrapper.find('.coral').exists()).toBe(true)
    expect(wrapper.find('.crab-body').exists()).toBe(false)
  })
})
