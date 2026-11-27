#!/usr/bin/env node
'use strict';
/**
 * Copies Firestore data from the PRIMARY project (mywebtools-f8d53) to the BACKUP project
 * (rajib-tutorial-2) so the app's client-side failover has real data to read.
 *
 * Env:
 *   FIREBASE_SERVICE_ACCOUNT         service-account JSON of the PRIMARY project (already used by send_update_push.py)
 *   FIREBASE_SERVICE_ACCOUNT_BACKUP  service-account JSON of the BACKUP project  (new secret)
 *   DRY_RUN=1            count only, write nothing
 *   FORCE=1              overwrite backup docs even if they were changed in the backup after the last sync
 *   SUBCOLLECTIONS=1     also copy sub-collections (costs 1 extra read per doc; the app uses root collections only)
 *   SKIP_COLLECTIONS     comma list of root collections to skip   (default: push_messages)
 *   SKIP_ID_PREFIXES     comma list of doc-id prefixes to skip    (default: _call_  = temporary call signalling)
 *   MAX_DOCS             safety cap on docs read per run          (default: 15000)
 *
 * Overwrite protection: while the app runs on the backup (failover), users can write data there that the
 * primary does not have. After every run we store `_sync_meta/state.finishedAt` in the backup. On the next run a
 * backup doc whose updateTime is newer than that was changed *after* the last sync, so it is NOT overwritten
 * (listed in the summary instead). Without a previous sync, docs that already exist in the backup are left alone.
 */
const fs = require('fs');
const admin = require('firebase-admin');

const env = (k, d = '') => String(process.env[k] === undefined ? d : process.env[k]).trim();
const flag = k => ['1', 'true', 'yes'].includes(env(k).toLowerCase());
const list = s => s.split(',').map(x => x.trim()).filter(Boolean);

const DRY = flag('DRY_RUN');
const FORCE = flag('FORCE');
const SUBS = flag('SUBCOLLECTIONS');
const MAX_DOCS = parseInt(env('MAX_DOCS', '15000'), 10) || 15000;
const SKIP_COLS = new Set(list(env('SKIP_COLLECTIONS', 'push_messages')));
const SKIP_PREFIXES = list(env('SKIP_ID_PREFIXES', '_call_'));
const META_PATH = '_sync_meta/state';
const CHUNK = 200;

function fail(msg) { console.log(`::error title=Firestore sync::${msg}`); process.exit(1); }

function parseSA(name) {
  const raw = env(name);
  if (!raw) fail(`${name} secret is not set (repo Settings > Secrets and variables > Actions).`);
  try { return JSON.parse(raw); } catch (e) { fail(`${name} is not valid JSON.`); }
}

const saA = parseSA('FIREBASE_SERVICE_ACCOUNT');
const saB = parseSA('FIREBASE_SERVICE_ACCOUNT_BACKUP');
if (saA.project_id === saB.project_id) fail('Primary and backup service accounts belong to the same project - refusing to copy a project onto itself.');

const src = admin.initializeApp({ credential: admin.credential.cert(saA) }, 'primary').firestore();
const dst = admin.initializeApp({ credential: admin.credential.cert(saB) }, 'backup').firestore();

const stats = { read: 0, created: 0, updated: 0, skippedFilter: 0, skippedExisting: 0, skippedNewer: 0, errors: 0 };
const newerPaths = [];
let truncated = false;
let lastDone = null; // Timestamp of the previous finished sync
let writer = null;
let pending = [];

async function flush() {
  if (!pending.length) return;
  const batch = pending; pending = [];
  const snaps = await dst.getAll(...batch.map(b => b.dstRef));
  snaps.forEach((s, i) => {
    const { dstRef, data } = batch[i];
    let action;
    if (!s.exists) action = 'create';
    else if (FORCE) action = 'update';
    else if (!lastDone) { stats.skippedExisting++; return; }
    else if (s.updateTime.toMillis() > lastDone.toMillis()) {
      stats.skippedNewer++; if (newerPaths.length < 50) newerPaths.push(dstRef.path); return;
    } else action = 'update';
    if (action === 'create') stats.created++; else stats.updated++;
    if (!DRY) writer.set(dstRef, data).catch(e => { stats.errors++; console.log(`write failed ${dstRef.path}: ${e.message}`); });
  });
}

async function copyCollection(srcCol, isRoot) {
  if (isRoot && (SKIP_COLS.has(srcCol.id) || srcCol.id.startsWith('_sync_'))) { console.log(`skip collection ${srcCol.id}`); return; }
  for await (const snap of srcCol.stream()) {
    if (stats.read >= MAX_DOCS) { truncated = true; return; }
    stats.read++;
    if (SKIP_PREFIXES.some(p => snap.id.startsWith(p))) { stats.skippedFilter++; continue; }
    pending.push({ dstRef: dst.doc(snap.ref.path), data: snap.data() });
    if (pending.length >= CHUNK) await flush();
    if (SUBS) {
      const subs = await snap.ref.listCollections();
      for (const sc of subs) { await flush(); await copyCollection(sc, false); if (truncated) return; }
    }
  }
}

async function main() {
  console.log(`primary=${saA.project_id} -> backup=${saB.project_id}  dry=${DRY} force=${FORCE} subs=${SUBS}`);

  try {
    const meta = await dst.doc(META_PATH).get();
    if (meta.exists) lastDone = meta.get('finishedAt') || null;
  } catch (e) {
    if (e.code === 5 || e.code === 7) fail(`Cannot use the backup Firestore (${e.code}): create the Firestore database in project ${saB.project_id} and make sure the service account has the "Cloud Datastore User" role.`);
    throw e;
  }
  console.log(lastDone ? `previous sync finished at ${lastDone.toDate().toISOString()}` : 'no previous sync recorded - existing backup docs will be left untouched');

  writer = dst.bulkWriter();
  let quotaHit = false;
  try {
    const roots = await src.listCollections();
    console.log('root collections: ' + roots.map(c => c.id).join(', '));
    for (const col of roots) { await copyCollection(col, true); await flush(); if (truncated) break; }
  } catch (e) {
    if (e.code === 8) { quotaHit = true; console.log(`::warning title=Firestore sync::Primary quota exhausted while reading (${e.message}). Partial copy; will retry next run.`); }
    else throw e;
  }
  await flush();
  await writer.close();

  if (!DRY) {
    await dst.doc(META_PATH).set({
      finishedAt: admin.firestore.FieldValue.serverTimestamp(),
      source: saA.project_id,
      stats, truncated, quotaHit,
    });
  }

  const lines = [
    `### Firestore sync ${saA.project_id} -> ${saB.project_id}${DRY ? ' (dry run)' : ''}`,
    `- docs read from primary: **${stats.read}**`,
    `- created in backup: **${stats.created}**, updated: **${stats.updated}**`,
    `- skipped by filter: ${stats.skippedFilter}, already in backup (no previous sync): ${stats.skippedExisting}`,
    `- skipped because the backup copy is newer (changed during failover): **${stats.skippedNewer}**`,
    `- write errors: **${stats.errors}**`,
  ];
  if (newerPaths.length) lines.push('', 'Backup-newer docs (first 50):', ...newerPaths.map(p => `- \`${p}\``));
  if (truncated) lines.push('', `**Stopped at MAX_DOCS=${MAX_DOCS}** - raise MAX_DOCS to copy the rest.`);
  console.log(lines.join('\n'));
  if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');

  if (stats.errors) fail(`${stats.errors} write(s) failed.`);
  if (truncated) fail(`Stopped at MAX_DOCS=${MAX_DOCS}; the remaining docs were not copied.`);
}

main().catch(e => fail(`${e.code || ''} ${e.message}`));
