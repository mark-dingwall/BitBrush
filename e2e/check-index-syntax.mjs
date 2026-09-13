import { readFile } from 'node:fs/promises';

const html = await readFile(new URL('../src/main/resources/static/index.html', import.meta.url), 'utf8');
let scripts = 0;
for (const [, attributes, source] of html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)) {
  if (/\bsrc\s*=/i.test(attributes) || !source.trim()) continue;
  new Function(source);
  scripts++;
}
if (scripts === 0) throw new Error('No inline scripts found in index.html');
console.log(`Compiled ${scripts} inline script(s) from index.html`);
