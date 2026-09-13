import { expect, Page, Request, test } from '@playwright/test';
import {
  deliverStompMessage,
  expireTurnstile,
  installLocalBrowserHarness,
  readBitbrushTestState,
} from './support/local-browser-harness';

const PIN = 'A😀b!';
const DIFFERENT_PIN = 'Xyz?';
const SAVED_UUID = '11111111-2222-4333-8444-555555555555';
const SERVER_UUID = '99999999-aaaa-4bbb-8ccc-dddddddddddd';

// Credential-bearing requests and input values must not enter trace/video artifacts.
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>(done => { resolve = done; });
  return { promise, resolve };
}

async function seedIdentity(page: Page, entries: Record<string, string>) {
  await page.addInitScript(values => {
    for (const [key, value] of Object.entries(values)) localStorage.setItem(key, value);
  }, entries);
}

async function expectNoStomp(page: Page) {
  const state = await readBitbrushTestState(page);
  expect(state?.stomp.constructorArgs.length ?? 0, 'STOMP must wait for confirmed identity').toBe(0);
  expect(state?.stomp.activations.length ?? 0, 'STOMP must not activate before identity settles').toBe(0);
}

async function expectConnectedAs(page: Page, uuid: string) {
  await expect.poll(async () => (await readBitbrushTestState(page))?.stomp.activations.length ?? 0).toBe(1);
  const headers = (await readBitbrushTestState(page)).stomp.connectHeaders;
  expect(headers.length === 1 && Object.keys(headers[0]).length === 1 && headers[0].uuid === uuid,
    'CONNECT must contain only the authoritative identity header').toBe(true);
}

async function expectNoStoredPin(page: Page) {
  const absent = await page.evaluate(markers => [localStorage, sessionStorage].every(storage =>
    Object.entries(storage).every(([key, value]) =>
      !key.toLowerCase().includes('pin') && markers.every(marker => !key.includes(marker) && !value.includes(marker)))),
  [PIN, DIFFERENT_PIN]);
  expect(absent, 'PIN material must be absent from browser storage').toBe(true);
}

async function expectStoredIdentity(page: Page, uuid: string, username: string) {
  await expectNoStoredPin(page);
  const matches = await page.evaluate(expected =>
    localStorage.length === 2 && sessionStorage.length === 0 &&
    localStorage.getItem('bitbrush_uuid') === expected.uuid &&
    localStorage.getItem('bitbrush_username') === expected.username,
  { uuid, username });
  expect(matches, 'Only the authoritative UUID and username may be persisted').toBe(true);
}

async function expectEmptyIdentity(page: Page) {
  await expectNoStoredPin(page);
  expect(await page.evaluate(() => localStorage.length === 0 && sessionStorage.length === 0),
    'Unconfirmed or stale identity must not be persisted').toBe(true);
}

async function expectClearedPins(page: Page) {
  const cleared = await page.locator('#modal input[type="password"]').evaluateAll(inputs =>
    inputs.length === 2 && inputs.every(input => (input as HTMLInputElement).value === ''));
  expect(cleared, 'Both transient PIN fields must be cleared').toBe(true);
}

async function fillCreate(page: Page, pin = PIN, confirmation = PIN) {
  await expect(page.getByLabel('PIN', { exact: true })).toBeVisible();
  await page.getByLabel('Username', { exact: true }).fill('Painter');
  await page.getByLabel('PIN', { exact: true }).fill(pin);
  await page.getByLabel('Confirm PIN', { exact: true }).fill(confirmation);
}

async function fillRecovery(page: Page) {
  await expect(page.getByRole('button', { name: 'Already have one? Log in' })).toBeVisible();
  await page.getByRole('button', { name: 'Already have one? Log in' }).click();
  await page.getByLabel('Username', { exact: true }).fill('Painter');
  await page.getByLabel('PIN', { exact: true }).fill(PIN);
}

test('switches the two identity modes and clears masked fields without creating an identity', async ({ page }) => {
  // Break: eagerly persisting a UUID, retaining PINs on a mode change, or treating UTF-16 length as the PIN limit.
  await installLocalBrowserHarness(page);
  await page.goto('/index.html');
  await expect(page.getByRole('button', { name: 'Create account', exact: true })).toBeVisible();
  await expectEmptyIdentity(page);
  await expectNoStomp(page);
  await fillCreate(page);
  const pinInputs = page.locator('#modal input[type="password"]');
  expect(await pinInputs.evaluateAll(inputs => inputs.length === 2 && inputs.every(input =>
    input.getAttribute('autocomplete') === 'new-password' && !input.hasAttribute('maxlength')))).toBe(true);

  await page.getByRole('button', { name: 'Already have one? Log in' }).click();
  await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeVisible();
  await expect(page.getByLabel('Confirm PIN', { exact: true })).toBeHidden();
  await expect(page.getByLabel('PIN', { exact: true })).toHaveAttribute('autocomplete', 'current-password');
  await expectClearedPins(page);
  await page.getByLabel('PIN', { exact: true }).fill(PIN);
  await page.getByRole('button', { name: 'Need an account? Create one' }).click();
  await expect(page.getByLabel('Confirm PIN', { exact: true })).toBeVisible();
  await expectClearedPins(page);
  await expectEmptyIdentity(page);
  await expectNoStomp(page);
});

for (const mode of ['create', 'recover']) {
  test(`keeps ${mode} usable after scrolling the identity dialog on a short viewport`, async ({ page }) => {
    // Break: a vertically centered, unbounded dialog leaves the login action below the viewport without scrolling.
    await page.setViewportSize({ width: 667, height: 375 });
    await installLocalBrowserHarness(page);
    await page.route(mode === 'create' ? '**/api/users' : '**/api/users/recover', route => route.fulfill({
      status: mode === 'create' ? 201 : 200,
      json: { uuid: SERVER_UUID, username: 'Short_Viewport_Painter' },
    }));
    await page.goto('/index.html');
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.mouse.move(333, 187);
    await page.mouse.wheel(0, 600);
    const loginToggle = page.getByRole('button', { name: 'Already have one? Log in' });
    await expect(loginToggle).toBeInViewport({ ratio: 1 });
    await loginToggle.click();

    if (mode === 'create') {
      await page.getByRole('button', { name: 'Need an account? Create one' }).click();
      await fillCreate(page);
      await page.getByRole('button', { name: 'Create account', exact: true }).click();
    } else {
      await page.getByLabel('Username', { exact: true }).fill('Painter');
      await page.getByLabel('PIN', { exact: true }).fill(PIN);
      await page.getByRole('button', { name: 'Log in', exact: true }).click();
    }
    await expect(page.getByRole('dialog')).toBeHidden();
    await expectClearedPins(page);
    await expectStoredIdentity(page, SERVER_UUID, 'Short_Viewport_Painter');
    await expectConnectedAs(page, SERVER_UUID);
  });
}

test('creates with opaque Unicode PINs and waits for the authoritative response before STOMP', async ({ page }) => {
  // Break: modifying PIN strings, missing confirmation, persisting the proposed UUID, or connecting before creation resolves.
  const reply = deferred();
  let request: Request | undefined;
  await installLocalBrowserHarness(page);
  await page.route('**/api/users', async route => {
    request = route.request();
    await reply.promise;
    await route.fulfill({ status: 201, json: { uuid: SERVER_UUID, username: 'Server_Painter' } });
  });
  await page.goto('/index.html');
  await fillCreate(page);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect.poll(() => Boolean(request), { message: 'Create must submit to the identity API' }).toBe(true);
  const body = request!.postDataJSON();
  expect(Object.keys(body).sort()).toEqual(['pin', 'pinConfirmation', 'username', 'uuid']);
  expect(body.pin === PIN && body.pinConfirmation === PIN, 'PIN fields must be submitted unchanged').toBe(true);
  expect(body.username).toBe('Painter');
  expect(typeof body.uuid === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(body.uuid),
    'Create must propose a temporary canonical UUID').toBe(true);
  await expectEmptyIdentity(page);
  await expectNoStomp(page);
  await expect(page.getByRole('button', { name: 'Create account', exact: true })).toBeDisabled();
  reply.resolve();
  await expect(page.getByRole('dialog')).toBeHidden();
  await expectClearedPins(page);
  await expectStoredIdentity(page, SERVER_UUID, 'Server_Painter');
  await expectConnectedAs(page, SERVER_UUID);
});

test('renders server confirmation validation and clears both PINs after create failure', async ({ page }) => {
  // Break: local PIN equality checking blocks server canonicalization, or a failure keeps credential fields populated.
  let request: Request | undefined;
  await installLocalBrowserHarness(page);
  await page.route('**/api/users', async route => {
    request = route.request();
    await route.fulfill({ status: 400, json: { type: 'about:blank', title: 'Bad Request', status: 400, detail: 'Invalid PIN' } });
  });
  await page.goto('/index.html');
  await fillCreate(page, PIN, DIFFERENT_PIN);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Invalid PIN');
  const body = request!.postDataJSON();
  expect(body.pin === PIN && body.pinConfirmation === DIFFERENT_PIN,
    'Confirmation validation must receive both original PIN strings').toBe(true);
  await expectClearedPins(page);
  await expectEmptyIdentity(page);
  await expectNoStomp(page);
  await expect(page.getByRole('dialog')).toBeVisible();
});

test('sends invalid PIN whitespace unchanged for server validation', async ({ page }) => {
  // Break: trimming PINs before the server validates their opaque Unicode content.
  const rawPin = ` ${PIN} `;
  let unchanged = false;
  await installLocalBrowserHarness(page);
  await page.route('**/api/users', async route => {
    const body = route.request().postDataJSON();
    unchanged = body.pin === rawPin && body.pinConfirmation === rawPin;
    await route.fulfill({ status: 400, json: { type: 'about:blank', title: 'Bad Request', status: 400, detail: 'Invalid PIN' } });
  });
  await page.goto('/index.html');
  await fillCreate(page, rawPin, rawPin);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Invalid PIN');
  expect(unchanged, 'PIN whitespace must reach server validation unchanged').toBe(true);
  await expectClearedPins(page);
  await expectEmptyIdentity(page);
});

test('recovers with only username and PIN and waits before constructing STOMP', async ({ page }) => {
  // Break: creating a new UUID during recovery or connecting with anything except the recovered UUID.
  const reply = deferred();
  let request: Request | undefined;
  await installLocalBrowserHarness(page);
  await page.route('**/api/users/recover', async route => {
    request = route.request();
    await reply.promise;
    await route.fulfill({ json: { uuid: SERVER_UUID, username: 'Recovered_Painter' } });
  });
  await page.goto('/index.html');
  await fillRecovery(page);
  await page.getByRole('button', { name: 'Log in', exact: true }).click();
  await expect.poll(() => Boolean(request), { message: 'Recovery must use its dedicated endpoint' }).toBe(true);
  const body = request!.postDataJSON();
  expect(Object.keys(body).sort()).toEqual(['pin', 'username']);
  expect(body.pin === PIN, 'Recovery must submit the original PIN string').toBe(true);
  expect(body.username).toBe('Painter');
  await expectEmptyIdentity(page);
  await expectNoStomp(page);
  reply.resolve();
  await expect(page.getByRole('dialog')).toBeHidden();
  await expectClearedPins(page);
  await expectStoredIdentity(page, SERVER_UUID, 'Recovered_Painter');
  await expectConnectedAs(page, SERVER_UUID);
});

for (const failure of [
  { status: 401, title: 'Unauthorized', detail: 'Invalid username or PIN' },
  { status: 429, title: 'Too Many Requests', detail: 'Identity recovery is temporarily unavailable' },
  { status: 503, title: 'Service Unavailable', detail: 'PIN credential processing is temporarily unavailable' },
]) {
  test(`clears recovery PIN and preserves the form after HTTP ${failure.status}`, async ({ page }) => {
    // Break: persisting an unsuccessful recovery, closing its modal, or retaining the PIN for a retry.
    await installLocalBrowserHarness(page);
    await page.route('**/api/users/recover', route => route.fulfill({
      status: failure.status, headers: { 'Retry-After': '5' }, json: { type: 'about:blank', ...failure },
    }));
    await page.goto('/index.html');
    await fillRecovery(page);
    await page.getByRole('button', { name: 'Log in', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText(failure.detail);
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeEnabled();
    await expectClearedPins(page);
    await expectEmptyIdentity(page);
    await expectNoStomp(page);
  });
}

for (const storedUsername of [undefined, 'Outdated_Painter']) {
  test(`reconnects with UUID only when username is ${storedUsername ? 'outdated' : 'missing'}`, async ({ page }) => {
    // Break: depending on a stored username, posting to create, sending a PIN, or using stale identity before the response.
    const reply = deferred();
    let request: Request | undefined;
    await installLocalBrowserHarness(page);
    const entries: Record<string, string> = { bitbrush_uuid: SAVED_UUID };
    if (storedUsername) entries.bitbrush_username = storedUsername;
    await seedIdentity(page, entries);
    await page.route('**/api/users/reconnect', async route => {
      request = route.request();
      await reply.promise;
      await route.fulfill({ json: { uuid: SERVER_UUID, username: 'Current_Painter' } });
    });
    await page.goto('/index.html');
    await expect.poll(() => Boolean(request), { message: 'A saved UUID must use reconnect' }).toBe(true);
    const body = request!.postDataJSON();
    expect(Object.keys(body)).toEqual(['uuid']);
    expect(body.uuid === SAVED_UUID, 'Reconnect must submit the saved UUID').toBe(true);
    expect(request!.headers()['x-turnstile-token'] === undefined, 'Reconnect must not consume a Turnstile token').toBe(true);
    await expectNoStomp(page);
    reply.resolve();
    await expectConnectedAs(page, SERVER_UUID);
    await expectStoredIdentity(page, SERVER_UUID, 'Current_Painter');
    await expect(page.getByRole('dialog')).toBeHidden();
  });
}

test('clears both stale identity entries on reconnect 404 and opens create', async ({ page }) => {
  // Break: treating unknown UUID as a new account or retaining either half of a stale identity.
  await installLocalBrowserHarness(page);
  await seedIdentity(page, { bitbrush_uuid: SAVED_UUID, bitbrush_username: 'Stale_Painter' });
  await page.route('**/api/users/reconnect', route => route.fulfill({
    status: 404, json: { type: 'about:blank', title: 'User Not Found', status: 404, detail: 'User not found' },
  }));
  await page.goto('/index.html');
  await expect(page.getByRole('button', { name: 'Create account', exact: true })).toBeVisible();
  await expectEmptyIdentity(page);
  await expectNoStomp(page);
});

test('discards a username without a UUID before offering create or login', async ({ page }) => {
  // Break: generating and persisting a replacement UUID for an orphaned username.
  let identityRequests = 0;
  await installLocalBrowserHarness(page);
  await seedIdentity(page, { bitbrush_username: 'Orphaned_Painter' });
  await page.route('**/api/users**', async route => { identityRequests++; await route.fulfill({ status: 500 }); });
  await page.goto('/index.html');
  await expect(page.getByRole('button', { name: 'Create account', exact: true })).toBeVisible();
  await expectEmptyIdentity(page);
  expect(identityRequests).toBe(0);
  await expectNoStomp(page);
});

test('refreshes expired and rejected Turnstile tokens before a successful retry', async ({ page }) => {
  // Break: sending an expired token or reusing the rejected challenge on a second create request.
  const tokens: Array<string | undefined> = [];
  await installLocalBrowserHarness(page);
  await page.route('**/api/users', async route => {
    tokens.push(route.request().headers()['x-turnstile-token']);
    if (tokens.length === 1) {
      await route.fulfill({ status: 403, json: { type: 'about:blank', title: 'Forbidden', status: 403, detail: 'Turnstile verification failed' } });
    } else {
      await route.fulfill({ status: 201, json: { uuid: SERVER_UUID, username: 'Verified_Painter' } });
    }
  });
  await page.goto('/index.html');
  await fillCreate(page);
  await expect.poll(async () => (await readBitbrushTestState(page))?.turnstile.renders.length ?? 0).toBe(1);
  await expireTurnstile(page);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Verification failed');
  await expectClearedPins(page);
  await expectNoStomp(page);
  await fillCreate(page);
  await page.getByRole('button', { name: 'Create account', exact: true }).click();
  await expectConnectedAs(page, SERVER_UUID);
  expect(tokens.length === 2 && tokens.every(Boolean) && tokens[0] !== tokens[1],
    'Each attempt must carry a fresh verification token').toBe(true);
  expect((await readBitbrushTestState(page)).turnstile.resets.length).toBeGreaterThanOrEqual(2);
  await expectStoredIdentity(page, SERVER_UUID, 'Verified_Painter');
});

test('does not send private UUID or PIN markers to console through STOMP debug', async ({ page }) => {
  // Break: forwarding raw STOMP CONNECT frames to a console logger.
  const consoleText: string[] = [];
  page.on('console', message => consoleText.push(message.text()));
  await installLocalBrowserHarness(page);
  await seedIdentity(page, { bitbrush_uuid: SAVED_UUID, bitbrush_username: 'Painter' });
  await page.goto('/index.html');
  await expectConnectedAs(page, SAVED_UUID);
  await page.evaluate(markers => {
    for (const options of window.__bitbrushTest.stomp.constructorArgs) {
      if (typeof options.debug === 'function') options.debug('>>> CONNECT\nuuid:' + markers.uuid + '\npin:' + markers.pin + '\n\n');
    }
  }, { uuid: SAVED_UUID, pin: PIN });
  const output = consoleText.join('\n');
  expect(output.indexOf(SAVED_UUID) === -1, 'Browser console must not contain private identity material').toBe(true);
  expect(output.indexOf(PIN) === -1, 'Browser console must not contain PIN material').toBe(true);
});

test('uses public author IDs for tooltip caching and invalidates both authors on broadcasts and erasure', async ({ page }) => {
  // Break: keying cache entries with the removed authorUuid field, or failing to invalidate old/new author highlights.
  const infoRequests: number[] = [];
  const authors = new Map([[125, 'author_alpha'], [126, 'author_alpha'], [127, 'author_beta'], [128, 'author_beta']]);
  await installLocalBrowserHarness(page);
  await seedIdentity(page, { bitbrush_uuid: SAVED_UUID, bitbrush_username: 'Painter' });
  await page.route('**/api/pixels/*/*/info', async route => {
    const x = Number(new URL(route.request().url()).pathname.split('/')[3]);
    infoRequests.push(x);
    const authorId = authors.get(x);
    if (!authorId) { await route.fulfill({ status: 404 }); return; }
    await route.fulfill({ json: {
      authorId, username: authorId === 'author_alpha' ? 'Alpha' : 'Beta', placedAt: '2026-09-12T00:00:00Z',
      authorPixels: [...authors].filter(([, id]) => id === authorId).map(([x]) => ({ x, y: 125 })),
    } });
  });
  await page.goto('/index.html');
  await expectConnectedAs(page, SAVED_UUID);
  await expect(page.locator('#loading-overlay')).toBeHidden();
  const canvas = await page.locator('#canvas').boundingBox();
  async function hover(x: number, username: string) {
    await page.mouse.move(canvas!.x + (x + 0.5) * canvas!.width / 250, canvas!.y + 125.5 * canvas!.height / 250);
    await expect(page.locator('#tooltip')).toBeVisible();
    await expect(page.locator('#tooltip-username')).toHaveText(username);
  }
  await hover(125, 'Alpha');
  await hover(126, 'Alpha');
  await hover(127, 'Beta');
  await hover(128, 'Beta');
  expect(infoRequests).toEqual([125, 127]);

  authors.set(125, 'author_beta');
  await deliverStompMessage(page, '/topic/pixels', { x: 125, y: 125, color: '#00CC00', authorId: 'author_beta', erased: false });
  await hover(126, 'Alpha');
  await hover(127, 'Beta');
  expect(infoRequests).toEqual([125, 127, 126, 127]);

  authors.delete(127);
  await deliverStompMessage(page, '/topic/pixels', { x: 127, y: 125, color: '#000000', authorId: 'author_beta', erased: true });
  await page.mouse.move(1, 1);
  await page.mouse.move(canvas!.x + 127.5 * canvas!.width / 250, canvas!.y + 125.5 * canvas!.height / 250);
  await expect.poll(() => infoRequests.length).toBe(5);
  await expect(page.locator('#tooltip')).toBeHidden();
  await hover(128, 'Beta');
  expect(infoRequests).toEqual([125, 127, 126, 127, 127, 128]);
});
