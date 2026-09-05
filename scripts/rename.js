// Pickwick -> <new name>, front to back. Usage:
//
//   node rename.js "Yosemite Kids"            # text pass only, prints derivations
//   node rename.js "Yosemite Kids" --dry-run  # report what would change, touch nothing
//
// Text pass only; directory and file moves are done with git mv afterwards so
// history follows (the script prints the exact mv list). Specific tokens first
// (package, paths, env prefix, docker names, identifiers, log tags), then a
// generic pass for the product name that SKIPS lines which must keep saying
// Pickwick: upstream URLs, the GPL attribution, the signing key, the local
// emulator AVD names.
const fs = require('fs');
const path = require('path');

const display = process.argv[2];
const dry = process.argv.includes('--dry-run');
if (!display || display.startsWith('-')) { console.error('usage: node rename.js "New Name" [--dry-run]'); process.exit(2); }

// Every form derived from the display name, printed so a bad derivation is
// caught before anything is written.
const words = display.trim().split(/\s+/);
const N = {
  display,                                             // "Yosemite Kids"
  slug: words.join('-').toLowerCase(),                 // yosemite-kids   (repo, image, apk, skills)
  pkg: words.join('').toLowerCase(),                   // yosemitekids    (io.<pkg>.app, url scheme, unix user)
  ident: words.map(w => w[0].toUpperCase() + w.slice(1).toLowerCase()).join(''), // YosemiteKids (Kotlin, log tag)
  env: words.join('_').toUpperCase() + '_',            // YOSEMITE_KIDS_  (env / gradle property prefix)
  shout: words.join('_').toUpperCase(),                // YOSEMITE_KIDS   (bare upper-case mentions)
  initials: words.map(w => w[0].toLowerCase()).join(''), // yk            (the old "pw" abbreviation)
};
// Identifiers named Pickwick<Thing> keep <Thing>; the prefix follows the first word.
N.identPrefix = words[0][0].toUpperCase() + words[0].slice(1).toLowerCase();  // Yosemite
console.log('derivations:', JSON.stringify(N, null, 2));

const ROOT = process.cwd();
const SKIP_DIRS = new Set(['.git', 'build', '.gradle', 'node_modules', '.idea', 'captures']);
const SKIP_PATHS = [
  'docs/design/parent-settings',   // third-party design reference for the old name
  'docs/UPSTREAM-LOG.md',          // a log OF Pickwick
  'docs/.upstream-seen',
  'docs/RENAME.md',                // the plan itself names both
  'scripts/rename.js',             // this script: its regexes spell the old name
  'version.json',                  // upstream's; replaced by the release repo (roadmap §1)
  'LICENSE',
];
const TEXT_EXT = new Set(['.kt', '.kts', '.java', '.md', '.yml', '.yaml', '.sh', '.ps1', '.xml', '.json',
  '.html', '.js', '.mjs', '.toml', '.txt', '.gradle', '.properties', '.pro', '.cfg', '.tsv', '.csv', '.css', '.svg']);
const TEXT_NAMES = new Set(['gradlew', '.gitattributes', '.gitignore', '.dockerignore', 'Dockerfile']);

// Lines that keep the old name. Checked against the ORIGINAL line.
// pickwick.workers.dev is upstream's suggestion worker: a real host the app
// POSTs to. Renaming it produced a host that does not exist and a feature
// that failed with a network error — a rename must not change behaviour.
const PROTECT = /itcon-pty-au|pickwick\.tv|pickwick\.workers\.dev|pickwick-fork-release|pickwickfork|~\/\.pickwick|pickwick_phone|pickwick_tv|based on Pickwick|fork of Pickwick|upstream Pickwick|Pickwick upstream/;

const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const SPECIFIC = [
  [/io\.pickwick\.app/g, `io.${N.pkg}.app`],
  [/io\.pickwick\.hub/g, `io.${N.pkg}.hub`],
  [/io\/pickwick\/app/g, `io/${N.pkg}/app`],
  [/io\/pickwick\/hub/g, `io/${N.pkg}/hub`],
  [/io\\pickwick\\app/g, `io\\${N.pkg}\\app`],
  [/io\\pickwick\\hub/g, `io\\${N.pkg}\\hub`],
  [/pickwick:\/\//g, `${N.pkg}://`],
  // The manifest declares the scheme bare (android:scheme="pickwick") and
  // MainActivity compares data.scheme to "pickwick"; the QR generator writes
  // pickwick://. All three must derive the SAME slug or pairing breaks — so
  // the quoted lowercase form maps to pkg, same as the :// form above.
  [/"pickwick"/g, `"${N.pkg}"`],
  [/PICKWICK_/g, N.env],
  [/ghcr\.io\/mrain1p\/pickwick-hub/g, `ghcr.io/mrain1p/${N.slug}-hub`],
  [/pickwick-hub/g, `${N.slug}-hub`],
  [/pickwick-entrypoint/g, `${N.slug}-entrypoint`],
  [/\.pickwick-write-test/g, `.${N.slug}-write-test`],
  [/useradd --system --uid 10001 pickwick/g, `useradd --system --uid 10001 ${N.pkg}`],
  [/chown -R pickwick:pickwick/g, `chown -R ${N.pkg}:${N.pkg}`],
  [/setpriv[^\n]*pickwick[^\n]*/g, m => m.replace(/\bpickwick\b/g, N.pkg)],
  [/pickwick-(check|emulator|lan-api|release|sync|upstream)/g, `${N.slug}-$1`],
  [/Pickwick(Icons|Screen|DarkColors|LightColors|Typography|Directory)/g, `${N.identPrefix}$1`],
  [/PickwickApp\b/g, `${N.ident}App`],
  [/Log\.([iwedv])\(\s*"Pickwick"/g, `Log.$1("${N.ident}"`],
  [/rootProject\.name\s*=\s*"Pickwick"/g, `rootProject.name = "${N.ident}"`],
  // Android resource names cannot contain spaces: the style in themes.xml and
  // the manifest's android:theme reference must stay one token, or
  // mergeDebugResources fails with "' ' is not a valid resource name character".
  [/Theme\.Pickwick\b/g, `Theme.${N.ident}`],
  // logcat's tag filter is one shell argument; it must match the Log tag
  // set above, not the display name (a space would split it in two).
  [/-s Pickwick:\*/g, `-s ${N.ident}:*`],
  // Quoted identifiers built on the name — thread names ("Pickwick-dns"),
  // the ImageVector namespace ("Pickwick.$name"), the update temp file
  // ("Pickwick-update.apk") — stay one token. Only a quote followed by the
  // bare name and then a separator matches; "Pickwick hub" and "Pickwick: ..."
  // are display text and fall through to the generic pass.
  // ...except the downloaded update, which is an APK and follows the APK
  // naming (yosemite-kids.apk, yosemite-kids-<version>.apk). Before the
  // rule above, which would otherwise claim it.
  [/"Pickwick-update\.apk"/g, `"${N.slug}-update.apk"`],
  [/"Pickwick([-.])/g, `"${N.ident}$1`],
  // Abbreviations of the old name that no regex on "pickwick" can see: the
  // Pw-prefixed composable and the hub's pw_ session cookie.
  [/\bPwChip\b/g, `${N.identPrefix}Chip`],
  [/\bpw_session\b/g, `${N.initials}_session`],
];
const GENERIC = [
  [/Pickwick's/g, `${N.display}'`],
  [/Pickwicks\b/g, N.display],
  [/Pickwick/g, N.display],
  [/PICKWICK/g, N.shout],
  [/pickwick/g, N.slug],
];

function isText(file) {
  const base = path.basename(file);
  return TEXT_EXT.has(path.extname(base).toLowerCase()) || TEXT_NAMES.has(base);
}
const skipped = rel => SKIP_PATHS.some(s => rel === s || rel.startsWith(s + '/'));

let files = 0, changed = 0, protectedLines = 0;
const touched = [];
function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const rel = path.relative(ROOT, full).split(path.sep).join('/');
    const st = fs.statSync(full);
    if (st.isDirectory()) { if (!SKIP_DIRS.has(name) && !skipped(rel)) walk(full); continue; }
    if (!isText(full) || skipped(rel)) continue;
    files++;
    const orig = fs.readFileSync(full, 'utf8');
    let text = orig;
    for (const [re, to] of SPECIFIC) text = text.replace(re, to);
    const out = text.split('\n').map((line) => {
      if (PROTECT.test(line)) { if (/[Pp]ickwick/.test(line)) protectedLines++; return line; }
      let l = line;
      for (const [re, to] of GENERIC) l = l.replace(re, to);
      return l;
    }).join('\n');
    if (out !== orig) { touched.push(rel); if (!dry) fs.writeFileSync(full, out, 'utf8'); changed++; }
  }
}
walk(ROOT);
console.log(`${dry ? 'would rewrite' : 'rewrote'} ${changed} of ${files} text files; ${protectedLines} protected lines keep "Pickwick"`);

// The moves git has to make so history follows the package directories.
console.log('\ngit mv list (run after the text pass):');
for (const p of ['app/src/main/java', 'app/src/test/java', 'app/src/androidTest/java',
                 'core/src/main/kotlin', 'core/src/test/kotlin', 'hub/src/main/kotlin', 'hub/src/test/kotlin']) {
  if (fs.existsSync(path.join(ROOT, p, 'io/pickwick'))) console.log(`  git mv ${p}/io/pickwick ${p}/io/${N.pkg}`);
}
for (const s of ['check', 'emulator', 'lan-api', 'release', 'sync', 'upstream']) {
  if (fs.existsSync(path.join(ROOT, '.claude/skills/pickwick-' + s))) console.log(`  git mv .claude/skills/pickwick-${s} .claude/skills/${N.slug}-${s}`);
}
console.log(`  git mv <PickwickScreen.kt> <${N.identPrefix}Screen.kt>   # any file named Pickwick*.kt`);
