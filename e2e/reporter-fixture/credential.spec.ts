import { test, expect } from '@playwright/test';
test('synthetic credential failure', async ({}, testInfo) => {
  if (process.env.REPORTER_FIXTURE_MODE === 'success') return;
  if (process.env.REPORTER_FIXTURE_MODE === 'timeout') await new Promise(resolve => setTimeout(resolve, 60000));
  const secret = process.env.REPORTER_SENTINEL!;
  console.log(secret);
  console.error(secret);
  await testInfo.attach('credential', { body: secret, contentType: 'text/plain' });
  expect(secret).toBe('designated mismatch');
});
if (process.env.REPORTER_FIXTURE_MODE === 'unrelated') {
  test('unrelated failure', () => { throw new Error('unrelated'); });
}
