/**
 * apply-logo.js — wires the brand logo in as the Android launcher icon.
 *
 * Usage:  node scripts/apply-logo.js [path-to-source-png]
 *   default source: assets/brand/logo.png
 *
 * We have no image-resizing library in this environment, so we use the standard single-source
 * pattern: drop the high-res square PNG into the top density bucket (xxxhdpi) and let Android
 * downscale it for every lower density. Stale default icons in the other buckets are removed so an
 * exact-density match can't beat our icon. Re-run whenever the logo file changes.
 *
 * The in-app splash / onboarding load the logo straight from assets/brand/logo.png via require(),
 * so those update automatically once the file exists — no copying needed for the JS side.
 */
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const source = path.resolve(root, process.argv[2] || 'assets/brand/logo.png');

if (!fs.existsSync(source)) {
  console.error(`\n✗ Source logo not found: ${source}`);
  console.error('  Drop your logo PNG at assets/brand/logo.png (or pass a path) and re-run.\n');
  process.exit(1);
}

const resDir = path.join(root, 'android/app/src/main/res');
const iconNames = ['ic_launcher.png', 'ic_launcher_round.png'];
// Highest bucket holds the real asset; Android resolves + downscales it for all lower densities.
const primary = 'xxxhdpi';
const lowerBuckets = ['mdpi', 'hdpi', 'xhdpi', 'xxhdpi'];

const primaryDir = path.join(resDir, `mipmap-${primary}`);
fs.mkdirSync(primaryDir, { recursive: true });
for (const name of iconNames) {
  fs.copyFileSync(source, path.join(primaryDir, name));
}

let removed = 0;
for (const bucket of lowerBuckets) {
  for (const name of iconNames) {
    const p = path.join(resDir, `mipmap-${bucket}`, name);
    if (fs.existsSync(p)) {
      fs.rmSync(p);
      removed++;
    }
  }
}

console.log(`\n✓ Logo applied from ${path.relative(root, source)}`);
console.log(`  Written to mipmap-${primary} (${iconNames.join(', ')}); ${removed} stale default icon(s) removed.`);
console.log('  Splash / onboarding / notification pull from assets/brand/logo.png directly.');
console.log('  Rebuild to see the new icon:  gradlew :app:assembleStandalone\n');
