'use strict';
/* Focus Mode (main process). Port of the "Simple Blocks" tab of RasFocus-Exe-Release.
   - state is saved in userData/focus.json, so an active session survives restarts
   - a hidden PowerShell helper reports the foreground window (300 ms) and every process that has
     a visible window (every ~2 s); the decisions are made in focus-core.js
   - blocked app  -> process killed; blocked site -> Ctrl+W on the browser tab + minimise;
     Task Manager / uninstall windows -> closed; a quote popup is shown each time */
const { app, BrowserWindow, ipcMain, dialog, Notification, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn, execFile } = require('child_process');
const core = require('./focus-core');

const IS_WIN = process.platform === 'win32';
const SELF_EXE = path.basename(process.execPath);
const STATE_FILE = () => path.join(app.getPath('userData'), 'focus.json');
const HELPER_DIR = () => path.join(app.getPath('userData'), 'focus-helper');

let st = { settings: Object.assign({}, core.DEFAULTS), active: false, endAt: 0, pw: null };
let helper = null, restartTimer = null, expiryTimer = null;
let getMainWin = () => null, hooks = {};
let lastAlert = 0, lastAct = 0, procBuf = [], lastProcs = [];

/* ---------- persistence ---------- */
function load() {
  try {
    const j = JSON.parse(fs.readFileSync(STATE_FILE(), 'utf8'));
    st.settings = core.sanitizeSettings(j.settings, core.DEFAULTS);
    st.active = !!j.active && Number(j.endAt) > Date.now();
    st.endAt = st.active ? Number(j.endAt) : 0;
    st.pw = st.active && j.pw && j.pw.salt && j.pw.hash ? j.pw : null;
  } catch (_) { /* first run */ }
}
function save() {
  try {
    fs.mkdirSync(path.dirname(STATE_FILE()), { recursive: true });
    fs.writeFileSync(STATE_FILE(), JSON.stringify({ settings: st.settings, active: st.active, endAt: st.endAt, pw: st.pw }));
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
    settings: {
      mode: s.mode, controlMode: s.controlMode, showQuotes: s.showQuotes, quoteLang: s.quoteLang,
      hours: s.hours, mins: s.mins, sites: s.sites.slice(),
      apps: lockedApps().concat(s.apps.map((a) => ({ type: a.type, name: a.name, locked: false }))),
    },
    common: { sites: core.COMMON_SITES, apps: core.COMMON_APPS },
  };
}
function broadcast() {
  const ps = publicState();
  const mw = getMainWin();
  if (mw && !mw.isDestroyed()) { mw.webContents.send('focus:state', ps); mw.webContents.send('focus:badge', st.active); }
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

/* ---------- Windows helper (PowerShell) ---------- */
const MONITOR_PS = `$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class RFMon {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
}
"@
function Clean([string]$s) { if ($null -eq $s) { return '' }; return ($s -replace '[\\t\\r\\n]+', ' ') }
$names = @{}
$tick = 0
while ($true) {
  $h = [RFMon]::GetForegroundWindow()
  if ($h -ne [IntPtr]::Zero) {
    $sb = New-Object System.Text.StringBuilder 512
    [void][RFMon]::GetWindowText($h, $sb, 512)
    [uint32]$procId = 0
    [void][RFMon]::GetWindowThreadProcessId($h, [ref]$procId)
    if (-not $names.ContainsKey($procId)) {
      $pr = Get-Process -Id $procId
      if ($pr) { $names[$procId] = $pr.ProcessName + '.exe' }
    }
    [Console]::Out.WriteLine("FG\`t" + $h.ToInt64() + "\`t" + $procId + "\`t" + $names[$procId] + "\`t" + (Clean $sb.ToString()))
  }
  if ($tick % 7 -eq 0) {
    if ($names.Count -gt 500) { $names.Clear() }
    foreach ($p in Get-Process) {
      if ($p.MainWindowHandle -ne 0) {
        [Console]::Out.WriteLine("P\`t" + $p.Id + "\`t" + $p.ProcessName + ".exe\`t" + (Clean $p.MainWindowTitle))
      }
    }
    [Console]::Out.WriteLine("ENDP")
  }
  $tick++
  Start-Sleep -Milliseconds 300
}
`;

const ACT_PS = `param([string]$Action, [long]$Hwnd)
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class RFAct {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr w, IntPtr l);
}
"@
$h = [IntPtr]$Hwnd
if ($Action -eq 'closetab') {
  if ([RFAct]::GetForegroundWindow() -eq $h) {
    [System.Windows.Forms.SendKeys]::SendWait('^w')
    Start-Sleep -Milliseconds 80
  }
  [void][RFAct]::ShowWindow($h, 6)
} elseif ($Action -eq 'closewin') {
  [void][RFAct]::PostMessage($h, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
}
`;

function writeScripts() {
  fs.mkdirSync(HELPER_DIR(), { recursive: true });
  const bom = '\ufeff'; // Windows PowerShell 5 reads BOM-less files as ANSI
  fs.writeFileSync(path.join(HELPER_DIR(), 'monitor.ps1'), bom + MONITOR_PS, 'utf8');
  fs.writeFileSync(path.join(HELPER_DIR(), 'act.ps1'), bom + ACT_PS, 'utf8');
}
const PS_ARGS = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File'];

function activeApps() { return lockedApps().concat(st.settings.apps); }

function onForeground(parts) {
  if (!st.active) return;
  const [, hwnd, pid, proc, title] = parts;
  if (Number(pid) === process.pid) return;
  const d = core.decideForeground({ proc, title: title || '', selfName: SELF_EXE, sites: st.settings.sites });
  if (!d) return;
  const now = Date.now();
  if (now - lastAct < 1500) return;
  lastAct = now;
  execFile('powershell.exe', PS_ARGS.concat([path.join(HELPER_DIR(), 'act.ps1'), '-Action', d.action, '-Hwnd', String(hwnd)]),
    { windowsHide: true, timeout: 8000 }, () => {});
  showAlert(d.msg);
}

function onSweep(list) {
  lastProcs = list;
  if (!st.active) return;
  const apps = activeApps();
  for (const p of list) {
    if (p.pid === process.pid || (helper && p.pid === helper.pid)) continue;
    const d = core.decideProcess({ mode: st.settings.mode, apps, proc: p.name, title: p.title, selfName: SELF_EXE });
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

function startEngine() {
  if (!IS_WIN || helper) return;
  try {
    writeScripts();
    helper = spawn('powershell.exe', PS_ARGS.concat([path.join(HELPER_DIR(), 'monitor.ps1')]),
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
    if (st.active) { clearTimeout(restartTimer); restartTimer = setTimeout(startEngine, 2000); restartTimer.unref(); }
  });
}
function stopEngine() {
  clearTimeout(restartTimer);
  if (helper) { const h = helper; helper = null; try { h.kill(); } catch (_) {} }
  procBuf = [];
}

/* ---------- session control ---------- */
function finish(notify) {
  st.active = false; st.endAt = 0; st.pw = null;
  save(); stopEngine(); broadcast();
  if (notify && Notification.isSupported()) {
    new Notification({ title: 'Focus Mode', body: 'Focus session শেষ। দারুণ কাজ! 🎉' }).show();
  }
}

function startSession(o) {
  if (!IS_WIN) return { ok: false, error: 'Focus Mode শুধু Windows এ চলে।' };
  if (st.active) return { ok: false, error: 'Focus আগে থেকেই চলছে।' };
  const s = st.settings;
  const mins = s.hours * 60 + s.mins;
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
  st.active = true; st.endAt = Date.now() + mins * 60000; st.pw = pw;
  save(); startEngine(); armExpiry(); broadcast();
  if (hooks.onStart) hooks.onStart();
  return { ok: true };
}

function stopSession(o) {
  if (!st.active) return { ok: true };
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
  expiryTimer = setInterval(() => { if (st.active && Date.now() >= st.endAt) finish(true); }, 1000);
  expiryTimer.unref();
}

/* ---------- page (opens inside the main app window; Back returns to the site) ---------- */
function openWindow() {
  const mw = getMainWin();
  if (!mw || mw.isDestroyed()) return;
  if (mw.isMinimized()) mw.restore();
  mw.show(); mw.focus();
  mw.loadFile(path.join(__dirname, 'focus.html'));
}

/* ---------- IPC ---------- */
// only the local focus.html page (never the website) may use the Focus API
function fromFocusWin(e) {
  const mw = getMainWin();
  if (!mw || mw.isDestroyed() || e.sender !== mw.webContents) return false;
  try {
    const u = new URL(e.senderFrame && e.senderFrame.url ? e.senderFrame.url : mw.webContents.getURL());
    return u.protocol === 'file:' && /\/focus\.html$/.test(u.pathname);
  } catch (_) { return false; }
}

function init(opts) {
  getMainWin = (opts && opts.getMainWin) || getMainWin;
  hooks = opts || {};
  load();
  if (st.active) { startEngine(); armExpiry(); }
  save();

  ipcMain.on('focus:open', openWindow);
  ipcMain.handle('focus:get', (e) => (fromFocusWin(e) ? publicState() : null));
  ipcMain.handle('focus:update', (e, patch) => {
    if (!fromFocusWin(e)) return { ok: false };
    if (st.active) return { ok: false, error: 'Focus চলার সময় পরিবর্তন করা যাবে না।', state: publicState() };
    st.settings = core.sanitizeSettings(patch, st.settings);
    save(); broadcast();
    return { ok: true, state: publicState() };
  });
  ipcMain.handle('focus:start', (e, o) => (fromFocusWin(e) ? startSession(o) : { ok: false }));
  ipcMain.handle('focus:stop', (e, o) => (fromFocusWin(e) ? stopSession(o) : { ok: false }));
  ipcMain.handle('focus:pickExe', async (e) => {
    if (!fromFocusWin(e) || st.active) return null;
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

module.exports = {
  init,
  openWindow,
  isActive: () => st.active,
  stop: stopEngine, // app is quitting: end the helper (the session itself stays saved)
};
