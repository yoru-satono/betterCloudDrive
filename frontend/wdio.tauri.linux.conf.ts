import path from 'node:path'

const appPath = path.resolve(process.env.TAURI_E2E_APP_PATH || 'src-tauri/target/release/betterclouddrive')

export const config: WebdriverIO.Config = {
  runner: 'local',
  specs: ['./e2e-tauri/**/*.wdio.ts'],
  maxInstances: 1,
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
  services: [
    [
      '@wdio/tauri-service',
      {
        appBinaryPath: appPath,
        driverProvider: 'embedded',
        embeddedPort: Number(process.env.TAURI_NATIVE_DRIVER_PORT || 4445),
        startTimeout: 60_000,
        statusPollTimeout: 5_000,
      },
    ],
  ],
  capabilities: [
    {
      browserName: 'tauri',
      'tauri:options': {
        application: appPath,
      },
    } as WebdriverIO.Capabilities,
  ],
  before: async (_capabilities, _specs, browser) => {
    await browser.execute(() => {
      localStorage.clear()
      localStorage.setItem('preferredLocale', 'zh-Hans')
    })
    await browser.refresh()
  },
}
