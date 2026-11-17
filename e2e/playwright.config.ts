import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: 'tests',
  testMatch: '**/bitbrush-widget.spec.ts',
  timeout: 60000,
  outputDir: 'test-output/portfolio',
  reporter: [['./reporters/safe.ts', { outputFile: 'safe-results/portfolio/results.xml' }]],
  use: {
    baseURL: 'https://mark.dingwall.com.au',
    headless: true,
    screenshot: 'off',
    video: 'off',
    trace: 'off',
  },
});
