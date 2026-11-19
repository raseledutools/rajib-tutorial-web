'use strict';
/* Runs inside the embedded YouTube / Studio / Google pages (the <webview> in index.html).
   Google refuses sign-in from "embedded browsers", so make this page look like a normal Chrome:
   same client-hints brands as the user-agent and the window.chrome object a real Chrome has. */
const { webFrame } = require('electron');
try {
  const full = process.versions.chrome || '130.0.0.0';
  const major = full.split('.')[0];
  webFrame.executeJavaScript('(' + function (FULL, MAJOR) {
    var brands = [
      { brand: 'Chromium', version: MAJOR },
      { brand: 'Google Chrome', version: MAJOR },
      { brand: 'Not_A Brand', version: '24' },
    ];
    var list = [
      { brand: 'Chromium', version: FULL },
      { brand: 'Google Chrome', version: FULL },
      { brand: 'Not_A Brand', version: '24.0.0.0' },
    ];
    var uad = {
      brands: brands, mobile: false, platform: 'Windows',
      getHighEntropyValues: function () {
        return Promise.resolve({ brands: brands, mobile: false, platform: 'Windows', platformVersion: '10.0.0',
          architecture: 'x86', bitness: '64', model: '', uaFullVersion: FULL, fullVersionList: list, wow64: false });
      },
      toJSON: function () { return { brands: brands, mobile: false, platform: 'Windows' }; },
    };
    try { Object.defineProperty(Navigator.prototype, 'userAgentData', { get: function () { return uad; }, configurable: true }); } catch (e) {}
    try {
      if (!window.chrome) window.chrome = {};
      if (!window.chrome.runtime) window.chrome.runtime = {};
      if (!window.chrome.app) window.chrome.app = { isInstalled: false };
      if (!window.chrome.loadTimes) window.chrome.loadTimes = function () { return {}; };
      if (!window.chrome.csi) window.chrome.csi = function () { return {}; };
    } catch (e) {}
  }.toString() + ')(' + JSON.stringify(full) + ',' + JSON.stringify(major) + ')');
} catch (_) {}

/* ---- YouTube: no ads inside the embedded view (www / m / music.youtube.com; not Studio) ----
   1) main.js already cancels the ad network requests;
   2) here: strip ad data from the player response before the player sees it, hide ad elements,
      and, if an ad still starts (server-side inserted), mute it, fast-forward it and press "Skip". */
try {
  if (/(^|\.)youtube\.com$/.test(location.hostname) && location.hostname !== 'studio.youtube.com') {
    webFrame.executeJavaScript('(' + function () {
      var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
      function strip(o) {
        try {
          if (!o || typeof o !== 'object') return o;
          for (var i = 0; i < KEYS.length; i++) if (KEYS[i] in o) { try { delete o[KEYS[i]]; } catch (e) {} }
          if (o.playerResponse) strip(o.playerResponse);
          if (o.response && o.response.playerResponse) strip(o.response.playerResponse);
        } catch (e) {}
        return o;
      }
      function hasAds(r) { return r && typeof r === 'object' && (r.adPlacements || r.playerAds || r.adSlots || r.playerResponse || r.response); }
      // JSON.parse + fetch().json() — covers /youtubei/v1/player and the page's inline data
      var _parse = JSON.parse;
      JSON.parse = function () { var r = _parse.apply(this, arguments); if (hasAds(r)) strip(r); return r; };
      if (window.Response && Response.prototype.json) {
        var _json = Response.prototype.json;
        Response.prototype.json = function () { return _json.apply(this, arguments).then(function (r) { if (hasAds(r)) strip(r); return r; }); };
      }
      // first page load: window.ytInitialPlayerResponse
      try {
        var _ipr;
        Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true,
          get: function () { return _ipr; }, set: function (v) { _ipr = strip(v); } });
      } catch (e) {}
      // cosmetic: hide ad boxes
      var css = '.ad-showing .ytp-ad-module,.ytp-ad-overlay-container,.ytp-ad-image-overlay,#player-ads,#masthead-ad,' +
        'ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,' +
        'ytd-promoted-video-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytd-companion-slot-renderer,' +
        'ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,.ytd-merch-shelf-renderer{display:none!important}';
      function addCss() {
        try {
          if (document.getElementById('__rt_noads')) return;
          var root = document.head || document.documentElement;
          if (!root) return;                       // too early (document start): retried below
          var st = document.createElement('style'); st.id = '__rt_noads'; st.textContent = css;
          root.appendChild(st);
        } catch (e) {}
      }
      document.addEventListener('DOMContentLoaded', addCss);
      addCss();
      // safety net for ads that still start: mute + jump to the end + press Skip
      setInterval(function () {
        addCss();
        try {
          var pl = document.querySelector('.html5-video-player');
          if (!pl || !pl.classList.contains('ad-showing')) return;
          var v = pl.querySelector('video');
          if (v) { v.muted = true; if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration; v.playbackRate = 16; }
          var b = document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-slot button');
          if (b) b.click();
        } catch (e) {}
      }, 250);
    }.toString() + ')()');
  }
} catch (_) {}

/* ---- Every site: hide the common ad boxes (Google ads, ad slots, Google Search sponsored results) ---- */
try {
  const h = location.hostname;
  if (h !== 'accounts.google.com' && h !== 'studio.youtube.com') {
    const CSS = [
      'ins.adsbygoogle', '.adsbygoogle', '.google-auto-placed', '[id^="google_ads_iframe"]', '[id^="google_ads_"]', '[id^="div-gpt-ad"]',
      'iframe[id^="google_ads"]', 'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication.com"]', 'iframe[src*="adnxs.com"]',
      '[data-ad-slot]', '[data-ad-client]', '[data-google-query-id]', '[aria-label="Advertisement" i]', '[aria-label="Ads" i]',
      '.advertisement', '.ad-slot', '.adslot', '.ad-unit', '.ad-banner', '.adbox', '#ad-banner', '.sponsored-ad',
      '[id^="taboola-"]', '.trc_related_container', '[id^="outbrain"]', '.OUTBRAIN', '.ob-widget',
      /* Google Search: sponsored results */
      '#tads', '#tadsb', '#bottomads', '.commercial-unit-desktop-top', '.commercial-unit-desktop-rhs', '.pla-unit-container', '.cu-container', '[data-text-ad]',
    ].join(',') + '{display:none!important}';
    const put = () => {
      if (document.getElementById('__rt_adcss')) return true;
      const root = document.head || document.documentElement;
      if (!root) return false;
      const st = document.createElement('style'); st.id = '__rt_adcss'; st.textContent = CSS; root.appendChild(st);
      return true;
    };
    if (!put()) { const mo = new MutationObserver(() => { if (put()) mo.disconnect(); }); mo.observe(document, { childList: true, subtree: true }); }
    document.addEventListener('DOMContentLoaded', put);
  }
} catch (_) {}

/* ---- Lesson player (exe): the course video is a <webview> page opened with "#rtlesson" ----
   index.html shows ONLY the picture of the video and drives it with its own controls, without the YouTube IFrame API.
   Here: hide everything YouTube draws on top (title, channel name, logo, share / watch-later, end-screen, suggestions),
   make the picture fill the whole view, and tell the app if the page shows a player error so it can try another way. */
try {
  const isLesson = location.hostname === 'www.youtube.com' && /(^|[#&])rtlesson/.test(location.hash);
  const isEmbed = isLesson && location.pathname.indexOf('/embed/') === 0;
  const isWatch = isLesson && location.pathname === '/watch';
  if (isEmbed || isWatch) {
    const HIDE = [
      '.ytp-chrome-top', '.ytp-chrome-bottom', '.ytp-gradient-top', '.ytp-gradient-bottom', '.ytp-title', '.ytp-title-channel',
      '.ytp-title-beacon', '.ytp-watermark', '.ytp-youtube-button', '.ytp-share-button', '.ytp-watch-later-button', '.ytp-copylink-button',
      '.ytp-pause-overlay', '.ytp-pause-overlay-container', '.ytp-endscreen-content', '.html5-endscreen', '.ytp-ce-element',
      '.ytp-ce-covering-overlay', '.ytp-cards-teaser', '.ytp-cards-button', '.ytp-large-play-button', '.ytp-spinner', '.ytp-popup',
      '.ytp-contextmenu', '.ytp-iv-video-content', '.annotation', '.ytp-suggested-action', '.ytp-paid-content-overlay',
      '.ytp-videowall-still', '.ytp-impression-link', '.ytp-player-content', '.ytp-tooltip', '.ytp-bezel', '.ytp-bezel-text-wrapper',
    ];
    let css = 'html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important;cursor:default!important}' +
      '.html5-video-player{background:#000!important;cursor:default!important}';
    if (isWatch) {
      HIDE.push('ytd-masthead', '#masthead-container', '#secondary', '#below', '#related', '#comments', '#chat', 'ytd-watch-metadata',
        'ytd-miniplayer', 'tp-yt-app-drawer', 'ytd-mini-guide-renderer', '#guide', '#guide-wrapper', 'ytd-popup-container',
        'ytd-consent-bump-v2-lightbox', 'tp-yt-iron-overlay-backdrop');
      css += 'ytd-app,#page-manager{background:#000!important;margin:0!important}' +
        'ytd-watch-flexy #primary,ytd-watch-flexy #primary-inner,ytd-watch-flexy #columns{margin:0!important;padding:0!important;max-width:none!important}' +
        '#movie_player,#player-container-outer,#player-container-inner,#player-container,#player,#ytd-player,.html5-video-container{' +
        'position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;' +
        'max-height:none!important;margin:0!important;z-index:2147483000!important;background:#000!important}' +
        '.html5-main-video{width:100%!important;height:100%!important;left:0!important;top:0!important;object-fit:contain!important}';
    }
    css += HIDE.join(',') + '{display:none!important}';
    const put = () => {
      if (document.getElementById('__rt_lesson')) return true;
      const root = document.head || document.documentElement;
      if (!root) return false;
      const st = document.createElement('style'); st.id = '__rt_lesson'; st.textContent = css; root.appendChild(st);
      return true;
    };
    if (!put()) { const mo = new MutationObserver(() => { if (put()) mo.disconnect(); }); mo.observe(document, { childList: true, subtree: true }); }
    document.addEventListener('DOMContentLoaded', put);
    // no keyboard / right-click inside the lesson picture: the app's own controls are the only controls
    window.addEventListener('keydown', (e) => { e.stopImmediatePropagation(); e.preventDefault(); }, true);
    window.addEventListener('contextmenu', (e) => e.preventDefault(), true);
    // player error (video not allowed to play here, removed, ...): tell the app once so it can switch to the next method
    let reported = false;
    setInterval(() => {
      if (reported) return;
      try {
        const e = document.querySelector('.ytp-error,yt-player-error-message-renderer');
        if (e && e.getClientRects().length) { reported = true; console.log('__rt_lesson_error__'); }
      } catch (_) {}
    }, 600);
  }
} catch (_) {}
