// Preserve production address/share behavior; only desktop detection is replaced.
export * from '../../frontend/src/config/runtime'
export const isDesktopRuntime = () => true
