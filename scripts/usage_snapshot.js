#!/usr/bin/env node
'use strict';
/**
 * Publishes today's Firestore usage of BOTH projects (all users together, from Cloud Monitoring) into the document
 * `_sync_meta/usage` of both projects, so the Android "Server Control" screen can show how much of the free quota is left.
 *
 * Needs the "Monitoring Viewer" IAM role on each service account (Google Cloud Console -> IAM). Without it the document
 * carries an `error` text and the app says so instead of showing numbers.
 *
 * Env: FIREBASE_SERVICE_ACCOUNT (project A), FIREBASE_SERVICE_ACCOUNT_BACKUP (project B)
 */
const admin = require('firebase-admin');
const { GoogleAuth } = require('google-auth-library');

const enc = m => String(m).replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');
const SA = { A: JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT || '{}'), B: JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_BACKUP || '{}') };
if (!SA.A.project_id || !SA.B.project_id) { console.log('::error title=Usage snapshot::service-account secrets missing'); process.exit(1); }

// Spark quotas reset at midnight Pacific time: start of the current Pacific day.
function pacificDayStart() {
  const f = new Intl.DateTimeFormat('en-US', { timeZone: 'America/Los_Angeles', hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const p = Object.fromEntries(f.formatToParts(new Date()).map(x => [x.type, x.value]));
  return new Date(Date.now() - (((+p.hour) % 24) * 3600 + (+p.minute) * 60 + (+p.second)) * 1000);
}

async function total(sa, token, metric, start, end) {
  const q = new URLSearchParams({
    filter: `metric.type="firestore.googleapis.com/document/${metric}"`,
    'interval.startTime': start.toISOString(), 'interval.endTime': end.toISOString(),
    'aggregation.alignmentPeriod': Math.max(60, Math.floor((end - start) / 1000)) + 's',
    'aggregation.perSeriesAligner': 'ALIGN_SUM', 'aggregation.crossSeriesReducer': 'REDUCE_SUM',
  });
  const r = await fetch(`https://monitoring.googleapis.com/v3/projects/${sa.project_id}/timeSeries?${q}`, { headers: { Authorization: 'Bearer ' + token } });
  const t = await r.text();
  let j; try { j = JSON.parse(t); } catch (e) { j = {}; }
  if (r.status === 403) throw new Error('Monitoring permission নেই (403)');
  if (r.status !== 200) throw new Error(`Monitoring HTTP ${r.status} ${(j.error && j.error.message) || ''}`.slice(0, 120));
  return (j.timeSeries || []).reduce((a, s) => a + s.points.reduce((b, p) => b + Number(p.value.int64Value || p.value.doubleValue || 0), 0), 0);
}

(async () => {
  const start = pacificDayStart(), end = new Date();
  const doc = { atMs: Date.now(), windowStart: start.toISOString(), error: '' };
  try {
    for (const k of ['A', 'B']) {
      const token = await new GoogleAuth({ credentials: SA[k], scopes: ['https://www.googleapis.com/auth/cloud-platform'] }).getAccessToken();
      for (const m of ['read', 'write', 'delete']) doc[`${k}_${m}`] = await total(SA[k], token, `${m}_count`, start, end);
    }
  } catch (e) {
    doc.error = String(e.message || e).slice(0, 160);
    for (const k of ['A', 'B']) for (const m of ['read', 'write', 'delete']) delete doc[`${k}_${m}`];
  }

  let written = 0;
  for (const k of ['A', 'B']) {
    try {
      const app = admin.initializeApp({ credential: admin.credential.cert(SA[k]) }, 'p' + k);
      await app.firestore().doc('_sync_meta/usage').set(doc);
      written++;
    } catch (e) { console.log(`write to ${k} failed: ${e.code || ''} ${e.message}`); }   // e.g. project over quota: the other one still has it
  }
  const msg = doc.error ? `Monitoring unavailable: ${doc.error}` : `A: read ${doc.A_read} write ${doc.A_write} delete ${doc.A_delete} | B: read ${doc.B_read} write ${doc.B_write} delete ${doc.B_delete}`;
  console.log(`::${doc.error ? 'warning' : 'notice'} title=Usage snapshot::${enc(msg + `\nwritten to ${written}/2 projects`)}`);
  if (!written) process.exit(1);
})().catch(e => { console.log(`::error title=Usage snapshot::${enc(e.message)}`); process.exit(1); });
