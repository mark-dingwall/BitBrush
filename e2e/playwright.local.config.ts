import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: 'tests',
  testMatch: [
    '**/local-harness.spec.ts',
    '**/identity-full-page.spec.ts',
    '**/identity-widget.spec.ts',
  ],
  timeout: 60000,
  use: {
    baseURL: 'http://127.0.0.1:4173',
    headless: true,
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  webServer: {
    command: 'node local-server.mjs',
    url: 'http://127.0.0.1:4173/index.html',
    reuseExistingServer: false,
  },
});
