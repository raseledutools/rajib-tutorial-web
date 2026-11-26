/* Focus noise: calm background sounds made live with the Web Audio API (no audio files, works offline).
   One file, two users: the browser focus page (extfocus.html, served by ext-server.js) and the app's Focus tab (focus.html).
   Both pages have a strict CSP (inline scripts only), so this file is inlined into them at serve time (marker: NOISE_JS).
   Use:  FocusNoise.mount(containerElement, { theme: 'dark' | 'light', lang: 'bn' | 'en' }) */
(function (root) {
  'use strict';

  const TYPES = [
    { id: 'rain',  icon: '🌧️', en: 'Rain',         bn: 'বৃষ্টি' },
    { id: 'ocean', icon: '🌊', en: 'Ocean waves',  bn: 'সমুদ্রের ঢেউ' },
    { id: 'wind',  icon: '🍃', en: 'Wind',         bn: 'বাতাস' },
    { id: 'fire',  icon: '🔥', en: 'Fireplace',    bn: 'আগুনের শব্দ' },
    { id: 'fan',   icon: '🌀', en: 'Fan hum',      bn: 'ফ্যানের শব্দ' },
    { id: 'brown', icon: '🟤', en: 'Brown noise',  bn: 'ব্রাউন নয়েজ' },
    { id: 'pink',  icon: '🌸', en: 'Pink noise',   bn: 'পিঙ্ক নয়েজ' },
    { id: 'white', icon: '⚪', en: 'White noise',  bn: 'হোয়াইট নয়েজ' },
  ];
  const LOOP_SEC = 8, XFADE_SEC = 0.25;
  const AC = root.AudioContext || root.webkitAudioContext;

  let ctx = null, master = null, cur = null, current = null, volume = 0.5;
  const buffers = {}, listeners = [];

  try {
    const sv = JSON.parse(root.localStorage.getItem('rf_noise') || 'null');
    if (sv && typeof sv.volume === 'number') volume = Math.min(1, Math.max(0, sv.volume));
  } catch (_) { /* storage may be unavailable: defaults */ }
  function remember() { try { root.localStorage.setItem('rf_noise', JSON.stringify({ volume })); } catch (_) {} }
  function emit() { listeners.forEach((f) => { try { f(); } catch (_) {} }); }
  const level = () => volume * volume;   // volume slider is perceptual, not linear

  function getCtx() {
    if (!AC) return null;
    if (!ctx) {
      ctx = new AC();
      master = ctx.createGain(); master.gain.value = level();
      master.connect(ctx.destination);
    }
    if (ctx.state === 'suspended') { try { ctx.resume(); } catch (_) {} }
    return ctx;
  }

  /* 8 s of noise; the last 0.25 s is cross-faded into the start and playback loops from there, so there is no click */
  function noiseBuffer(kind) {
    if (buffers[kind]) return buffers[kind];
    const sr = ctx.sampleRate, len = Math.floor(sr * LOOP_SEC), buf = ctx.createBuffer(1, len, sr), d = buf.getChannelData(0);
    if (kind === 'white') {
      for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
    } else if (kind === 'pink') {
      let b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
      for (let i = 0; i < len; i++) {
        const w = Math.random() * 2 - 1;
        b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.969 * b2 + w * 0.153852;
        b3 = 0.8665 * b3 + w * 0.3104856; b4 = 0.55 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.016898;
        d[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11; b6 = w * 0.115926;
      }
    } else if (kind === 'short') {   // a tiny burst for rain drops / fire pops
      const n = Math.floor(sr * 0.1); const sb = ctx.createBuffer(1, n, sr), sd = sb.getChannelData(0);
      for (let i = 0; i < n; i++) sd[i] = Math.random() * 2 - 1;
      return (buffers[kind] = sb);
    } else {   // brown
      let last = 0;
      for (let i = 0; i < len; i++) { last = (last + 0.02 * (Math.random() * 2 - 1)) / 1.02; d[i] = last * 3.5; }
    }
    const x = Math.floor(sr * XFADE_SEC);
    for (let i = 0; i < x; i++) { const t = i / x; d[len - x + i] = d[len - x + i] * (1 - t) + d[i] * t; }
    return (buffers[kind] = buf);
  }

  /* ----- one "layer" = the whole sound of one type; it owns its nodes + timers so it can be torn down cleanly ----- */
  function makeLayer(type) {
    const L = { srcs: [], nodes: [], timers: [], out: ctx.createGain() };
    L.out.gain.value = 0;
    const keep = (n) => { L.nodes.push(n); return n; };
    const gain = (v) => { const g = keep(ctx.createGain()); g.gain.value = v; return g; };
    const filt = (t, f, q) => { const n = keep(ctx.createBiquadFilter()); n.type = t; n.frequency.value = f; if (q != null) n.Q.value = q; return n; };
    const loop = (kind) => {
      const s = ctx.createBufferSource(); s.buffer = noiseBuffer(kind); s.loop = true;
      const x = XFADE_SEC; s.loopStart = x; s.loopEnd = s.buffer.duration;
      s.start(0, x + Math.random() * (s.buffer.duration - x - 0.5));
      L.srcs.push(s); return s;
    };
    const lfo = (hz, depth, target) => {   // slow wobble: oscillator -> depth -> an AudioParam
      const o = ctx.createOscillator(); o.type = 'sine'; o.frequency.value = hz;
      const g = gain(depth); o.connect(g); g.connect(target); o.start(0); L.srcs.push(o);
    };
    const chain = (...ns) => { for (let i = 0; i < ns.length - 1; i++) ns[i].connect(ns[i + 1]); return ns[ns.length - 1]; };
    const burst = (when, freq, q, peak, dur) => {   // one short click: rain drop / ember pop
      const s = ctx.createBufferSource(); s.buffer = noiseBuffer('short');
      const f = ctx.createBiquadFilter(); f.type = 'bandpass'; f.frequency.value = freq; f.Q.value = q;
      const g = ctx.createGain();
      g.gain.setValueAtTime(0, when); g.gain.linearRampToValueAtTime(peak, when + 0.002); g.gain.exponentialRampToValueAtTime(0.0001, when + dur);
      s.connect(f); f.connect(g); g.connect(L.out); s.start(when); s.stop(when + dur + 0.03);
    };
    const scheduler = (fn) => { L.timers.push(setInterval(() => { try { fn(ctx.currentTime); } catch (_) {} }, 100)); };

    if (type === 'white') {
      chain(loop('white'), filt('lowpass', 9000), gain(0.3), L.out);
    } else if (type === 'pink') {
      chain(loop('pink'), gain(0.6), L.out);
    } else if (type === 'brown') {
      chain(loop('brown'), gain(1.2), L.out);
    } else if (type === 'rain') {
      chain(loop('pink'), filt('lowpass', 4200), gain(0.55), L.out);           // steady body
      chain(loop('white'), filt('highpass', 3500), gain(0.05), L.out);         // fine patter
      scheduler((t) => { const n = Math.floor(Math.random() * 4); for (let i = 0; i < n; i++) burst(t + Math.random() * 0.1, 1500 + Math.random() * 4000, 1.2, 0.03 + Math.random() * 0.07, 0.03 + Math.random() * 0.03); });
    } else if (type === 'ocean') {
      const lp = filt('lowpass', 800), swell = gain(0.5);
      chain(loop('brown'), lp, swell, L.out);
      lfo(0.09, 0.32, swell.gain); lfo(0.15, 0.12, swell.gain); lfo(0.07, 260, lp.frequency);   // waves roll in and out
    } else if (type === 'wind') {
      const bp = filt('bandpass', 520, 0.9), amp = gain(0.6);
      chain(loop('pink'), bp, amp, L.out);
      lfo(0.06, 260, bp.frequency); lfo(0.04, 0.25, amp.gain);                                    // gusts
    } else if (type === 'fan') {
      chain(loop('brown'), filt('lowpass', 700), gain(1.0), L.out);
      [[120, 0.03], [240, 0.012]].forEach(([hz, v]) => { const o = ctx.createOscillator(); o.type = 'sine'; o.frequency.value = hz; const g = gain(v); o.connect(g); g.connect(L.out); o.start(0); L.srcs.push(o); });
    } else if (type === 'fire') {
      chain(loop('brown'), filt('lowpass', 420), gain(0.8), L.out);                               // low rumble
      scheduler((t) => { const n = Math.floor(Math.random() * 3); for (let i = 0; i < n; i++) { const big = Math.random() < 0.08; burst(t + Math.random() * 0.1, 900 + Math.random() * 2600, 0.8, big ? 0.3 : 0.05 + Math.random() * 0.12, big ? 0.05 : 0.015 + Math.random() * 0.03); } });
    } else {
      return null;
    }
    L.out.connect(master);
    return L;
  }

  function dispose(L) {
    if (!L) return;
    L.timers.forEach(clearInterval);
    L.srcs.forEach((s) => { try { s.stop(); } catch (_) {} });
    try { L.out.disconnect(); } catch (_) {}
    L.nodes.forEach((n) => { try { n.disconnect(); } catch (_) {} });
  }
  function fadeOut(L, sec) {
    if (!L) return;
    const t = ctx.currentTime;
    try { L.out.gain.cancelScheduledValues(t); L.out.gain.setValueAtTime(L.out.gain.value, t); L.out.gain.linearRampToValueAtTime(0, t + sec); } catch (_) {}
    setTimeout(() => dispose(L), sec * 1000 + 120);
  }

  function play(type) {
    if (!TYPES.some((x) => x.id === type)) return stop();
    if (type === current) return true;
    if (!getCtx()) return false;
    const old = cur;
    let L = null;
    try { L = makeLayer(type); } catch (_) { L = null; }
    if (!L) return false;
    fadeOut(old, 0.5);
    const t = ctx.currentTime;
    L.out.gain.setValueAtTime(0, t); L.out.gain.linearRampToValueAtTime(1, t + 0.8);
    cur = L; current = type; emit();
    return true;
  }
  function stop() {
    if (cur) { fadeOut(cur, 0.5); const c = ctx; setTimeout(() => { if (!cur && c && c.state === 'running') { try { c.suspend(); } catch (_) {} } }, 900); }
    cur = null; current = null; emit();
  }
  function setVolume(v) {
    volume = Math.min(1, Math.max(0, Number(v) || 0));
    if (master) { const t = ctx.currentTime; try { master.gain.cancelScheduledValues(t); master.gain.setTargetAtTime(level(), t, 0.05); } catch (_) {} }
    remember(); emit();
  }

  /* ----- small UI ----- */
  let styled = false;
  function addStyle() {
    if (styled) return; styled = true;
    const s = document.createElement('style');
    s.textContent = [
      '.rfnz{display:flex;flex-direction:column;gap:8px;font:13.5px/1.4 "Segoe UI",system-ui,sans-serif}',
      '.rfnz .rfnz-h{font-weight:600;display:flex;gap:8px;align-items:center}',
      '.rfnz .rfnz-st{font-weight:400;opacity:.75;font-size:12.5px}',
      '.rfnz .rfnz-c{display:flex;flex-wrap:wrap;gap:6px}',
      '.rfnz .rfnz-b{cursor:pointer;border-radius:999px;padding:6px 12px;font-size:13px;line-height:1.2;transform:none;box-shadow:none;border:1px solid transparent}',
      '.rfnz .rfnz-v{display:flex;align-items:center;gap:8px}',
      '.rfnz .rfnz-v input{flex:1;max-width:220px;accent-color:#2ea597;cursor:pointer}',
      '.rfnz.dark{color:#e3e3e3}',
      '.rfnz.dark .rfnz-b{background:rgba(255,255,255,.09);color:#e3e3e3;border-color:rgba(255,255,255,.14)}',
      '.rfnz.dark .rfnz-b:hover:not(.on){background:rgba(255,255,255,.17);transform:none;box-shadow:none}',
      '.rfnz.dark .rfnz-b.on{background:#8fd3c8;color:#10201d;border-color:#8fd3c8;font-weight:600}',
      '.rfnz.light{color:#1f2937}',
      '.rfnz.light .rfnz-b{background:#fff;color:#374151;border-color:#d4d8e5}',
      '.rfnz.light .rfnz-b:hover:not(.on){background:#f1f3fb;transform:none;box-shadow:none}',
      '.rfnz.light .rfnz-b.on{background:#5b5bf0;color:#fff;border-color:#5b5bf0;font-weight:600}',
    ].join('\n');
    document.head.appendChild(s);
  }
  function mount(el, opts) {
    opts = opts || {};
    const lang = opts.lang === 'en' ? 'en' : 'bn';
    const T = lang === 'en'
      ? { title: 'Focus noise', off: 'Off', playing: 'playing', none: 'off', na: 'Audio is not available here', vol: 'Volume' }
      : { title: 'ফোকাস নয়েজ', off: 'বন্ধ', playing: 'চলছে', none: 'বন্ধ', na: 'এখানে অডিও চালানো যাচ্ছে না', vol: 'ভলিউম' };
    addStyle();
    el.textContent = '';
    const box = document.createElement('div'); box.className = 'rfnz ' + (opts.theme === 'light' ? 'light' : 'dark');
    const h = document.createElement('div'); h.className = 'rfnz-h';
    const ht = document.createElement('span'); ht.textContent = '🎧 ' + T.title;
    const st = document.createElement('span'); st.className = 'rfnz-st';
    h.append(ht, st);
    const chips = document.createElement('div'); chips.className = 'rfnz-c';
    const mk = (id, text) => { const b = document.createElement('button'); b.type = 'button'; b.className = 'rfnz-b'; b.textContent = text; b.dataset.id = id; b.onclick = () => { if (id === 'off') stop(); else if (!play(id)) st.textContent = T.na; }; chips.appendChild(b); return b; };
    mk('off', '🔇 ' + T.off);
    TYPES.forEach((x) => mk(x.id, x.icon + ' ' + x[lang]));
    const vr = document.createElement('div'); vr.className = 'rfnz-v';
    const lo = document.createElement('span'); lo.textContent = '🔈'; lo.title = T.vol;
    const rg = document.createElement('input'); rg.type = 'range'; rg.min = '0'; rg.max = '100'; rg.value = String(Math.round(volume * 100)); rg.title = T.vol;
    rg.oninput = () => setVolume(rg.value / 100);
    const hi = document.createElement('span'); hi.textContent = '🔊';
    vr.append(lo, rg, hi);
    box.append(h, chips, vr); el.appendChild(box);
    function paint() {
      chips.querySelectorAll('.rfnz-b').forEach((b) => b.classList.toggle('on', b.dataset.id === (current || 'off')));
      const t = TYPES.find((x) => x.id === current);
      st.textContent = t ? '— ' + t[lang] + ' ' + T.playing : '— ' + T.none;
    }
    listeners.push(paint); paint();
  }

  root.FocusNoise = { TYPES, play, stop, setVolume, mount, supported: !!AC, current: () => current, volume: () => volume };
})(typeof window !== 'undefined' ? window : globalThis);
