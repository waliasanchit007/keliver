#!/usr/bin/env bash
#
# keliver-new-publish-target-selftest — the publish scaffolder refuses bad input
# without writing anything, and otherwise wires an app whose bundles are signed.
#
#   scripts/keliver-new-publish-target-selftest.sh <disposable-parent> [--build]
#
# --build also compiles the scaffolded app's bundle against Maven Central, in a
# disposable Gradle home and user.home, four ways:
#   signed        tools bin set: the manifest verifies against the store's key,
#                 and the private key is in no --info log, no process argument
#                 (no Zipline `--sign`) and no .gradle/ execution history (U31)
#   no tools bin  the bundle is UNSIGNED, with a warning (and /publish refuses it)
#   block moved   the signing block above kotlin {}: still signed (the block no
#                 longer depends on its position)
#   resolver error  a resolver that exits non-zero fails the build
# Without --build it also checks the upgrade of an app wired by tools 0.3.6:
# exactly the old block is replaced in place; an edited one is refused.
# The relay half (POST /publish stores the signed bundle and refuses the
# unsigned one) is in keliver-adopter-acceptance.sh, which runs the packaged
# portal.
#
# Uses fixture apps and a fixture STORE (PORTAL_STORE, inside the run
# directory); the JVM's user.home is inside the run directory too, behind
# keliver_require_isolated_store. --build generates a disposable Ed25519 key
# pair there; the private key is never printed.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# KELIVER_PUBLISH_TARGET_SCAFFOLD runs a PACKAGED copy instead (bin/ of an
# unpacked tools bundle), which finds its template at bin/../templates.
SCAFFOLD="${KELIVER_PUBLISH_TARGET_SCAFFOLD:-$ROOT/scripts/keliver-new-publish-target.sh}"
INIT="$ROOT/scripts/keliver-init"
DEV="$ROOT/scripts/keliver-new-device-target.sh"
. "$ROOT/scripts/keliver-test-isolation-guard.sh"
DISP="$(keliver_make_run_dir "${1:?usage: $0 <disposable-parent> [--build]}" publish-target)" || exit $?
BUILD=0; [ "${2:-}" = "--build" ] && BUILD=1
pass=0; fail=0
echo "scaffolder: $SCAFFOLD"
ok()  { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
snapshot() { ( cd "$1" && find . -type f ! -path './.gradle/*' ! -path './build/*' -print0 | sort -z | xargs -0 shasum -a 256 ) 2>/dev/null; }

[ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ] && export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
STORE="$DISP/store"; mkdir -p "$STORE/keys" "$DISP/home"
export JAVA_TOOL_OPTIONS="-Duser.home=$DISP/home" PORTAL_STORE="$STORE"
unset KELIVER_TOOLS_BIN KP KELIVER_USE_MAVEN_LOCAL

# <dir> [device]: a keliver-init app, with the device target when asked.
make_app() {
  ( cd "$DISP" && "$INIT" Demo "$1" >/dev/null 2>&1 ) || { echo "keliver-init failed for $1" >&2; exit 2; }
  if [ "${2:-}" = device ]; then
    ( cd "$DISP/$1" && "$DEV" >/dev/null 2>&1 ) || { echo "device target failed for $1" >&2; exit 2; }
  fi
  keliver_require_isolated_store "$DISP" "$DISP/$1" > /dev/null || { echo "isolation guard refused" >&2; exit 2; }
}

refuses() { # <label> <expected message fragment> <app dir>
  local label="$1" want="$2" app="$3" before after out rc
  before="$(snapshot "$app")"
  out="$( cd "$app" && "$SCAFFOLD" 2>&1 )"; rc=$?
  after="$(snapshot "$app")"
  if [ "$rc" != 0 ] && printf '%s' "$out" | grep -qF -- "$want" && [ "$before" = "$after" ] \
     && ! ls -d "$app"/.keliver-publish-target.* >/dev/null 2>&1; then
    ok "refuses $label, and the app is byte-identical"
  else
    bad "$label: rc=$rc, changed=$([ "$before" = "$after" ] && echo no || echo YES), output: $(printf '%s' "$out" | head -2 | tr '\n' ' ')"
  fi
}

echo "=== refusals"
make_app bare
refuses "an app without the device target" "Run keliver-new-device-target.sh first" "$DISP/bare"
make_app commented
printf "\n// id 'app.cash.zipline'\n// binaries.executable()\n" >> "$DISP/commented/build.gradle"
refuses "a Zipline plugin that is only in a comment" "does not apply the Zipline plugin" "$DISP/commented"
make_app nomain device
rm "$DISP/nomain/src/jsMain/kotlin/device/Main.kt"
refuses "an app without the bundle entry point" "no src/jsMain/kotlin/device/Main.kt" "$DISP/nomain"
make_app signed device
printf '\ntasks.withType(app.cash.zipline.gradle.ZiplineCompileTask).configureEach { signingKeys.set([]) }\n' >> "$DISP/signed/build.gradle"
refuses "an app that already configures signingKeys" "already configures signingKeys" "$DISP/signed"
make_app othertask device
python3 - "$DISP/othertask/keliver.portal.json" <<'PY'
import json, sys
p = sys.argv[1]; c = json.load(open(p)); c['publishTask'] = ':other:compile'
open(p, 'w').write(json.dumps(c, indent=2) + '\n')
PY
refuses "a different publishTask already set" 'already sets "publishTask": ":other:compile"' "$DISP/othertask"
make_app badjson device
printf '{ "screensDir": ' > "$DISP/badjson/keliver.portal.json"
refuses "an invalid keliver.portal.json" "is not valid JSON" "$DISP/badjson"
make_app kts device
mv "$DISP/kts/build.gradle" "$DISP/kts/build.gradle.kts"
refuses "an app without a Groovy build.gradle" "no build.gradle here" "$DISP/kts"
mkdir -p "$DISP/empty"
refuses "a directory that is not an app" "no keliver.portal.json here" "$DISP/empty"

echo "=== a device-target app is wired"
make_app good device
APP="$DISP/good"
chmod 640 "$APP/build.gradle"
cp "$APP/keliver.portal.json" "$DISP/portal.json.before"
out="$( cd "$APP" && "$SCAFFOLD" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && ok "scaffolds (exit 0)" || bad "scaffold failed: rc=$rc $(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
python3 - "$DISP/portal.json.before" "$APP/keliver.portal.json" <<'PY' && ok "keliver.portal.json gains publishTask/publishOutput and keeps every other setting" || bad "keliver.portal.json is wrong"
import json, sys
before, after = (json.load(open(p)) for p in sys.argv[1:3])
assert after.pop('publishTask') == ':compileDevelopmentExecutableKotlinJsZipline'
assert after.pop('publishOutput') == 'build/zipline/Development'
assert after == before, (after, before)
PY
python3 - "$APP/build.gradle" <<'PY' && ok "the signing block is appended once, after the kotlin {} block" || bad "the signing block is missing, doubled or above kotlin {}"
import re, sys
s = open(sys.argv[1]).read()
assert s.count('keliver: PUBLISH SIGNING') == 1
assert s.count('ManifestSigner.Builder()') == 1
assert 'signingKeys.set(' not in s, 'the key must never be a Zipline signingKeys input (U31)'
k = re.search(r'(?m)^kotlin\s*\{', s).start()
assert s.index('keliver: PUBLISH SIGNING') > k
PY
[ "$(stat -c '%a' "$APP/build.gradle" 2>/dev/null || stat -f '%Lp' "$APP/build.gradle")" = 640 ] \
  && ok "build.gradle keeps its mode (640)" || bad "build.gradle's mode changed"
ls -d "$APP"/.keliver-publish-target.* >/dev/null 2>&1 && bad "a staging directory was left behind" || ok "no staging directory left behind"
grep -rqs 'ed25519.priv' "$APP/keliver.portal.json" && bad "keliver.portal.json names the private key" || ok "keliver.portal.json names no key"
refuses "a second run" "publishing is already wired" "$APP"

echo "=== settings already equal to what it writes are accepted"
make_app same device
python3 - "$DISP/same/keliver.portal.json" <<'PY'
import json, sys
p = sys.argv[1]; c = json.load(open(p))
c['publishTask'] = ':compileDevelopmentExecutableKotlinJsZipline'; c['publishOutput'] = 'build/zipline/Development'
open(p, 'w').write(json.dumps(c, indent=2) + '\n')
PY
( cd "$DISP/same" && "$SCAFFOLD" > /dev/null 2>&1 ) && grep -q 'keliver: PUBLISH SIGNING' "$DISP/same/build.gradle" \
  && ok "matching publish settings: only the signing block is added" || bad "matching publish settings were refused"

echo "=== an app wired by tools 0.3.6 is upgraded in place (U31)"
LEGACY="$ROOT/scripts/templates/publish/legacy/signing-0.3.6.gradle"
make_app legacy device
( cd "$DISP/legacy" && "$SCAFFOLD" > /dev/null 2>&1 )   # wire it, then put the 0.3.6 block back
python3 - "$DISP/legacy/build.gradle" "$LEGACY" <<'PY'
import sys
p, legacy = sys.argv[1], open(sys.argv[2]).read()
s = open(p).read()
i = s.index('// ---------------------------------------------------------------------------\n// keliver: PUBLISH SIGNING')
open(p, 'w').write(s[:i] + legacy + '\n// a line the adopter added after the block\n')
PY
cp "$DISP/legacy/build.gradle" "$DISP/legacy.before"
out="$( cd "$DISP/legacy" && "$SCAFFOLD" 2>&1 )"; rc=$?
[ "$rc" = 0 ] && printf '%s' "$out" | grep -q 'was replaced by the current one (U31)' \
  && printf '%s' "$out" | grep -q 'keep .*keliver-store-path' \
  && ok "the 0.3.6 signing block is recognised and replaced, with the U31 advice (keep the store pointer)" \
  || bad "upgrade: rc=$rc $(printf '%s' "$out" | head -2 | tr '\n' ' ')"
python3 - "$DISP/legacy.before" "$DISP/legacy/build.gradle" "$LEGACY" "$ROOT/scripts/templates/publish/signing.gradle" <<'PY' \
  && ok "the upgrade swaps exactly the old block; every other line is kept" || bad "the upgrade changed more than the block"
import sys
before, after, legacy, new = (open(p).read() for p in sys.argv[1:5])
assert legacy not in after and after.count(new) == 1
assert after == before.replace(legacy, new)
PY
refuses "a run after the upgrade" "publishing is already wired" "$DISP/legacy"
make_app edited device
( cd "$DISP/edited" && "$SCAFFOLD" > /dev/null 2>&1 )
python3 - "$DISP/edited/build.gradle" "$LEGACY" <<'PY'
import sys
p, legacy = sys.argv[1], open(sys.argv[2]).read()
s = open(p).read()
i = s.index('// ---------------------------------------------------------------------------\n// keliver: PUBLISH SIGNING')
open(p, 'w').write(s[:i] + legacy.replace("'portal-ed25519'", "'portal-ed25519' /* mine */"))
PY
refuses "an edited 0.3.6 block" "is not one this script wrote" "$DISP/edited"
# The upgrade swaps the block only: an app on another publish variant keeps it.
make_app prodvariant device
( cd "$DISP/prodvariant" && "$SCAFFOLD" > /dev/null 2>&1 )
python3 - "$DISP/prodvariant" "$LEGACY" <<'PY'
import json, sys
app, legacy = sys.argv[1], open(sys.argv[2]).read()
p = app + '/build.gradle'; s = open(p).read()
i = s.index('// ---------------------------------------------------------------------------\n// keliver: PUBLISH SIGNING')
open(p, 'w').write(s[:i] + legacy.rstrip('\n'))   # also: the file's final newline stripped
c = json.load(open(app + '/keliver.portal.json'))
c['publishTask'] = ':compileProductionExecutableKotlinJsZipline'; c['publishOutput'] = 'build/zipline/Production'
open(app + '/keliver.portal.json', 'w').write(json.dumps(c, indent=2) + '\n')
PY
cp "$DISP/prodvariant/keliver.portal.json" "$DISP/prodvariant.json.before"
( cd "$DISP/prodvariant" && "$SCAFFOLD" > "$DISP/prodvariant.log" 2>&1 ) \
  && cmp -s "$DISP/prodvariant.json.before" "$DISP/prodvariant/keliver.portal.json" \
  && grep -q 'ManifestSigner.Builder()' "$DISP/prodvariant/build.gradle" \
  && ok "an upgrade keeps a Production publishTask untouched, and accepts a block missing its final newline" \
  || bad "upgrade with a Production publishTask: $(head -2 "$DISP/prodvariant.log" | tr '\n' ' ')"
make_app ownkeys device
( cd "$DISP/ownkeys" && "$SCAFFOLD" > /dev/null 2>&1 )
python3 - "$DISP/ownkeys/build.gradle" "$LEGACY" <<'PY'
import sys
p, legacy = sys.argv[1], open(sys.argv[2]).read()
s = open(p).read()
i = s.index('// ---------------------------------------------------------------------------\n// keliver: PUBLISH SIGNING')
open(p, 'w').write(s[:i] + "zipline { signingKeys { } }\n" + legacy)
PY
refuses "an upgrade when the app also configures signingKeys itself" "already configures signingKeys" "$DISP/ownkeys"

echo "=== comment markers inside strings are strings"
make_app strings device
python3 - "$DISP/strings/build.gradle" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
s = s.replace('kotlin {', "def glob = '**/*.js'\ndef site = 'https://example.com/x'\n\nkotlin {", 1)
s += '\n/* a real comment */\n'
open(p, 'w').write(s)
PY
( cd "$DISP/strings" && "$SCAFFOLD" > "$DISP/strings.log" 2>&1 ) && grep -q 'keliver: PUBLISH SIGNING' "$DISP/strings/build.gradle" \
  && ok "a '**/*.js' glob and a URL in strings do not hide the kotlin {} block" \
  || bad "strings were read as comments: $(head -1 "$DISP/strings.log")"

echo "=== through a symlink, as an installed command"
make_app linked device
ln -s "$SCAFFOLD" "$DISP/keliver-new-publish-target"
( cd "$DISP/linked" && "$DISP/keliver-new-publish-target" > /dev/null 2>&1 ) && grep -q 'keliver: PUBLISH SIGNING' "$DISP/linked/build.gradle" \
  && ok "a symlinked invocation finds the template" || bad "a symlinked invocation failed"

if [ "$BUILD" = 1 ]; then
  echo "=== the scaffolded app's bundle is signed — and the ways it is not"
  [ -n "${JAVA_HOME:-}" ] || { bad "JAVA_HOME is not set"; }
  export GRADLE_USER_HOME="$DISP/gradle-home"
  mkdir -p "$GRADLE_USER_HOME"
  grep -E '^systemProp\.javax\.net\.ssl\.trustStore' "$HOME/.gradle/gradle.properties" > "$GRADLE_USER_HOME/gradle.properties" 2>/dev/null || true
  T="$DISP/tools"; mkdir -p "$T"
  cat > "$T/KeyGen.java" <<'JAVA'
import java.nio.file.*; import java.security.*; import java.util.HexFormat;
// Writes a raw Ed25519 pair as hex, the way the relay stores it. Prints nothing.
public class KeyGen { public static void main(String[] a) throws Exception {
  KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
  byte[] priv = kp.getPrivate().getEncoded(), pub = kp.getPublic().getEncoded();
  Files.writeString(Path.of(a[0], "ed25519.priv"), HexFormat.of().formatHex(priv, priv.length - 32, priv.length));
  Files.writeString(Path.of(a[0], "ed25519.pub"), HexFormat.of().formatHex(pub, pub.length - 32, pub.length)); } }
JAVA
  cat > "$T/Verify.java" <<'JAVA'
import java.nio.file.*; import java.security.*; import java.security.spec.*; import java.util.HexFormat;
// Verify.java <payload file> <public key hex> <signature hex>: prints true or false.
public class Verify { public static void main(String[] a) throws Exception {
  byte[] der = HexFormat.of().parseHex("302a300506032b6570032100" + a[1].trim());
  PublicKey k = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
  Signature s = Signature.getInstance("Ed25519"); s.initVerify(k); s.update(Files.readAllBytes(Path.of(a[0])));
  System.out.println(s.verify(HexFormat.of().parseHex(a[2].trim()))); } }
JAVA
  ( umask 077; "$JAVA_HOME/bin/java" "$T/KeyGen.java" "$STORE/keys" ) || bad "could not generate the disposable key pair"
  PUB="$(tr -d '[:space:]' < "$STORE/keys/ed25519.pub")"
  MANIFEST="$APP/build/zipline/Development/manifest.zipline.json"

  compile() { # <log> [env assignments...]: compile the bundle at --info; returns gradle's exit
    local log="$1"; shift
    rm -f "$MANIFEST"   # so a stale manifest can never answer for this compile
    ( cd "$APP" && env "$@" ./gradlew --console=plain --info compileDevelopmentExecutableKotlinJsZipline > "$log" 2>&1 )
  }
  # U31: "absent" when the private key (hex or raw) is in none of the files given.
  key_in() {
    python3 - "$STORE/keys/ed25519.priv" "$@" <<'PY'
import sys
hexk = open(sys.argv[1]).read().strip().lower()
raw = bytes.fromhex(hexk)
hits = [p for p in sys.argv[2:] if (lambda b: raw in b or hexk.encode() in b.lower())(open(p, 'rb').read())]
print('absent' if not hits else 'PRESENT in ' + ' '.join(hits))
PY
  }
  # Prints "signed:<true|false>" or "unsigned", from the manifest as built.
  signature_of() {
    [ -f "$MANIFEST" ] || { echo missing; return; }
    python3 - "$MANIFEST" "$T/payload.bin" <<'PY' | {
import json, sys
m = json.load(open(sys.argv[1]))
sig = m.get('unsigned', {}).get('signatures', {}).get('portal-ed25519')
del m['unsigned']
open(sys.argv[2], 'w', encoding='utf-8').write(json.dumps(m, separators=(',', ':'), ensure_ascii=False))
print(sig or '')
PY
      read -r sig
      if [ -z "$sig" ]; then echo unsigned; else echo "signed:$("$JAVA_HOME/bin/java" "$T/Verify.java" "$T/payload.bin" "$PUB" "$sig")"; fi
    }
  }

  compile "$DISP/build-signed.log" KELIVER_TOOLS_BIN="$ROOT/scripts"; rc=$?
  if [ "$rc" = 0 ] && [ -f "$MANIFEST" ]; then
    got="$(signature_of)"
    [ "$got" = "signed:true" ] && ok "with the tools bin: the bundle is signed and verifies against the store's public key" \
      || bad "with the tools bin: expected a verifying signature, got $got"
    # Positive control first: the --info log must show Zipline's compiler command
    # line at all, or the absence of --sign in it would prove nothing.
    if ! grep -q 'compile --input' "$DISP/build-signed.log"; then bad "U31: the --info log has no Zipline compile command line to check"
    elif grep -q -- '--sign' "$DISP/build-signed.log"; then bad "U31: the --info log shows a Zipline --sign argument"
    else ok "U31: Zipline's compile command line is in the --info log, with no --sign argument"; fi
    HIST="$(find "$APP/.gradle" -name executionHistory.bin 2>/dev/null | head -1)"
    if [ -n "$HIST" ]; then
      got="$(key_in "$DISP/build-signed.log" "$HIST")"
      [ "$got" = absent ] && ok "U31: the private key is in neither the --info log nor .gradle/'s execution history" \
        || bad "U31: the private key is $got"
    else
      bad "U31: no executionHistory.bin to check under $APP/.gradle"
    fi
  else
    bad "the signed compile failed (rc=$rc): $(grep -E '^e: |What went wrong' -A2 "$DISP/build-signed.log" | head -4 | tr '\n' ' ')"
  fi

  compile "$DISP/build-nobin.log"; rc=$?
  [ "$rc" = 0 ] && [ "$(signature_of)" = unsigned ] && grep -q 'so this bundle is UNSIGNED' "$DISP/build-nobin.log" \
    && ok "without a tools bin: the bundle compiles UNSIGNED and the build says so" \
    || bad "without a tools bin: rc=$rc, signature $(signature_of)"

  cp "$APP/build.gradle" "$DISP/build.gradle.scaffolded"
  python3 - "$APP/build.gradle" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
i = s.index('\n// ---------------------------------------------------------------------------\n// keliver: PUBLISH SIGNING')
block, rest = s[i:], s[:i]
k = re.search(r'(?m)^kotlin\s*\{', rest).start()
open(p, 'w').write(rest[:k] + block.lstrip('\n') + '\n' + rest[k:])
PY
  compile "$DISP/build-moved.log" KELIVER_TOOLS_BIN="$ROOT/scripts"; rc=$?
  [ "$rc" = 0 ] && [ "$(signature_of)" = signed:true ] \
    && ok "the block moved above kotlin {}: the bundle is still signed (position no longer matters)" \
    || bad "the block moved above kotlin {}: rc=$rc, signature $(signature_of)"
  cp "$DISP/build.gradle.scaffolded" "$APP/build.gradle"

  # A malformed key fails the build, and the failure does not quote the key file.
  cp "$STORE/keys/ed25519.priv" "$DISP/priv.saved"
  ( umask 077; head -c 63 "$DISP/priv.saved" > "$STORE/keys/ed25519.priv" )
  compile "$DISP/build-badkey.log" KELIVER_TOOLS_BIN="$ROOT/scripts"; rc=$?
  bad63="$(cat "$STORE/keys/ed25519.priv")"
  if [ "$rc" != 0 ] && grep -q 'is not a 64-hex-digit Ed25519 private key' "$DISP/build-badkey.log" \
     && ! grep -qF -- "$bad63" "$DISP/build-badkey.log"; then
    ok "a malformed key fails the build without its content in the log"
  else
    bad "a malformed key: rc=$rc, or its content is in the log"
  fi
  cp "$DISP/priv.saved" "$STORE/keys/ed25519.priv"; rm -f "$DISP/priv.saved"; unset bad63

  mkdir -p "$DISP/brokenbin"
  printf '#!/bin/sh\necho "split identity" >&2\nexit 3\n' > "$DISP/brokenbin/keliver-store-path.sh"
  chmod +x "$DISP/brokenbin/keliver-store-path.sh"
  compile "$DISP/build-resolver.log" KELIVER_TOOLS_BIN="$DISP/brokenbin"; rc=$?
  [ "$rc" != 0 ] && grep -q 'keliver-store-path.sh exited 3: split identity' "$DISP/build-resolver.log" \
    && ok "a resolver that exits non-zero fails the build" || bad "a failing resolver: rc=$rc"

  ( cd "$APP" && ./gradlew --stop > /dev/null 2>&1 )
fi

if [ -s "$STORE/keys/ed25519.priv" ]; then
  grep -qF -f "$STORE/keys/ed25519.priv" "$DISP"/*/build.gradle "$DISP"/*/keliver.portal.json \
    && bad "the private key is in a scaffolded file" || ok "the private key is in no scaffolded file"
fi

echo
echo "publish-target scaffold: $pass passed, $fail failed   ($DISP)"
[ "$fail" -eq 0 ]
