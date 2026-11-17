import { expect, test } from '@playwright/test';
import { Client, observeSocket, onlyIdentity, ready, seed, url } from '../support/real-client';

const server = 'https://bitbrush.fly.dev';
for (const client of ['full', 'widget'] as Client[]) {
  test(`deployed ${client} reconnect and transport`, async ({ page }) => {
    const uuid = process.env.BITBRUSH_E2E_UUID;
    expect(Boolean(uuid && /^[0-9a-f-]{36}$/.test(uuid)), 'E_SMOKE_IDENTITY_REQUIRED').toBe(true);
    // Fail closed against accidental future writes, even if UI startup changes.
    await page.route('**/api/**', route => {
      const request = route.request();
      const reconnect = request.method() === 'POST' && request.url() === server + '/api/users/reconnect';
      if (request.method() === 'GET' || request.method() === 'OPTIONS' || reconnect) return route.continue();
      return route.abort('blockedbyclient');
    });
    const health = await page.request.get(server + '/actuator/health');
    expect(health.status(), 'E_FLY_HEALTH').toBe(200);
    expect((await health.json()).status === 'UP', 'E_FLY_HEALTH').toBe(true);
    await seed(page, client, { uuid: uuid! });
    const socket = observeSocket(page);
    const reconnect = page.waitForResponse(r => r.url() === server + '/api/users/reconnect');
    const snapshot = page.waitForResponse(r => r.url() === server + '/api/canvas');
    await page.goto(url(client, server));
    const response = await reconnect;
    expect(response.status(), 'E_SMOKE_RECONNECT').toBe(200);
    const identity = await response.json();
    expect(identity.uuid === uuid, 'E_SMOKE_IDENTITY').toBe(true);
    expect((await snapshot).status(), 'E_SMOKE_CANVAS').toBe(200);
    await ready(page, client);
    await expect.poll(() => socket.connected && socket.bank && socket.messages >= 2, { message: 'E_SMOKE_WEBSOCKET' }).toBe(true);
    await onlyIdentity(page, client, identity);
  });
}
