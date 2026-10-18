import { provideGlobalConfig } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// 组件样式由构建插件按需引入；这里只放基础变量、暗色变量和函数式调用的组件样式。
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import { createApp, h } from 'vue'
import type { Router } from 'vue-router'
import { RouterView } from 'vue-router'
import './theme.css'
import './color-mode'

export function boot(router: Router): void {
  const app = createApp({ render: () => h(RouterView) })
  provideGlobalConfig({ locale: zhCn }, app, true)
  app.use(router).mount('#app')
}
