import { afterEach, beforeEach, vi } from 'vitest'

const unexpectedConsoleErrors: unknown[][] = []
const originalFetch = globalThis.fetch

beforeEach(() => {
  unexpectedConsoleErrors.length = 0
  vi.spyOn(console, 'error').mockImplementation((...args: unknown[]) => {
    unexpectedConsoleErrors.push(args)
  })
  globalThis.fetch = vi.fn(async (input: RequestInfo | URL) => {
    throw new Error(`Unexpected network request in unit test: ${String(input)}`)
  }) as typeof fetch
})

vi.mock('vue-sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
    info: vi.fn(),
    warning: vi.fn(),
  },
}))

Object.defineProperty(globalThis.URL, 'createObjectURL', {
  configurable: true,
  value: vi.fn(() => 'blob:mock-url'),
})

Object.defineProperty(globalThis.URL, 'revokeObjectURL', {
  configurable: true,
  value: vi.fn(),
})

if (!globalThis.crypto.randomUUID) {
  Object.defineProperty(globalThis.crypto, 'randomUUID', {
    configurable: true,
    value: vi.fn(() => 'mock-uuid'),
  })
}

afterEach(() => {
  globalThis.fetch = originalFetch
  if (unexpectedConsoleErrors.length) {
    const rendered = unexpectedConsoleErrors.map((args) => args.map(String).join(' ')).join('\n')
    unexpectedConsoleErrors.length = 0
    throw new Error(`Unexpected console.error in unit test:\n${rendered}`)
  }
  vi.clearAllMocks()
  vi.restoreAllMocks()
  localStorage.clear()
})
