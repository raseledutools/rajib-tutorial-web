'use strict';
/* Safe Browser extension manager (Chrome-style).
   Extensions live in <userData>/extensions/<key>/ and are loaded into the Safe Browser session (EXT_PARTITION) at every start.
   Ways to add one: unpacked folder, .crx / .zip file, or a Chrome Web Store link / extension id.
   NOTE: Electron implements only a subset of the chrome.* extension APIs, so simple extensions (content scripts, ad / dark-mode /
   translate / password tools, ...) work; extensions that need browser-UI APIs (tabs strip, side panel, ...) may only partly work. */
const { app, session, dialog, BrowserWindow, net } = require('electron');
const path = require('path');
const fs = require('fs');
const zlib = require('zlib');

const MAX_DOWNLOAD = 120 * 1024 * 1024;
const MAX_UNZIPPED = 250 * 1024 * 1024;

/* ---------- minimal zip / crx reader (no dependency) ---------- */
function zipStart(buf) {
  if (buf.length > 16 && buf.toString('latin1', 0, 4) === 'Cr24') {   // .crx header
    const ver = buf.readUInt32LE(4);
    if (ver === 3) return 12 + buf.readUInt32LE(8);
    if (ver === 2) return 16 + buf.readUInt32LE(8) + buf.readUInt32LE(12);
  }
  return 0;
}
function unzip(input, dest) {
  const buf = input.subarray(zipStart(input));
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65535); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('This is not a valid .zip / .crx file');
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const root = path.resolve(dest);
  let total = 0;
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('Corrupt zip file');
    const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20), usize = buf.readUInt32LE(p + 24);
    const nl = buf.readUInt16LE(p + 28), el = buf.readUInt16LE(p + 30), cl = buf.readUInt16LE(p + 32), lo = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nl);
    p += 46 + nl + el + cl;
    const target = path.resolve(root, name);
    if (target !== root && !target.startsWith(root + path.sep)) continue;   // never write outside the folder (zip-slip)
    if (name.endsWith('/')) { fs.mkdirSync(target, { recursive: true }); continue; }
    total += usize;
    if (total > MAX_UNZIPPED) throw new Error('Extension is too large');
    const lnl = buf.readUInt16LE(lo + 26), lel = buf.readUInt16LE(lo + 28);
    const start = lo + 30 + lnl + lel;
    const raw = buf.subarray(start, start + csize);
    let data;
    if (method === 0) data = raw;
    else if (method === 8) data = zlib.inflateRawSync(raw);
    else throw new Error('Unsupported zip compression');
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, data);
  }
}

function readJson(f) { try { return JSON.parse(fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, '')); } catch (_) { return null; } }
// the folder that really holds manifest.json (zip files often wrap everything in one sub-folder)
function findRoot(dir) {
  if (fs.existsSync(path.join(dir, 'manifest.json'))) return dir;
  try {
    for (const d of fs.readdirSync(dir, { withFileTypes: true })) {
      if (d.isDirectory() && fs.existsSync(path.join(dir, d.name, 'manifest.json'))) return path.join(dir, d.name);
    }
  } catch (_) {}
  return null;
}
function storeId(input) {
  const m = String(input || '').match(/[a-p]{32}/);
  return m ? m[0] : '';
}
function rmrf(p) { try { fs.rmSync(p, { recursive: true, force: true }); } catch (_) {} }

module.exports = function createExtensions({ partition, getMainWin }) {
  const baseDir = () => path.join(app.getPath('userData'), 'extensions');
  const stateFile = () => path.join(app.getPath('userData'), 'extensions.json');
  const ses = () => session.fromPartition(partition);
  const api = () => ses().extensions || ses();   // Electron 35+: session.extensions.*
  let entries = null;          // [{ key, dir, enabled, source }]
  const runtime = new Map();   // key -> Electron extension id (assigned when loaded)

  function load_() {
    if (entries) return entries;
    const s = readJson(stateFile());
    entries = Array.isArray(s && s.list) ? s.list.filter((e) => e && e.key && e.dir && fs.existsSync(path.join(e.dir, 'manifest.json'))) : [];
    return entries;
  }
  function save_() { try { fs.mkdirSync(path.dirname(stateFile()), { recursive: true }); fs.writeFileSync(stateFile(), JSON.stringify({ list: load_() })); } catch (_) {} }

  // "__MSG_appName__" -> the real text from _locales
  function t(dir, m, str) {
    str = String(str || '');
    const mm = str.match(/^__MSG_(.+)__$/);
    if (!mm) return str;
    const langs = [m.default_locale, 'en', 'en_US'].filter(Boolean);
    for (const l of langs) {
      const j = readJson(path.join(dir, '_locales', l, 'messages.json'));
      if (j) { const k = Object.keys(j).find((x) => x.toLowerCase() === mm[1].toLowerCase()); if (k && j[k] && j[k].message) return String(j[k].message); }
    }
    return str;
  }
  function iconOf(dir, m) {
    try {
      const a = m.action || m.browser_action || m.page_action || {};
      let icons = m.icons || (a.default_icon && typeof a.default_icon === 'object' ? a.default_icon : null);
      let rel = typeof a.default_icon === 'string' ? a.default_icon : null;
      if (!rel && icons) {
        const sizes = Object.keys(icons).map(Number).filter(Boolean).sort((x, y) => x - y);
        const pick = sizes.find((s) => s >= 32) || sizes[sizes.length - 1];
        rel = icons[String(pick)];
      }
      if (!rel) return '';
      const f = path.resolve(dir, String(rel).replace(/^\//, ''));
      if (!f.startsWith(path.resolve(dir) + path.sep)) return '';
      const st = fs.statSync(f);
      if (st.size > 200 * 1024) return '';
      const ext = path.extname(f).toLowerCase();
      const mime = ext === '.svg' ? 'image/svg+xml' : ext === '.jpg' || ext === '.jpeg' ? 'image/jpeg' : ext === '.gif' ? 'image/gif' : 'image/png';
      return 'data:' + mime + ';base64,' + fs.readFileSync(f).toString('base64');
    } catch (_) { return ''; }
  }
  function describe(e) {
    const m = readJson(path.join(e.dir, 'manifest.json')) || {};
    const a = m.action || m.browser_action || m.page_action || {};
    return {
      key: e.key,
      name: t(e.dir, m, m.name) || e.key,
      version: String(m.version || ''),
      enabled: !!e.enabled,
      icon: iconOf(e.dir, m),
      canOpen: !!(a.default_popup || (m.options_ui && m.options_ui.page) || m.options_page),
      source: e.source || '',
    };
  }

  async function loadOne(e) {
    try {
      const ex = await api().loadExtension(e.dir, { allowFileAccess: true });
      if (ex && ex.id) runtime.set(e.key, ex.id);
      return true;
    } catch (err) { return String((err && err.message) || err); }
  }
  async function unloadOne(e) {
    const id = runtime.get(e.key);
    if (!id) return;
    try { await api().removeExtension(id); } catch (_) {}
    runtime.delete(e.key);
  }

  /* put a ready folder (already holding manifest.json) into the extensions dir, load it, remember it */
  async function register(srcDir, source, copy) {
    const root = findRoot(srcDir);
    if (!root) return { ok: false, error: 'manifest.json not found. Choose the extension folder (the one that contains manifest.json).' };
    const m = readJson(path.join(root, 'manifest.json'));
    if (!m || !m.name || !m.version) return { ok: false, error: 'manifest.json is not valid.' };
    const key = 'x' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
    const dest = path.join(baseDir(), key);
    try {
      fs.mkdirSync(baseDir(), { recursive: true });
      if (copy) fs.cpSync(root, dest, { recursive: true });
      else fs.renameSync(root, dest);
    } catch (err) { return { ok: false, error: 'Could not copy the extension: ' + err.message }; }
    const e = { key, dir: dest, enabled: true, source };
    const r = await loadOne(e);
    if (r !== true) { rmrf(dest); return { ok: false, error: 'Chrome could not load this extension: ' + r }; }
    load_().push(e); save_();
    return { ok: true, name: describe(e).name };
  }

  async function installBuffer(buf, source) {
    const tmp = path.join(app.getPath('temp'), 'rt-ext-' + Date.now().toString(36));
    try {
      fs.mkdirSync(tmp, { recursive: true });
      unzip(buf, tmp);
      return await register(tmp, source, false);
    } catch (err) {
      return { ok: false, error: String((err && err.message) || err) };
    } finally { rmrf(tmp); }
  }

  const methods = {
    async init() {
      for (const e of load_()) {
        if (!e.enabled) continue;
        const r = await loadOne(e);
        if (r !== true) e.error = r;
      }
    },
    list() { return load_().map(describe); },

    async addFolder() {
      const r = await dialog.showOpenDialog(getMainWin() || undefined, { title: 'Choose the extension folder (contains manifest.json)', properties: ['openDirectory'] });
      if (r.canceled || !r.filePaths[0]) return { ok: false, canceled: true };
      return register(r.filePaths[0], 'folder', true);
    },
    async addFile() {
      const r = await dialog.showOpenDialog(getMainWin() || undefined, { title: 'Choose an extension file', properties: ['openFile'], filters: [{ name: 'Chrome extension', extensions: ['crx', 'zip'] }] });
      if (r.canceled || !r.filePaths[0]) return { ok: false, canceled: true };
      try { return await installBuffer(fs.readFileSync(r.filePaths[0]), 'file'); }
      catch (err) { return { ok: false, error: err.message }; }
    },
    async addStore(input) {
      const id = storeId(input);
      if (!id) return { ok: false, error: 'Paste a Chrome Web Store link (or the 32-letter extension id).' };
      const ver = String(process.versions.chrome || '120.0.0.0');
      const url = 'https://clients2.google.com/service/update2/crx?response=redirect&os=win&arch=x64&os_arch=x86_64&nacl_arch=x86-64&prod=chromecrx&prodchannel=&prodversion=' +
        ver + '&lang=en-US&acceptformat=crx2,crx3&x=' + encodeURIComponent('id=' + id + '&installsource=ondemand&uc');
      let buf;
      try {
        const res = await net.fetch(url, { redirect: 'follow' });
        if (!res.ok) return { ok: false, error: 'Chrome Web Store did not give this extension (HTTP ' + res.status + ').' };
        const ab = await res.arrayBuffer();
        if (ab.byteLength < 100) return { ok: false, error: 'Chrome Web Store did not give this extension (wrong id?).' };
        if (ab.byteLength > MAX_DOWNLOAD) return { ok: false, error: 'Extension is too large.' };
        buf = Buffer.from(ab);
      } catch (err) { return { ok: false, error: 'Download failed. Check your internet connection.' }; }
      return installBuffer(buf, 'store:' + id);
    },
    async remove(key) {
      const list = load_(), i = list.findIndex((e) => e.key === key);
      if (i < 0) return { ok: false, error: 'Extension not found.' };
      await unloadOne(list[i]);
      rmrf(list[i].dir);
      list.splice(i, 1); save_();
      return { ok: true };
    },
    async toggle(key, on) {
      const e = load_().find((x) => x.key === key);
      if (!e) return { ok: false, error: 'Extension not found.' };
      if (on) { const r = await loadOne(e); if (r !== true) return { ok: false, error: String(r) }; }
      else await unloadOne(e);
      e.enabled = !!on; save_();
      return { ok: true };
    },
    // the toolbar-icon click of a Chrome extension: show its popup (or its options page) in a small window
    async open(key) {
      const e = load_().find((x) => x.key === key);
      const id = e && runtime.get(e.key);
      if (!e || !id) return { ok: false, error: 'Turn the extension on first.' };
      const m = readJson(path.join(e.dir, 'manifest.json')) || {};
      const a = m.action || m.browser_action || m.page_action || {};
      const popup = a.default_popup;
      const page = popup || (m.options_ui && m.options_ui.page) || m.options_page;
      if (!page) return { ok: false, error: 'This extension has no popup or options page.' };
      const w = new BrowserWindow({
        width: popup ? 420 : 900, height: popup ? 600 : 700, parent: getMainWin() || undefined, autoHideMenuBar: true,
        title: describe(e).name,
        webPreferences: { partition, sandbox: true, contextIsolation: true, nodeIntegration: false, devTools: !app.isPackaged },
      });
      w.setMenuBarVisibility(false);
      w.loadURL('chrome-extension://' + id + '/' + String(page).replace(/^\//, ''));
      return { ok: true };
    },
  };
  return methods;
};
