'use strict';
/* Focus Mode (main process). Port of the "Simple Blocks" tab of RasFocus-Exe-Release.
   - state is saved in userData/focus.json, so an active session survives restarts
   - a hidden PowerShell helper reports the foreground window (100 ms, incl. the browser address bar) and every process that has
     a visible window (every ~2 s); the decisions are made in focus-core.js
   - blocked app  -> process killed; blocked site -> Ctrl+W on the browser tab + minimise;
     Task Manager / uninstall windows -> closed; a quote popup is shown each time
   - Schedule Block: saved profiles (days + time window + sites/apps) that block on their own, with or without a Focus session */
const { app, BrowserWindow, ipcMain, dialog, Notification, screen, webContents } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn, execFile } = require('child_process');
const http = require('http');
const core = require('./focus-core');
const safetyMod = require('./safety');

const IS_WIN = process.platform === 'win32';
const SELF_EXE = path.basename(process.execPath);
const STATE_FILE = () => path.join(app.getPath('userData'), 'focus.json');
const HELPER_DIR = () => path.join(app.getPath('userData'), 'focus-helper');

const QUICK_MIN = 25;
const DEFAULT_QSITES = ['raseledutools.github.io'];
let st = { settings: Object.assign({}, core.DEFAULTS), active: false, endAt: 0, pw: null, schedules: [], quick: false, qsites: DEFAULT_QSITES.slice(), pomo: null, pomoN: 1, tmSet: false };
const POMO_BREAK_MIN = 5, POMO_MAX = 8;   // Pomodoro: QUICK_MIN focus + 5 min break, up to 8 sessions
/* Ready-made Schedule Block profiles, added once on first run. They start switched OFF: an active profile can't be edited or deleted until its window ends, so the user turns on the ones they want. */
const PRESET_SCHEDULES = [
  { name: 'Study Time', days: [6, 0, 1, 2, 3, 4], start: '09:00', end: '13:00', sites: ['facebook.com', 'youtube.com', 'instagram.com', 'tiktok.com'], apps: [{ type: 'exe', name: 'discord.exe' }, { type: 'exe', name: 'telegram.exe' }] },
  { name: 'Deep Work', days: [0, 1, 2, 3, 4], start: '14:00', end: '18:00', sites: ['facebook.com', 'reddit.com', 'twitter.com', 'tiktok.com'], apps: [{ type: 'exe', name: 'discord.exe' }] },
  { name: 'Night Block', days: [0, 1, 2, 3, 4, 5, 6], start: '23:00', end: '06:00', sites: ['facebook.com', 'youtube.com', 'instagram.com', 'tiktok.com', 'reddit.com'], apps: [{ type: 'exe', name: 'discord.exe' }, { type: 'exe', name: 'telegram.exe' }] },
];
function presetSchedules() {
  return PRESET_SCHEDULES.map((p) => core.sanitizeSchedule(Object.assign({ enabled: false, allDay: false }, p)).value).filter(Boolean);
}
let schedBlocks = { ids: [], names: [], sites: [], apps: [] }; // what the schedules block right now (refreshed every 10 s)
let helper = null, restartTimer = null, expiryTimer = null, schedTimer = null;
let getMainWin = () => null, hooks = {};
let lastAlert = 0, lastAct = 0, procBuf = [], lastProcs = [];
const extSrv = require('./ext-server').create({
  htmlPath: path.join(__dirname, 'extfocus.html'),
  noisePath: path.join(__dirname, 'focus-noise.js'),
  getState: () => ({ active: st.active && st.quick, endAt: st.endAt, now: Date.now(), sites: st.qsites.slice(), minutes: QUICK_MIN,
    breakMinutes: POMO_BREAK_MIN, maxSessions: POMO_MAX, sessions: st.pomoN || 1, pomo: st.pomo && !st.pomo.app ? Object.assign({}, st.pomo) : null }),
  start: (o) => startQuick(o && o.sessions),
  pomo: (o) => pomoControl(o),
  addSite: (raw) => {
    if (st.active) return { ok: false, error: 'locked' };
    const site = core.normalizeSite(raw);
    if (!site) return { ok: false, error: 'invalid' };
    if (!st.qsites.includes(site) && st.qsites.length < 40) st.qsites.push(site);
    save(); return { ok: true, sites: st.qsites.slice() };
  },
  removeSite: (raw) => {
    if (st.active) return { ok: false, error: 'locked' };
    st.qsites = st.qsites.filter((x) => x !== raw);
    save(); return { ok: true, sites: st.qsites.slice() };
  },
});
const extPortNow = () => extSrv.port();
function startExtServer() { extSrv.listen(); }
function stopExtServer() { if (!(st.active && st.quick) && !st.pomo) extSrv.close(); }   // a Quick Focus session / Pomodoro needs the page
let extAdult = false; // Settings -> Adult Block for all browsers (works with or without a Focus session)

/* ---------- persistence ---------- */
function load() {
  try {
    const j = JSON.parse(fs.readFileSync(STATE_FILE(), 'utf8'));
    st.settings = core.sanitizeSettings(j.settings, core.DEFAULTS);
    st.active = !!j.active && Number(j.endAt) > Date.now();
    st.endAt = st.active ? Number(j.endAt) : 0;
    const pwOk = j.pw && j.pw.salt && j.pw.hash ? j.pw : null;
    st.pw = st.active ? pwOk : null;
    st.schedules = core.sanitizeSchedules(j.schedules);
    st.seeded = !!j.schedSeeded || st.schedules.length > 0;
    st.quick = st.active && !!j.quick;
    st.tmSet = !!j.tmSet;
    st.pomoN = Math.min(POMO_MAX, Math.max(1, Math.floor(Number(j.pomoN)) || 1));
    st.pomo = null;
    const pm = j.pomo;
    if (pm && typeof pm === 'object') {
      const total = Math.min(POMO_MAX, Math.floor(Number(pm.total)) || 0), idx = Math.floor(Number(pm.idx)) || 0;
      const phase = pm.phase === 'break' ? 'break' : 'focus';
      if (total >= 2 && idx >= 1 && idx <= total && ((phase === 'focus' && st.active && st.quick === !pm.app) || (phase === 'break' && !st.active))) {
        st.pomo = { total, idx, phase, breakEndAt: phase === 'break' ? Number(pm.breakEndAt) || 0 : 0, app: !!pm.app };   // app = started from the app's Focus tab (Quick/browser Pomodoro: false)
        if (!st.active && st.pomo.app) st.pw = pwOk;   // the stop password must survive the break
      }
    }
    if (Array.isArray(j.qsites)) { const q = core.sanitizeSettings({ sites: j.qsites }, core.DEFAULTS).sites; st.qsites = q.length || j.qsites.length === 0 ? q : DEFAULT_QSITES.slice(); }
  } catch (_) { /* first run */ }
  if (!st.seeded) { st.schedules = presetSchedules(); st.seeded = true; save(); }
}
function save() {
  try {
    fs.mkdirSync(path.dirname(STATE_FILE()), { recursive: true });
    fs.writeFileSync(STATE_FILE(), JSON.stringify({ settings: st.settings, active: st.active, endAt: st.endAt, pw: st.pw, schedules: st.schedules, schedSeeded: !!st.seeded, quick: st.quick, qsites: st.qsites, pomo: st.pomo, pomoN: st.pomoN, tmSet: !!st.tmSet }));
  } catch (_) {}
}

/* ---------- state for the UI ---------- */
function lockedApps() {
  // Same idea as EnforceSystemApps(): in Allow mode these must always stay allowed.
  if (st.settings.mode !== 'allow') return [];
  return ['explorer.exe', 'svchost.exe', SELF_EXE, 'chrome.exe', 'msedge.exe'].map((name) => ({ type: 'exe', name, locked: true }));
}
function publicState() {
  const s = st.settings;
  return {
    platform: process.platform,
    supported: IS_WIN,
    active: st.active,
    endAt: st.endAt,
    now: Date.now(),
    hasPassword: !!st.pw,
    pomo: st.pomo ? { total: st.pomo.total, idx: st.pomo.idx, phase: st.pomo.phase, breakEndAt: st.pomo.breakEndAt, app: !!st.pomo.app } : null,
    pomoMinutes: QUICK_MIN, breakMinutes: POMO_BREAK_MIN, maxSessions: POMO_MAX,
    settings: {
      mode: s.mode, controlMode: s.controlMode, showQuotes: s.showQuotes, quoteLang: s.quoteLang,
      hours: s.hours, mins: s.mins, sites: s.sites.slice(),
      apps: lockedApps().concat(s.apps.map((a) => ({ type: a.type, name: a.name, locked: false }))),
    },
    common: { sites: core.COMMON_SITES, apps: core.COMMON_APPS },
    schedules: st.schedules.map((p) => ({
      id: p.id, name: p.name, enabled: p.enabled, days: p.days.slice(), allDay: p.allDay, start: p.start, end: p.end,
      sites: p.sites.slice(), apps: p.apps.map((a) => ({ type: a.type, name: a.name })),
      activeNow: core.isScheduleActive(p, new Date()), // a profile that is blocking right now can't be edited / switched off / deleted
    })),
  };
}
// the Focus page lives in a <webview> (rtfocus://app/focus.html) inside the main window
function focusGuests() {
  try { return webContents.getAllWebContents().filter((w) => !w.isDestroyed() && w.getType() === 'webview' && w.getURL().startsWith('rtfocus://app/focus.html')); } catch (_) { return []; }
}
function broadcast() {
  const ps = publicState();
  const mw = getMainWin();
  focusGuests().forEach((g) => { try { g.send('focus:state', ps); } catch (_) {} });
  if (mw && !mw.isDestroyed()) mw.webContents.send('focus:badge', st.active);
  if (hooks.onActiveChange) hooks.onActiveChange(st.active);
}

/* ---------- alert popup (quote) ---------- */
function esc(s) { return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); }
function showAlert(customMsg) {
  const now = Date.now();
  if (now - lastAlert < 4000) return;
  lastAlert = now;
  try {
    const text = core.pickMessage({ showQuotes: st.settings.showQuotes, quoteLang: st.settings.quoteLang, customMsg });
    const W = 1000, H = 250;
    const wa = screen.getPrimaryDisplay().workArea;
    const w = new BrowserWindow({
      width: W, height: H, x: Math.round(wa.x + (wa.width - W) / 2), y: wa.y + 80,
      frame: false, resizable: false, skipTaskbar: true, focusable: false, show: false, alwaysOnTop: true,
      backgroundColor: '#145028',
      webPreferences: { sandbox: true, contextIsolation: true, nodeIntegration: false, devTools: false },
    });
    w.setAlwaysOnTop(true, 'screen-saver');
    const html = '<meta charset="utf-8"><body style="margin:0;height:100vh;box-sizing:border-box;border:4px solid #0ca8b0;'
      + 'background:#145028;color:#fff;display:flex;align-items:center;justify-content:center;text-align:center;'
      + 'font:700 32px \'Segoe UI\',system-ui,sans-serif;padding:20px;overflow:hidden">' + esc(text) + '</body>';
    w.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html));
    w.once('ready-to-show', () => { if (!w.isDestroyed()) w.showInactive(); });
    setTimeout(() => { if (!w.isDestroyed()) w.close(); }, 6000).unref();
  } catch (_) {}
}

/* ---------- Task Manager ----------
   Task Manager runs ELEVATED, so a normal (non-admin) app can neither kill nor close it: that is why only the pop-up showed.
   Fix: while a Focus session runs, the per-user policy DisableTaskMgr=1 is set (HKCU, no admin needed), so Task Manager
   cannot be opened (Ctrl+Shift+Esc, taskbar, Ctrl+Alt+Del). It is removed when the session ends, and also at the next start
   of the app if the app was killed in between (st.tmSet remembers that we set it). */
const TM_KEY = 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System';
function setTaskMgrPolicy(on) {
  if (!IS_WIN) return;
  if (on && !st.tmSet) {
    st.tmSet = true; save();
    execFile('reg', ['add', TM_KEY, '/v', 'DisableTaskMgr', '/t', 'REG_DWORD', '/d', '1', '/f'], { windowsHide: true }, (err) => { if (err) { st.tmSet = false; save(); } });
  } else if (!on && st.tmSet) {
    st.tmSet = false; save();
    execFile('reg', ['delete', TM_KEY, '/v', 'DisableTaskMgr', '/f'], { windowsHide: true }, () => {});
  }
}
const syncTaskMgr = () => setTaskMgrPolicy(st.active);

/* ---------- Windows helpers (PowerShell, two long-running processes) ----------
   focus-monitor.ps1: foreground window (title + address bar of browsers) every 100 ms, windowed processes every ~1 s
   focus-actor.ps1  : stays running and waits for commands (closetab / tofocus / closewin), so a block needs no new
                      PowerShell start-up + compile (that was ~1-2 s per block). It re-checks the tab is still the one it
                      decided about right before pressing keys, so switching tabs can never hit the wrong tab. */
function writeScripts() {
  fs.mkdirSync(HELPER_DIR(), { recursive: true });
  const bom = '\ufeff'; // Windows PowerShell 5 reads BOM-less files as ANSI
  for (const f of ['focus-monitor.ps1', 'focus-actor.ps1']) {
    fs.writeFileSync(path.join(HELPER_DIR(), f), bom + fs.readFileSync(path.join(__dirname, f), 'utf8'), 'utf8');
  }
}
const PS_ARGS = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File'];

let actor = null;
function startActor() {
  if (!IS_WIN || actor) return;
  try {
    actor = spawn('powershell.exe', ['-STA'].concat(PS_ARGS, [path.join(HELPER_DIR(), 'focus-actor.ps1')]),
      { windowsHide: true, stdio: ['pipe', 'ignore', 'ignore'] });
  } catch (_) { actor = null; return; }
  const me = actor;
  me.stdin.on('error', () => {});
  me.on('error', () => { if (actor === me) actor = null; });
  me.on('exit', () => { if (actor === me) actor = null; });   // started again on the next action
}
function stopActor() { if (actor) { const a = actor; actor = null; try { a.stdin.end(); } catch (_) {} try { a.kill(); } catch (_) {} } }
function act(action, hwnd, url, title) {
  if (!actor) startActor();
  if (!actor) return;
  try { actor.stdin.write([action, String(hwnd), url || '', Buffer.from(String(title || ''), 'utf8').toString('base64')].join('\t') + '\n'); } catch (_) {}
}

function activeApps() { return st.quick ? [] : lockedApps().concat(st.settings.apps); }
const sessMode = () => (st.quick ? 'block' : st.settings.mode);   // Quick Focus: only Task Manager / uninstall are guarded, the rest is about browser tabs

function onForeground(parts) {
  if (!st.active && !schedBlocks.sites.length && !schedBlocks.apps.length && !extAdult) return;
  const [, hwnd, pid, proc, title, url] = parts;
  if (Number(pid) === process.pid || (helper && Number(pid) === helper.pid)) return;
  // 1) a blocked / not-allowed APP that comes to the front is killed at once (no waiting for the ~1 s process sweep)
  if (st.active || schedBlocks.apps.length) {
    let dp = st.active ? core.decideProcess({ mode: sessMode(), apps: activeApps(), proc, title: title || '', selfName: SELF_EXE }) : null;
    if (!dp && schedBlocks.apps.length) dp = core.decideScheduledProcess({ apps: schedBlocks.apps, proc, title: title || '', selfName: SELF_EXE });
    if (dp) { try { process.kill(Number(pid)); } catch (_) {} showAlert(dp.msg); return; }
  }
  // 2) browser tabs (address bar when it can be read, else the window title)
  const base = { proc, title: title || '', url: url || '', selfName: SELF_EXE };
  let d = null;
  if (st.active && st.quick) d = core.decideQuickForeground(Object.assign({ sites: st.qsites, isAdultTitle: safetyMod.isAdultTitle }, base));
  else if (st.active) d = core.decideForeground(Object.assign({ mode: st.settings.mode, sites: st.settings.sites }, base));
  if (!d && schedBlocks.sites.length) d = core.decideScheduledForeground(Object.assign({ sites: schedBlocks.sites }, base));
  if (!d && (extAdult || (st.active && st.quick))) d = core.decideAdultForeground(Object.assign({ isAdultTitle: safetyMod.isAdultTitle, isAdultHost: safetyMod.isAdultHost }, base));
  if (!d) d = core.decideFocusSearch(base);   // "focus" typed in the search bar of any browser -> this tab becomes the focus page
  if (!d) return;
  if (d.soft && !extPortNow()) { startExtServer(); return; }   // page server not up yet: it is next poll
  const now = Date.now();
  if (now - lastAct < 700) return;
  lastAct = now;
  if (d.action === 'tofocus' && !extPortNow()) { startExtServer(); d = { action: 'closetab', msg: null }; }   // local page not ready: old behaviour
  if (d.action === 'tofocus') { act('tofocus', hwnd, 'http://127.0.0.1:' + extPortNow() + '/focus', title); lastAct = now + 800; return; }  // the tab itself becomes the focus page
  act(d.action, hwnd, '', title);
  showAlert(d.msg);
}

function onSweep(list) {
  lastProcs = list;
  if (!st.active && !schedBlocks.apps.length) return;
  const apps = activeApps();
  for (const p of list) {
    if (p.pid === process.pid || (helper && p.pid === helper.pid)) continue;
    let d = st.active ? core.decideProcess({ mode: sessMode(), apps, proc: p.name, title: p.title, selfName: SELF_EXE }) : null;
    if (!d && schedBlocks.apps.length) d = core.decideScheduledProcess({ apps: schedBlocks.apps, proc: p.name, title: p.title, selfName: SELF_EXE });
    if (!d) continue;
    try { process.kill(p.pid); } catch (_) { /* elevated / protected: nothing we can do */ }
    showAlert(d.msg);
  }
}

function onLine(line) {
  line = line.replace(/\r$/, '');
  if (line.startsWith('FG\t')) return onForeground(line.split('\t'));
  if (line === 'ENDP') { const l = procBuf; procBuf = []; return onSweep(l); }
  if (line.startsWith('P\t')) {
    const [, pid, name, title] = line.split('\t');
    if (procBuf.length < 400) procBuf.push({ pid: Number(pid), name, title: title || '' });
  }
}

function engineNeeded() { return st.active || schedBlocks.ids.length > 0 || extAdult; }
/* Recompute which schedule profiles are blocking right now; start / stop the helper accordingly. */
function refreshSchedule() {
  const before = schedBlocks.ids.join(',');
  schedBlocks = core.activeScheduleBlocks(st.schedules, new Date());
  if (engineNeeded()) startEngine(); else if (helper) stopEngine();
  if (schedBlocks.ids.join(',') !== before) broadcast();
}

function startEngine() {
  if (!IS_WIN || helper) return;
  try {
    writeScripts();
    startActor();
    helper = spawn('powershell.exe', PS_ARGS.concat([path.join(HELPER_DIR(), 'focus-monitor.ps1')]),
      { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
  } catch (_) { helper = null; return; }
  let buf = '';
  helper.stdout.setEncoding('utf8');
  helper.stdout.on('data', (chunk) => {
    buf += chunk;
    let i;
    while ((i = buf.indexOf('\n')) >= 0) { onLine(buf.slice(0, i)); buf = buf.slice(i + 1); }
    if (buf.length > 100000) buf = '';
  });
  const me = helper;
  // powershell.exe missing / blocked: give up quietly instead of crashing the app (no restart loop)
  helper.on('error', () => { if (helper === me) helper = null; });
  helper.on('exit', () => {
    if (helper === me) helper = null;
    // someone killed the helper while a session is running: bring it back
    if (engineNeeded()) { clearTimeout(restartTimer); restartTimer = setTimeout(startEngine, 2000); restartTimer.unref(); }
  });
}
function stopEngine() {
  clearTimeout(restartTimer);
  if (helper) { const h = helper; helper = null; try { h.kill(); } catch (_) {} }
  stopActor();
  procBuf = [];
}

/* ---------- session control ---------- */
function finish(notify) {
  const pomoTotal = st.pomo ? st.pomo.total : 0;
  st.active = false; st.endAt = 0; st.pw = null; st.quick = false; st.pomo = null;
  save(); syncTaskMgr(); refreshSchedule(); if (!engineNeeded()) stopEngine(); broadcast();
  if (notify && Notification.isSupported()) {
    new Notification({ title: 'Focus Mode', body: pomoTotal ? 'সব ' + pomoTotal + ' টি Pomodoro session শেষ। দারুণ কাজ! 🎉' : 'Focus session শেষ। দারুণ কাজ! 🎉' }).show();
  }
  if (!extAdult) stopExtServer();
}

/* Quick Focus: started from the focus tab (any browser). Runs until the time is over and cannot be stopped. */
function startQuick(sessions) {
  if (!IS_WIN) return { ok: false, error: 'windows-only' };
  if (st.active || st.pomo) return { ok: false, error: 'running' };
  const total = Math.min(POMO_MAX, Math.max(1, Math.floor(Number(sessions)) || 1));   // Pomodoro: how many 25-minute sessions
  st.pomoN = total;
  st.pomo = total > 1 ? { total, idx: 1, phase: 'focus', breakEndAt: 0 } : null;
  st.active = true; st.quick = true; st.pw = null; st.endAt = Date.now() + QUICK_MIN * 60000;
  save(); syncTaskMgr(); startExtServer(); startEngine(); armExpiry(); broadcast();
  if (hooks.onStart) hooks.onStart();
  return { ok: true, endAt: st.endAt, now: Date.now() };
}

function startSession(o) {
  if (!IS_WIN) return { ok: false, error: 'Focus Mode শুধু Windows এ চলে।' };
  if (st.active || st.pomo) return { ok: false, error: 'Focus / Pomodoro আগে থেকেই চলছে।' };
  const s = st.settings;
  const pn = o && Number(o.pomodoro) >= 1 ? Math.min(POMO_MAX, Math.floor(Number(o.pomodoro))) : 0;   // Pomodoro: N x 25-minute sessions
  const mins = pn ? QUICK_MIN : s.hours * 60 + s.mins;
  if (mins < 1) return { ok: false, error: 'সময় কমপক্ষে ১ মিনিট দিন।' };
  if (s.mode === 'block' && !s.sites.length && !s.apps.length) {
    return { ok: false, error: 'Block করার জন্য অন্তত একটি website বা app যোগ করুন।' };
  }
  let pw = null;
  if (s.controlMode === 'parents') {
    const p = o && typeof o.password === 'string' ? o.password : '';
    if (p.length < 4 || p.length > 40) return { ok: false, error: 'Parent password ৪–৪০ অক্ষরের হতে হবে।' };
    pw = core.hashPassword(p);
  }
  st.pomo = pn > 1 ? { total: pn, idx: 1, phase: 'focus', breakEndAt: 0, app: true } : null;
  st.active = true; st.quick = false; st.endAt = Date.now() + mins * 60000; st.pw = pw;
  save(); syncTaskMgr(); startEngine(); armExpiry(); broadcast();
  if (hooks.onStart) hooks.onStart();
  return { ok: true };
}

function stopSession(o) {
  if (!st.active && !(st.pomo && st.pomo.app)) return { ok: true };   // (an app Pomodoro break counts as running: same stop rules)
  if (st.quick) return { ok: false, error: 'এই Quick Focus (২৫ মিনিট) সময় শেষ না হওয়া পর্যন্ত বন্ধ করা যাবে না।' };
  const mode = st.settings.controlMode;
  if (mode === 'parents') {
    if (!core.verifyPassword(o && o.password, st.pw)) return { ok: false, error: 'ভুল password।' };
  } else if (mode === 'longtext') {
    if (core.countWords(o && o.text) < 200) return { ok: false, error: 'কমপক্ষে ২০০ শব্দ লিখতে হবে।' };
  }
  finish(false);
  return { ok: true };
}

function armExpiry() {
  clearInterval(expiryTimer);
  expiryTimer = setInterval(() => {
    const now = Date.now();
    if (st.active) {
      if (now < st.endAt) return;
      if (st.pomo && st.pomo.idx < st.pomo.total) startBreak(); else finish(true);
    } else if (st.pomo && st.pomo.phase === 'break' && now >= st.pomo.breakEndAt) nextPomoFocus();
  }, 1000);
  expiryTimer.unref();
}
const notify = (body) => { try { if (Notification.isSupported()) new Notification({ title: 'Pomodoro', body }).show(); } catch (_) {} };

/* Pomodoro = N Quick Focus sessions (25 min each) with a short break in between. Blocking (and the Task Manager lock) is off during a break. */
function startBreak() {
  st.active = false; st.endAt = 0; st.quick = false; if (!st.pomo.app) st.pw = null;
  st.pomo.phase = 'break'; st.pomo.breakEndAt = Date.now() + POMO_BREAK_MIN * 60000;
  save(); syncTaskMgr(); refreshSchedule(); if (!engineNeeded()) stopEngine(); broadcast();
  notify('Session ' + st.pomo.idx + '/' + st.pomo.total + ' শেষ ☕ ' + POMO_BREAK_MIN + ' মিনিট বিরতি নিন।');
}
function nextPomoFocus() {
  if (!st.pomo) return;
  st.pomo.idx += 1; st.pomo.phase = 'focus'; st.pomo.breakEndAt = 0;
  st.active = true; st.quick = !st.pomo.app; if (st.quick) st.pw = null; st.endAt = Date.now() + QUICK_MIN * 60000;
  save(); syncTaskMgr(); if (st.quick) startExtServer(); startEngine(); armExpiry(); broadcast();
  notify('Session ' + st.pomo.idx + '/' + st.pomo.total + ' শুরু — ফোকাস করুন 🔒');
}
function pomoControl(o) {
  if (!st.pomo || st.pomo.app || st.pomo.phase !== 'break') return { ok: false };
  const a = o && o.action;
  if (a === 'skip') { nextPomoFocus(); return { ok: true }; }
  if (a === 'stop') { st.pomo = null; save(); broadcast(); return { ok: true }; }
  return { ok: false };
}

/* ---------- page (a view inside the website layout: sidebar + title bar stay; see main.js openFocusView) ---------- */
function openWindow() {
  if (hooks.openView) hooks.openView();
}

/* ---------- IPC ---------- */
// only the local Focus page (the <webview> showing rtfocus://app/focus.html inside the main window; never the website) may use the Focus API
function fromFocusWin(e) {
  const mw = getMainWin();
  if (!mw || mw.isDestroyed()) return false;
  try {
    const s = e.sender;
    return s.getType() === 'webview' && s.hostWebContents === mw.webContents && s.getURL().startsWith('rtfocus://app/focus.html');
  } catch (_) { return false; }
}

function init(opts) {
  getMainWin = (opts && opts.getMainWin) || getMainWin;
  hooks = opts || {};
  load();
  syncTaskMgr();   // session running -> Task Manager locked; app was killed during a session that is over -> unlocked again
  if (st.active) { if (st.quick) startExtServer(); startEngine(); armExpiry(); }
  else if (st.pomo) { startExtServer(); armExpiry(); }   // Pomodoro break: the page + the next session timer
  refreshSchedule();
  clearInterval(schedTimer);
  schedTimer = setInterval(refreshSchedule, 10000);
  schedTimer.unref();
  save();

  ipcMain.on('focus:open', openWindow);
  ipcMain.handle('focus:isActive', (e) => { const mw = getMainWin(); return !!(mw && !mw.isDestroyed() && e.sender === mw.webContents && st.active); });
  ipcMain.handle('focus:get', (e) => (fromFocusWin(e) ? publicState() : null));
  ipcMain.handle('focus:update', (e, patch) => {
    if (!fromFocusWin(e)) return { ok: false };
    if (st.active || (st.pomo && st.pomo.app)) return { ok: false, error: 'Focus চলার সময় পরিবর্তন করা যাবে না।', state: publicState() };
    st.settings = core.sanitizeSettings(patch, st.settings);
    save(); broadcast();
    return { ok: true, state: publicState() };
  });
  /* ---- Schedule Block profiles ---- */
  const schedLocked = (id) => { const p = st.schedules.find((x) => x.id === id); return !!p && core.isScheduleActive(p, new Date()); };
  const LOCK_MSG = 'এই profile এখন block করছে — সময় শেষ না হওয়া পর্যন্ত পরিবর্তন / বন্ধ / delete করা যাবে না।';
  ipcMain.handle('focus:schedSave', (e, o) => {
    if (!fromFocusWin(e)) return { ok: false };
    const id = o && typeof o.id === 'string' ? o.id : '';
    if (id && schedLocked(id)) return { ok: false, error: LOCK_MSG, state: publicState() };
    const idx = st.schedules.findIndex((x) => x.id === id);
    if (idx < 0 && st.schedules.length >= core.MAX_SCHEDULES) return { ok: false, error: 'সর্বোচ্চ ' + core.MAX_SCHEDULES + ' টি profile রাখা যাবে।', state: publicState() };
    const r = core.sanitizeSchedule(o, idx >= 0 ? id : undefined);
    if (!r.value) return { ok: false, error: r.error, state: publicState() };
    if (idx >= 0) st.schedules[idx] = r.value; else st.schedules.push(r.value);
    save(); refreshSchedule(); broadcast();
    return { ok: true, state: publicState() };
  });
  ipcMain.handle('focus:schedDelete', (e, id) => {
    if (!fromFocusWin(e)) return { ok: false };
    if (schedLocked(id)) return { ok: false, error: LOCK_MSG, state: publicState() };
    st.schedules = st.schedules.filter((x) => x.id !== id);
    save(); refreshSchedule(); broadcast();
    return { ok: true, state: publicState() };
  });
  ipcMain.handle('focus:schedToggle', (e, id, enabled) => {
    if (!fromFocusWin(e)) return { ok: false };
    if (schedLocked(id)) return { ok: false, error: LOCK_MSG, state: publicState() };
    const p = st.schedules.find((x) => x.id === id);
    if (!p) return { ok: false, state: publicState() };
    p.enabled = !!enabled;
    save(); refreshSchedule(); broadcast();
    return { ok: true, state: publicState() };
  });
  ipcMain.handle('focus:start', (e, o) => (fromFocusWin(e) ? startSession(o) : { ok: false }));
  ipcMain.handle('focus:stop', (e, o) => (fromFocusWin(e) ? stopSession(o) : { ok: false }));
  ipcMain.handle('focus:pickExe', async (e) => {
    if (!fromFocusWin(e)) return null; // (focus:update / schedSave refuse changes while locked)
    const r = await dialog.showOpenDialog(getMainWin(), { properties: ['openFile'], filters: [{ name: 'Executable Files', extensions: ['exe'] }, { name: 'All Files', extensions: ['*'] }] });
    return r.canceled || !r.filePaths[0] ? null : path.basename(r.filePaths[0]);
  });
  ipcMain.handle('focus:running', async (e) => {
    if (!fromFocusWin(e) || !IS_WIN) return [];
    const names = await new Promise((resolve) => {
      execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command',
        "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-Process | Where-Object { $_.MainWindowHandle -ne 0 } | ForEach-Object { $_.ProcessName + '.exe' }"],
      { windowsHide: true, timeout: 8000, encoding: 'utf8' }, (err, out) => resolve(err ? lastProcs.map((p) => p.name) : String(out).split(/\r?\n/)));
    });
    const seen = new Set();
    const out = [];
    for (const n of names) {
      const name = String(n || '').trim();
      const k = name.toLowerCase();
      if (name && !seen.has(k) && !core.SAFE.has(k) && k !== SELF_EXE.toLowerCase()) { seen.add(k); out.push(name); }
    }
    return out.sort((a, b) => a.localeCompare(b)).slice(0, 100);
  });
}

/* Settings -> "Adult Block (all browsers)": start / stop the window watcher on its own. */
function setExtAdult(on) {
  extAdult = !!on;
  if ((extAdult || (st.active && st.quick)) && IS_WIN) startExtServer(); else stopExtServer();
  if (engineNeeded()) startEngine(); else if (helper) stopEngine();
}

module.exports = {
  init, setExtAdult,
  openWindow,
  isActive: () => st.active,
  stop: () => { clearInterval(schedTimer); stopEngine(); stopExtServer(); }, // app is quitting: end the helper (the session itself stays saved)
};
