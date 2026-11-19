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
