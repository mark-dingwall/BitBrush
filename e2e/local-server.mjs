import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const staticRoot = path.resolve(here, '../src/main/resources/static');
const fixtureRoot = path.resolve(here, 'fixtures');
const host = '127.0.0.1';
const port = 4173;

const contentTypes = {
  '.css': 'text/css; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

function resolveWithin(root, relativePath) {
  const filePath = path.resolve(root, relativePath);
  return filePath === root || filePath.startsWith(root + path.sep) ? filePath : null;
}

function requestedFile(pathname) {
  if (pathname === '/' || pathname === '/index.html') {
    return resolveWithin(staticRoot, 'index.html');
  }
  if (pathname === '/widget-host.html') {
    return resolveWithin(fixtureRoot, 'widget-host.html');
  }
  if (pathname.startsWith('/fixtures/')) {
    return resolveWithin(fixtureRoot, pathname.slice('/fixtures/'.length));
  }
  return resolveWithin(staticRoot, pathname.slice(1));
}

function respond(response, statusCode, body = '') {
  response.writeHead(statusCode, { 'Content-Type': 'text/plain; charset=utf-8' });
  response.end(body);
}

const server = createServer(async (request, response) => {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    respond(response, 405, 'Method Not Allowed');
    return;
  }

  let pathname;
  try {
    const target = request.url || '/';
    const queryIndex = target.search(/[?#]/);
    pathname = decodeURIComponent(queryIndex === -1 ? target : target.slice(0, queryIndex));
  } catch {
    respond(response, 400, 'Bad Request');
    return;
  }

  if (pathname.split('/').some(segment => segment === '.' || segment === '..')) {
    respond(response, 403, 'Forbidden');
    return;
  }

  const filePath = requestedFile(pathname);
  if (!filePath) {
    respond(response, 403, 'Forbidden');
    return;
  }

  try {
    const fileStat = await stat(filePath);
    if (!fileStat.isFile()) {
      respond(response, 404, 'Not Found');
      return;
    }
    const body = request.method === 'HEAD' ? undefined : await readFile(filePath);
    response.writeHead(200, {
      'Content-Type': contentTypes[path.extname(filePath)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
    });
    response.end(body);
  } catch (error) {
    if (error?.code === 'ENOENT') {
      respond(response, 404, 'Not Found');
      return;
    }
    respond(response, 500, 'Internal Server Error');
  }
});

server.listen(port, host);
