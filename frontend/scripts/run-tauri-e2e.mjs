import { spawn } from 'node:child_process'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import { inspect } from 'node:util'
import { fileURLToPath } from 'node:url'
import { download as downloadEdgeDriver } from 'edgedriver'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const appPath = path.resolve(root, process.env.TAURI_E2E_APP_PATH || 'src-tauri/target/release/betterclouddrive.exe')
const driverPort = Number(process.env.TAURI_DRIVER_PORT || 4444)
const nativePort = Number(process.env.TAURI_NATIVE_DRIVER_PORT || 4445)
const tauriDriverPath = process.env.TAURI_DRIVER_PATH || 'tauri-driver'
const wdioBin = path.resolve(root, 'node_modules/@wdio/cli/bin/wdio.js')
const wdioConfig = path.resolve(root, 'wdio.tauri.conf.ts')
const downloadDir = path.resolve(process.env.BCD_E2E_DOWNLOAD_DIR || path.join(root, '.wdio-cache', 'downloads', String(Date.now())))
const uploadDir = path.resolve(process.env.BCD_E2E_UPLOAD_DIR || path.join(root, '.wdio-cache', 'uploads', String(Date.now()), 'tauri-upload-folder'))
let nativeDriverPath = process.env.EDGEWEBDRIVER_PATH || process.env.TAURI_NATIVE_DRIVER_PATH

if (!fs.existsSync(appPath)) {
  fail(`Tauri app not found at ${appPath}. Run "npm run tauri:e2e:build" first, or set TAURI_E2E_APP_PATH.`)
}

if (!fs.existsSync(wdioBin)) {
  fail('WebdriverIO CLI is not installed. Run "npm install" in frontend first.')
}

const tauriDriverArgs = [
  '--port',
  String(driverPort),
  '--native-port',
  String(nativePort),
]

if (!nativeDriverPath) {
  nativeDriverPath = findOnPath('msedgedriver.exe')
}

if (!nativeDriverPath) {
  try {
    const edgeDriverVersion = process.env.EDGEDRIVER_VERSION || await detectWebView2Version()
    nativeDriverPath = await downloadEdgeDriver(
      edgeDriverVersion,
      process.env.EDGEDRIVER_CACHE_DIR || path.resolve(root, '.wdio-cache', 'edgedriver'),
    )
  } catch (error) {
    fail(`Unable to prepare msedgedriver.exe. Install Microsoft Edge/WebView2 and set EDGEWEBDRIVER_PATH, or set EDGEDRIVER_VERSION for the edgedriver package.\n${formatError(error)}`)
  }
}

if (nativeDriverPath) {
  if (!fs.existsSync(nativeDriverPath)) {
    fail(`Native WebDriver not found at EDGEWEBDRIVER_PATH/TAURI_NATIVE_DRIVER_PATH: ${nativeDriverPath}`)
  }
  tauriDriverArgs.push('--native-driver', nativeDriverPath)
}

const tauriDriver = spawn(tauriDriverPath, tauriDriverArgs, {
  cwd: root,
  env: {
    ...process.env,
    BCD_E2E_DOWNLOAD_DIR: downloadDir,
    BCD_E2E_UPLOAD_DIR: uploadDir,
  },
  stdio: ['ignore', 'pipe', 'pipe'],
  windowsHide: true,
})

let tauriDriverOutput = ''
let tauriDriverSpawnError = null
tauriDriver.stdout.on('data', chunk => {
  const text = chunk.toString()
  tauriDriverOutput += text
  process.stdout.write(text)
})
tauriDriver.stderr.on('data', chunk => {
  const text = chunk.toString()
  tauriDriverOutput += text
  process.stderr.write(text)
})
tauriDriver.once('error', error => {
  tauriDriverSpawnError = error
})

const cleanup = () => {
  if (!tauriDriver.killed) tauriDriver.kill()
}

process.once('SIGINT', () => {
  cleanup()
  process.exit(130)
})
process.once('SIGTERM', () => {
  cleanup()
  process.exit(143)
})
process.once('exit', cleanup)

try {
  await waitForPort(driverPort, 20_000)
} catch (error) {
  cleanup()
  const hint = nativeDriverPath
    ? ''
    : '\nSet EDGEWEBDRIVER_PATH to msedgedriver.exe if it is not on PATH.'
  fail(`tauri-driver did not start on port ${driverPort}.${hint}\n${tauriDriverOutput || String(error)}`)
}

const wdio = spawn(process.execPath, [wdioBin, 'run', wdioConfig], {
  cwd: root,
  env: {
    ...process.env,
    E2E_BACKEND_URL: process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080',
    E2E_MAILPIT_URL: process.env.E2E_MAILPIT_URL || 'http://127.0.0.1:8025',
    TAURI_DRIVER_PORT: String(driverPort),
    TAURI_E2E_APP_PATH: appPath,
    BCD_E2E_DOWNLOAD_DIR: downloadDir,
    BCD_E2E_UPLOAD_DIR: uploadDir,
  },
  stdio: 'inherit',
  windowsHide: true,
})

const exitCode = await new Promise(resolve => {
  wdio.once('exit', code => resolve(code ?? 1))
})

cleanup()
process.exit(Number(exitCode))

function waitForPort(port, timeoutMs) {
  const started = Date.now()
  return new Promise((resolve, reject) => {
    const tryConnect = () => {
      const socket = net.createConnection({ host: '127.0.0.1', port })
      socket.once('connect', () => {
        socket.end()
        resolve()
      })
      socket.once('error', error => {
        socket.destroy()
        if (tauriDriverSpawnError) {
          reject(tauriDriverSpawnError)
          return
        }
        if (tauriDriver.exitCode !== null) {
          reject(new Error(`tauri-driver exited with code ${tauriDriver.exitCode}`))
          return
        }
        if (Date.now() - started > timeoutMs) {
          reject(error)
          return
        }
        setTimeout(tryConnect, 250)
      })
    }
    tryConnect()
  })
}

function fail(message) {
  console.error(message)
  process.exit(1)
}

async function detectWebView2Version() {
  if (process.platform !== 'win32') return undefined
  const candidateRoots = [
    process.env['PROGRAMFILES(X86)'] && path.join(process.env['PROGRAMFILES(X86)'], 'Microsoft/EdgeWebView/Application'),
    process.env.PROGRAMFILES && path.join(process.env.PROGRAMFILES, 'Microsoft/EdgeWebView/Application'),
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Microsoft/EdgeWebView/Application'),
  ].filter(Boolean)

  for (const rootPath of candidateRoots) {
    const versions = await fs.promises.readdir(rootPath, { withFileTypes: true }).catch(() => [])
    const version = versions
      .filter(entry => entry.isDirectory() && /^\d+\.\d+\.\d+\.\d+$/.test(entry.name))
      .map(entry => entry.name)
      .sort(compareVersions)
      .pop()
    if (version) return version
  }
  return undefined
}

function findOnPath(binary) {
  const pathValue = process.env.PATH || ''
  const pathExts = process.platform === 'win32'
    ? (process.env.PATHEXT || '.EXE;.CMD;.BAT;.COM').split(';')
    : ['']
  for (const dir of pathValue.split(path.delimiter)) {
    if (!dir) continue
    for (const ext of pathExts) {
      const suffix = process.platform === 'win32' && path.extname(binary) === '' ? ext : ''
      const candidate = path.resolve(dir, `${binary}${suffix}`)
      if (fs.existsSync(candidate)) return candidate
    }
  }
  return null
}

function compareVersions(left, right) {
  const leftParts = left.split('.').map(Number)
  const rightParts = right.split('.').map(Number)
  for (let index = 0; index < Math.max(leftParts.length, rightParts.length); index += 1) {
    const diff = (leftParts[index] || 0) - (rightParts[index] || 0)
    if (diff) return diff
  }
  return 0
}

function formatError(error) {
  if (error instanceof Error) return error.stack || error.message
  return inspect(error, { depth: 4, colors: false })
}
