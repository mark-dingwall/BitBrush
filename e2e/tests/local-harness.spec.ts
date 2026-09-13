import { expect, test } from '@playwright/test';
import { request as httpRequest } from 'node:http';
import { installRoutesFromNonSpecModule } from './support/local-harness-consumer';
import { LOCAL_ORIGIN, readBitbrushTestState } from './support/local-browser-harness';

function rawRequestStatus(pathname: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const request = httpRequest({ host: '127.0.0.1', port: 4173, path: pathname }, response => {
      response.resume();
      response.on('end', () => resolve(response.statusCode ?? 0));
    });
    request.on('error', reject);
    request.end();
  });
}

test.describe('deterministic local browser harness', () => {
  test('rejects encoded path traversal', async () => {
    await expect(rawRequestStatus('/%2e%2e/%2e%2e/etc/passwd')).resolves.toBe(403);
  });

  test('loads the full-page client with recorded local fakes', async ({ page }) => {
    const harness = await installRoutesFromNonSpecModule(page);
    await page.addInitScript(() => {
      localStorage.setItem('bitbrush_uuid', '11111111-2222-4333-8444-555555555555');
    });

    await page.goto(`${LOCAL_ORIGIN}/index.html`);

    await expect(page.locator('#canvas')).toBeVisible();
    await expect.poll(() => readBitbrushTestState(page).then(state => state?.stomp.subscriptions.length ?? 0)).toBe(5);
    // UUID reconnect does not wait for the independent Turnstile script.
    await expect.poll(() => readBitbrushTestState(page).then(state => state?.turnstile.renders.length ?? 0)).toBe(1);
    const state = await readBitbrushTestState(page);
    expect(state.turnstile.renders).toEqual([{ sitekey: '0x4AAAAAACwHh6lB9uQESFQA' }]);
    expect(state.stomp.constructorArgs).toHaveLength(1);
    expect(state.stomp.activations).toHaveLength(1);
    expect(state.stomp.activations[0]).toEqual(expect.any(Number));
    expect(state.stomp.connectHeaders[0]).toHaveProperty('uuid');
    expect(state.stomp.subscriptions).toEqual([
      '/topic/pixels', '/topic/users/count', '/app/users/count', '/user/queue/bank', '/app/bank'
    ]);
    expect(state.stomp.injectedMessages).toHaveLength(5);
    expect(harness.blockedExternalRequests).toEqual([]);
  });

  test('loads the widget client with recorded local fakes', async ({ page }) => {
    const harness = await installRoutesFromNonSpecModule(page);

    await page.goto(`${LOCAL_ORIGIN}/widget-host.html`);

    await expect(page.locator('.bitbrush-widget canvas')).toBeVisible();
    await expect.poll(() => readBitbrushTestState(page).then(state => state?.stomp.subscriptions.length ?? 0)).toBe(5);
    const state = await readBitbrushTestState(page);
    expect(state.sockjs).toEqual([{ url: LOCAL_ORIGIN + '/ws' }]);
    expect(state.turnstile.renders).toEqual([{ sitekey: 'local-turnstile-site-key' }]);
    expect(state.stomp.constructorArgs).toHaveLength(1);
    expect(state.stomp.activations).toHaveLength(1);
    expect(state.stomp.activations[0]).toEqual(expect.any(Number));
    expect(state.stomp.connectHeaders[0]).toHaveProperty('uuid');
    expect(state.stomp.subscriptions).toEqual([
      '/topic/pixels', '/topic/users/count', '/app/users/count', '/user/queue/bank', '/app/bank'
    ]);
    expect(state.stomp.injectedMessages).toHaveLength(5);
    expect(harness.blockedExternalRequests).toEqual([]);
  });
});
