import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { bank, canvas, Client, observeSocket, onlyIdentity, prefix, ready, seed, substituteFullPageKey, target, url } from '../support/real-client';

const PIN = 'A😀b!'; // Four code points; five UTF-16 code units.
test.describe.configure({ mode: 'serial' });

for (const [index, client] of (['full', 'widget'] as Client[]).entries()) {
  test(`real ${client} identity and shared canvas`, async ({ page, browser }) => {
    const username = `CI_${client}_${randomUUID().slice(0, 8)}`;
    const unchanged = client === 'full' ? await substituteFullPageKey(page) : async () => {};
    const socket = observeSocket(page);
    await page.goto(url(client));
    await page.getByLabel('Username', { exact: true }).fill(username);
    await page.getByLabel('PIN', { exact: true }).fill(PIN);
    await page.getByLabel('Confirm PIN', { exact: true }).fill(PIN);
    // This milestone requires a browser-issued Turnstile token and real Siteverify.
    const created = page.waitForResponse(r => r.url() === target + '/api/users' && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Create account', exact: true }).click();
    const response = await created;
    expect(response.status(), 'E_REAL_CREATE').toBe(201);
    const identity = await response.json();
    expect(identity.username === username && typeof identity.uuid === 'string', 'E_CREATE_IDENTITY').toBe(true);
    await ready(page, client);
    await expect.poll(() => socket.connected, { message: 'E_REAL_STOMP' }).toBe(true);
    const balance = Number((await bank(page, client).innerText()).split('/')[0]);
    await expect.poll(async () => Number((await bank(page, client).innerText()).split('/')[0]),
      { timeout: 12000, message: 'E_BANK_TICK' }).toBeGreaterThan(balance);
    await onlyIdentity(page, client, identity);

    await page.evaluate(() => localStorage.clear());
    await page.reload();
    await page.getByRole('button', { name: 'Already have one? Log in' }).click();
    await page.getByLabel('Username', { exact: true }).fill(username);
    await page.getByLabel('PIN', { exact: true }).fill('Bad?');
    const incorrect = page.waitForResponse(r => r.url() === target + '/api/users/recover');
    await page.getByRole('button', { name: 'Log in', exact: true }).click();
    expect((await incorrect).status(), 'E_RECOVERY_REJECTION').toBe(401);
    expect(await page.evaluate(() => localStorage.length === 0), 'E_FAILED_RECOVERY_STORAGE').toBe(true);
    await page.getByLabel('PIN', { exact: true }).fill(PIN);
    const recovered = page.waitForResponse(r => r.url() === target + '/api/users/recover');
    await page.getByRole('button', { name: 'Log in', exact: true }).click();
    const recovery = await recovered;
    expect(recovery.status(), 'E_REAL_RECOVERY').toBe(200);
    const restored = await recovery.json();
    expect(restored.uuid === identity.uuid && restored.username === identity.username, 'E_RECOVERED_IDENTITY').toBe(true);
    await ready(page, client);
    await onlyIdentity(page, client, identity);

    const reconnected = page.waitForResponse(r => r.url() === target + '/api/users/reconnect');
    await page.reload();
    expect((await reconnected).status(), 'E_RECONNECT').toBe(200);
    await ready(page, client);
    await onlyIdentity(page, client, identity);

    // Independently loaded observer must already be subscribed before placement.
    const observerContext = await browser.newContext({ viewport: { width: 1200, height: 900 } });
    try {
      const observer = await observerContext.newPage();
      if (client === 'full') await substituteFullPageKey(observer);
      await seed(observer, client, identity);
      const observerSocket = observeSocket(observer);
      const snapshot = observer.waitForResponse(r => r.url() === target + '/api/canvas');
      await observer.goto(url(client));
      expect((await snapshot).status(), 'E_OBSERVER_SNAPSHOT').toBe(200);
      await ready(observer, client);
      await expect.poll(() => observerSocket.connected).toBe(true);
      const x = 120 + index * 10, y = 120;
      const swatch = page.locator(client === 'full' ? '#palette .swatch' : '.bbw-palette .bbw-swatch').nth(5);
      await swatch.click();
      const color = await swatch.evaluate(el => getComputedStyle(el).backgroundColor.match(/\d+/g)!.map(Number));
      const placed = page.waitForResponse(r => r.url() === target + '/api/pixels' && r.request().method() === 'POST');
      const box = await canvas(page, client).boundingBox();
      expect(Boolean(box), 'E_CANVAS_VISIBLE').toBe(true);
      await page.mouse.click(box!.x + (x + .5) / 250 * box!.width, box!.y + (y + .5) / 250 * box!.height);
      expect((await placed).status(), 'E_UI_PLACEMENT').toBe(201);
      await expect.poll(async () => {
        const response = await page.request.get(target + '/api/canvas');
        if (!response.ok()) return false;
        const pixels = await response.json();
        return pixels.some((p: any) => p.x === x && p.y === y && p.color.toLowerCase() === '#' + color.map((c: number) => c.toString(16).padStart(2, '0')).join(''));
      }, { message: 'E_CANVAS_PERSISTENCE' }).toBe(true);
      await expect.poll(async () => canvas(observer, client).evaluate((el, { x, y, color }) => {
        const rgba = (el as HTMLCanvasElement).getContext('2d')!.getImageData(x * 2, y * 2, 1, 1).data;
        return color.every((value: number, i: number) => value === rgba[i]) && rgba[3] === 255;
      }, { x, y, color }), { message: 'E_BROADCAST_RENDER' }).toBe(true);
    } finally { await observerContext.close(); }

    await unchanged();
  });
}
