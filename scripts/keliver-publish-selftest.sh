#!/usr/bin/env bash
#
# Self-test for keliver-publish (the static-layout publisher, W3), through the
# shell wrapper the tools bundle ships and the relay install's JVM entry point.
#
#   scripts/keliver-publish-selftest.sh <work-dir> [--relay-home DIR]
#
# --relay-home: an existing portal-relay install (build/install/portal-relay, or
# a tools bundle's relay/). Without it, `./gradlew :portal-relay:installDist`
# runs in this checkout.
#
# The bundles are signed here with a throwaway Ed25519 key that exists only in
# the JVM that signs them; only its PUBLIC half is written to disk. Every store
# lookup runs with user.home inside <work-dir>, behind the repository's
# isolation guard. Nothing outside <work-dir> is written.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO="$(cd "$HERE/.." && pwd -P)"
WORK="${1:?usage: $0 <work-dir> [--relay-home DIR]}"
shift
RELAY_HOME=""
while [ $# -gt 0 ]; do
  case "$1" in
    --relay-home) RELAY_HOME="${2:?}"; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
mkdir -p "$WORK"
WORK="$(cd "$WORK" && pwd -P)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$WORK/home"
unset PORTAL_STORE KELIVER_PUBLIC_KEY_HEX KELIVER_SIGNING_KEY_FILE KELIVER_TOOLS_BIN KP
mkdir -p "$WORK/home"

pass=0; fail=0
ok(){ printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
bad(){ printf '  FAIL  %s\n' "$1"; fail=$((fail+1)); }
sha256(){ if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
# What the site holds: every path and every file's hash.
snapshot(){ ( cd "$1" 2>/dev/null && find . -print | LC_ALL=C sort | while read -r p; do
  if [ -f "$p" ]; then echo "$p $(sha256 "$p" | cut -d' ' -f1)"; else echo "$p"; fi; done ); }

# --- the tools layout: bin/ beside relay/, as in the bundle --------------------
if [ -z "$RELAY_HOME" ]; then
  ( cd "$REPO" && ./gradlew -q :portal-relay:installDist ) || { echo "installDist failed" >&2; exit 1; }
  RELAY_HOME="$REPO/portal-relay/build/install/portal-relay"
fi
[ -x "$RELAY_HOME/bin/keliver-publish-jvm" ] || { echo "no keliver-publish-jvm in $RELAY_HOME/bin" >&2; exit 1; }
TOOLS="$WORK/tools"
rm -rf "$TOOLS"; mkdir -p "$TOOLS/bin"
cp "$REPO/scripts/keliver-publish" "$REPO/scripts/keliver-store-path.sh" "$TOOLS/bin/"
ln -s "$RELAY_HOME" "$TOOLS/relay"
PUBLISH="$TOOLS/bin/keliver-publish"

# --- signed fixtures, from a JVM that keeps its private keys ------------------
cat > "$WORK/Fixture.java" <<'JAVA'
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** args: <out-dir> <title> <mode: signed|unsigned|foreign> <pub-out> ; one key per JVM run, private half never written. */
public class Fixture {
  static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
  public static void main(String[] a) throws Exception {
    Path out = Paths.get(a[0]); Files.createDirectories(out);
    KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    byte[] pub = pair.getPublic().getEncoded();
    Files.writeString(Paths.get(a[3]), hex(Arrays.copyOfRange(pub, pub.length - 32, pub.length)) + "\n");
    StringBuilder modules = new StringBuilder();
    for (String m : new String[] {"lib", "main"}) {
      byte[] code = ("// " + m + " of " + a[1] + "\n").getBytes(StandardCharsets.UTF_8);
      Files.write(out.resolve(m + ".zipline"), code);
      if (modules.length() > 0) modules.append(',');
      modules.append("\"./").append(m).append(".js\":{\"url\":\"").append(m).append(".zipline\",\"sha256\":\"")
        .append(hex(MessageDigest.getInstance("SHA-256").digest(code))).append("\",\"dependsOnIds\":[]}");
    }
    String payload = "{\"modules\":{" + modules + "},\"mainModuleId\":\"./main.js\",\"mainFunction\":\"zipline.ziplineMain\"}";
    String sigs = "{}";
    if (!a[2].equals("unsigned")) {
      Signature s = Signature.getInstance("Ed25519");
      s.initSign(pair.getPrivate());
      s.update(payload.getBytes(StandardCharsets.UTF_8));
      sigs = "{\"portal-ed25519\":\"" + hex(s.sign()) + "\"}";
    }
    Files.writeString(out.resolve("manifest.zipline.json"),
      "{\"unsigned\":{\"signatures\":" + sigs + ",\"freshAtEpochMs\":null,\"baseUrl\":null}," + payload.substring(1));
  }
}
JAVA
fixture(){ "$JAVA_HOME/bin/java" "$WORK/Fixture.java" "$@" 2>/dev/null; }
# Each signed fixture is signed by its own run's key; that run wrote the public half to <name>.pub.
fixture "$WORK/fx/one" One signed "$WORK/fx/one.pub"
fixture "$WORK/fx/unsigned" Unsigned unsigned "$WORK/fx/unsigned.pub"

# --- the app: a fake gradlew that "builds" by copying a fixture ---------------
APP="$WORK/app"
rm -rf "$APP"; mkdir -p "$APP/src/jsMain/kotlin/screens"
cat > "$APP/keliver.portal.json" <<'JSON'
{"screensDir": "src/jsMain/kotlin/screens", "publishTask": ":compileDevelopmentExecutableKotlinJsZipline",
 "publishOutput": "build/zipline/Development"}
JSON
printf '# required by every screen\nhost-sql@1\n' > "$APP/src/jsMain/kotlin/screens/capabilities.txt"
cat > "$APP/gradlew" <<'SH'
#!/bin/bash
# Records what it was asked and with which tools bin, then "builds" $FIXTURE.
echo "task=$1 toolsBin=${KELIVER_TOOLS_BIN:-}" >> "$(dirname "$0")/gradlew.calls"
[ "${FAIL_BUILD:-}" = 1 ] && exit 1
rm -rf "$(dirname "$0")/build/zipline/Development"; mkdir -p "$(dirname "$0")/build/zipline"
cp -R "$FIXTURE" "$(dirname "$0")/build/zipline/Development"
SH
chmod +x "$APP/gradlew"
SITE="$WORK/site"
rm -rf "$SITE"

echo "==> keliver-publish self-test ($RELAY_HOME)"

# 1. usage
"$PUBLISH" "$APP" --public-key-file "$WORK/fx/one.pub" > "$WORK/u.log" 2>&1; rc=$?
[ "$rc" = 2 ] && grep -q -- "--out is required" "$WORK/u.log" && ok "no --out: usage, exit 2" || bad "no --out: exit $rc"
"$PUBLISH" "$APP" --out "$SITE" --frobnicate > "$WORK/u.log" 2>&1; rc=$?
[ "$rc" = 2 ] && ok "an unknown option: exit 2" || bad "an unknown option: exit $rc"
"$PUBLISH" "$APP" "$WORK" --out "$SITE" > "$WORK/u.log" 2>&1; rc=$?
[ "$rc" = 2 ] && grep -q "one app directory only" "$WORK/u.log" && ok "two app directories: exit 2" || bad "two app directories: exit $rc"
[ ! -e "$SITE" ] && ok "usage errors wrote nothing" || bad "a usage error created $SITE"

# 2. an empty out dir is refused without --init (a CI checkout that did not download the live
#    index would otherwise republish v1 at sequence 1), before any build runs
: > "$APP/gradlew.calls"
FIXTURE="$WORK/fx/one" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" > "$WORK/p0.log" 2>&1; rc=$?
[ "$rc" = 4 ] && grep -q -- "--init" "$WORK/p0.log" && [ ! -e "$SITE" ] && [ ! -s "$APP/gradlew.calls" ] \
  && ok "no live index and no --init: refused (exit 4), nothing built or written" || { bad "no index: exit $rc"; cat "$WORK/p0.log"; }

#    publish v1, through the app's build, as the first publish ever
FIXTURE="$WORK/fx/one" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --init > "$WORK/p1.log" 2>&1; rc=$?
[ "$rc" = 0 ] && ok "v1 published: $(grep 'published v' "$WORK/p1.log")" || { bad "v1: exit $rc"; cat "$WORK/p1.log"; }
grep -qx "task=:compileDevelopmentExecutableKotlinJsZipline toolsBin=$TOOLS/bin" "$APP/gradlew.calls" \
  && ok "it ran the app's publishTask with KELIVER_TOOLS_BIN = the tools bin" || bad "build call: $(cat "$APP/gradlew.calls")"
python3 - "$SITE/bundles" > "$WORK/check1.txt" 2>&1 <<'PY'
import hashlib, json, os, sys
b = sys.argv[1]; idx = json.load(open(os.path.join(b, 'index.json')))
assert idx['format'] == 1, idx
[e] = idx['entries']
assert (e['sequence'], e['version'], e['channel'], e['widgetVersion']) == (1, 1, 'stable', 1), e
assert e['capabilities'] == ['host-sql@1'], e
assert e['manifest'] == 'v1/manifest.zipline.json', e
assert e['manifestSha256'] == hashlib.sha256(open(os.path.join(b, e['manifest']), 'rb').read()).hexdigest(), e
assert sorted(os.listdir(os.path.join(b, 'v1'))) == ['lib.zipline', 'main.zipline', 'manifest.zipline.json']
print('index ok', e['manifestSha256'][:12])
PY
[ $? = 0 ] && ok "index.json: format 1, sequence 1, capabilities from capabilities.txt, the manifest's sha256" \
  || { bad "index.json after v1"; cat "$WORK/check1.txt"; }

# 3. refusals leave the site byte-identical
BEFORE="$(snapshot "$SITE")"
refused(){  # $1 label, $2 expected text, rest: env + command
  local label="$1" want="$2"; shift 2
  env "$@" > "$WORK/r.log" 2>&1; local rc=$?
  if [ "$rc" = 4 ] && grep -q "REFUSED" "$WORK/r.log" && grep -q -- "$want" "$WORK/r.log" && [ "$(snapshot "$SITE")" = "$BEFORE" ]; then
    ok "$label: refused (exit 4), site byte-identical"
  else
    bad "$label: exit $rc, $(grep -m1 REFUSED "$WORK/r.log")"; tail -5 "$WORK/r.log"
  fi
}
fixture "$WORK/fx/foreign" Foreign signed "$WORK/fx/foreign.pub"
refused "a bundle signed by another key" "does not verify" \
  FIXTURE="$WORK/fx/foreign" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub"
refused "an unsigned bundle" "UNSIGNED" \
  FIXTURE="$WORK/fx/unsigned" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub"
cp -R "$WORK/fx/one" "$WORK/fx/tampered"; echo "x" >> "$WORK/fx/tampered/lib.zipline"
refused "a module that differs from the signed manifest" "signed manifest says" \
  FIXTURE="$WORK/fx/tampered" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub"
FAIL_BUILD=1 FIXTURE="$WORK/fx/one" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" > "$WORK/r.log" 2>&1; rc=$?
[ "$rc" = 3 ] && [ "$(snapshot "$SITE")" = "$BEFORE" ] && ok "a failed build: exit 3, site byte-identical" || bad "a failed build: exit $rc"
cp "$SITE/bundles/index.json" "$WORK/index.good"; echo "{not json" > "$SITE/bundles/index.json"
BEFORE="$(snapshot "$SITE")"
refused "an unreadable index" "not a JSON object" \
  FIXTURE="$WORK/fx/one" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub"
cp "$WORK/index.good" "$SITE/bundles/index.json"; BEFORE="$(snapshot "$SITE")"

# 4. v2: the key from the environment, a channel, --skip-build
fixture "$WORK/fx/two" Two signed "$WORK/fx/two.pub"
rm -rf "$APP/build/zipline/Development"; cp -R "$WORK/fx/two" "$APP/build/zipline/Development"
: > "$APP/gradlew.calls"
KELIVER_PUBLIC_KEY_HEX="$(cat "$WORK/fx/two.pub")" "$PUBLISH" "$APP" --out "$SITE" --skip-build --channel beta > "$WORK/p2.log" 2>&1; rc=$?
[ "$rc" = 0 ] && [ ! -s "$APP/gradlew.calls" ] && ok "v2 with KELIVER_PUBLIC_KEY_HEX and --skip-build (no build ran)" || { bad "v2: exit $rc"; cat "$WORK/p2.log"; }
python3 - "$SITE/bundles" "$WORK/index.good" > "$WORK/check2.txt" 2>&1 <<'PY'
import json, os, sys
b = sys.argv[1]; idx = json.load(open(os.path.join(b, 'index.json'))); old = json.load(open(sys.argv[2]))
assert [e['sequence'] for e in idx['entries']] == [1, 2], idx
assert idx['entries'][0] == old['entries'][0], 'the v1 entry changed'
assert idx['entries'][1]['channel'] == 'beta'
assert sorted(n for n in os.listdir(b) if not n.startswith('.')) == ['index.json', 'v1', 'v2'], os.listdir(b)
assert not [n for n in os.listdir(b) if n.startswith('.staging') or '.tmp-' in n], os.listdir(b)
print('ok')
PY
[ $? = 0 ] && ok "index.json: v1's entry unchanged, v2 at sequence 2 on channel beta, no staging left" || { bad "index after v2"; cat "$WORK/check2.txt"; }

# 5. the public key from this app's store (an isolated one)
# shellcheck source=/dev/null
. "$REPO/scripts/keliver-test-isolation-guard.sh"
if keliver_require_isolated_store "$WORK" "$APP" > "$WORK/guard.log" 2>&1; then
  ok "isolation guard: user.home and the resolved store are inside the work dir"
  STORE="$("$TOOLS/bin/keliver-store-path.sh" "$APP")" || { bad "the store resolver refused $APP"; STORE="$WORK/unresolved-store"; }
  "$PUBLISH" "$APP" --out "$SITE" --skip-build > "$WORK/s.log" 2>&1; rc=$?
  [ "$rc" = 2 ] && grep -q "no public key at" "$WORK/s.log" && ok "no key anywhere: exit 2, says so" || bad "no key: exit $rc"
  mkdir -p "$STORE/keys" && cp "$WORK/fx/two.pub" "$STORE/keys/ed25519.pub"
  "$PUBLISH" "$APP" --out "$SITE" --skip-build > "$WORK/s.log" 2>&1; rc=$?
  [ "$rc" = 0 ] && grep -q "published v3 (sequence 3" "$WORK/s.log" && ok "the store's public key is used when none is given" \
    || { bad "store key: exit $rc"; tail -5 "$WORK/s.log"; }
else
  bad "isolation guard refused"; cat "$WORK/guard.log"
fi

echo "keliver-publish self-test: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
