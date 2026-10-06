#!/usr/bin/env bash
#
# keliver new-publish-target — let the portal publish SIGNED bundles of this app.
#
#   keliver-new-publish-target.sh
#
# Run from the APP repo root (the directory holding keliver.portal.json), after
# keliver-new-device-target.sh: publishing compiles the same Zipline bundle the
# device path serves, from src/jsMain/kotlin/device/Main.kt.
#
# The portal's POST /publish runs `publishTask` from keliver.portal.json and
# keeps `publishOutput` as the next bundle version — but only if its manifest is
# signed with this app's key, because production hosts refuse anything else.
# The relay does not sign; the Zipline compile task does. A keliver-init app has
# neither the two settings (the defaults name Keliver's own repository) nor any
# signing, so this writes:
#
#   1. keliver.portal.json: "publishTask": ":compileDevelopmentExecutableKotlinJsZipline"
#                           "publishOutput": "build/zipline/Development"
#   2. build.gradle: a signing block appended at the END, below kotlin {} —
#      above it, the bundle compiles UNSIGNED without an error. It signs with
#      keys/ed25519.priv of the store keliver-store-path.sh resolves for this
#      app; the private key is read only by that compile task, never by this.
#
# The development variant is what the reference app's production checks ran on;
# the production (minified) variant has not been run on a device.
#
# ALL-OR-NOTHING: everything is validated and staged before anything is written.
# A rejected input leaves the app byte-identical.
set -euo pipefail

PUBLISH_TASK=":compileDevelopmentExecutableKotlinJsZipline"
PUBLISH_OUTPUT="build/zipline/Development"

SELF="${BASH_SOURCE[0]}"
while [ -L "$SELF" ]; do
  link="$(readlink "$SELF")"; case "$link" in /*) SELF="$link" ;; *) SELF="$(dirname "$SELF")/$link" ;; esac
done
HERE="$(cd "$(dirname "$SELF")" && pwd -P)"

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) sed -n '2,30p' "$SELF"; exit 0 ;;
    *)         echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "keliver-new-publish-target: $*" >&2; echo "Nothing was written." >&2; exit 1; }

APP="$(pwd -P)"
[ -f "$APP/keliver.portal.json" ] || fail "no keliver.portal.json here — run from the app root."
[ -f "$APP/build.gradle" ] || fail "no build.gradle here (a Groovy build.gradle, as keliver-init writes, is required)."

# Beside this script in the repository (scripts/templates), one level up in the
# tools bundle (templates/).
BLOCK=""
for t in "$HERE/templates/publish/signing.gradle" "$HERE/../templates/publish/signing.gradle"; do
  [ -f "$t" ] && { BLOCK="$t"; break; }
done
[ -n "$BLOCK" ] || fail "the publish template is missing (looked in $HERE/templates and $HERE/../templates)."

STAGE="$(mktemp -d "$APP/.keliver-publish-target.XXXXXX")" || fail "could not create a staging directory in $APP."
trap 'rm -rf "$STAGE"' EXIT

# Validate and stage in one parse. Prints "ok <what changed>" or "ERROR <why>".
# (Written to a file first: bash 3.2 misparses a heredoc inside $(...).)
cat > "$STAGE/plan.py" <<'PY'
import json, os, re, shutil, sys
app, block_path, stage, task, output = sys.argv[1:6]

def fail(msg):
    print("ERROR " + msg); sys.exit(0)

def strip_comments(src):
    # Groovy comments out, string literals kept: a '**/*.js' or a URL in a
    # string must not open a comment that hides real code.
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2
        elif src[i] in '\'"':
            q = src[i:i + 3] if src[i:i + 3] in ("'''", '"""') else src[i]
            j = i + len(q)
            while j < n and not src.startswith(q, j):
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + len(q)]); i = j + len(q)
        else:
            out.append(src[i]); i += 1
    return ''.join(out)

build = open(os.path.join(app, 'build.gradle'), encoding='utf-8').read()
code = strip_comments(build)
if not re.search(r"""id\s*\(?\s*['"]app\.cash\.zipline['"]""", code):
    fail("build.gradle does not apply the Zipline plugin. Run keliver-new-device-target.sh first: "
         "publishing compiles the bundle the device path serves.")
if 'binaries.executable()' not in code:
    fail("build.gradle has no binaries.executable() in its js target. Run keliver-new-device-target.sh first.")
if not os.path.isfile(os.path.join(app, 'src/jsMain/kotlin/device/Main.kt')):
    fail("no src/jsMain/kotlin/device/Main.kt (the bundle's entry point). Run keliver-new-device-target.sh first.")
if not re.search(r'(?m)^kotlin\s*\{', code):
    fail("build.gradle has no top-level `kotlin {` block; the signing block must follow it.")
if 'keliver: PUBLISH SIGNING' in build:
    fail("build.gradle already has the signing block this script writes: publishing is already wired.")
if 'signingKeys' in code:
    fail("build.gradle already configures signingKeys. Remove that block first, or keep it and add "
         "publishTask/publishOutput to keliver.portal.json yourself.")

try:
    with open(os.path.join(app, 'keliver.portal.json'), encoding='utf-8') as f:
        cfg = json.load(f)
except ValueError as e:
    fail(f"keliver.portal.json is not valid JSON: {e}")
if not isinstance(cfg, dict):
    fail("keliver.portal.json is not a JSON object.")
for key, want in (('publishTask', task), ('publishOutput', output)):
    have = cfg.get(key)
    if have is not None and have != want:
        fail(f'keliver.portal.json already sets "{key}": "{have}". This writes "{want}"; '
             f"remove yours first if that is what you want.")

changes = []
if cfg.get('publishTask') != task or cfg.get('publishOutput') != output:
    cfg['publishTask'] = task
    cfg['publishOutput'] = output
    changes.append('keliver.portal.json')
with open(os.path.join(stage, 'keliver.portal.json'), 'w', encoding='utf-8') as f:
    f.write(json.dumps(cfg, indent=2, ensure_ascii=False) + '\n')

block = open(block_path, encoding='utf-8').read()
with open(os.path.join(stage, 'build.gradle'), 'w', encoding='utf-8') as f:
    f.write(build if build.endswith('\n') else build + '\n')
    f.write(block)
changes.append('build.gradle')
for name in ('keliver.portal.json', 'build.gradle'):
    shutil.copymode(os.path.join(app, name), os.path.join(stage, name))
print('ok ' + ' '.join(changes))
PY
PLAN="$(python3 "$STAGE/plan.py" "$APP" "$BLOCK" "$STAGE" "$PUBLISH_TASK" "$PUBLISH_OUTPUT" 2>"$STAGE/plan.err")" \
  || fail "validation failed: $(tail -1 "$STAGE/plan.err" 2>/dev/null || echo 'python3 is required')"
case "$PLAN" in
  ok*) ;;
  ERROR*) fail "${PLAN#ERROR }" ;;
  *) fail "unexpected validation output: $PLAN" ;;
esac

# Write: each file is replaced by a rename from the same directory, with its
# mode kept. Nothing above this line touched the app. The two renames are not
# one atomic step; keliver.portal.json goes first, so if the second fails, a
# rerun accepts the settings it finds and adds the block.
for f in ${PLAN#ok }; do
  mv -f "$STAGE/$f" "$APP/$f"
done

echo "==> publishing wired for $APP"
echo "    keliver.portal.json  publishTask $PUBLISH_TASK, publishOutput $PUBLISH_OUTPUT"
echo "    build.gradle         signing block appended (keep it BELOW kotlin {})"
echo
echo "Next:"
echo "  keliver-portal                     # start it once; the first start creates the app's key"
echo "  curl -X POST http://localhost:8077/publish"
echo "                                     # refused, and nothing stored, unless the bundle is signed"
echo "A production host must trust the same key: keliver-new-production-host.sh copies"
echo "this app's keys/ed25519.pub into it."
