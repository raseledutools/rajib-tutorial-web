'use strict';
/* Safe Browser helpers:
   1) adult-site blocking + forced SafeSearch (Settings → Adult Block)
   2) login cookies survive closing the app (session cookies are turned into 30-day cookies)
   3) cache cleaning when the app closes / goes to the tray (cookies, localStorage, IndexedDB are NOT touched => no sign-out) */

const ADULT_DOMAINS = [
  'pornhub.com', 'xvideos.com', 'xnxx.com', 'xhamster.com', 'redtube.com', 'youporn.com', 'spankbang.com', 'eporner.com',
  'tnaflix.com', 'beeg.com', 'tube8.com', 'motherless.com', 'xtube.com', 'brazzers.com', 'bangbros.com', 'naughtyamerica.com',
  'realitykings.com', 'chaturbate.com', 'stripchat.com', 'livejasmin.com', 'bongacams.com', 'cam4.com', 'myfreecams.com',
  'onlyfans.com', 'fansly.com', 'e-hentai.org', 'nhentai.net', 'hentaihaven.xxx', 'hqporner.com', 'porntrex.com', 'txxx.com',
  'hclips.com', 'vporn.com', '4tube.com', 'sunporno.com', 'tubegalore.com', 'fuq.com', 'thothub.to', 'camsoda.com',
  'erome.com', 'rule34.xxx', 'gelbooru.com', 'literotica.com', 'xvideos2.com', 'banglachoti.com', 'sex.com', 'adultfriendfinder.com',
  'ashleymadison.com', 'playboy.com', 'penthouse.com', 'hustler.com', 'javhd.com', 'missav.com', 'jable.tv', 'xfreehd.com',
];
const SET = new Set(ADULT_DOMAINS);
// unambiguous words inside the host name, plus whole-label words (sex / nude / nsfw ...) so "sussex" or "essex" stay fine
const SUBSTR = /(porn|xxx|hentai|xvideo|xnxx|xhamster|redtube|youporn|brazzers|camgirl|sexcam|livesex|nudepic|fuck|erotic)/;
const LABEL = /(^|[.-])(sex|sexy|nude|nudes|nsfw|fap|jav|milf|bdsm|escort|escorts)([.-]|$)/;
const BAD_TLD = /\.(xxx|porn|sex|adult)$/;

function isAdultHost(host) {
  host = String(host || '').toLowerCase().replace(/\.$/, '');
  if (!host) return false;
  const parts = host.split('.');
  for (let i = 0; i < parts.length - 1; i++) if (SET.has(parts.slice(i).join('.'))) return true;
  return BAD_TLD.test(host) || SUBSTR.test(host) || LABEL.test(host);
}

// adult words typed into a search engine (the page then turns into a focus page, see index.html toFocus)
const ADULT_Q = /(\bporn\w*|\bxxx\b|\bxvideos?\b|\bxnxx\b|\bxhamster\b|\bhentai\b|\bnsfw\b|\bnudes?\b|\bnaked\b|\berotic\w*|\bblowjob\b|\bmilf\b|\bbdsm\b|\bfetish\b|\bcamgirls?\b|\bhorny\b|\bonlyfans\b|\bboobs?\b|\bpussy\b|\bsexy\b|\bsex(?!\s*(education|ed|ratio|chromosomes?|hormones?|determination|discrimination|differences?|offenders?|trafficking))\b|bangla\s*choti|choti\s*golpo|চটি|সেক্স|পর্ন|নেংটা|যৌন\s*(ভিডিও|গল্প))/iu;
const SEARCH_HOST = /(^|\.)(google|bing|duckduckgo|yahoo|yandex|brave|ecosia|youtube|startpage)\./i;
function isAdultSearch(u) {
  if (!SEARCH_HOST.test(u.hostname + '.')) return false;
  const q = u.searchParams.get('q') || u.searchParams.get('search_query') || u.searchParams.get('p') || u.searchParams.get('text') || '';
  return !!q && ADULT_Q.test(q);
}

// returns a redirect URL that forces SafeSearch, or '' when nothing has to change
function safeSearchUrl(u) {
  const h = u.hostname.replace(/^www\./, '');
  if (/^google\.[a-z.]+$/.test(h) && (u.pathname === '/search' || u.pathname === '/imgres')) {
    if (u.searchParams.get('safe') !== 'active') { u.searchParams.set('safe', 'active'); return u.toString(); }
  } else if (h === 'bing.com' && /^\/(images\/)?(search|async)/.test(u.pathname)) {
    if (u.searchParams.get('adlt') !== 'strict') { u.searchParams.set('adlt', 'strict'); return u.toString(); }
  } else if (h === 'duckduckgo.com' && u.pathname === '/') {
    if (u.searchParams.has('q') && u.searchParams.get('kp') !== '1') { u.searchParams.set('kp', '1'); return u.toString(); }
  }
  return '';
}

module.exports = function createSafety({ getSessions, isAdultBlockOn }) {
  return {
    isAdultHost,
    // used from the session's onBeforeRequest: { cancel:true } (blocked) / { redirectURL } (SafeSearch) / null
    adultCheck(d) {
      try {
        if (!isAdultBlockOn()) return null;
        if (d.resourceType !== 'mainFrame' && d.resourceType !== 'subFrame') return null;
        const u = new URL(d.url);
        if (u.protocol !== 'https:' && u.protocol !== 'http:') return null;
        if (isAdultHost(u.hostname) || isAdultSearch(u)) return { cancel: true };
        const r = safeSearchUrl(u);
        return r ? { redirectURL: r } : null;
      } catch (_) { return null; }
    },

    // login cookies stay after the app is closed: a cookie without an expiry date would be dropped at exit
    persistCookies(ses) {
      const DAYS30 = 30 * 24 * 3600;
      ses.cookies.on('changed', (_e, c, cause, removed) => {
        try {
          if (removed || !c.session || cause !== 'explicit') return;
          const host = String(c.domain || '').replace(/^\./, '');
          if (!host) return;
          const o = {
            url: (c.secure ? 'https://' : 'http://') + host + (c.path || '/'),
            name: c.name, value: c.value, path: c.path || '/', secure: !!c.secure, httpOnly: !!c.httpOnly,
            expirationDate: Math.floor(Date.now() / 1000) + DAYS30,
          };
          if (!c.hostOnly) o.domain = c.domain;
          if (c.sameSite) o.sameSite = c.sameSite;
          ses.cookies.set(o).catch(() => {});
        } catch (_) {}
      });
    },

    // clean caches only. Cookies / localStorage / IndexedDB / saved site copy (offline mode) stay untouched.
    async clean() {
      const { def, ext } = getSessions();
      const jobs = [];
      for (const s of [def, ext]) {
        jobs.push(s.clearCache());
        try { jobs.push(s.clearCodeCaches({})); } catch (_) {}
      }
      jobs.push(ext.clearStorageData({ storages: ['cachestorage', 'shadercache'] }));
      await Promise.allSettled(jobs);
      try { await ext.cookies.flushStore(); } catch (_) {}
      try { await def.cookies.flushStore(); } catch (_) {}
      try { ext.flushStorageData(); def.flushStorageData(); } catch (_) {}
    },
  };
};
