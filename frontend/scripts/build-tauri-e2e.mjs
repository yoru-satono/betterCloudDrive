import { spawn } from 'node:child_process'

const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const args = ['exec', 'tauri', 'build', '--no-bundle']
const env = { ...process.env }

if (process.platform === 'linux') {
  args.push('--features', 'e2e-webdriver', '--config', 'src-tauri/tauri.e2e.conf.json')
  env.VITE_TAURI_E2E = '1'
}

const child = spawn(pnpm, args, {
  env,
  stdio: 'inherit',
  windowsHide: true,
})

child.once('error', (error) => {
  console.error(`Unable to start the Tauri E2E build: ${error.message}`)
  process.exit(1)
})

child.once('exit', (code, signal) => {
  if (signal) {
    console.error(`Tauri E2E build was terminated by ${signal}.`)
    process.exit(1)
  }
  process.exit(code ?? 1)
})
