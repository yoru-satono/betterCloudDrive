import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import App from './App.vue'
import './style.css'
import 'vue-sonner/style.css'
import { i18n, initializeLocale } from './i18n'
import { initializeTheme } from './theme'

initializeTheme()

async function bootstrap() {
  if (import.meta.env.VITE_TAURI_E2E === '1') {
    await import('@wdio/tauri-plugin')
  }
  await initializeLocale()
  const app = createApp(App)
  app.use(createPinia())
  app.use(i18n)
  app.use(router)
  app.mount('#app')
}

void bootstrap().catch((error) => {
  console.error('Failed to initialize application locale', error)
})
