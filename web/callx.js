/* Meeting extras for the call page: full screen for shared screens (automatic), zoom, speaker / gallery view,
   chat, reactions, raise hand, participants, call timer, and screen sharing from a computer.
   Shared by the website / exe (call.html) and the APK (group_call.html; the build copies this file into its assets).
   It extends the page's own functions, so older app versions in the same call simply ignore the new signals. */
(function () {
  'use strict';
  if (INFO.solo) return;                       // SIM call relay: keep that screen minimal
  const WEB = !!window.__WEBCALL;
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; };
  const native = (fn, a) => { try { if (window.AndroidCall && AndroidCall[fn]) AndroidCall[fn](a); } catch (e) {} };

  const css = el('style');
  css.textContent = `
#bar{gap:6px;padding-left:6px;padding-right:6px}
.btn{width:min(48px,10.6vw);height:min(48px,10.6vw);flex:none;position:relative}
@media (max-height:380px) and (min-width:301px){#bar,#top{display:flex!important}#grid{padding:50px 6px 74px}#bar{padding-top:6px;padding-bottom:8px}.btn{width:40px;height:40px}}
.tile .fs{position:absolute;right:8px;bottom:8px;width:34px;height:34px;border:0;border-radius:10px;background:#000a;color:#fff;font-size:17px;z-index:2}
.tile.me .fs{display:none}
body.duo .tile.me .fs{display:none}
body.duo .tile:not(.me) .fs{bottom:auto;top:calc(env(safe-area-inset-top,0px) + 62px);left:12px;right:auto}
.tile .hand{position:absolute;left:8px;top:34px;font-size:26px;display:none;z-index:2}
.tile.speaking{outline:3px solid #34d399;outline-offset:-3px}
body.stage #grid{display:block;padding:0}
body.stage #grid .tile{display:none}
body.stage #grid .tile.staged{display:flex;position:fixed;inset:0;width:auto;height:auto;border-radius:0;z-index:3;background:#000;touch-action:none;outline:0}
body.stage #grid .tile.staged video{object-fit:contain;transform-origin:0 0;will-change:transform}
body.stage.fill #grid .tile.staged video{object-fit:cover}
body.stage #grid .tile.staged .fs{display:none}
body.stage #grid .tile.staged .nm{left:10px;right:auto;transform:none;bottom:88px;font-size:12.5px}
body.stage #grid .tile.thumb{display:flex;position:fixed;right:8px;width:92px;height:68px;border-radius:10px;z-index:4;box-shadow:0 2px 10px #000a}
body.stage #grid .tile.thumb .av{width:34px;height:34px;font-size:16px}
body.stage #grid .tile.thumb .nm{display:block;left:4px;right:auto;transform:none;bottom:3px;font-size:10px;padding:1px 6px}
body.stage #grid .tile.thumb .fs{display:none}
body.stage #wait{display:none!important}
#stagebar{position:fixed;left:10px;top:calc(env(safe-area-inset-top,0px) + 54px);z-index:6;display:none;gap:8px}
body.stage #stagebar{display:flex}
#stagebar button{border:0;border-radius:18px;background:#000b;color:#fff;font-size:13px;padding:8px 13px}
body.stage.hidec #bar,body.stage.hidec #top,body.stage.hidec #stagebar,body.stage.hidec .tile.thumb,body.stage.hidec #grid .tile.staged .nm{opacity:0;pointer-events:none}
#bar,#top,#stagebar{transition:opacity .25s}
#shareNote{position:fixed;left:50%;transform:translateX(-50%);top:calc(env(safe-area-inset-top,0px) + 54px);z-index:7;display:none;gap:10px;align-items:center;background:#15803d;padding:6px 6px 6px 14px;border-radius:20px;font-size:13px;white-space:nowrap}
#shareNote button{border:0;border-radius:14px;background:#e8365d;color:#fff;padding:6px 12px;font-size:12.5px}
.badge{position:absolute;right:-3px;top:-3px;min-width:17px;height:17px;border-radius:9px;background:#e8365d;color:#fff;font-size:10.5px;line-height:17px;font-weight:700;display:none}
.sheet{position:fixed;z-index:9;left:0;right:0;bottom:0;max-height:72%;display:none;flex-direction:column;background:#1b1e25;border-radius:18px 18px 0 0;box-shadow:0 -4px 24px #000a}
@media (min-width:720px){.sheet{left:auto;top:0;width:340px;max-height:none;border-radius:0}}
.sheet.open{display:flex}
.sheet h3{margin:0;padding:14px 16px;font-size:15px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid #ffffff18}
.sheet h3 button{border:0;background:none;color:#fff;font-size:18px;padding:0 4px}
.sheet .body{overflow-y:auto;padding:8px 12px;flex:1;min-height:60px}
.row{display:flex;align-items:center;gap:12px;width:100%;border:0;background:none;color:#fff;text-align:left;font-size:15px;padding:12px 6px}
.row span:first-child{width:26px;text-align:center}
.row .grow{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#reacts{display:flex;justify-content:space-between;padding:6px 2px 10px}
#reacts button{border:0;background:#2b2f38;border-radius:14px;font-size:24px;width:44px;height:44px;padding:0}
.cm{margin:6px 0;max-width:85%;padding:7px 11px;border-radius:12px;background:#2b2f38;font-size:14px;white-space:pre-wrap;word-break:break-word;width:fit-content}
.cm.mine{margin-left:auto;background:#1d4ed8}
.cm small{display:block;opacity:.7;font-size:11px}
#chatForm{display:flex;gap:8px;padding:10px 12px calc(env(safe-area-inset-bottom,0px) + 12px)}
#chatForm input{flex:1;min-width:0;border:0;border-radius:20px;background:#2b2f38;color:#fff;padding:10px 14px;font-size:15px;outline:0;user-select:text}
#chatForm button{border:0;border-radius:20px;background:#2563eb;color:#fff;padding:0 16px;font-size:14px}
.fly{position:fixed;z-index:8;font-size:46px;pointer-events:none;animation:fly 2.8s ease-out forwards}
@keyframes fly{0%{transform:translateY(0) scale(.6);opacity:0}15%{opacity:1;transform:translateY(-20px) scale(1.15)}100%{transform:translateY(-220px) scale(1);opacity:0}}
`;
  document.head.appendChild(css);

  /* ───── state ───── */
  const hands = {}, muted = {}, level = {};
  const known = new Set();                    // sharers already shown automatically
  let staged = '', stageWhy = '';             // why: share (automatic) | pin (user) | speaker (follows the voice)
  let speakerView = false, hideT = null, handUp = false, unread = 0, t0 = 0;
  const chat = [];

  const tileOf = (id) => $('t_' + id);
  const nameOf = (id) => (id === me ? myName + ' (You)' : (peers[id] && peers[id].name) || 'Member');
  const others = () => Object.keys(peers);

  /* ───── full screen stage ───── */
  const view = { s: 1, x: 0, y: 0 };
  function applyZoom() {
    const t = tileOf(staged); if (!t) return;
    const v = t.querySelector('video'), W = t.clientWidth, H = t.clientHeight;
    view.s = Math.max(1, Math.min(6, view.s));
    view.x = Math.min(0, Math.max(W - W * view.s, view.x));
    view.y = Math.min(0, Math.max(H - H * view.s, view.y));
    v.style.transform = view.s === 1 ? '' : `translate(${view.x}px,${view.y}px) scale(${view.s})`;
  }
  function zoomAt(cx, cy, s) {
    s = Math.max(1, Math.min(6, s));
    const k = s / view.s;
    view.x = cx - (cx - view.x) * k; view.y = cy - (cy - view.y) * k; view.s = s;
    applyZoom();
  }
  function resetZoom() { view.s = 1; view.x = view.y = 0; const t = tileOf(staged); if (t) t.querySelector('video').style.transform = ''; }

  function wake() {
    document.body.classList.remove('hidec'); clearTimeout(hideT);
    if (staged && stageWhy !== 'speaker') hideT = setTimeout(() => { if (staged && !document.querySelector('.sheet.open')) document.body.classList.add('hidec'); }, 4000);
  }
  function thumbs() {
    let n = 0;
    Array.from(grid.children).forEach((t) => {
      const show = !!staged && t.id !== 't_' + staged && n < 3;
      t.classList.toggle('thumb', show);
      t.style.top = show ? `calc(env(safe-area-inset-top,0px) + ${104 + n++ * 76}px)` : '';
    });
  }
  function enterStage(id, why) {
    if (!tileOf(id)) return;
    if (staged && staged !== id) { resetZoom(); tileOf(staged) && tileOf(staged).classList.remove('staged'); }
    const fresh = !staged;
    staged = id; stageWhy = why;
    tileOf(id).classList.add('staged'); document.body.classList.add('stage');
    thumbs(); wake();
    if (fresh && why !== 'speaker') native('fullScreen', true);   // phone: rotate freely, hide the system bars
  }
  function exitStage() {
    if (!staged) return;
    resetZoom(); const t = tileOf(staged); if (t) t.classList.remove('staged');
    staged = ''; stageWhy = ''; speakerView = false;
    document.body.classList.remove('stage', 'hidec', 'fill'); clearTimeout(hideT); thumbs();
    native('fullScreen', false);
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  }
  // a real user click may also take the whole monitor / browser screen
  function wholeScreen() { try { if (WEB && !document.fullscreenElement) document.documentElement.requestFullscreen().catch(() => {}); } catch (e) {} }
  function pin(id) { speakerView = false; enterStage(id, 'pin'); wholeScreen(); }

  /** Shows a newly shared screen full screen by itself; leaves when that share stops. */
  function reconcile() {
    others().forEach((id) => {
      if (peers[id].sharing && !known.has(id) && tileOf(id)) { known.add(id); speakerView = false; enterStage(id, 'share'); toast(nameOf(id) + ' — screen shown full screen'); }
    });
    known.forEach((id) => { if (!peers[id] || !peers[id].sharing) { known.delete(id); if (staged === id && stageWhy !== 'speaker') exitStage(); } });
    if (staged && !tileOf(staged)) { staged = ''; document.body.classList.remove('stage', 'hidec', 'fill'); thumbs(); native('fullScreen', false); }
    if (speakerView && !others().some((i) => peers[i].sharing)) {
      let best = '', top = 0.02; others().forEach((i) => { if ((level[i] || 0) > top) { top = level[i]; best = i; } });
      if (!staged && others().length) enterStage(best || others()[0], 'speaker');
      else if (best && best !== staged) enterStage(best, 'speaker');
    }
    if (staged) thumbs();
  }

  /* gestures on the full screen picture: tap = show / hide buttons, double tap = zoom, pinch / wheel = zoom, drag = move */
  const pts = new Map(); let pinch = null, moved = false, lastTap = 0;
  grid.addEventListener('pointerdown', (e) => {
    const t = e.target.closest('.tile'); if (!t || e.target.closest('.fs')) return;
    if (!staged) return;
    if (t.classList.contains('thumb')) { pin(t.id.slice(2)); return; }
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY }); moved = false;
    try { t.setPointerCapture(e.pointerId); } catch (x) {}
    if (pts.size === 2) { const [a, b] = [...pts.values()]; pinch = { d: Math.hypot(a.x - b.x, a.y - b.y) || 1, s: view.s }; }
  });
  grid.addEventListener('pointermove', (e) => {
    const p = pts.get(e.pointerId); if (!p) return;
    const dx = e.clientX - p.x, dy = e.clientY - p.y;
    if (Math.abs(dx) + Math.abs(dy) > 4) moved = true; else if (!moved) return;
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pts.size === 2 && pinch) { const [a, b] = [...pts.values()]; zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, pinch.s * Math.hypot(a.x - b.x, a.y - b.y) / pinch.d); }
    else if (pts.size === 1) { view.x += dx; view.y += dy; applyZoom(); }
  });
  const up = (e) => {
    if (!pts.delete(e.pointerId)) return;
    if (pts.size < 2) pinch = null;
    if (moved || pts.size || e.type === 'pointercancel') return;
    const now = Date.now();
    if (now - lastTap < 320) { lastTap = 0; if (view.s > 1) resetZoom(); else zoomAt(e.clientX, e.clientY, 2.5); wake(); }
    else { lastTap = now; if (document.body.classList.contains('hidec')) wake(); else { clearTimeout(hideT); document.body.classList.add('hidec'); } }
  };
  grid.addEventListener('pointerup', up); grid.addEventListener('pointercancel', up);
  grid.addEventListener('dblclick', (e) => { const t = e.target.closest('.tile'); if (!staged && t && t.id !== 't_' + me) pin(t.id.slice(2)); });
  grid.addEventListener('wheel', (e) => { if (!staged) return; e.preventDefault(); zoomAt(e.clientX, e.clientY, view.s * (e.deltaY < 0 ? 1.15 : 1 / 1.15)); wake(); }, { passive: false });
  document.addEventListener('mousemove', () => { if (staged && !pts.size) wake(); });
  window.addEventListener('resize', () => { if (staged) { resetZoom(); thumbs(); } });

  const stagebar = el('div'); stagebar.id = 'stagebar';
  const bExit = el('button', '', '✕ Exit full screen'), bFill = el('button', '', '⤢ Fit / Fill');
  bExit.onclick = exitStage;
  bFill.onclick = () => { resetZoom(); document.body.classList.toggle('fill'); wholeScreen(); wake(); };
  stagebar.append(bExit, bFill); document.body.appendChild(stagebar);

  /* ───── tiles ───── */
  function decorate(id) {
    const t = tileOf(id); if (!t) return;
    if (!t.querySelector('.fs')) {
      const f = el('button', 'fs', '⛶'); f.title = 'Full screen'; f.onclick = () => pin(id);
      t.append(f, el('div', 'hand', '✋'));
    }
    t.querySelector('.hand').style.display = hands[id] ? 'block' : 'none';
    const st = $('st_' + id); if (st && id !== me) st.textContent = muted[id] ? '🔇' : '';
  }
  const baseMakeTile = window.makeTile;
  window.makeTile = function (id, name, isLocal) { const t = baseMakeTile(id, name, isLocal); decorate(id); return t; };
  const baseDrop = window.dropPeer;
  window.dropPeer = function (id, now) { if (staged === id) exitStage(); delete hands[id]; delete muted[id]; delete level[id]; known.delete(id); baseDrop(id, now); refreshPeople(); };

  /* The one who answered an offer must send on the offered video line; otherwise their screen share never leaves. */
  const baseNewPeer = window.newPeer;
  window.newPeer = function (id, name) {
    const existed = !!peers[id], p = baseNewPeer(id, name);
    if (!existed) {
      const set = p.pc.setRemoteDescription.bind(p.pc);
      p.pc.setRemoteDescription = async function (d) {
        await set(d);
        if (!d || d.type !== 'offer') return;
        const tv = p.pc.getTransceivers().find((x) => x.mid !== null && x.receiver.track.kind === 'video');
        if (!tv) return;
        tv.direction = 'sendrecv';
        if (tv.sender !== p.vs) {
          p.vs = tv.sender;
          const tr = sharing ? shareTrack : (local && local.getVideoTracks()[0]) || null;
          if (tr) await p.vs.replaceTrack(tr);
        }
      };
    }
    return p;
  };

  /* ───── signals ───── */
  const parse = (s) => { try { return JSON.parse(s || '{}') || {}; } catch (e) { return {}; } };
  const tell = (to) => { if (!micOn) send(to, 'mic', JSON.stringify({ on: false })); if (handUp) send(to, 'hand', JSON.stringify({ on: true })); };
  const baseSignal = window.onSignal;
  window.onSignal = async function (arr) {
    for (const s of arr) {
      const id = s.from, d = parse(s.data);
      if (s.kind === 'mic') muted[id] = d.on === false;
      else if (s.kind === 'hand') { hands[id] = !!d.on; if (d.on) toast('✋ ' + (s.name || 'Member') + ' raised a hand'); }
      else if (s.kind === 'react') fly(String(d.e || '').slice(0, 4), id);
      else if (s.kind === 'chat') addChat(s.name || 'Member', String(d.t || '').slice(0, 1000), false);
      else { await baseSignal([s]); if (s.kind === 'join') tell(id); }
      decorate(id);
    }
    reconcile(); refreshPeople();
  };
  const baseMic = window.toggleMic;
  window.toggleMic = function () { baseMic(); send('', 'mic', JSON.stringify({ on: micOn })); refreshPeople(); };

  /* ───── screen share from a computer (phones use the app's own capture) ───── */
  if (WEB) {
    const can = !!(navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) && !/Android|iPhone|iPad/i.test(navigator.userAgent);
    $('bShare').style.setProperty('display', can ? 'flex' : 'none', 'important');
    const note = el('div'); note.id = 'shareNote';
    const stop = el('button', '', 'Stop sharing'); note.append(el('span', '', '🖥 You are sharing your screen'), stop); document.body.appendChild(note);
    const baseEnd = window.endShare;
    window.endShare = function (x) {
      if (!sharing) return;
      const tr = shareTrack; baseEnd(x); shareTrack = null; note.style.display = 'none';
      if (tr) { tr.onended = null; tr.stop(); }
    };
    stop.onclick = () => window.endShare();
    let asking = false;
    window.toggleShare = async function () {
      if (sharing) { window.endShare(); return; }
      if (asking) return; asking = true;
      try {
        const ms = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: { ideal: 15, max: 30 }, width: { max: 1920 }, height: { max: 1080 } }, audio: false });
        const tr = ms.getVideoTracks()[0];
        if (left) { tr.stop(); return; }
        try { tr.contentHint = 'detail'; } catch (e) {}
        shareTrack = tr; sharing = true; tr.onended = () => window.endShare();
        for (const p of Object.values(peers)) if (p.vs) await p.vs.replaceTrack(tr).catch(() => {});
        send('', 'share', JSON.stringify({ on: true }));
        note.style.display = 'flex'; refreshBtns();
      } catch (e) { toast('Screen sharing was cancelled or is not available (update the Windows app)'); }
      finally { asking = false; }
    };
  }

  /* ───── panels: more, participants, chat ───── */
  function sheet(id, title) {
    const s = el('div', 'sheet'); s.id = id;
    const h = el('h3'), x = el('button', '', '✕'); h.append(el('span', '', title), x);
    x.onclick = () => s.classList.remove('open');
    const b = el('div', 'body'); s.append(h, b); document.body.appendChild(s);
    return { box: s, body: b, title: h.firstChild };
  }
  const more = sheet('moreSheet', 'More'), people = sheet('peopleSheet', 'Participants'), talk = sheet('chatSheet', 'Chat');
  function open(s) { document.querySelectorAll('.sheet').forEach((x) => x.classList.remove('open')); s.box.classList.add('open'); wake(); }
  function row(icon, label, fn) {
    const r = el('button', 'row'), a = el('span', '', icon), b = el('span', 'grow', label);
    r.append(a, b); if (fn) r.onclick = fn; return r;
  }
  function fly(e, id) {
    if (!e) return;
    const t = tileOf(staged && id !== me ? staged : id), r = t && t.offsetParent !== null ? t.getBoundingClientRect() : null;
    const f = el('div', 'fly', e);
    f.style.left = (r && r.width ? r.left + r.width / 2 - 24 : innerWidth / 2 - 24) + 'px';
    f.style.top = Math.min(innerHeight - 160, r && r.height ? r.top + r.height / 2 : innerHeight / 2) + 'px';
    document.body.appendChild(f); setTimeout(() => f.remove(), 2900);
  }
  function fillMore() {
    more.body.textContent = '';
    const rx = el('div'); rx.id = 'reacts';
    ['👍', '👏', '❤️', '😂', '😮', '🎉'].forEach((e) => {
      const b = el('button', '', e); b.onclick = () => { send('', 'react', JSON.stringify({ e })); fly(e, me); more.box.classList.remove('open'); }; rx.appendChild(b);
    });
    more.body.append(rx,
      row('✋', handUp ? 'Lower hand' : 'Raise hand', () => { handUp = !handUp; hands[me] = handUp; decorate(me); send('', 'hand', JSON.stringify({ on: handUp })); more.box.classList.remove('open'); }),
      row('👥', 'Participants (' + (others().length + 1) + ')', () => { open(people); refreshPeople(); }),
      row(speakerView ? '▦' : '👤', speakerView ? 'Gallery view' : 'Speaker view', () => {
        more.box.classList.remove('open');
        if (speakerView) { exitStage(); return; }
        if (!others().length) { toast('No one else is in the call yet'); return; }
        exitStage(); speakerView = true; reconcile();
      }),
      row('⛶', staged ? 'Exit full screen' : 'Full screen', () => {
        more.box.classList.remove('open');
        if (staged) { exitStage(); return; }
        const id = others().find((i) => peers[i].sharing) || others()[0];
        if (id) pin(id); else toast('No one else is in the call yet');
      }));
  }
  function refreshPeople() {
    if (!people.box.classList.contains('open')) return;
    people.body.textContent = '';
    [me, ...others()].forEach((id) => {
      const mute = id === me ? !micOn : muted[id], share = id === me ? sharing : peers[id] && peers[id].sharing;
      const r = row(hands[id] ? '✋' : '👤', nameOf(id), id === me ? null : () => { people.box.classList.remove('open'); pin(id); });
      r.appendChild(el('span', '', (share ? '🖥 ' : '') + (mute ? '🔇' : '🎤')));
      people.body.appendChild(r);
    });
  }

  const form = el('form'); form.id = 'chatForm';
  const input = el('input'); input.placeholder = 'Message everyone'; input.maxLength = 1000; input.autocomplete = 'off';
  form.append(input, el('button', '', 'Send')); talk.box.appendChild(form);
  talk.body.appendChild(el('div', 'cm', 'Messages are seen by people who are in the call now.'));
  form.onsubmit = (e) => {
    e.preventDefault(); const t = input.value.trim(); if (!t) return;
    input.value = ''; send('', 'chat', JSON.stringify({ t })); addChat('You', t, true);
  };
  function addChat(name, text, mine) {
    if (!text) return;
    chat.push({ name, text });
    const m = el('div', 'cm' + (mine ? ' mine' : '')); m.append(el('small', '', name), document.createTextNode(text));
    talk.body.appendChild(m); talk.body.scrollTop = talk.body.scrollHeight;
    if (!mine && !talk.box.classList.contains('open')) { unread++; badge(); toast('💬 ' + name + ': ' + text.slice(0, 80)); }
  }

  const bChat = el('button', 'btn', '💬'), bMore = el('button', 'btn', '⋯'), dot = el('span', 'badge');
  bChat.id = 'bChat'; bMore.id = 'bMore'; bChat.title = 'Chat'; bMore.title = 'More'; bChat.appendChild(dot);
  const end = document.querySelector('#bar .end');
  $('bar').insertBefore(bChat, end); $('bar').insertBefore(bMore, end);
  function badge() { dot.textContent = unread > 9 ? '9+' : unread; dot.style.display = unread ? 'block' : 'none'; }
  bChat.onclick = () => { open(talk); unread = 0; badge(); talk.body.scrollTop = talk.body.scrollHeight; };
  bMore.onclick = () => { fillMore(); open(more); };

  document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT') return;
    if (e.key === 'Escape') { const o = document.querySelector('.sheet.open'); if (o) o.classList.remove('open'); else exitStage(); }
    else if (e.key.toLowerCase() === 'f' && !e.ctrlKey && !e.metaKey) { if (staged) exitStage(); else { const id = others().find((i) => peers[i].sharing) || others()[0]; if (id) pin(id); } }
  });

  /* ───── who is speaking + call timer ───── */
  const ttl = $('ttl') || document.querySelector('#top b'), title = ttl.textContent;
  setInterval(async () => {
    if (left) return;
    if (!t0 && Object.values(peers).some((p) => p.pc.connectionState === 'connected')) t0 = Date.now();
    if (t0) { const s = Math.floor((Date.now() - t0) / 1000); ttl.textContent = title + ' · ' + (s >= 3600 ? Math.floor(s / 3600) + ':' : '') + String(Math.floor(s / 60) % 60).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0'); }
    for (const id of others()) {
      let v = 0;
      try { (await peers[id].pc.getStats()).forEach((r) => { if (r.type === 'inbound-rtp' && r.kind === 'audio' && r.audioLevel) v = Math.max(v, r.audioLevel); }); } catch (e) {}
      level[id] = v;
      const t = tileOf(id); if (t) t.classList.toggle('speaking', v > 0.02 && !muted[id]);
    }
    reconcile();
  }, 1000);
})();
