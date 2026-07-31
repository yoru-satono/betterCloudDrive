import { spawn } from 'node:child_process'

const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const env = { ...process.env }

// linuxdeploy's bundled binutils cannot strip Fedora 44 libraries that contain
// RELR sections. Keep the libraries intact on Linux; Windows uses the original
// Tauri build path unchanged.
if (process.platform === 'linux' && env.NO_STRIP === undefined) {
  env.NO_STRIP = '1'
}

const child = spawn(pnpm, ['exec', 'tauri', 'build'], {
  env,
  stdio: 'inherit',
  windowsHide: true,
})

child.once('error', (error) => {
  console.error(`Unable to start the Tauri build: ${error.message}`)
  process.exit(1)
})

child.once('exit', (code, signal) => {
  if (signal) {
    console.error(`Tauri build was terminated by ${signal}.`)
    process.exit(1)
  }
  process.exit(code ?? 1)
})
