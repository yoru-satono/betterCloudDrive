import { createApp, nextTick } from 'vue'
import { createPinia } from 'pinia'
import App from '../../frontend/src/App.vue'
import router from '../../frontend/src/router'
import '../../frontend/src/style.css'
import 'vue-sonner/style.css'
import { i18n, initializeLocale } from '../../frontend/src/i18n'
import { initializeTheme } from '../../frontend/src/theme'
import { configureConnection, invoke } from './bridge'
import { getApiBaseUrl } from './runtime'
import './session'

async function bootstrap() {
  await configureConnection(getApiBaseUrl())
  initializeTheme()
  await initializeLocale()
  const app = createApp(App)
  app.use(createPinia())
  app.use(i18n)
  app.use(router)
  app.mount('#app')
  await nextTick()
  const brand = document.querySelector('.desktop-titlebar__brand')
  if (brand) {
    const update = () => {
      const r = brand.getBoundingClientRect()
      void invoke('window:drag-regions', { regions: [{ x: r.x, y: r.y, width: r.width, height: r.height }] })
    }
    new ResizeObserver(update).observe(brand)
    update()
  }
}
void bootstrap().catch(() => {
  const root = document.querySelector('#app')
  if (root) root.textContent = 'Desktop initialization failed. Check the configured API address and restart.'
})
