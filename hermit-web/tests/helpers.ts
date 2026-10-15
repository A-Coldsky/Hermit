import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

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

export function mountPage(component: Component, props: Record<string, unknown> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }],
  })
  return mount(component, { props, global: { plugins: [ElementPlus, router] } })
}
