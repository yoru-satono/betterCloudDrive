import './style.css'
import 'vue-sonner/style.css'
import { initializeDesktop } from './platform/desktop'
import { getApiBaseUrl } from './config/runtime'
async function bootstrap() {
  await initializeDesktop(getApiBaseUrl())
  const { mountApplication } = await import('./bootstrap')
  await mountApplication()
}
void bootstrap().catch(() => {
  const root = document.querySelector('#app')
  if (root) root.textContent = 'Application initialization failed. Check the configured API address and reload.'
})
