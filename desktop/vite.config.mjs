import { defineConfig } from '../frontend/node_modules/vite/dist/node/index.js'
import vue from '../frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(here, '../frontend')
export default defineConfig({
  root: frontend,
  plugins: [
    {
      name: 'desktop-entry',
      enforce: 'pre',
      resolveId(id, importer) {
        if (id === '/src/main.ts') return path.join(here, 'web/main.ts')
        if (importer && id.startsWith('.') && path.resolve(path.dirname(importer), id).replace(/\\/g, '/').replace(/\.ts$/, '') === path.join(frontend, 'src/api/client').replace(/\\/g, '/')) return path.join(here, 'web/client.ts')
      },
    },
    vue(),
  ],
  resolve: { dedupe: ['vue', 'pinia', 'vue-router', 'vue-sonner', 'vue-i18n', 'axios'], alias: [
    { find: '@/components/file/OnlyOfficeEditor.vue', replacement: path.join(here, 'web/components/OnlyOfficeEditor.vue') },
    { find: '@/platform/desktop', replacement: path.join(here, 'web/bridge.ts') },
    ...[['@/config/runtime', 'runtime'], ['@/api/client', 'client'], ['@/stores/auth', 'auth'], ['@/stores/preview', 'preview']]
      .map(([find, name]) => ({ find, replacement: path.join(here, 'web/' + name + '.ts') })),
    { find: '@', replacement: path.join(frontend, 'src') },
  ] },
  define: {
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(process.env.BCD_DESKTOP_API_BASE || 'http://127.0.0.1:8080/api/v1'),
    'import.meta.env.VITE_WEB_BASE_URL': JSON.stringify(process.env.BCD_DESKTOP_WEB_BASE || 'http://127.0.0.1:3000'),
  },
  build: { outDir: path.join(here, 'web-dist'), emptyOutDir: true, target: 'es2022' },
})
