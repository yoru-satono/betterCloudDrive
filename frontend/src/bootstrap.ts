import { createApp, nextTick } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './style.css'
import { i18n, initializeLocale } from './i18n'
import { initializeTheme } from './theme'
import { invoke } from './platform/desktop'
import { isDesktopRuntime } from './config/runtime'
import './platform/session'

export async function mountApplication() {
  initializeTheme()
  await initializeLocale()
  const app = createApp(App)
  app.use(createPinia())
  app.use(i18n)
  app.use(router)
  app.mount('#app')
  await nextTick()
  const brand = document.querySelector('.desktop-titlebar__brand')
  if (isDesktopRuntime() && brand) {
    const update = () => {
      const r = brand.getBoundingClientRect()
      void invoke('window:drag-regions', { regions: [{ x: r.x, y: r.y, width: r.width, height: r.height }] })
    }
    new ResizeObserver(update).observe(brand)
    update()
  }
}
