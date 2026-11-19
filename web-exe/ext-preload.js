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
