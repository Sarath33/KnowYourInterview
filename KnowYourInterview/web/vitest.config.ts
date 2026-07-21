import { defineConfig, mergeConfig } from 'vitest/config'
import viteConfig from './vite.config'

// Separate from vite.config.ts on purpose: keeps test-only config (jsdom, setup
// files, coverage) out of the production build config, while still reusing the
// same plugins (React) via mergeConfig so JSX/fast-refresh behave identically
// in tests and in `vite build`.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      globals: false,
      setupFiles: ['./src/test/setup.ts'],
      css: true,
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        exclude: ['src/main.tsx', 'src/vite-env.d.ts', 'src/test/**'],
      },
    },
  }),
)
