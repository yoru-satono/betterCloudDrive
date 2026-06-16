import path from 'node:path'

const appPath = path.resolve(process.env.TAURI_E2E_APP_PATH || 'src-tauri/target/release/betterclouddrive.exe')
const driverPort = Number(process.env.TAURI_DRIVER_PORT || 4444)

export const config: WebdriverIO.Config = {
  runner: 'local',
  specs: ['./e2e-tauri/**/*.wdio.ts'],
  maxInstances: 1,
  hostname: '127.0.0.1',
  port: driverPort,
  path: '/',
  logLevel: 'warn',
  bail: 1,
  waitforTimeout: 15_000,
  connectionRetryTimeout: 120_000,
  connectionRetryCount: 1,
  framework: 'mocha',
  reporters: ['spec'],
  mochaOpts: {
    timeout: 120_000,
  },
  tsConfigPath: './tsconfig.wdio.json',
  capabilities: [
    {
      'tauri:options': {
        application: appPath,
      },
    } as WebdriverIO.Capabilities,
  ],
}
