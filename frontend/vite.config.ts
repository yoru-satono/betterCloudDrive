import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, URL } from 'node:url'

function parseEnvFile(filePath: string) {
  if (!fs.existsSync(filePath)) return {}
  return Object.fromEntries(
    fs.readFileSync(filePath, 'utf8')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#') && line.includes('='))
      .map((line) => {
        const index = line.indexOf('=')
        return [line.slice(0, index).trim(), line.slice(index + 1).trim()]
      })
  )
}

export default defineConfig(({ mode, command }) => {
  const workspaceRoot = fileURLToPath(new URL('..', import.meta.url))
  const env = {
    ...(command === 'serve' ? parseEnvFile(path.join(workspaceRoot, 'config/env-files/frontend.env')) : {}),
    ...loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), 'VITE_'),
    ...process.env,
  }
  const devBackendProxyBase = env.VITE_DEV_BACKEND_PROXY_BASE || 'http://localhost:8080'

  return {
    plugins: [vue()],
    build: {
      target: 'es2018',
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      proxy: {
        '/api': devBackendProxyBase,
        '/webdav': devBackendProxyBase
      }
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['src/test/setup.ts'],
      clearMocks: true,
      reporters: process.env.CI ? ['default', 'junit'] : ['default'],
      outputFile: process.env.CI ? { junit: '../test-results/web/unit-junit.xml' } : undefined,
      coverage: {
        provider: 'v8',
        reportsDirectory: '../test-results/coverage/web',
        reporter: ['text', 'html', 'json-summary', 'lcov'],
        include: ['src/**/*.{ts,vue}'],
        exclude: ['src/**/*.test.ts', 'src/**/__tests__/**', 'src/test/**'],
      },
    }
  }
})
