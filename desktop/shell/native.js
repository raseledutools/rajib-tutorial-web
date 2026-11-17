'use strict';
/* Native Section – the Android app's native screens, ported for desktop.
   Same Firestore data as the web and Android apps (read through the main process). */
const WHATSAPP = 'https://wa.me/8801566054963';
const SEMS = ['1st Semester', '2nd Semester', '3rd Semester', '4th Semester', '5th Semester', '6th Semester', '7th Semester', '8th Semester'];
const QB_CATS = ['BUET', 'PSC', 'Other'];
const N = { courses: [], members: [], resources: [], live: {}, loaded: false, loading: false, err: '', tab: 'home', course: null, lesson: '', sem: '', q: '', cat: '', timer: null };

const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch (_) { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch (_) {} },
  del: (k) => { try { localStorage.removeItem(k); } catch (_) {} },
};
let devId = store.get('rt_device_id');
if (!devId) { devId = 'dev_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8); store.set('rt_device_id', devId); }
N.mobile = store.get('rt_mobile'); N.token = store.get('rt_token');
if (N.mobile && !N.token) N.mobile = null;

/* ── helpers ported from Store.java ── */
const S = (m, k) => (m && typeof m[k] === 'string' ? m[k] : '');
const today = () => { const d = new Date(), p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`; };
const normMobile = (v) => { let d = String(v || '').replace(/\D/g, ''); if (d.startsWith('880')) d = '0' + d.slice(3); return d; };
const safeUrl = (u) => (/^https:\/\//i.test((u || '').trim()) ? u.trim() : '');
const bKey = (b) => (b || '').trim().toLowerCase();
const lessonsLabel = (n) => n + (n === 1 ? ' lesson' : ' lessons');
function ytEmbed(url) {
  const m = /(?:youtube\.com\/(?:watch\?v=|embed\/|shorts\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})/.exec(url || '');
  return m ? `https://www.youtube.com/embed/${m[1]}?autoplay=1&rel=0&modestbranding=1&iv_load_policy=3&playsinline=1&color=white&enablejsapi=0` : null;
}
function platformOf(u) {
  try { const h = new URL(u).hostname.replace(/^www\./, '');
    if (h.includes('youtu')) return 'YouTube Live'; if (h.includes('meet.google')) return 'Google Meet'; if (h.includes('zoom')) return 'Zoom';
    if (h.includes('facebook') || h.includes('fb.watch')) return 'Facebook Live'; return h; } catch (_) { return 'Online'; }
}
const strList = (o) => (Array.isArray(o) ? o.filter((x) => typeof x === 'string') : null);
function parseMember(d) {
  const x = d.data;
  return { id: d.id, name: S(x, 'name'), mobile: S(x, 'mobile'), expiry: S(x, 'expiry'), batch: S(x, 'batch').trim(), isMember: x.isMember === true,
    allCourses: typeof x.allCourses === 'boolean' ? x.allCourses : null, courseIds: strList(x.courseIds), allowedDevices: strList(x.allowedDevices) || [],
    activeDevice: x.activeDevice && typeof x.activeDevice === 'object' ? x.activeDevice : null, sessionToken: S(x, 'sessionToken'), sessionDevice: S(x, 'sessionDevice') };
}
function parseAll(docs) {
  const r = { courses: [], members: [], resources: [], live: {} };
  for (const d of docs) {
    const x = d.data;
    if (d.id === '_live_settings') { const l = x.live && typeof x.live === 'object' ? x.live : {};
      for (const k of ['classTitle', 'classTime', 'classUrl', 'examTitle', 'examTime', 'examUrl', 'premiumUrl', 'premiumInfo', 'payBkash', 'payNagad', 'payRocket']) r.live[k] = S(l, k);
      continue; }
    if (d.id.startsWith('_member_')) { r.members.push(parseMember(d)); continue; }
    if (d.id.startsWith('_res_')) { r.resources.push({ id: d.id, type: S(x, 'type'), category: S(x, 'category'), title: S(x, 'title'), url: S(x, 'url'), note: S(x, 'note'), at: Number(x.at) || 0 }); continue; }
    if (/^_(disc|call|payreq)_/.test(d.id)) continue;
    const price = typeof x.price === 'number' ? Math.max(0, Math.floor(x.price)) : parseInt(String(x.price || '').replace(/\D/g, ''), 10) || 0;
    r.courses.push({ id: d.id, courseName: S(x, 'courseName'), title: S(x, 'title') || d.id, className: S(x, 'className'), subject: S(x, 'subject'), description: S(x, 'description'), price,
      videos: (Array.isArray(x.videos) ? x.videos : []).filter((v) => v && typeof v === 'object').map((v) => ({ id: S(v, 'id'), title: S(v, 'title'), url: S(v, 'cloudinaryUrl') || S(v, 'url'), durationMinutes: Number(v.durationMinutes) || 0 })) });
  }
  r.resources.sort((a, b) => b.at - a.at);
  return r;
}
const curMember = () => (N.mobile ? N.members.find((m) => m.id === '_member_' + N.mobile) || null : null);
const memberValid = (m) => !!m && !!N.mobile && (!m.expiry || today() <= m.expiry);
const isPremium = () => memberValid(curMember());
const hasAll = (m) => m.allCourses === true || (m.allCourses === null && m.courseIds === null);
const courseAllowed = (c) => { const m = curMember(); return !!(c && m && isPremium() && (hasAll(m) || (m.courseIds && m.courseIds.includes(c.id)))); };
const dropSession = () => { N.mobile = null; N.token = null; store.del('rt_mobile'); store.del('rt_token'); };
const notifForMe = (target, mobile, batch) => {
  if (target === 'all') return true; if (!N.mobile) return false;
  if (target === 'batch') { const me = curMember(); return !!me && !!bKey(batch) && bKey(batch) === bKey(me.batch); }
  return !!mobile && normMobile(mobile) === normMobile(N.mobile);
};

async function memberAuth(mobile, force) {
  const fail = { err: "We couldn't verify your access right now. Please try again." };
  let d; try { d = await api.fs.get('_member_' + mobile); } catch (_) { return fail; }
  const m = d ? parseMember(d) : null;
  if (!m || !m.isMember) return { noAccess: true };
  if (m.expiry && today() > m.expiry) return { err: 'This Premium membership has expired. Please renew it to continue.' };
  if (m.allowedDevices.length && !m.allowedDevices.includes(devId)) return { err: 'This device is not allowed to log in. Please contact the admin.' };
  if (!force && m.sessionToken && m.sessionDevice && m.sessionDevice !== devId) return { conflict: true, other: (m.activeDevice && m.activeDevice.label) || 'another device' };
  const token = crypto.randomUUID().replace(/-/g, '');
  const dev = { deviceId: devId, label: 'Windows · PC', loginTime: Date.now() };
  try { await api.fs.memberSession(mobile, { activeDevice: dev, sessionToken: token, sessionDevice: devId }); } catch (_) { return fail; }
  Object.assign(m, { activeDevice: dev, sessionToken: token, sessionDevice: devId });
  N.members = N.members.filter((x) => x.id !== m.id).concat(m);
  N.mobile = mobile; N.token = token; store.set('rt_mobile', mobile); store.set('rt_token', token);
  return { ok: true, name: m.name || 'Member' };
}
async function checkSession(forPlay) {
  if (!N.mobile) return '';
  try {
    const d = await api.fs.get('_member_' + N.mobile);
    if (!d) return forPlay ? '' : 'This account has been removed.';
    const m = parseMember(d);
    if (m.sessionToken && m.sessionToken !== N.token) return forPlay ? "This video can't be played because your account is logged in on another device." : 'Your account was logged in on another device. This session has ended.';
    if (forPlay && m.allowedDevices.length && !m.allowedDevices.includes(devId)) return 'This device is not allowed to watch videos.';
    if (m.expiry && today() > m.expiry) return 'Your Premium membership has expired.';
    return '';
  } catch (_) { return null; }
}
function memberLogout() {
  const mobile = N.mobile; dropSession();
  if (mobile) api.fs.memberSession(mobile, { activeDevice: null, sessionToken: null, sessionDevice: null }).catch(() => {});
  renderNative();
}

/* ── modal ── */
function modal(...kids) {
  const bg = el('div', { class: 'mbg', onclick: (e) => { if (e.target === bg) bg.remove(); } }, el('div', { class: 'modal' }, kids));
  document.body.append(bg); return bg;
}
function loginModal(after) {
  const mob = el('input', { type: 'tel', placeholder: '01XXXXXXXXX', maxlength: 14 });
  const out = el('div', { class: 'msg er' });
  const run = async (force) => {
    const m = normMobile(mob.value);
    if (!/^01\d{9}$/.test(m)) { out.textContent = 'Enter a valid mobile number'; return; }
    out.className = 'msg'; out.textContent = 'Checking…';
    const r = await memberAuth(m, force);
    out.className = 'msg er';
    if (r.ok) { bg.remove(); toast('Welcome, ' + r.name); renderNative(); if (after) after(); }
    else if (r.noAccess) { out.textContent = 'This number does not have Premium access.'; }
    else if (r.conflict) { out.replaceChildren('Already logged in on ' + r.other + '. ', btn('Log in here instead', () => run(true), 'btn sm')); }
    else out.textContent = r.err || 'Failed';
  };
  const bg = modal(el('h2', { text: 'Premium login' }), el('label', { class: 'l', text: 'Mobile number' }), mob, out,
    el('div', { class: 'flex' }, btn('Cancel', () => bg.remove(), 'btn ghost'), btn('Login', () => run(false))));
  mob.focus();
}
function buyModal(c) {
  const me = curMember();
  const name = el('input', { placeholder: 'Your name', value: me ? me.name : '' });
  const mob = el('input', { type: 'tel', placeholder: '01XXXXXXXXX', value: N.mobile || '' });
  const out = el('div', { class: 'msg er' });
  const pay = (kind) => async () => {
    const m = normMobile(mob.value);
    if (!name.value.trim() || !/^01\d{9}$/.test(m)) { out.textContent = 'Enter your name and a valid mobile number'; return; }
    out.className = 'msg'; out.textContent = 'Starting payment…';
    try { await api.pay({ kind, name: name.value.trim(), mobile: m, courseId: c.id }); bg.remove(); toast('Payment page opened'); }
    catch (e) { out.className = 'msg er'; out.textContent = String(e.message || e).replace(/^Error invoking remote method '[^']+': (Error: )?/, ''); }
  };
  const L = N.live, manual = [['bKash', L.payBkash], ['Nagad', L.payNagad], ['Rocket', L.payRocket]].filter((x) => x[1]);
  const bg = modal(el('h2', { text: 'Buy: ' + c.title }), el('div', { class: 'sub', text: '৳' + c.price }),
    el('label', { class: 'l', text: 'Name' }), name, el('label', { class: 'l', text: 'Mobile number' }), mob, out,
    el('div', { class: 'flex' }, btn('Pay with bKash', pay('bkash')), btn('Card / Mobile banking', pay('card'), 'btn ghost')),
    manual.length ? el('div', { class: 'sub', style: 'margin-top:14px', text: 'Manual payment: ' + manual.map((x) => x[0] + ' ' + x[1]).join(' · ') + '. Then send a screenshot on WhatsApp.' }) : null,
    el('div', { class: 'flex' }, btn('WhatsApp', () => api.openExternalWindow(WHATSAPP), 'btn ghost sm'), btn('Close', () => bg.remove(), 'btn ghost sm')));
}

/* ── data loading + shell ── */
async function loadAll() {
  N.loading = true; N.err = '';
  try {
    Object.assign(N, parseAll(await api.fs.list())); N.loaded = true;
    if (N.mobile && !isPremium()) { const m = curMember(); dropSession(); toast(m && m.expiry && today() > m.expiry ? 'Your Premium membership has expired' : 'Premium session ended'); }
  } catch (e) { N.err = "Couldn't load data. Check your internet connection."; }
  N.loading = false;
}
const TABS = [['home', '🏠 Home'], ['class', '🎥 Live Class'], ['exam', '📝 Live Exam'], ['disc', '💬 Discussion'], ['inbox', '🔔 Notifications'], ['circular', '📢 Circular'], ['qbank', '📚 Question Bank'], ['recent', '🆕 Recent QB']];
function setTab(t) { N.tab = t; N.course = null; N.cat = ''; renderNative(); }

async function renderNative() {
  clearInterval(N.timer); N.timer = null;
  const p = panes.native;
  if (!N.loaded && !N.loading) { p.replaceChildren(el('p', { class: 'sub', text: 'Loading…' })); await loadAll(); }
  if (current !== 'native') return;
  const me = curMember();
  const acct = el('div', { class: 'acct' }, N.mobile && me ? [`👤 ${me.name || 'Member'}`, btn('Logout', memberLogout, 'btn ghost sm')] : [btn('Premium login', () => loginModal(), 'btn sm')],
    btn('↻', async () => { N.loaded = false; await loadAll(); renderNative(); toast(N.err || 'Updated'); }, 'btn ghost sm'));
  const body = el('div');
  p.replaceChildren(el('div', { class: 'ntop' }, el('div', { class: 'ntabs' }, TABS.map(([k, t]) => el('button', { class: 'tab' + (N.tab === k ? ' on' : ''), text: t, onclick: () => setTab(k) }))), acct), body);
  if (N.err && !N.loaded) { body.append(el('div', { class: 'offline' }, el('p', { text: N.err }), btn('Retry', async () => { await loadAll(); renderNative(); }))); return; }
  ({ home: viewHome, class: () => viewLive(body, false), exam: () => viewLive(body, true), disc: viewDisc, inbox: viewInbox, circular: () => viewRes(body, 'circular'), qbank: () => viewRes(body, 'qbank'), recent: () => viewRes(body, 'recent') }[N.tab])(body);
}

/* ── Home + Course ── */
function viewHome(body) {
  if (N.course) return viewCourse(body, N.courses.find((c) => c.id === N.course));
  const L = N.live, tv = N.courses.reduce((a, c) => a + c.videos.length, 0);
  body.append(el('div', { class: 'hero' }, el('h2', { text: 'Welcome to Rajib Tutorial' }), el('div', { text: 'EEE courses with live classes and exams.' }),
    btn(L.classUrl ? '🎥 Join Live Class  ● LIVE' : '🎥 Live Class', () => setTab('class')), btn(L.examUrl ? '📝 Take Live Exam  ● LIVE' : '📝 Live Exam', () => setTab('exam'))),
    el('div', { class: 'stats' }, [[N.courses.length, 'Courses'], [tv, 'Video lessons'], [8, 'Semesters covered']].map(([n, l]) => el('div', { class: 'stat' }, el('b', { text: String(n) }), el('span', { text: l })))));
  const used = [...new Set(N.courses.map((c) => c.className).filter(Boolean))].sort((a, b) => (SEMS.indexOf(a) + 99) % 99 - (SEMS.indexOf(b) + 99) % 99);
  if (N.sem && !used.includes(N.sem)) N.sem = '';
  const search = el('input', { placeholder: 'Search courses', value: N.q });
  const sem = el('select', {}, el('option', { value: '', text: 'All Semesters' }), used.map((u) => el('option', { value: u, text: u })));
  sem.value = N.sem;
  const grid = el('div', { class: 'grid' }), cnt = el('div', { class: 'sub' });
  const draw = () => {
    const q = N.q.trim().toLowerCase();
    const list = N.courses.filter((c) => (!N.sem || c.className === N.sem) && (!q || [c.title, c.courseName, c.subject, c.className, c.description].join(' ').toLowerCase().includes(q)));
    cnt.textContent = N.courses.length ? `${list.length} of ${N.courses.length} courses` : '';
    grid.replaceChildren(...(list.length ? list.map((c) => el('div', { class: 'card ccard', onclick: () => { N.course = c.id; N.lesson = ''; renderNative(); } },
      el('div', { class: 'nm', text: c.title }), el('div', { class: 'ds', text: [c.className, c.subject].filter(Boolean).join(' · ') }),
      el('span', { class: 'badge', text: lessonsLabel(c.videos.length) }), ' ',
      c.price > 0 ? el('span', { class: 'badge ' + (courseAllowed(c) ? 'ok' : 'pr'), text: courseAllowed(c) ? '✓ Unlocked' : '৳' + c.price }) : null)) : [el('p', { class: 'sub', text: N.courses.length ? 'No courses match your search.' : 'New courses will be published soon.' })]));
  };
  search.addEventListener('input', () => { N.q = search.value; draw(); });
  sem.addEventListener('change', () => { N.sem = sem.value; draw(); });
  body.append(el('div', { class: 'tools' }, search, sem), cnt, grid, el('p', { class: 'sub', style: 'margin-top:22px', text: 'Admin tools (manage courses, members, notifications) are in Web Section → ⋮ → Admin Login.' }));
  draw();
}
function viewCourse(body, c) {
  if (!c) { N.course = null; return viewHome(body); }
  const player = el('div', { class: 'player', text: 'Select a lesson to start' });
  const title = el('div', { class: 't', style: 'margin-top:10px;font-weight:600' }), meta = el('div', { class: 'sub' });
  const rows = c.videos.map((v, i) => el('div', { class: 'lesson', onclick: () => play(v) }, el('span', { text: `${i + 1}. ${v.title || 'Lesson ' + (i + 1)}` }), el('small', { text: v.durationMinutes ? v.durationMinutes + ' min' : '' })));
  async function play(v) {
    if (!courseAllowed(c)) return gate(c);
    if (N.mobile) { const m = await checkSession(true); if (m) { toast(m); if (/another device|expired/.test(m)) { dropSession(); renderNative(); } return; } }
    const emb = ytEmbed(v.url), url = safeUrl(v.url);
    player.replaceChildren(emb ? el('iframe', { src: emb, allow: 'autoplay; fullscreen; encrypted-media', referrerpolicy: 'strict-origin-when-cross-origin' })
      : url ? el('video', { src: url, controls: '', autoplay: '', controlsList: 'nodownload noremoteplayback', disablePictureInPicture: '' }) : 'Cannot play this video');
    title.textContent = v.title; meta.textContent = c.title;
    rows.forEach((r, i) => r.classList.toggle('on', c.videos[i] === v));
  }
  const buy = c.price > 0 && !courseAllowed(c) ? el('div', { class: 'flex' }, btn(`Buy this course · ৳${c.price}`, () => buyModal(c)), btn('Already bought? Login', () => loginModal(renderNative), 'btn ghost')) : null;
  body.append(btn('← All courses', () => { N.course = null; renderNative(); }, 'back'), el('h2', { text: c.title }), el('div', { class: 'sub', text: [c.className, c.subject, lessonsLabel(c.videos.length)].filter(Boolean).join(' · ') }),
    c.description ? el('p', { text: c.description }) : null, buy, el('div', { class: 'cwrap' }, el('div', {}, player, title, meta), el('div', { class: 'lessons' }, c.videos.length ? rows : el('div', { class: 'lesson', text: 'No lessons yet' }))));
}
function gate(c) {
  const premium = isPremium();
  const bg = modal(el('h2', { text: 'Premium course' }),
    el('p', { class: 'sub', text: premium ? 'Your Premium account does not include this course.' : 'Log in with your Premium number to watch' + (c.price > 0 ? ', or buy this course.' : '.') }),
    el('div', { class: 'flex' }, premium ? null : btn('Login', () => { bg.remove(); loginModal(renderNative); }),
      c.price > 0 ? btn('Buy · ৳' + c.price, () => { bg.remove(); buyModal(c); }, 'btn ghost') : null, btn('Close', () => bg.remove(), 'btn ghost')));
}

/* ── Live class / exam ── */
function viewLive(body, exam) {
  const L = N.live, title = exam ? L.examTitle : L.classTitle, time = exam ? L.examTime : L.classTime, url = safeUrl(exam ? L.examUrl : L.classUrl);
  const name = exam ? 'Live Exam' : 'Live Class';
  body.append(el('div', { class: 'hero' }, el('h2', { text: url ? (title || name) + '  ● LIVE' : name + ' — not scheduled' }), el('div', { text: time || 'No time announced yet.' }),
    url ? btn('Join ' + name + ' ↗', () => api.openExternalWindow(url)) : null),
    el('div', { class: 'panel' }, el('div', { class: 'nm', text: '🌐 Platform' }), el('div', { class: 'sub', text: url ? platformOf(url) : 'Not available yet' }),
      el('div', { class: 'nm', text: '💡 Tips' }), el('div', { class: 'sub', text: 'Join about 10 minutes before the start time. Keep your microphone muted unless asked.' })));
}

/* ── Resources ── */
function viewRes(body, type) {
  const label = { circular: 'Current Circular', qbank: 'Question Bank', recent: 'Recent Question Bank' }[type];
  let list = N.resources.filter((r) => r.type === type);
  body.append(el('h2', { text: label }));
  if (type === 'qbank') {
    body.append(el('div', { class: 'chips', style: 'margin-bottom:12px' }, ['', ...QB_CATS].map((k) => el('span', { class: 'chip', style: N.cat === k ? 'background:var(--p);color:#fff' : '', text: k || 'All', onclick: () => { N.cat = k; renderNative(); } }))));
    if (N.cat) list = list.filter((r) => r.category === N.cat);
  }
  if (!list.length) return body.append(el('p', { class: 'sub', text: 'Nothing here yet.' }));
  list.forEach((r) => body.append(el('div', { class: 'item' }, el('div', { class: 'sp' }, el('div', { class: 't', text: r.title }), r.note ? el('div', { class: 'n', text: r.note }) : null,
    el('div', { class: 'n', text: [r.category, r.at ? new Date(r.at).toLocaleDateString() : ''].filter(Boolean).join(' · ') })),
    safeUrl(r.url) ? btn('Open ↗', () => api.openExternalWindow(r.url), 'btn sm') : null)));
}

/* ── Notifications ── */
async function viewInbox(body) {
  body.append(el('h2', { text: 'Notifications' }), el('p', { class: 'sub', text: 'Loading…' }));
  try {
    const docs = await api.fs.push();
    const items = docs.map((d) => d.data).filter((x) => notifForMe(S(x, 'target'), S(x, 'mobile'), S(x, 'batch'))).sort((a, b) => (Number(b.at) || 0) - (Number(a.at) || 0));
    body.querySelector('p').remove();
    if (!items.length) return body.append(el('p', { class: 'sub', text: N.mobile ? 'No notifications yet.' : 'No notifications yet. Premium members also see messages meant for them after login.' }));
    items.forEach((x) => body.append(el('div', { class: 'item' }, el('div', { class: 'sp' }, el('div', { class: 't', text: S(x, 'title') }), el('div', { class: 'n', style: 'user-select:text', text: S(x, 'body') }),
      el('div', { class: 'n', text: x.at ? new Date(Number(x.at)).toLocaleString() : '' })))));
  } catch (e) { const p = body.querySelector('p'); if (p) p.textContent = "Couldn't load notifications."; }
}

/* ── Discussion (text chat; polls every 4 s while open) ── */
function viewDisc(body) {
  if (!isPremium()) {
    return body.append(el('div', { class: 'panel' }, el('h2', { text: '💬 Discussion' }), el('p', { class: 'sub', text: 'The discussion room is for Premium members.' }), btn('Premium login', () => loginModal(renderNative))));
  }
  const me = curMember(), msgs = el('div', { class: 'msgs' }), input = el('input', { placeholder: 'Write a message…', maxlength: 2000 }), err = el('div', { class: 'err' });
  let sig = '';
  const typeLabel = { image: '🖼️', video: '🎬', voice: '🎤', file: '📎', pdf: '📄', poll: '📊 Poll (open it in the Web Section)', call: '📞 Call' };
  const draw = (docs) => {
    const list = docs.slice().reverse();
    const s = list.length + ':' + (list.length ? list[list.length - 1].id : ''); if (s === sig) return; sig = s;
    const near = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 80;
    msgs.replaceChildren(...list.map((d) => {
      const x = d.data, mine = S(x, 'mobile') === N.mobile, type = S(x, 'type') || 'text', url = safeUrl(S(x, 'url'));
      return el('div', { class: 'bub' + (mine ? ' me' : '') }, el('div', { class: 'w', text: (S(x, 'name') || 'Member') + (S(x, 'role') === 'admin' ? ' · Admin' : '') + (x.ts ? ' · ' + new Date(Number(x.ts)).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '') }),
        type === 'image' && url ? el('img', { src: url }) : null,
        type !== 'text' && type !== 'image' ? el('div', { text: (typeLabel[type] || '📎') + (S(x, 'fileName') ? ' ' + S(x, 'fileName') : '') }) : null,
        S(x, 'text') ? el('div', { text: S(x, 'text') }) : null,
        url && type !== 'image' ? btn('Open ↗', () => api.openExternalWindow(url), 'btn ghost sm') : null);
    }));
    if (near || sig === s) msgs.scrollTop = msgs.scrollHeight;
  };
  const fetchChat = async () => { try { draw(await api.fs.chat(80)); err.textContent = ''; } catch (_) { err.textContent = 'Connection problem — retrying…'; } };
  const send = async () => {
    const t = input.value.trim(); if (!t) return; input.value = '';
    try { await api.fs.post({ name: me.name || 'Member', mobile: N.mobile, text: t }); fetchChat(); }
    catch (e) { input.value = t; err.textContent = 'Could not send. Check your internet.'; }
  };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') send(); });
  body.append(el('div', { class: 'chat' }, msgs, err, el('div', { class: 'cin' }, input, btn('Send', send))));
  fetchChat(); N.timer = setInterval(() => { if (current === 'native' && N.tab === 'disc') fetchChat(); }, 4000);
}
