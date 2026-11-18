'use strict';
/**
 * Offline lesson videos for the Windows exe.
 *
 * Direct video files (e.g. Cloudinary) are downloaded by the main process straight to disk
 * (userData/offline-videos) instead of being held in RAM, so even very long lectures work.
 * Downloads resume where they stopped (after a dropped connection or an app restart) and the
 * saved file is served back with HTTP Range support so seeking works.
 * YouTube lessons are never handled here (they can't be saved).
 *
 * Pure Node (no Electron imports) so it can be unit-tested.
 */
const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const crypto = require('crypto');

const MIN_FREE_BYTES = 300 * 1024 * 1024;   // always leave 300 MB free on the disk
const MAX_REDIRECTS = 5;

const sha1 = (s) => crypto.createHash('sha1').update(String(s)).digest('hex');

function create(dir, emit) {
  emit = emit || (() => {});
  fs.mkdirSync(dir, { recursive: true });
  const indexFile = path.join(dir, 'index.json');
  const active = new Map();   // id -> { req, cancelled, pct, got, total }

  let index = {};
  try { index = JSON.parse(fs.readFileSync(indexFile, 'utf8')) || {}; } catch (_) { index = {}; }
  const saveIndex = () => {
    try {
      const tmp = indexFile + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(index));
      fs.renameSync(tmp, indexFile);
    } catch (_) {}
  };

  const fileOf = (id) => path.join(dir, sha1(id) + '.mp4');
  const partOf = (id, url) => path.join(dir, sha1(id) + '.' + sha1(url).slice(0, 8) + '.part');
  const rm = (f) => { try { fs.unlinkSync(f); } catch (_) {} };

  function clearParts(id) {
    const k = sha1(id);
    try {
      for (const f of fs.readdirSync(dir)) if (f.startsWith(k + '.') && f.endsWith('.part')) rm(path.join(dir, f));
    } catch (_) {}
  }

  /** Saved copy of a lesson, or null. If `url` is given and differs from what was saved (the admin replaced the video), it is not a match. */
  function info(id, url) {
    const e = index[sha1(id)];
    if (!e || !fs.existsSync(fileOf(id))) return null;
    if (url && e.url && e.url !== url) return null;
    let size = e.size;
    try { size = fs.statSync(fileOf(id)).size; } catch (_) {}
    return Object.assign({}, e, { size });
  }

  function list() {
    const out = [];
    for (const k of Object.keys(index)) {
      const e = index[k];
      if (e && e.id && fs.existsSync(fileOf(e.id))) out.push(Object.assign({}, e, { size: fs.statSync(fileOf(e.id)).size }));
    }
    return out.sort((a, b) => (b.at || 0) - (a.at || 0));
  }

  function activeList() {
    return [...active.entries()].map(([id, a]) => ({ id, pct: a.pct, got: a.got, total: a.total }));
  }

  function freeBytes() {
    try { const s = fs.statfsSync(dir); return Number(s.bavail) * Number(s.bsize); } catch (_) { return Infinity; }
  }

  function remove(id) {
    const a = active.get(id);
    if (a) { a.cancelled = true; try { a.req && a.req.destroy(); } catch (_) {} }
    rm(fileOf(id));
    clearParts(id);
    delete index[sha1(id)];
    saveIndex();
    return true;
  }

  function cancel(id) {
    const a = active.get(id);
    if (!a) return;
    a.cancelled = true;
    try { a.req && a.req.destroy(); } catch (_) {}
  }

  /** GET with redirects; `headers` may carry a Range. Resolves with the IncomingMessage. */
  function get(url, headers, hops, holder) {
    return new Promise((resolve, reject) => {
      let u;
      try { u = new URL(url); } catch (e) { return reject(new Error('bad url')); }
      if (u.protocol !== 'https:' && u.protocol !== 'http:') return reject(new Error('bad protocol'));
      const lib = u.protocol === 'https:' ? https : http;
      const req = lib.get(u, { headers }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          if (hops >= MAX_REDIRECTS) return reject(new Error('too many redirects'));
          return resolve(get(new URL(res.headers.location, u).toString(), headers, hops + 1, holder));
        }
        resolve(res);
      });
      holder.req = req;
      req.setTimeout(30000, () => req.destroy(new Error('timeout')));
      req.on('error', reject);
    });
  }

  /** Starts (or resumes) saving a lesson. Returns a short status string; progress / result come through `emit`. */
  function start(o) {
    const id = String(o && o.id || ''), url = String(o && o.url || '');
    if (!id || !/^https?:\/\//i.test(url)) return { error: 'bad request' };
    if (info(id, url)) return { status: 'saved' };
    if (active.has(id)) return { status: 'busy' };
    // the admin replaced the video: drop the old copy first
    const old = index[sha1(id)];
    if (old && old.url && old.url !== url) { rm(fileOf(id)); clearParts(id); delete index[sha1(id)]; saveIndex(); }

    const st = { req: null, cancelled: false, pct: 0, got: 0, total: 0 };
    active.set(id, st);
    emit({ type: 'progress', id, pct: 0, got: 0, total: 0 });

    const title = String(o.title || ''), course = String(o.course || '');
    run(id, url, title, course, st)
      .then((r) => (r === 'retry' ? run(id, url, title, course, st) : r))
      .then((r) => {
        active.delete(id);
        if (r === 'done') emit({ type: 'done', id });
        else if (r === 'cancelled') emit({ type: 'cancel', id });
        else emit({ type: 'error', id, error: r });
      })
      .catch((e) => { active.delete(id); emit({ type: st.cancelled ? 'cancel' : 'error', id, error: String(e && e.message || e) }); });
    return { status: 'started' };
  }

  async function run(id, url, title, course, st) {
    const part = partOf(id, url);
    let have = 0;
    try { have = fs.statSync(part).size; } catch (_) {}
    const headers = { 'User-Agent': 'RajibTutorial-exe' };
    if (have > 0) headers.Range = 'bytes=' + have + '-';
    const res = await get(url, headers, 0, st);
    if (st.cancelled) { res.resume(); return 'cancelled'; }
    let resume = false;
    if (res.statusCode === 206 && have > 0) resume = true;
    else if (res.statusCode === 416 && have > 0) { res.resume(); rm(part); return 'retry'; }     // stale partial file: next start begins fresh
    else if (res.statusCode !== 200) { res.resume(); return 'HTTP ' + res.statusCode; }
    else have = 0;                                                                                // server ignored Range: start over

    const len = Number(res.headers['content-length']) || 0;
    const total = len ? len + have : 0;
    if (total && freeBytes() - (total - have) < MIN_FREE_BYTES) { res.resume(); return 'disk full'; }

    st.total = total; st.got = have;
    const out = fs.createWriteStream(part, { flags: resume ? 'a' : 'w' });
    let lastPct = -2, lastAt = 0;
    const tick = (force) => {
      const pct = total ? Math.min(99, Math.floor(st.got * 100 / total)) : -1;
      const now = Date.now();
      if (force || (pct !== lastPct && now - lastAt > 300)) {
        lastPct = pct; lastAt = now; st.pct = pct;
        emit({ type: 'progress', id, pct, got: st.got, total });
      }
    };
    tick(true);

    const result = await new Promise((resolve) => {
      let finished = false;
      const end = (r) => { if (!finished) { finished = true; resolve(r); } };
      res.on('data', (c) => {
        if (st.cancelled) return;
        st.got += c.length;
        if (!out.write(c)) { res.pause(); out.once('drain', () => res.resume()); }
        tick(false);
      });
      res.on('end', () => end('end'));
      res.on('aborted', () => end('aborted'));
      res.on('error', () => end('aborted'));
      res.on('close', () => end(res.complete ? 'end' : 'aborted'));
      out.on('error', () => end('write'));
    });
    await new Promise((r) => out.end(r));

    if (st.cancelled) { rm(part); return 'cancelled'; }
    if (result === 'write') return 'write error';
    if (result === 'aborted' || (total && st.got < total)) return 'connection lost (will resume next time)';

    rm(fileOf(id));
    fs.renameSync(part, fileOf(id));
    index[sha1(id)] = { id, url, title, course, size: st.got, at: Date.now() };
    saveIndex();
    return 'done';
  }

  /** Reads a saved lesson with HTTP Range semantics. Returns { status, headers, stream } or null. */
  function open(id, rangeHeader) {
    const f = fileOf(id);
    if (!info(id)) return null;
    let size;
    try { size = fs.statSync(f).size; } catch (_) { return null; }
    const headers = { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes' };
    let start = 0, end = size - 1, status = 200;
    const m = /^bytes=(\d*)-(\d*)$/.exec(String(rangeHeader || '').trim());
    if (m && (m[1] !== '' || m[2] !== '')) {
      if (m[1] === '') { start = Math.max(0, size - Number(m[2])); }
      else { start = Number(m[1]); if (m[2] !== '') end = Math.min(size - 1, Number(m[2])); }
      if (start > end || start >= size) {
        return { status: 416, headers: Object.assign(headers, { 'Content-Range': 'bytes */' + size }), stream: null };
      }
      status = 206;
      headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + size;
    }
    headers['Content-Length'] = String(end - start + 1);
    return { status, headers, stream: fs.createReadStream(f, { start, end }) };
  }

  return { start, cancel, remove, info, list, activeList, open };
}

module.exports = { create };
