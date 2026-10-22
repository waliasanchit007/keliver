#!/usr/bin/env node
// node serve.mjs <dir> <port>: a static file server for the built editor, on
// 127.0.0.1 only, with no caching (the editor's loader has a constant file
// name, so a cached one would run a stale build).
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';

const [dir, port] = process.argv.slice(2);
if (!dir || !port) { console.error('usage: serve.mjs <dir> <port>'); process.exit(2); }
const root = path.resolve(dir);
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.wasm': 'application/wasm', '.json': 'application/json', '.map': 'application/json', '.css': 'text/css',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.otf': 'font/otf', '.woff2': 'font/woff2', '.txt': 'text/plain',
};

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  let p = path.join(root, decodeURIComponent(url.pathname));
  if (!p.startsWith(root)) { res.writeHead(403).end(); return; }
  if (fs.existsSync(p) && fs.statSync(p).isDirectory()) p = path.join(p, 'index.html');
  fs.readFile(p, (err, data) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain' }).end('not found'); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(p)] ?? 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(data);
  });
}).listen(+port, '127.0.0.1', () => console.log(`serving ${root} on http://127.0.0.1:${port}`));
