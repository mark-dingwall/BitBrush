import { createServer } from 'node:http';
// The widget always comes from the tested/deployed application, never the checkout.
const target = process.env.BITBRUSH_TARGET || 'http://localhost:8080';
if (!['http://localhost:8080', 'https://bitbrush.fly.dev'].includes(target)) throw new Error('E_TARGET');
const html = `<!doctype html><html><head><meta charset="utf-8"><title>BitBrush verification host</title></head>
<body><main id="bitbrush-container" style="width:500px"></main><script>
window.bitbrushConfig={server:${JSON.stringify(target)},container:'#bitbrush-container',turnstileSiteKey:'1x00000000000000000000BB'};
</script><script src="${target}/bitbrush-widget.js"></script></body></html>`;
createServer((req, res) => {
  if (req.url !== '/' || req.method !== 'GET') { res.writeHead(404).end(); return; }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(html);
}).listen(4174, '127.0.0.1');
