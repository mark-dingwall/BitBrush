import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: 'tests',
  testMatch: '**/bitbrush-widget.spec.ts',
  timeout: 60000,
  use: {
    baseURL: 'https://mark.dingwall.com.au',
    headless: true,
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
});
