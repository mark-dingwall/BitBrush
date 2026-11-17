import { test, expect } from '@playwright/test';
import { writeFileSync } from 'node:fs';
test('synthetic credential failure', async ({}, testInfo) => {
  if (process.env.REPORTER_FIXTURE_MODE === 'success') return;
  if (process.env.REPORTER_FIXTURE_MODE === 'same-test-error') throw new Error('Unrelated fixture error');
  if (process.env.REPORTER_FIXTURE_MODE === 'timeout') await new Promise(resolve => setTimeout(resolve, 60000));
  const secret = process.env.REPORTER_SENTINEL!;
  console.log(secret);
  console.error(secret);
  await testInfo.attach('credential', { body: secret, contentType: 'text/plain' });
  try {
    expect(secret).toBe('designated mismatch');
  } catch (expected) {
    // The marker is private and constant; the raw assertion still carries the sentinel.
    writeFileSync(process.env.REPORTER_EXPECTED_FILE!, 'E_EXPECTED_CREDENTIAL_FAILURE', { flag: 'wx' });
    throw expected;
  }
});
if (process.env.REPORTER_FIXTURE_MODE === 'unrelated') {
  test('unrelated failure', () => { throw new Error('unrelated'); });
}
