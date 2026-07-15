import { defineConfig, devices } from '@playwright/test'

const useDockerStack = process.env.E2E_USE_DOCKER === '1'
const baseURL = process.env.E2E_BASE_URL || (useDockerStack ? 'http://127.0.0.1:3000' : 'http://127.0.0.1:5173')

export default defineConfig({
  testDir: './e2e',
  testMatch: /.*\.(e2e|setup)\.ts/,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  outputDir: '../test-results/web/playwright-artifacts',
  reporter: [
    ['list'],
    ['html', { outputFolder: '../test-results/web/playwright-html', open: 'never' }],
    ['junit', { outputFile: '../test-results/web/playwright-junit.xml' }],
  ],
  use: {
    baseURL,
    // E2E copy assertions are authored against the Chinese UI. Lock the
    // browser locale instead of inheriting the developer machine locale.
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'setup',
      testMatch: /.*\.setup\.ts/,
    },
    {
      name: 'core',
      use: { ...devices['Desktop Chrome'] },
      testMatch: /^(?!.*onlyoffice).*\.e2e\.ts/,
    },
    {
      name: 'office',
      use: { ...devices['Desktop Chrome'] },
      testMatch: /.*onlyoffice.*\.e2e\.ts/,
    },
  ],
  ...(useDockerStack
    ? {}
    : {
        webServer: [
          {
            command: 'pnpm run dev --host 127.0.0.1',
            url: baseURL,
            reuseExistingServer: !process.env.CI,
            timeout: 120_000,
          },
        ],
      }),
})
