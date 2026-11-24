import { defineConfig } from '../../frontend/node_modules/vite/dist/node/index.js'
import vue from '../../frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const here = path.dirname(fileURLToPath(import.meta.url))
const frontend = path.resolve(here, '../../frontend')
export default defineConfig({
  root: frontend,
  plugins: [vue()],
  resolve: { alias: [
    { find: '@/platform/desktop', replacement: path.join(here, 'bridge.ts') },
    { find: '@/config/runtime', replacement: path.join(here, 'runtime.ts') },
    { find: '@', replacement: path.join(frontend, 'src') },
  ] },
  define: {
    'import.meta.env.VITE_API_BASE_URL': JSON.stringify(process.env.POC_API_BASE || 'http://127.0.0.1:8080/api/v1'),
    'import.meta.env.VITE_WEB_BASE_URL': JSON.stringify('http://127.0.0.1:3000'),
  },
  build: { outDir: path.join(here, 'web'), emptyOutDir: true, target: 'es2018' },
})
