import base from './vite.config.mjs'
export default {
  ...base,
  resolve: { ...base.resolve, dedupe: [...base.resolve.dedupe, '@vue/test-utils'] },
  test: {
    environment: 'jsdom',
    include: ['../desktop/tests/web/**/*.test.ts'],
    setupFiles: ['src/test/setup.ts'],
    clearMocks: true,
  },
}
