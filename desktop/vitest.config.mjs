import { defineConfig } from '../frontend/node_modules/vitest/dist/config.js'
import vue from '../frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
const here = path.dirname(fileURLToPath(import.meta.url))
export default defineConfig({
  root: path.join(here, '../frontend'), plugins: [vue()],
  resolve: { dedupe: ['vue', 'pinia', 'vue-router', 'vue-i18n', 'axios', 'vue-sonner', '@vue/test-utils'], alias: { '@': path.join(here, '../frontend/src') } },
  test: { environment: 'jsdom', include: ['../desktop/tests/web/**/*.test.ts'],
    setupFiles: ['src/test/setup.ts', '../desktop/tests/web/qtSetup.ts'], clearMocks: true },
})
