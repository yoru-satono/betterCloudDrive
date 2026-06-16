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
  reporter: process.env.CI ? [['html', { open: 'never' }], ['list']] : 'list',
  use: {
    baseURL,
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
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      testMatch: /.*\.e2e\.ts/,
    },
  ],
  ...(useDockerStack
    ? {}
    : {
        webServer: [
          {
            command: 'npm run dev -- --host 127.0.0.1',
            url: baseURL,
            reuseExistingServer: !process.env.CI,
            timeout: 120_000,
          },
        ],
      }),
})
