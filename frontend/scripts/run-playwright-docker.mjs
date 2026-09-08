import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadWorkspaceE2EEnv } from './e2e-env.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const workspaceRoot = path.resolve(root, '..')
const playwrightCli = createRequire(import.meta.url).resolve('@playwright/test/cli')
const playwrightArgs = process.argv.slice(2).filter((argument, index) => argument !== '--' || index !== 0)
const e2eEnv = loadWorkspaceE2EEnv(workspaceRoot)

const child = spawn(process.execPath, [playwrightCli, 'test', ...playwrightArgs], {
  cwd: root,
  env: {
    ...e2eEnv,
    E2E_USE_DOCKER: e2eEnv.E2E_USE_DOCKER || '1',
    E2E_BASE_URL: e2eEnv.E2E_BASE_URL || 'http://127.0.0.1:3000',
    E2E_BACKEND_URL: e2eEnv.E2E_BACKEND_URL || 'http://127.0.0.1:8080',
  },
  stdio: 'inherit',
  windowsHide: true,
})

const exitCode = await new Promise(resolve => {
  child.once('exit', code => resolve(code ?? 1))
})

process.exit(Number(exitCode))
