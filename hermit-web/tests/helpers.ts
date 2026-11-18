import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { Component } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

export interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
}

export function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

export interface MountOptions {
  props?: Record<string, unknown>
  /** 弹窗和抽屉会传送到 body，断言它们时需要挂到文档上。 */
  attach?: boolean
  path?: string
}

export async function mountPage(component: Component, options: MountOptions = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  await router.push(options.path ?? '/')
  await router.isReady()
  const wrapper = mount(component, {
    props: options.props ?? {},
    attachTo: options.attach ? document.body : undefined,
    global: { plugins: [ElementPlus, router] },
  }) as VueWrapper & { router: Router }
  wrapper.router = router
  return wrapper
}
