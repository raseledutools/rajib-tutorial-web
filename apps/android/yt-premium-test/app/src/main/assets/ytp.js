(function(){
  if (window.__ytp2) return;
  window.__ytp2 = 1;
  var B = window.YTP;   // native bridge (Save / Saved / state)

  // ── background playback: the page must never believe it is hidden ──
  try {
    var d = function(o, k, v){ Object.defineProperty(o, k, { get: function(){ return v; }, configurable: true }); };
    d(document, 'hidden', false); d(document, 'visibilityState', 'visible');
    d(document, 'webkitHidden', false); d(document, 'webkitVisibilityState', 'visible');
  } catch (e) {}
  ['visibilitychange', 'webkitvisibilitychange'].forEach(function(n){
    document.addEventListener(n, function(e){ e.stopImmediatePropagation(); }, true);
  });

  // ── ad slots ──
  try {
    var css = document.createElement('style');
    css.textContent = '.video-ads,#player-ads,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,' +
      'ytm-companion-slot,ytm-ad-slot-renderer,ytd-ad-slot-renderer,ytm-brand-video-singleton-renderer,' +
      'ytm-statement-banner-renderer,ytm-mealbar-promo-renderer,.ytp-ad-overlay-container,.ytp-ad-text-overlay' +
      '{display:none!important}';
    (document.head || document.documentElement).appendChild(css);
  } catch (e) {}

  // ── injected Save / Saved buttons: sit right next to the Subscribe button (outside the player, so taps always work) ──
  function vid() {
    try {
      var u = new URL(location.href), p = u.pathname;
      if (u.hostname.indexOf('youtu.be') >= 0) return p.slice(1) || null;
      if (p.indexOf('/shorts/') === 0) return p.split('/')[2] || null;
      if (p === '/watch') return u.searchParams.get('v');
    } catch (e) {}
    return null;
  }
  function mk(t) {
    var b = document.createElement('button');
    b.type = 'button';
    b.textContent = t;
    b.style.cssText = 'border:1px solid rgba(255,255,255,.25);border-radius:18px;padding:0 14px;height:36px;margin:0 0 0 6px;' +
      'color:#fff;background:#272727;font:600 13px sans-serif;white-space:nowrap;touch-action:manipulation;cursor:pointer';
    return b;
  }
  var box = document.createElement('div');
  box.id = 'ytp-box';
  var sb = mk('\u2B07 Save'), lb = mk('\uD83D\uDCC1');
  function hit(btn, fn) {
    var done = 0;
    function go(e) {
      e.preventDefault(); e.stopPropagation();
      var now = Date.now(); if (now - done < 400) return; done = now;   // touchend + click must not fire twice
      try { fn(); } catch (x) {}
    }
    btn.addEventListener('click', go, true);
    btn.addEventListener('touchend', go, true);
  }
  hit(sb, function(){ var id = vid(); if (id && B) B.save(id, document.title); });
  hit(lb, function(){ if (B) B.openSaved(); });
  box.appendChild(sb); box.appendChild(lb);

  function visible(el) { return el && (el.offsetWidth > 0 || el.offsetHeight > 0 || el.getClientRects().length > 0); }
  function findSub() {
    var sels = ['ytm-slim-owner-renderer ytm-subscribe-button-renderer', 'ytm-slim-owner-renderer yt-subscribe-button-view-model',
      'ytm-slim-owner-renderer subscribe-button-view-model', 'ytm-slim-owner-renderer .slim-owner-subscribe-button',
      'ytm-subscribe-button-renderer', 'yt-subscribe-button-view-model', 'subscribe-button-view-model', '#subscribe-button'];
    for (var i = 0; i < sels.length; i++) {
      var el = document.querySelector(sels[i]);
      if (el && visible(el) && !box.contains(el)) return el;
    }
    var bs = document.querySelectorAll('button'), n = Math.min(bs.length, 80);
    for (var j = 0; j < n; j++) {
      var b = bs[j];
      if (box.contains(b) || !visible(b)) continue;
      var t = (b.getAttribute('aria-label') || b.textContent || '').trim();
      if (/^(subscribe|subscribed|\u09B8\u09BE\u09AC\u09B8\u09CD\u0995\u09CD\u09B0\u09BE\u0987\u09AC)/i.test(t)) {
        return b.closest('yt-subscribe-button-view-model,subscribe-button-view-model,ytm-subscribe-button-renderer') || b;
      }
    }
    return null;
  }
  function setMode(m, css) { if (box.getAttribute('data-m') !== m) { box.setAttribute('data-m', m); box.style.cssText = css; } }

  function tick() {
    try {
      var id = vid();
      if (!id) { box.style.display = 'none'; return; }
      var sub = findSub();
      if (sub && sub.parentNode) {
        if (box.parentNode !== sub.parentNode || sub.nextSibling !== box) sub.parentNode.insertBefore(box, sub.nextSibling);
        setMode('sub', 'display:inline-flex;position:static;margin-left:8px;flex:none;align-items:center;white-space:nowrap;vertical-align:middle');
      } else {
        // no Subscribe button on this page (Shorts, odd layouts): a fixed pill on the page itself, never inside the player
        if (box.parentNode !== document.body) document.body.appendChild(box);
        setMode('fix', 'display:flex;position:fixed;top:64px;right:8px;z-index:2147483647;white-space:nowrap');
      }
      var s = B ? B.state(id) : 'idle';
      sb.textContent = s === 'saved' ? '\u2713 Saved' : (s.indexOf('p:') === 0 ? '\u23F3 ' + s.slice(2) + '% (cancel)' : '\u2B07 Save');
    } catch (e) {}
  }

  // ── background / lock-screen audio + floating (PiP) support ──
  try { document.hasFocus = function(){ return true; }; } catch (e) {}
  function rep() {
    try {
      var v = document.querySelector('video');
      if (B && B.info) B.info(vid() || '', document.title || '', v ? (v.currentTime || 0) : 0, !!(v && !v.paused && !v.ended));
    } catch (e) {}
  }
  // the app tells us (window.__ytpBg) when the screen is off / the app is not visible: then a pause we did not ask for is undone
  document.addEventListener('pause', function(e){
    var v = e.target;
    if (!(v instanceof HTMLVideoElement)) return;
    if (window.__ytpBg && !window.__ytpUserPause && !v.ended) {
      setTimeout(function(){
        if (window.__ytpBg && !window.__ytpUserPause && v.paused && !v.ended) {
          var p = v.play(); if (p && p.catch) p.catch(function(){});
        }
      }, 120);
    }
    rep();
  }, true);
  ['play', 'playing', 'ended', 'emptied'].forEach(function(n){
    document.addEventListener(n, function(e){
      if (e.target instanceof HTMLVideoElement) { if (n === 'play' || n === 'playing') window.__ytpUserPause = false; rep(); }
    }, true);
  });
  // notification Play/Pause button
  window.__ytpToggle = function(){
    var v = document.querySelector('video'); if (!v) return;
    if (v.paused) { window.__ytpUserPause = false; var p = v.play(); if (p && p.catch) p.catch(function(){}); }
    else { window.__ytpUserPause = true; v.pause(); }
  };
  // ── swipe down on the player = minimize (same as the Back button) ──
  var sw = null;
  document.addEventListener('touchstart', function(e){
    sw = null;
    if (location.pathname !== '/watch' || e.touches.length !== 1) return;
    var p = document.querySelector('.html5-video-player') || document.querySelector('#player-container-id') || document.querySelector('#player');
    if (!p) return;
    var t = e.touches[0], r = p.getBoundingClientRect();
    if (t.clientX >= r.left && t.clientX <= r.right && t.clientY >= r.top && t.clientY <= r.bottom) sw = { x: t.clientX, y: t.clientY };
  }, { passive: true, capture: true });
  document.addEventListener('touchmove', function(e){
    if (!sw || !e.touches.length) return;
    var t = e.touches[0], dy = t.clientY - sw.y, dx = t.clientX - sw.x;
    if (dy > 90 && Math.abs(dx) < dy * 0.6) { sw = null; if (B && B.minimize) B.minimize(); }
  }, { passive: true, capture: true });
  document.addEventListener('touchend', function(){ sw = null; }, { passive: true, capture: true });

  // ── "Open app" / "Get the app" buttons and banners ──
  try {
    var css2 = document.createElement('style');
    css2.textContent = 'ytm-open-app-button-renderer,ytm-app-promo-renderer,ytm-promo-banner-renderer,ytm-upsell-dialog-renderer,' +
      'ytm-mealbar-promo-renderer,ytm-open-in-app-promo-renderer,.open-app-button,.mobile-topbar-header-content .open-app-link' +
      '{display:none!important}';
    (document.head || document.documentElement).appendChild(css2);
  } catch (e) {}
  var OPEN = /^(open app|open in app|open the app|open youtube app|get the app|install app|use the app|get app|\u0985\u09CD\u09AF\u09BE\u09AA \u0996\u09C1\u09B2\u09C1\u09A8)$/i;
  function hideOpenApp() {
    try {
      var els = document.querySelectorAll('a,button,ytm-button-renderer'), n = Math.min(els.length, 150);
      for (var i = 0; i < n; i++) {
        var el = els[i];
        if (el.getAttribute('data-ytp-h')) continue;
        var t = ((el.getAttribute('aria-label') || '') + '').trim() || ((el.textContent || '') + '').trim();
        var href = (el.getAttribute('href') || '');
        if (OPEN.test(t) || href.indexOf('intent://') === 0 || href.indexOf('/app') === 0 && /app/i.test(t)) {
          var tgt = el.closest('ytm-button-renderer,ytm-open-app-button-renderer') || el;
          tgt.style.setProperty('display', 'none', 'important');
          el.setAttribute('data-ytp-h', '1');
        }
      }
    } catch (e) {}
  }

  // ── video ads: skip button, otherwise mute + jump to the end of the ad ──
  var muted = false;
  function ads() {
    try {
      var v = document.querySelector('video'), p = document.querySelector('.html5-video-player');
      var sk = document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button,.ytp-ad-skip-button-modern,.ytp-ad-overlay-close-button');
      if (sk) sk.click();
      var ad = (p && p.classList.contains('ad-showing')) || document.querySelector('.ad-showing');
      if (ad && v) {
        muted = true; v.muted = true; v.playbackRate = 16;
        if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
      } else if (muted && v) {
        muted = false; v.muted = false; v.playbackRate = 1;
      }
    } catch (e) {}
  }
  setInterval(function(){ ads(); }, 250);
  setInterval(function(){ tick(); rep(); hideOpenApp(); }, 700);
  tick();
})();
