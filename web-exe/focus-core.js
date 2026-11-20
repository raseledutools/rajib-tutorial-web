'use strict';
/* Focus Mode — pure logic (no Electron / no OS calls), so it can be unit-tested anywhere.
   Ported from RasFocus-Exe-Release / tab_blocks.cpp ("Simple Blocks" tab). */
const crypto = require('crypto');

const QUOTES = [
  { bn: 'সময়ের মূল্য বোঝো, জীবন তোমার মূল্য বুঝবে।', en: 'Understand the value of time, life will understand your value.' },
  { bn: 'সফলতা আসে ফোকাস থেকে, ডিস্ট্রাকশন থেকে নয়।', en: 'Success comes from focus, not from distraction.' },
  { bn: 'আজকের সময় নষ্ট মানে, কালকের স্বপ্ন নষ্ট।', en: "Wasting time today means ruining tomorrow's dreams." },
  { bn: 'যে নিজের মনকে নিয়ন্ত্রণ করতে পারে, সে পৃথিবী জয় করতে পারে।', en: 'He who can control his mind can conquer the world.' },
  { bn: 'বড় কিছু পেতে হলে ছোট আনন্দগুলো ত্যাগ করতে হয়।', en: 'To achieve something big, you have to sacrifice small pleasures.' },
  { bn: 'তোমার আজকের ফোকাস নির্ধারণ করবে তোমার কালকের ভবিষ্যৎ।', en: 'Your focus today determines your future tomorrow.' },
  { bn: 'যা তোমাকে তোমার লক্ষ্য থেকে দূরে সরিয়ে নেয়, তাকে এখনই না বলো।', en: 'Say no to whatever takes you away from your goals.' },
  { bn: 'সাময়িক আনন্দ নয়, স্থায়ী সফলতার দিকে নজর দাও।', en: 'Focus on permanent success, not temporary pleasure.' },
];

const COMMON_SITES = ['facebook.com', 'youtube.com', 'instagram.com', 'tiktok.com', 'reddit.com', 'twitter.com'];
const COMMON_APPS = ['chrome.exe', 'msedge.exe', 'firefox.exe', 'telegram.exe', 'whatsapp.exe', 'discord.exe', 'vlc.exe'];

// Only these processes get their *tab* closed when a blocked site shows in the title.
const BROWSERS = new Set([
  'chrome.exe', 'msedge.exe', 'firefox.exe', 'brave.exe', 'opera.exe', 'opera_gx.exe', 'vivaldi.exe',
  'iexplore.exe', 'chromium.exe', 'yandex.exe', 'arc.exe', 'waterfox.exe', 'librewolf.exe', 'tor.exe',
]);

// Windows' own plumbing: never killed, in any mode (killing these breaks the desktop).
const SAFE = new Set([
  'explorer.exe', 'svchost.exe', 'dwm.exe', 'csrss.exe', 'winlogon.exe', 'services.exe', 'lsass.exe',
  'sihost.exe', 'ctfmon.exe', 'taskhostw.exe', 'runtimebroker.exe', 'searchhost.exe', 'searchapp.exe',
  'searchui.exe', 'startmenuexperiencehost.exe', 'shellexperiencehost.exe', 'textinputhost.exe',
  'lockapp.exe', 'fontdrvhost.exe', 'conhost.exe', 'powershell.exe', 'securityhealthsystray.exe',
]);

const lc = (s) => String(s == null ? '' : s).toLowerCase();

/* ---------- input normalising ---------- */
function normalizeSite(raw) {
  let s = lc(raw).trim();
  s = s.replace(/^[a-z]+:\/\//, '').replace(/^(www|m)\./, '');
  s = s.split(/[\/?#]/)[0].trim();
  return /^[a-z0-9.-]{3,100}$/.test(s) ? s : '';
}
function normalizeApp(raw, type) {
  let s = String(raw == null ? '' : raw).trim();
  if (type === 'title') s = s.replace(/\s*\(Window\)$/i, '');
  else s = s.split(/[\\/]/).pop().trim();
  if (!s || s.length > 100 || /[<>"|?*\u0000-\u001f]/.test(s)) return null;
  return { type: type === 'title' ? 'title' : 'exe', name: s };
}
function siteCore(site) {
  const s = lc(site);
  const i = s.indexOf('.');
  const core = i >= 0 ? s.slice(0, i) : s;
  return core.length > 2 ? core : '';
}

/* ---------- matching (port of ShouldKillProcessBlocks / BackgroundBlockerThread) ---------- */
function exeMatch(proc, appName) {
  const p = lc(proc), a = lc(appName);
  if (!p || !a) return false;
  if (p === a || p === a + '.exe') return true;
  return a.length >= 4 && !a.includes('.') && p.includes(a);
}
function isUninstallTitle(t) {
  return t.includes('uninstall') || t.includes('programs and features') || t.includes('অ্যাপস ও বৈশিষ্ট্য');
}
function isTaskMgrTitle(t) { return t.includes('task manager') || t.includes('taskmgr'); }
function isBrowser(proc) { return BROWSERS.has(lc(proc)); }
function isSafe(proc) { return SAFE.has(lc(proc)); }

/* ---------- address-bar text (read by the PowerShell helper via UI Automation) ---------- */
// -> { host, internal } | null (nothing usable). Chrome / Edge show "example.com/path" without the scheme.
function parseUrlText(raw) {
  let t = String(raw == null ? '' : raw).trim();
  if (!t || /\s/.test(t.split(/[\/?#]/)[0])) return null;      // empty, or search text instead of a URL
  const m = t.match(/^([a-z][a-z0-9+.-]*):(\/\/)?/i);
  if (m) {
    const sch = lc(m[1]);
    if (sch !== 'http' && sch !== 'https') return { host: '', internal: true };   // chrome:// edge:// about: file: ...
    t = t.slice(m[0].length);
  }
  let host = lc(t.split(/[\/?#]/)[0]).replace(/^[^@]*@/, '').replace(/:\d+$/, '').replace(/^(www|m)\./, '');
  if (!host) return null;
  if (host === 'localhost' || host === '127.0.0.1' || !host.includes('.')) return { host, internal: true };   // our own focus page, new-tab pages
  return { host, internal: false };
}
function hostMatches(host, site) {
  const h = lc(host), s = lc(site);
  return !!h && !!s && (h === s || h.endsWith('.' + s));
}

/** What to do with the FOREGROUND window. null = leave it alone.
    block mode: a listed site (by address bar when it can be read, else by window title) -> close the tab.
    allow mode: with a site list, every site that is NOT listed -> close the tab (address bar only: a title can't tell). */
function decideForeground({ proc, title, url, mode, selfName, sites }) {
  const p = lc(proc), t = lc(title);
  if (!p || !t || p === lc(selfName)) return null;
  const browser = isBrowser(p);
  if (!browser && isTaskMgrTitle(t)) return { action: 'closewin', msg: 'Focus is Active. Task Manager is blocked!' };
  if (!browser && isUninstallTitle(t)) return { action: 'closewin', msg: 'Focus is Active. Uninstallation is blocked!' };
  if (!browser) return null;
  const u = parseUrlText(url);
  if (mode === 'allow') {
    if (!(sites || []).length || !u || u.internal) return null;
    return (sites || []).some((s) => hostMatches(u.host, s)) ? null : { action: 'closetab', msg: 'Focus is Active. Only allowed websites can open.' };
  }
  if (u && !u.internal) return (sites || []).some((s) => hostMatches(u.host, s)) ? { action: 'closetab', msg: null } : null;
  for (const site of sites || []) {
    const core = siteCore(site);
    if (core && t.includes(core)) return { action: 'closetab', msg: null };
  }
  return null;
}

/** Adult Block for EXTERNAL browsers: adult site / search in the address bar (seen at once, before the page title loads),
    or an adult word in the title -> turn that tab into the focus page. */
function decideAdultForeground({ proc, title, url, selfName, isAdultTitle, isAdultHost }) {
  const p = lc(proc);
  if (!p || p === lc(selfName) || !isBrowser(p)) return null;
  const u = parseUrlText(url);
  if (u && u.internal) return null;                                   // our focus page, new tab ...
  if (u && isAdultHost && isAdultHost(u.host)) return { action: 'tofocus', msg: null };
  let dec = String(url || '');
  try { dec = decodeURIComponent(dec.replace(/\+/g, ' ')); } catch (_) {}
  if (dec && /[?&#]/.test(dec) && isAdultTitle(dec)) return { action: 'tofocus', msg: null };   // adult words in a search query
  return title && isAdultTitle(String(title)) ? { action: 'tofocus', msg: null } : null;
}

/** What to do with a running process that has a visible window. null = leave it alone. */
function decideProcess({ mode, apps, proc, title, selfName }) {
  const p = lc(proc), t = lc(title);
  if (!p || p === lc(selfName)) return null;
  if (p === 'taskmgr.exe') return { reason: 'taskmgr', msg: 'Focus is Active. Task Manager is blocked!' };
  if (isSafe(p)) return null;
  if (!isBrowser(p) && isUninstallTitle(t)) return { reason: 'uninstall', msg: 'Focus is Active. Uninstallation is blocked!' };
  const hit = (app) => (app.type === 'title' ? t.includes(lc(app.name)) : exeMatch(p, app.name));
  if (mode === 'block') {
    return (apps || []).some(hit) ? { reason: 'app', msg: null } : null;
  }
  // allow mode: only listed apps may keep a visible window
  if ((apps || []).some(hit)) return null;
  return t.length > 0 ? { reason: 'notallowed', msg: 'Focus is Active. Only allowed apps can run.' } : null;
}

/* ---------- Schedule Block (profiles that block sites / apps at fixed days + times) ---------- */
const MAX_SCHEDULES = 30;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const toMin = (t) => { const [h, m] = String(t).split(':'); return Number(h) * 60 + Number(m); };

function cleanSites(list) {
  const seen = new Set(), out = [];
  for (const x of Array.isArray(list) ? list.slice(0, 300) : []) {
    const s = normalizeSite(x);
    if (s && !seen.has(s)) { seen.add(s); out.push(s); }
  }
  return out;
}
function cleanApps(list) {
  const seen = new Set(), out = [];
  for (const x of Array.isArray(list) ? list.slice(0, 300) : []) {
    const a = x && normalizeApp(x.name, x.type);
    const key = a && a.type + ':' + lc(a.name);
    if (a && !seen.has(key)) { seen.add(key); out.push(a); }
  }
  return out;
}
/** -> { value } or { error }. Never trusts the renderer. */
function sanitizeSchedule(o, existingId) {
  o = o && typeof o === 'object' ? o : {};
  let id = typeof o.id === 'string' && /^[a-z0-9-]{6,40}$/.test(o.id) ? o.id : existingId;
  if (!id) id = crypto.randomBytes(8).toString('hex');
  const name = String(o.name == null ? '' : o.name).replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 40) || 'Schedule';
  const days = [...new Set((Array.isArray(o.days) ? o.days : []).map(Number).filter((d) => Number.isInteger(d) && d >= 0 && d <= 6))].sort();
  if (!days.length) return { error: 'কমপক্ষে একটি দিন select করুন।' };
  const allDay = !!o.allDay;
  const start = TIME_RE.test(o.start) ? o.start : '09:00';
  const end = TIME_RE.test(o.end) ? o.end : '17:00';
  if (!allDay && start === end) return { error: 'Start আর End time এক হতে পারবে না (পুরো দিন চাইলে "All day" দিন)।' };
  const sites = cleanSites(o.sites), apps = cleanApps(o.apps);
  if (!sites.length && !apps.length) return { error: 'অন্তত একটি website বা app যোগ করুন।' };
  return { value: { id, name, enabled: o.enabled !== false, days, allDay, start, end, sites, apps } };
}
function sanitizeSchedules(list) {
  const out = [], ids = new Set();
  for (const x of Array.isArray(list) ? list.slice(0, MAX_SCHEDULES) : []) {
    const r = sanitizeSchedule(x);
    if (r.value && !ids.has(r.value.id)) { ids.add(r.value.id); out.push(r.value); }
  }
  return out;
}
/** Is this profile blocking at `date` (local time)? An overnight window (22:00-06:00) belongs to the day it STARTS on. */
function isScheduleActive(p, date) {
  if (!p || !p.enabled) return false;
  const d = date instanceof Date ? date : new Date();
  const day = d.getDay(), min = d.getHours() * 60 + d.getMinutes();
  const has = (n) => p.days.includes(n);
  if (p.allDay) return has(day);
  const a = toMin(p.start), b = toMin(p.end);
  if (a < b) return has(day) && min >= a && min < b;
  return (has(day) && min >= a) || (has((day + 6) % 7) && min < b);
}
/** Everything that must be blocked right now because of schedules. */
function activeScheduleBlocks(schedules, date) {
  const profiles = (schedules || []).filter((p) => isScheduleActive(p, date));
  const sites = [...new Set(profiles.flatMap((p) => p.sites))];
  const apps = [], seen = new Set();
  for (const a of profiles.flatMap((p) => p.apps)) { const k = a.type + ':' + lc(a.name); if (!seen.has(k)) { seen.add(k); apps.push(a); } }
  return { ids: profiles.map((p) => p.id), names: profiles.map((p) => p.name), sites, apps };
}
/** Foreground window while a schedule is on: only a blocked site in a browser tab is acted on (never Task Manager etc.). */
function decideScheduledForeground({ proc, title, url, selfName, sites }) {
  const p = lc(proc), t = lc(title);
  if (!p || !t || p === lc(selfName) || !isBrowser(p)) return null;
  const u = parseUrlText(url);
  if (u && !u.internal) return (sites || []).some((s) => hostMatches(u.host, s)) ? { action: 'closetab', msg: null } : null;
  for (const site of sites || []) {
    const core = siteCore(site);
    if (core && t.includes(core)) return { action: 'closetab', msg: null };
  }
  return null;
}
/** Running window while a schedule is on: kill it if it matches a scheduled app. */
function decideScheduledProcess({ apps, proc, title, selfName }) {
  const p = lc(proc), t = lc(title);
  if (!p || p === lc(selfName) || p === 'taskmgr.exe' || isSafe(p)) return null;
  const hit = (app) => (app.type === 'title' ? t.includes(lc(app.name)) : exeMatch(p, app.name));
  return (apps || []).some(hit) ? { reason: 'schedule', msg: null } : null;
}

/* ---------- quotes ---------- */
function pickMessage({ showQuotes, quoteLang, customMsg, rnd }) {
  if (customMsg) return customMsg;
  if (!showQuotes) return 'Focus Active! Access Denied.';
  const r = typeof rnd === 'number' ? rnd : Math.random();
  const q = QUOTES[Math.min(QUOTES.length - 1, Math.floor(r * QUOTES.length))];
  return quoteLang === 'en' ? q.en : q.bn;
}

/* ---------- long-text unlock + parent password ---------- */
function countWords(text) { return String(text || '').trim().split(/\s+/).filter(Boolean).length; }
function hashPassword(pw) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(pw), salt, 32);
  return { salt: salt.toString('hex'), hash: hash.toString('hex') };
}
function verifyPassword(pw, rec) {
  if (!rec || !rec.salt || !rec.hash || typeof pw !== 'string' || !pw) return false;
  try {
    const h = crypto.scryptSync(pw, Buffer.from(rec.salt, 'hex'), 32);
    const want = Buffer.from(rec.hash, 'hex');
    return h.length === want.length && crypto.timingSafeEqual(h, want);
  } catch (_) { return false; }
}

/* ---------- settings ---------- */
const DEFAULTS = Object.freeze({
  mode: 'block', controlMode: 'self', showQuotes: true, quoteLang: 'bn', hours: 1, mins: 0, sites: [], apps: [],
});
const clampInt = (v, lo, hi, d) => { const n = Math.round(Number(v)); return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : d; };

function sanitizeSettings(o, base) {
  const b = Object.assign({}, DEFAULTS, base || {});
  o = o && typeof o === 'object' ? o : {};
  const out = {
    mode: o.mode === 'allow' ? 'allow' : o.mode === 'block' ? 'block' : b.mode,
    controlMode: ['self', 'parents', 'longtext'].includes(o.controlMode) ? o.controlMode : b.controlMode,
    showQuotes: typeof o.showQuotes === 'boolean' ? o.showQuotes : b.showQuotes,
    quoteLang: o.quoteLang === 'en' ? 'en' : o.quoteLang === 'bn' ? 'bn' : b.quoteLang,
    hours: clampInt(o.hours, 0, 23, b.hours),
    mins: clampInt(o.mins, 0, 59, b.mins),
    sites: b.sites, apps: b.apps,
  };
  if (Array.isArray(o.sites)) {
    const seen = new Set();
    out.sites = [];
    for (const x of o.sites.slice(0, 300)) {
      const s = normalizeSite(x);
      if (s && !seen.has(s)) { seen.add(s); out.sites.push(s); }
    }
  }
  if (Array.isArray(o.apps)) {
    const seen = new Set();
    out.apps = [];
    for (const x of o.apps.slice(0, 300)) {
      const a = x && normalizeApp(x.name, x.type);
      const key = a && a.type + ':' + lc(a.name);
      if (a && !seen.has(key)) { seen.add(key); out.apps.push(a); }
    }
  }
  return out;
}

module.exports = {
  QUOTES, COMMON_SITES, COMMON_APPS, BROWSERS, SAFE,
  normalizeSite, normalizeApp, siteCore, exeMatch,
  parseUrlText, hostMatches, decideForeground, decideAdultForeground, decideProcess, pickMessage,
  countWords, hashPassword, verifyPassword, sanitizeSettings, DEFAULTS,
  MAX_SCHEDULES, sanitizeSchedule, sanitizeSchedules, isScheduleActive, activeScheduleBlocks,
  decideScheduledForeground, decideScheduledProcess,
};
