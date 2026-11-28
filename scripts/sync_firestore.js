#!/usr/bin/env node
'use strict';
/**
 * Two-way reconcile between the PRIMARY project (A, mywebtools-f8d53) and the BACKUP project (B, rajib-tutorial-2).
 *
 * The apps already mirror every write to both projects (write-through). This job is the safety net + the first full copy:
 *   - doc only in A                  -> copied to B
 *   - doc only in B (new in B since the last sync, or no sync yet) -> copied to A   (writes made while A's quota was gone)
 *   - doc in both but different      -> the copy with the newer updateTime wins and is written to the other side
 *   - identical docs                 -> untouched
 * It never deletes anything. Run it right after the daily quota reset, while A can still be read.
 *
 * Env:
 *   FIREBASE_SERVICE_ACCOUNT         service-account JSON of project A
 *   FIREBASE_SERVICE_ACCOUNT_BACKUP  service-account JSON of project B
 *   DRY_RUN=1            compare and report only, write nothing
 *   SKIP_COLLECTIONS     comma list of root collections to skip  (default: push_messages)
 *   SKIP_ID_PREFIXES     comma list of doc-id prefixes to skip   (default: _call_  = temporary call signalling)
 *   MAX_DOCS             safety cap on docs read per project per run (default: 15000)
 * Only root collections are handled (that is all the apps use).
 */
const fs = require('fs');
const { isDeepStrictEqual } = require('util');
const admin = require('firebase-admin');

const env = (k, d = '') => String(process.env[k] === undefined ? d : process.env[k]).trim();
const flag = k => ['1', 'true', 'yes'].includes(env(k).toLowerCase());
const list = s => s.split(',').map(x => x.trim()).filter(Boolean);
const enc = m => String(m).replace(/%/g, '%25').replace(/\r/g, '%0D').replace(/\n/g, '%0A');

const DRY = flag('DRY_RUN');
const MAX_DOCS = parseInt(env('MAX_DOCS', '15000'), 10) || 15000;
const SKIP_COLS = new Set(list(env('SKIP_COLLECTIONS', 'push_messages')));
const SKIP_PREFIXES = list(env('SKIP_ID_PREFIXES', '_call_'));
const META_PATH = '_sync_meta/state';

function fail(msg) { console.log(`::error title=Firestore sync::${enc(msg)}`); process.exit(1); }
function parseSA(name) {
  const raw = env(name);
  if (!raw) fail(`${name} secret is not set (repo Settings > Secrets and variables > Actions).`);
  try { return JSON.parse(raw); } catch (e) { fail(`${name} is not valid JSON.`); }
}

const saA = parseSA('FIREBASE_SERVICE_ACCOUNT');
const saB = parseSA('FIREBASE_SERVICE_ACCOUNT_BACKUP');
if (saA.project_id === saB.project_id) fail('Primary and backup service accounts belong to the same project - refusing to sync a project with itself.');

const dbA = admin.initializeApp({ credential: admin.credential.cert(saA) }, 'primary').firestore();
const dbB = admin.initializeApp({ credential: admin.credential.cert(saB) }, 'backup').firestore();

const st = { readA: 0, readB: 0, equal: 0, toB_new: 0, toB_upd: 0, toA_new: 0, toA_upd: 0, conflicts: 0, bOnlyOld: 0, filtered: 0, errors: 0 };
const conflictPaths = [];
let truncated = false, lastDone = null, wA = null, wB = null;

const skipDoc = id => SKIP_PREFIXES.some(p => id.startsWith(p));
const ms = ts => (ts ? ts.toMillis() : 0);

async function load(col, side) {
  const m = new Map();
  for await (const s of col.stream()) {
    if (st.readA + st.readB >= MAX_DOCS * 2) { truncated = true; break; }
    side === 'A' ? st.readA++ : st.readB++;
    if (skipDoc(s.id)) { st.filtered++; continue; }
    m.set(s.id, s);
  }
  return m;
}

function put(writer, db, snap, tag) {
  if (DRY) return;
  try {
    writer.set(db.doc(snap.ref.path), snap.data()).catch(e => { st.errors++; console.log(`write failed (${tag}) ${snap.ref.path}: ${e.message}`); });
  } catch (e) { st.errors++; console.log(`write failed (${tag}) ${snap.ref.path}: ${e.message}`); }
}

async function syncCollection(id) {
  const [a, b] = [await load(dbA.collection(id), 'A'), await load(dbB.collection(id), 'B')];
  for (const [docId, sa] of a) {
    const sb = b.get(docId);
    if (!sb) { st.toB_new++; put(wB, dbB, sa, 'A->B'); continue; }
    if (isDeepStrictEqual(sa.data(), sb.data())) { st.equal++; continue; }
    const ta = ms(sa.updateTime), tb = ms(sb.updateTime);
    if (lastDone && ta > lastDone && tb > lastDone) {   // changed on both sides since the last sync
      st.conflicts++; if (conflictPaths.length < 30) conflictPaths.push(`${sa.ref.path} (${ta >= tb ? 'A' : 'B'} kept)`);
    }
    if (ta >= tb) { st.toB_upd++; put(wB, dbB, sa, 'A->B'); }
    else { st.toA_upd++; put(wA, dbA, sb, 'B->A'); }
  }
  for (const [docId, sb] of b) {
    if (a.has(docId)) continue;
    // Only in B. Created in B since the last sync (or never synced) -> it belongs to A too. Older than the last sync -> it was
    // deleted in A; leave it alone (we never delete).
    if (!lastDone || ms(sb.createTime) > lastDone) { st.toA_new++; put(wA, dbA, sb, 'B->A'); }
    else st.bOnlyOld++;
  }
}

async function main() {
  console.log(`A=${saA.project_id} <-> B=${saB.project_id}  dry=${DRY}`);
  try {
    const meta = await dbB.doc(META_PATH).get();
    if (meta.exists && meta.get('finishedAt')) lastDone = meta.get('finishedAt').toMillis();
  } catch (e) {
    if (e.code === 5 || e.code === 7) fail(`Cannot use the backup Firestore (${e.code}): create the Firestore database in project ${saB.project_id} and give the service account the "Cloud Datastore User" role.`);
    throw e;
  }
  console.log(lastDone ? `previous sync finished ${new Date(lastDone).toISOString()}` : 'no previous sync recorded');

  wA = dbA.bulkWriter(); wB = dbB.bulkWriter();
  let incomplete = false;
  try {
    const ids = new Set([...(await dbA.listCollections()).map(c => c.id), ...(await dbB.listCollections()).map(c => c.id)]);
    for (const id of [...ids].sort()) {
      if (SKIP_COLS.has(id) || id.startsWith('_sync_')) { console.log(`skip collection ${id}`); continue; }
      console.log(`collection ${id}`);
      await syncCollection(id);
      if (truncated) break;
    }
  } catch (e) {
    if (e.code === 8) {
      incomplete = true;
      console.log(`::warning title=Firestore sync::${enc(`Quota exhausted while reading (${e.message}). Nothing more can be compared today; the next run (after the daily reset) continues.`)}`);
    } else throw e;
  }
  await wA.close(); await wB.close();

  if (!DRY && !incomplete && !truncated) {
    await dbB.doc(META_PATH).set({ finishedAt: admin.firestore.FieldValue.serverTimestamp(), stats: st });
  }

  const lines = [
    `Firestore sync ${saA.project_id} <-> ${saB.project_id}${DRY ? ' (dry run)' : ''}${incomplete ? ' - INCOMPLETE (quota)' : ''}`,
    `docs read: A ${st.readA}, B ${st.readB}; identical: ${st.equal}`,
    `A -> B: ${st.toB_new} new, ${st.toB_upd} updated`,
    `B -> A: ${st.toA_new} new, ${st.toA_upd} updated`,
    `changed on both sides since last sync (newer kept): ${st.conflicts}`,
    `only in B but older than last sync (left alone): ${st.bOnlyOld}; skipped by filter: ${st.filtered}; write errors: ${st.errors}`,
  ];
  if (conflictPaths.length) lines.push('conflicts:', ...conflictPaths.map(p => '  ' + p));
  if (truncated) lines.push(`STOPPED at MAX_DOCS=${MAX_DOCS} - raise MAX_DOCS to cover everything.`);
  console.log(lines.join('\n'));
  console.log(`::notice title=Firestore sync result::${enc(lines.join('\n'))}`);
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, '```\n' + lines.join('\n') + '\n```\n');

  if (st.errors) fail(`${st.errors} write(s) failed.`);
  if (truncated) fail(`Stopped at MAX_DOCS=${MAX_DOCS}; the remaining docs were not compared.`);
}

main().catch(e => fail(`${e.code || ''} ${e.message}`));
