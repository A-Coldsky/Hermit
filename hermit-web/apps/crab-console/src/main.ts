import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import { createApp, h } from 'vue'
import { RouterView } from 'vue-router'
import '@shared/theme.css'
import { router } from './router'

createApp({ render: () => h(RouterView) })
  .use(router)
  .use(ElementPlus, { locale: zhCn })
  .mount('#app')
