import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: 'backend', workers: 1, retries: 0, timeout: 90000,
  outputDir: 'test-output/backend',
  reporter: [['./reporters/safe.ts', { outputFile: 'safe-results/backend/results.xml' }]],
  use: { browserName: 'chromium', headless: true, trace: 'off', screenshot: 'off', video: 'off', viewport: { width: 1200, height: 900 } },
  webServer: { command: 'node widget-server.mjs', url: 'http://localhost:4174', reuseExistingServer: false,
    stdout: 'ignore', stderr: 'ignore', env: { BITBRUSH_TARGET: 'http://localhost:8080' } },
});
