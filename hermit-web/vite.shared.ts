import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import type { PluginOption } from 'vite'

export const sharedDir = fileURLToPath(new URL('./shared', import.meta.url))

/**
 * 两套页面共用的插件。Element Plus 按模板里用到的组件和 v-loading 指令引入 JS 与样式，
 * 不再整包加载；ElMessage / ElMessageBox 这类函数式调用的样式在各自 main.ts 里补上。
 */
export function webPlugins(): PluginOption[] {
  return [
    vue(),
    Components({
      dirs: [],
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css', directives: true })],
    }),
  ]
}
