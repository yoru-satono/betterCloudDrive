import { afterEach, vi } from 'vitest'

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
  vi.clearAllMocks()
  localStorage.clear()
})
