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

  // ── injected Save / Saved buttons (live inside the player, not fixed on the screen) ──
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
    b.textContent = t;
    b.style.cssText = 'border:0;border-radius:18px;padding:8px 12px;margin-left:6px;color:#fff;' +
      'background:rgba(0,0,0,.65);font:600 13px sans-serif;opacity:.9';
    ['touchstart', 'touchend', 'mousedown', 'mouseup'].forEach(function(n){
      b.addEventListener(n, function(e){ e.stopPropagation(); }, true);
    });
    return b;
  }
  var box = document.createElement('div');
  box.id = 'ytp-box';
  box.style.cssText = 'position:absolute;top:8px;right:8px;z-index:2147483647;display:none;white-space:nowrap';
  var sb = mk('\u2B07 Save'), lb = mk('\uD83D\uDCC1');
  sb.addEventListener('click', function(e){
    e.preventDefault(); e.stopPropagation();
    var id = vid(); if (id && B) B.save(id, document.title);
  }, true);
  lb.addEventListener('click', function(e){
    e.preventDefault(); e.stopPropagation();
    if (B) B.openSaved();
  }, true);
  box.appendChild(sb); box.appendChild(lb);

  function tick() {
    try {
      var id = vid();
      if (!id) { box.style.display = 'none'; return; }
      var host = document.querySelector('.html5-video-player') || document.querySelector('#player-container-id') ||
                 document.querySelector('#player');
      if (host) {
        if (box.parentNode !== host) host.appendChild(box);
        box.style.position = 'absolute';
      } else {
        if (box.parentNode !== document.body) document.body.appendChild(box);
        box.style.position = 'fixed';
      }
      box.style.display = 'block';
      var s = B ? B.state(id) : 'idle';
      sb.textContent = s === 'saved' ? '\u2713 Saved' : (s.indexOf('p:') === 0 ? '\u23F3 ' + s.slice(2) + '% (cancel)' : '\u2B07 Save');
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
  setInterval(tick, 700);
  tick();
})();
