/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // UI dev server against a running stack: API calls go to the real memes service
    // the e2e harness points this at its own memes instance; a human gets the stack's default
    proxy: { '/memes': process.env.MEMES_URL ?? 'http://localhost:8083' },
  },
  test: {
    // The unit suite exists for the states the BROWSER suite cannot hold still. e2e/ drives a real
    // stack, so anything that lives only BETWEEN two events — a favourite tile waiting for the
    // deletion cascade, a banner between a failed fetch and its retry — is a race there and a plain
    // assertion here. It does not replace e2e: nothing under this config talks to a service.
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    // e2e/ is cucumber-js + Playwright and owns its own runner; vitest must not collect it
    exclude: ['e2e/**', 'node_modules/**'],
  },
});
