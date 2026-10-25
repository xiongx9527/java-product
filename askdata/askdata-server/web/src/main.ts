import 'core-js/features/object/has-own'
// @ts-ignore: css-has-pseudo/browser lacks official type definitions
import cssHasPseudo from 'css-has-pseudo/browser'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.less'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import VueDOMPurifyHTML from 'vue-dompurify-html'
import { isSupportFlexGap } from './utils/utils'
// [askdata-xpack-patch] A29：挂载前先从后端拉取真实许可证状态
// （登录页/关于页/用户同步入口的 onMounted 同步读 LicenseGenerator.getLicense()）
import LicenseGenerator from './utils/xpackPatch'

// import 'element-plus/dist/index.css'
cssHasPseudo(document)

isSupportFlexGap()

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(i18n)
app.use(VueDOMPurifyHTML)
// A29：等许可证状态就绪后再挂载，保证首个页面（登录页）读到的是真实状态；
// 请求失败时 refreshLicense 自身兜底为社区版，不会阻塞启动。
LicenseGenerator.refreshLicense().finally(() => {
  app.mount('#app')
})
