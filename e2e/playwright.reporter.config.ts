import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: 'reporter-fixture', workers: 1, retries: 0, timeout: 5000,
  reporter: [['./reporters/safe.ts', { outputFile: process.env.SAFE_REPORT_FILE }]],
  outputDir: process.env.SAFE_RAW_DIR,
  use: { trace: 'off', screenshot: 'off', video: 'off' },
});
