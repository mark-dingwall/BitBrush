import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: 'smoke', workers: 1, retries: 0, timeout: 60000,
  outputDir: 'test-output/smoke',
  reporter: [['./reporters/safe.ts', { outputFile: 'safe-results/smoke/results.xml' }]],
  use: { browserName: 'chromium', headless: true, trace: 'off', screenshot: 'off', video: 'off' },
  webServer: { command: 'node widget-server.mjs', url: 'http://localhost:4174', reuseExistingServer: false,
    stdout: 'ignore', stderr: 'ignore', env: { BITBRUSH_TARGET: 'https://bitbrush.fly.dev' } },
});
