import { expect, Page, test } from '@playwright/test';
import { request as httpRequest } from 'node:http';

const LOCAL_ORIGIN = 'http://127.0.0.1:4173';

const FAKE_SOCKJS = `
  (() => {
    const state = window.__bitbrushTest ||= {
      sockjs: [], stomp: { constructorArgs: [], activations: [], connectHeaders: [], subscriptions: [], injectedMessages: [] }, turnstile: { renders: [] }
    };
    window.SockJS = function SockJS(url) { state.sockjs.push({ url }); this.url = url; };
  })();
`;

const FAKE_STOMP = `
  (() => {
    const state = window.__bitbrushTest ||= {
      sockjs: [], stomp: { constructorArgs: [], activations: [], connectHeaders: [], subscriptions: [], injectedMessages: [] }, turnstile: { renders: [] }
    };
    const bodies = {
      '/topic/pixels': '{"x":1,"y":2,"color":"#00CC00"}',
      '/topic/users/count': '1',
      '/app/users/count': '1',
      '/user/queue/bank': '{"balance":3,"maxBalance":5,"secondsUntilNextPoint":30}',
      '/app/bank': '{"balance":3,"maxBalance":5,"secondsUntilNextPoint":30}'
    };
    class Client {
      constructor(options) {
        this.options = options;
        state.stomp.constructorArgs.push(options);
        state.stomp.connectHeaders.push(options.connectHeaders || {});
      }
      activate() {
        state.stomp.activations.push(Date.now());
        this.socket = this.options.webSocketFactory && this.options.webSocketFactory();
        queueMicrotask(() => this.onConnect && this.onConnect({ command: 'CONNECTED', headers: {} }));
      }
      subscribe(destination, callback) {
        state.stomp.subscriptions.push(destination);
        if (Object.hasOwn(bodies, destination)) {
          const body = bodies[destination];
          state.stomp.injectedMessages.push({ destination, body });
          callback({ body });
        }
        return { id: destination, unsubscribe() {} };
      }
      deactivate() { return Promise.resolve(); }
    }
    window.StompJs = { Client };
  })();
`;

const FAKE_TURNSTILE = `
  (() => {
    const state = window.__bitbrushTest ||= {
      sockjs: [], stomp: { constructorArgs: [], activations: [], connectHeaders: [], subscriptions: [], injectedMessages: [] }, turnstile: { renders: [] }
    };
    window.turnstile = {
      render(container, options) {
        state.turnstile.renders.push({ sitekey: options.sitekey });
        queueMicrotask(() => options.callback && options.callback('local-turnstile-token'));
        return state.turnstile.renders.length;
      },
      reset() {}
    };
  })();
`;

async function installDeterministicRoutes(page: Page) {
  const blockedExternalRequests: string[] = [];
  await page.route('**/*', route => {
    if (new URL(route.request().url()).origin === LOCAL_ORIGIN) {
      return route.continue();
    }
    blockedExternalRequests.push(route.request().url());
    return route.abort('blockedbyclient');
  });
  await page.route('**/sockjs-client@*/dist/sockjs.min.js', route =>
    route.fulfill({ contentType: 'application/javascript', body: FAKE_SOCKJS }));
  await page.route('**/@stomp/stompjs@*/bundles/stomp.umd.min.js', route =>
    route.fulfill({ contentType: 'application/javascript', body: FAKE_STOMP }));
  await page.route('https://fonts.googleapis.com/**', route =>
    route.fulfill({ contentType: 'text/css', body: '' }));
  await page.route('https://challenges.cloudflare.com/**', route =>
    route.fulfill({ contentType: 'application/javascript', body: FAKE_TURNSTILE }));
  await page.route('**/api/**', route => {
    const { pathname } = new URL(route.request().url());
    if (pathname === '/api/canvas') {
      return route.fulfill({ contentType: 'application/json', body: '[]' });
    }
    if (pathname === '/api/stats') {
      return route.fulfill({ contentType: 'application/json', body: '{"totalPixels":0,"colorDistribution":[]}' });
    }
    if (pathname === '/api/users') {
      return route.fulfill({ contentType: 'application/json', body: '{"username":"Local Tester"}' });
    }
    return route.fulfill({ status: 204 });
  });
  return blockedExternalRequests;
}

async function fakeState(page: Page) {
  return page.evaluate(() => window.__bitbrushTest);
}

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
    const blockedExternalRequests = await installDeterministicRoutes(page);

    await page.goto(`${LOCAL_ORIGIN}/index.html`);

    await expect(page.locator('#canvas')).toBeVisible();
    await expect.poll(() => fakeState(page).then(state => state?.stomp.subscriptions.length ?? 0)).toBe(5);
    const state = await fakeState(page);
    expect(state.turnstile.renders).toEqual([{ sitekey: '0x4AAAAAACwHh6lB9uQESFQA' }]);
    expect(state.stomp.constructorArgs).toHaveLength(1);
    expect(state.stomp.activations).toHaveLength(1);
    expect(state.stomp.activations[0]).toEqual(expect.any(Number));
    expect(state.stomp.connectHeaders[0]).toHaveProperty('uuid');
    expect(state.stomp.subscriptions).toEqual([
      '/topic/pixels', '/topic/users/count', '/app/users/count', '/user/queue/bank', '/app/bank'
    ]);
    expect(state.stomp.injectedMessages).toHaveLength(5);
    expect(blockedExternalRequests).toEqual([]);
  });

  test('loads the widget client with recorded local fakes', async ({ page }) => {
    const blockedExternalRequests = await installDeterministicRoutes(page);

    await page.goto(`${LOCAL_ORIGIN}/widget-host.html`);

    await expect(page.locator('.bitbrush-widget canvas')).toBeVisible();
    await expect.poll(() => fakeState(page).then(state => state?.stomp.subscriptions.length ?? 0)).toBe(5);
    const state = await fakeState(page);
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
    expect(blockedExternalRequests).toEqual([]);
  });
});
