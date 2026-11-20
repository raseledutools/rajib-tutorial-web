'use strict';
// electron-builder afterPack: drop Chromium's unused UI language packs (~40 MB) → smaller installer, fewer files to write on update.
// Kept: en-US (Electron needs it) and bn (Bengali).
const fs = require('fs');
const path = require('path');
exports.default = async function afterPack(ctx) {
  const dir = path.join(ctx.appOutDir, 'locales');
  if (!fs.existsSync(dir)) return;
  const keep = new Set(['en-US.pak', 'bn.pak']);
  let n = 0;
  for (const f of fs.readdirSync(dir)) {
    if (f.endsWith('.pak') && !keep.has(f)) { try { fs.unlinkSync(path.join(dir, f)); n++; } catch (_) {} }
  }
  console.log('afterPack: removed ' + n + ' unused locale packs');
};
