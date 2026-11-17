import { expect, Page } from '@playwright/test';

export type Client = 'full' | 'widget';
export const target = 'http://localhost:8080';
export const url = (client: Client, server = target) => client === 'full' ? server + '/' : 'http://localhost:4174/';
export const prefix = (client: Client) => client === 'full' ? 'bitbrush_' : 'bitbrush_widget_';
export const canvas = (page: Page, client: Client) => page.locator(client === 'full' ? '#canvas' : '.bitbrush-widget canvas');
export const bank = (page: Page, client: Client) => page.locator(client === 'full' ? '#bank-balance' : '.bbw-bank-label');
export const status = (page: Page, client: Client) => page.locator(client === 'full' ? '#status-text' : '.bbw-status-text');

export function observeSocket(page: Page) {
  const observed = { connected: false, messages: 0, bank: false };
  page.on('websocket', socket => {
    socket.on('framereceived', event => {
      // Retain only counters; never retain frames or CONNECT bearer headers.
      const payload = String(event.payload);
      if (payload.includes('CONNECTED')) observed.connected = true;
      if (payload.includes('MESSAGE')) {
        observed.messages++;
        if (payload.includes('/bank')) observed.bank = true;
      }
    });
  });
  return observed;
}

export async function ready(page: Page, client: Client) {
  await expect(status(page, client)).toHaveText(/\d+ online/, { timeout: 25000 });
  await expect(bank(page, client)).toHaveText(/\d+\/25/, { timeout: 12000 });
  if (client === 'widget') await expect(page.locator('.bbw-loading-overlay')).toHaveCount(0);
}

export async function onlyIdentity(page: Page, client: Client, expected: {uuid: string; username: string}) {
  const valid = await page.evaluate(({ prefix, expected }) =>
    localStorage.length === 2 && sessionStorage.length === 0 &&
    localStorage.getItem(prefix + 'uuid') === expected.uuid &&
    localStorage.getItem(prefix + 'username') === expected.username,
  { prefix: prefix(client), expected });
  expect(valid, 'E_IDENTITY_STORAGE').toBe(true);
}

export async function seed(page: Page, client: Client, identity: { uuid: string; username?: string }) {
  // Run only once per origin: clearing on every reload would hide persistence bugs.
  await page.addInitScript(({ prefix, identity }) => {
    if (!localStorage.getItem(prefix + 'uuid')) {
      localStorage.setItem(prefix + 'uuid', identity.uuid);
      if (identity.username) localStorage.setItem(prefix + 'username', identity.username);
    }
  }, { prefix: prefix(client), identity });
}

export async function substituteFullPageKey(page: Page) {
  const original = await (await page.request.get(target + '/')).text();
  const constant = "const TURNSTILE_SITE_KEY = '0x4AAAAAACwHh6lB9uQESFQA';";
  expect(original.split(constant).length - 1, 'E_SITE_KEY_COUNT').toBe(1);
  await page.route(target + '/', async route => {
    const response = await route.fetch();
    const body = await response.text();
    expect(body === original, 'E_PACKAGED_HTML_CHANGED').toBe(true);
    await route.fulfill({ response, body: body.replace(constant,
      "const TURNSTILE_SITE_KEY = '1x00000000000000000000BB';") });
  });
  return async () => {
    const unchanged = await (await page.request.get(target + '/')).text();
    expect(unchanged === original, 'E_PACKAGED_HTML_CHANGED').toBe(true);
  };
}
