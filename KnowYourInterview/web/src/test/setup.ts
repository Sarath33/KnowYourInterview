// Extends Vitest's `expect` with jest-dom matchers (toBeInTheDocument, toHaveTextContent,
// etc.) and cleans up the jsdom document between tests. Loaded automatically for every
// test file via vitest.config.ts's `setupFiles`.
import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => {
  cleanup()
})
