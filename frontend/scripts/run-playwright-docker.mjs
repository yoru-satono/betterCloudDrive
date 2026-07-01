import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const playwrightCli = path.resolve(root, 'node_modules/playwright/cli.js')

const child = spawn(process.execPath, [playwrightCli, 'test'], {
  cwd: root,
  env: {
    ...process.env,
    E2E_USE_DOCKER: process.env.E2E_USE_DOCKER || '1',
    E2E_BASE_URL: process.env.E2E_BASE_URL || 'http://127.0.0.1:3000',
    E2E_BACKEND_URL: process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080',
  },
  stdio: 'inherit',
  windowsHide: true,
})

const exitCode = await new Promise(resolve => {
  child.once('exit', code => resolve(code ?? 1))
})

process.exit(Number(exitCode))
