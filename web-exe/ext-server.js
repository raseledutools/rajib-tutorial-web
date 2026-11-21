'use strict';
/* Local focus-page server (127.0.0.1 only, random port). Serves extfocus.html and a tiny JSON API used by that page:
     GET  /focus       the page (a per-run token is injected)
     GET  /api/state   { active, endAt, now, sites }
     POST /api/start   { sessions } start the 25-minute focus session (sessions > 1 = Pomodoro: 25 min x N with short breaks)
     POST /api/pomo    { action: 'skip' | 'stop' }  only during a Pomodoro break
     POST /api/sites   { add } | { remove }  (refused while a session runs)
   Every /api call needs the token header + a 127.0.0.1 Host (DNS-rebinding) + no foreign Origin (CSRF). No CORS headers are sent. */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function create({ htmlPath, getState, start, pomo, addSite, removeSite }) {
  const token = crypto.randomBytes(16).toString('hex');
  let srv = null, port = 0;
  const json = (res, code, o) => {
    res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' });
    res.end(JSON.stringify(o));
  };
  const hostOk = (req) => /^127\.0\.0\.1:\d+$/.test(String(req.headers.host || ''));
  const originOk = (req) => !req.headers.origin || req.headers.origin === 'http://127.0.0.1:' + port;
  function handle(req, res) {
    try {
      if (!hostOk(req)) { res.writeHead(403); return res.end('forbidden'); }
      const p = String(req.url || '/').split('?')[0];
      if (req.method === 'GET' && !p.startsWith('/api/')) {
        const html = fs.readFileSync(htmlPath, 'utf8').replace('__RF_TOKEN__', token);
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff',
          'content-security-policy': "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; base-uri 'none'; form-action 'none'" });
        return res.end(html);
      }
      if (!p.startsWith('/api/') || req.headers['x-rf-token'] !== token || !originOk(req)) return json(res, 403, { ok: false });
      if (req.method === 'GET' && p === '/api/state') return json(res, 200, getState());
      if (req.method !== 'POST') return json(res, 405, { ok: false });
      let body = '';
      req.on('data', (c) => { body += c; if (body.length > 2000) req.destroy(); });
      req.on('end', () => {
        let o = {};
        try { o = JSON.parse(body || '{}') || {}; } catch (_) {}
        if (p === '/api/start') return json(res, 200, start(o));
        if (p === '/api/pomo') return json(res, 200, pomo(o));
        if (p === '/api/sites') {
          if (typeof o.add === 'string') return json(res, 200, addSite(o.add));
          if (typeof o.remove === 'string') return json(res, 200, removeSite(o.remove));
        }
        json(res, 404, { ok: false });
      });
    } catch (_) { try { res.writeHead(500); res.end('error'); } catch (__) {} }
  }
  return {
    token,
    port: () => port,
    listen(cb) {
      if (srv) return;
      srv = http.createServer(handle);
      srv.on('error', () => { srv = null; port = 0; });
      srv.listen(0, '127.0.0.1', () => { try { port = srv.address().port; } catch (_) { port = 0; } if (cb) cb(port); });
      srv.unref();
    },
    close() { if (srv) { try { srv.close(); } catch (_) {} srv = null; port = 0; } },
  };
}
module.exports = { create };
