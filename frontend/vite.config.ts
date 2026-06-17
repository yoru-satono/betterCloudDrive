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

function loadModuleEnv(workspaceRoot: string) {
  const files = [
    'config/env-files/database.env',
    'config/env-files/storage.env',
    'config/env-files/security.env',
    'config/env-files/backend.env',
    'config/env-files/frontend.env',
    'config/env-files/observability.env',
    'config/env-files/mail.env',
  ]

  return files.reduce<Record<string, string>>((acc, file) => {
    return { ...acc, ...parseEnvFile(path.join(workspaceRoot, file)) }
  }, {})
}

export default defineConfig(({ mode }) => {
  const workspaceRoot = fileURLToPath(new URL('..', import.meta.url))
  const env = {
    ...loadModuleEnv(workspaceRoot),
    ...loadEnv(mode, workspaceRoot, ''),
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
      setupFiles: ['src/test/setup.ts']
    }
  }
})
