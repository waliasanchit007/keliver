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

/**
 * args: <out-dir> <title> <mode: signed|unsigned|foreign> <pub-out> [sequence [<dir>=<sequence>...]];
 * one key per JVM run, private half never written. Each <dir>=<sequence> gets the same bundle's
 * manifest signed again, by the same key, for that sequence (what keliverResign writes).
 */
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
    manifest(out, modules, a.length > 4 ? a[4] : null, !a[2].equals("unsigned"), pair);
    for (int i = 5; i < a.length; i++) {
      String[] dirSeq = a[i].split("=", 2);
      Path dir = Paths.get(dirSeq[0]); Files.createDirectories(dir);
      manifest(dir, modules, dirSeq[1], true, pair);
    }
  }
  static void manifest(Path dir, CharSequence modules, String sequence, boolean sign, KeyPair pair) throws Exception {
    // W4: the sequence is in the signed metadata, as the signing block writes it.
    String meta = sequence != null ? ",\"metadata\":{\"keliver.sequence\":\"" + sequence + "\"}" : "";
    String payload = "{\"modules\":{" + modules + "},\"mainModuleId\":\"./main.js\",\"mainFunction\":\"zipline.ziplineMain\"" + meta + "}";
    String sigs = "{}";
    if (sign) {
      Signature s = Signature.getInstance("Ed25519");
      s.initSign(pair.getPrivate());
      s.update(payload.getBytes(StandardCharsets.UTF_8));
      sigs = "{\"portal-ed25519\":\"" + hex(s.sign()) + "\"}";
    }
    Files.writeString(dir.resolve("manifest.zipline.json"),
      "{\"unsigned\":{\"signatures\":" + sigs + ",\"freshAtEpochMs\":null,\"baseUrl\":null}," + payload.substring(1));
  }
}
JAVA
fixture(){ "$JAVA_HOME/bin/java" "$WORK/Fixture.java" "$@" 2>/dev/null; }
# Each signed fixture is signed by its own run's key; that run wrote the public half to <name>.pub.
# one-s4 / one-s1: v1's manifest signed again by v1's key, as keliverResign would for sequence 4 (and,
# wrongly, for 1 again).
fixture "$WORK/fx/one" One signed "$WORK/fx/one.pub" 1 "$WORK/fx/one-s4=4" "$WORK/fx/one-s1=1"
fixture "$WORK/fx/unsigned" Unsigned unsigned "$WORK/fx/unsigned.pub" 1

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
# keliverResign (W4.3): records the directory it was given, then "re-signs" it by
# copying $RESIGN_FIXTURE's manifest over the copy's.
if [ "$1" = keliverResign ]; then
  echo "task=$1 $3 toolsBin=${KELIVER_TOOLS_BIN:-}" >> "$(dirname "$0")/gradlew.calls"
  dir="${2#-Pkeliver.resignDir=}"; echo "$dir" > "$(dirname "$0")/resign.dir"
  [ "${FAIL_BUILD:-}" = 1 ] && exit 1
  cp "$RESIGN_FIXTURE/manifest.zipline.json" "$dir/manifest.zipline.json"; exit 0
fi
echo "task=$1 $2 toolsBin=${KELIVER_TOOLS_BIN:-}" >> "$(dirname "$0")/gradlew.calls"
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
grep -qx "task=:compileDevelopmentExecutableKotlinJsZipline -Pkeliver.sequence=1 toolsBin=$TOOLS/bin" "$APP/gradlew.calls" \
  && ok "it ran the app's publishTask for sequence 1 (-Pkeliver.sequence), with KELIVER_TOOLS_BIN = the tools bin" \
  || bad "build call: $(cat "$APP/gradlew.calls")"
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
fixture "$WORK/fx/foreign" Foreign signed "$WORK/fx/foreign.pub" 2
refused "a bundle signed by another key" "does not verify" \
  FIXTURE="$WORK/fx/foreign" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub"
refused "an unsigned bundle" "UNSIGNED" \
  FIXTURE="$WORK/fx/unsigned" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub"
# W4: v1's own build again, now that the index is at sequence 2 (a replay of an old bundle).
refused "a bundle signed for an older sequence" "signed for sequence 1, but this publish is sequence 2" \
  FIXTURE="$WORK/fx/one" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub"
fixture "$WORK/fx/noseq" NoSeq signed "$WORK/fx/noseq.pub"
refused "a bundle with no signed sequence (a pre-W4 signing block)" "no signed keliver.sequence" \
  FIXTURE="$WORK/fx/noseq" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/noseq.pub"
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
fixture "$WORK/fx/two" Two signed "$WORK/fx/two.pub" 2
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
  fixture "$WORK/fx/three" Three signed "$WORK/fx/three.pub" 3
  rm -rf "$APP/build/zipline/Development"; cp -R "$WORK/fx/three" "$APP/build/zipline/Development"
  mkdir -p "$STORE/keys" && cp "$WORK/fx/three.pub" "$STORE/keys/ed25519.pub"
  "$PUBLISH" "$APP" --out "$SITE" --skip-build > "$WORK/s.log" 2>&1; rc=$?
  [ "$rc" = 0 ] && grep -q "published v3 (sequence 3" "$WORK/s.log" && ok "the store's public key is used when none is given" \
    || { bad "store key: exit $rc"; tail -5 "$WORK/s.log"; }
else
  bad "isolation guard refused"; cat "$WORK/guard.log"
fi

# 6. --republish (W4.3): v1's code again, as a new sequence (4), signed by keliverResign
cp "$SITE/bundles/index.json" "$WORK/index.before-republish"; BEFORE="$(snapshot "$SITE")"
usage_ok=1
for args in "--republish" "--republish x" "--republish 0" "--republish 1 --init" "--republish 1 --skip-build"; do
  # shellcheck disable=SC2086
  "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" $args > "$WORK/u.log" 2>&1; rc=$?
  # The JVM's own usage error, not the wrapper refusing an option it does not pass on.
  [ "$rc" = 2 ] && grep -q -- "--republish" "$WORK/u.log" && ! grep -q "unknown option" "$WORK/u.log" \
    || { usage_ok=0; bad "$args: exit $rc, $(head -1 "$WORK/u.log")"; }
done
[ "$usage_ok" = 1 ] && [ "$(snapshot "$SITE")" = "$BEFORE" ] \
  && ok "--republish usage errors (no number, not a number, 0, with --init, with --skip-build): exit 2, nothing written" \
  || bad "--republish usage errors: a check failed above, or the site changed"
: > "$APP/gradlew.calls"
refused "republishing a version the index has no entry for" "no entry for v9" \
  RESIGN_FIXTURE="$WORK/fx/one-s4" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --republish 9
refused "republishing v1 checked against another key" "does not verify" \
  RESIGN_FIXTURE="$WORK/fx/one-s4" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/two.pub" --republish 1
[ ! -s "$APP/gradlew.calls" ] && ok "neither refusal ran a re-sign" || bad "a refused republish ran: $(cat "$APP/gradlew.calls")"
refused "a re-sign that signs the old sequence again" "signed for sequence 1, but this publish is sequence 4" \
  RESIGN_FIXTURE="$WORK/fx/one-s1" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --republish 1
FAIL_BUILD=1 RESIGN_FIXTURE="$WORK/fx/one-s4" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --republish 1 > "$WORK/r.log" 2>&1; rc=$?
[ "$rc" = 3 ] && grep -q "the re-sign failed" "$WORK/r.log" && grep -q "keliver-new-publish-target.sh" "$WORK/r.log" \
  && [ "$(snapshot "$SITE")" = "$BEFORE" ] && ok "a failed re-sign: exit 3, names the scaffolder upgrade, site byte-identical" \
  || { bad "a failed re-sign: exit $rc"; tail -3 "$WORK/r.log"; }
: > "$APP/gradlew.calls"
mv "$APP/gradlew" "$APP/gradlew.off"
RESIGN_FIXTURE="$WORK/fx/one-s4" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --republish 1 > "$WORK/r.log" 2>&1; rc=$?
mv "$APP/gradlew.off" "$APP/gradlew"
[ "$rc" = 3 ] && grep -q "could not run" "$WORK/r.log" && [ "$(snapshot "$SITE")" = "$BEFORE" ] \
  && ok "no ./gradlew to run the re-sign: exit 3 (a failed build, not an I/O error), site byte-identical" || bad "no gradlew: exit $rc"
RESIGN_FIXTURE="$WORK/fx/one-s4" "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --republish 1 > "$WORK/rp.log" 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "republished v1 as v4 (sequence 4, channel stable)" "$WORK/rp.log" \
  && ok "v1 republished: $(grep -o 'republished v1 as v4 ([^)]*)' "$WORK/rp.log")" || { bad "republish: exit $rc"; cat "$WORK/rp.log"; }
grep -qx "task=keliverResign -Pkeliver.sequence=4 toolsBin=$TOOLS/bin" "$APP/gradlew.calls" \
  && ok "it ran the app's keliverResign for sequence 4, with KELIVER_TOOLS_BIN = the tools bin (no compile)" \
  || bad "re-sign call: $(cat "$APP/gradlew.calls")"
RDIR="$(cat "$APP/resign.dir" 2>/dev/null)"
case "$RDIR" in "$SITE"*|"") bad "the re-sign directory was '$RDIR' (inside the site, or none)";;
  *) [ ! -e "$RDIR" ] && ok "the scratch copy it re-signed is outside the site and was deleted" || bad "the scratch copy $RDIR was left";; esac
python3 - "$SITE/bundles" "$WORK/index.before-republish" > "$WORK/check6.txt" 2>&1 <<'PY'
import hashlib, json, os, sys
b = sys.argv[1]; idx = json.load(open(os.path.join(b, 'index.json'))); old = json.load(open(sys.argv[2]))
assert idx['entries'][:-1] == old['entries'], 'an earlier entry changed'
e = idx['entries'][-1]
assert (e['sequence'], e['version'], e['channel'], e['republishOf']) == (4, 4, 'stable', 1), e
assert e['capabilities'] == ['host-sql@1'], e
assert e['manifestSha256'] == hashlib.sha256(open(os.path.join(b, 'v4/manifest.zipline.json'), 'rb').read()).hexdigest(), e
for m in ('lib.zipline', 'main.zipline'):
    assert open(os.path.join(b, 'v1', m), 'rb').read() == open(os.path.join(b, 'v4', m), 'rb').read(), m
assert json.load(open(os.path.join(b, 'v4/manifest.zipline.json')))['metadata']['keliver.sequence'] == '4'
assert not [n for n in os.listdir(b) if n.startswith('.staging') or '.tmp-' in n], os.listdir(b)
print('ok')
PY
[ $? = 0 ] && ok "index.json: v4 = v1's modules byte for byte, signed for sequence 4, republishOf 1, v1's capabilities and channel" \
  || { bad "index after the republish"; cat "$WORK/check6.txt"; }

# 7. --promote (W4.4): v4 (stable, sequence 4) offered on beta too; no build, no new v<N>/
cp "$SITE/bundles/index.json" "$WORK/index.before-promote"; BEFORE="$(snapshot "$SITE")"; : > "$APP/gradlew.calls"
"$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --promote 4 > "$WORK/u.log" 2>&1; rc=$?
[ "$rc" = 2 ] && grep -q -- "needs --channel" "$WORK/u.log" && [ "$(snapshot "$SITE")" = "$BEFORE" ] \
  && ok "--promote without --channel: exit 2, nothing written" || bad "--promote without --channel: exit $rc"
refused "promoting to a channel that already has it" "already on channel stable" \
  "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --promote 4 --channel stable
refused "promoting below the channel's newest" "already take sequence 4, above 2" \
  "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/two.pub" --promote 2 --channel stable
refused "promoting a bundle checked against another key" "does not verify" \
  "$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/two.pub" --promote 4 --channel beta
"$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/one.pub" --promote 4 --channel beta > "$WORK/pr.log" 2>&1; rc=$?
[ "$rc" = 0 ] && grep -q "promoted sequence 4 (v4) from stable to beta" "$WORK/pr.log" && [ ! -s "$APP/gradlew.calls" ] \
  && ok "v4 promoted to beta: nothing built or signed" || { bad "promote: exit $rc"; cat "$WORK/pr.log"; }
python3 - "$SITE/bundles" "$WORK/index.before-promote" > "$WORK/check7.txt" 2>&1 <<'PY'
import json, os, sys
b = sys.argv[1]; idx = json.load(open(os.path.join(b, 'index.json'))); old = json.load(open(sys.argv[2]))
assert idx['entries'][:-1] == old['entries'], 'an earlier entry changed'
src, e = old['entries'][-1], idx['entries'][-1]
assert (e['sequence'], e['version'], e['channel'], e['promotedFrom']) == (4, 4, 'beta', 'stable'), e
assert all(e[k] == src[k] for k in ('manifest', 'manifestSha256', 'capabilities', 'widgetVersion')), e
assert sorted(n for n in os.listdir(b) if not n.startswith('.')) == ['index.json', 'v1', 'v2', 'v3', 'v4'], os.listdir(b)
print('ok')
PY
[ $? = 0 ] && ok "index.json: a second entry for v4 on beta, the same manifest and sha256, no new v<N>/" \
  || { bad "index after the promotion"; cat "$WORK/check7.txt"; }

# 8. constraints (W4.5): a rollout and a host-version gate set on publish, then the rollout
#    raised with --set-rollout, which needs no key at all (none given, none in the env)
fixture "$WORK/fx/five" Five signed "$WORK/fx/five.pub" 5
rm -rf "$APP/build/zipline/Development"; cp -R "$WORK/fx/five" "$APP/build/zipline/Development"
"$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/five.pub" --skip-build --rollout 101 > "$WORK/u.log" 2>&1; rc=$?
[ "$rc" = 2 ] && grep -q "0 to 100" "$WORK/u.log" && ok "--rollout 101: exit 2" || bad "--rollout 101: exit $rc"
"$PUBLISH" "$APP" --out "$SITE" --public-key-file "$WORK/fx/five.pub" --skip-build --rollout 0 --min-host-version 3 > "$WORK/c5.log" 2>&1; rc=$?
python3 - "$SITE/bundles/index.json" > "$WORK/check8.txt" 2>&1 <<'PY'
import json, sys
e = json.load(open(sys.argv[1]))['entries'][-1]
assert (e['sequence'], e['constraints']) == (5, {'rollout': 0, 'minHostVersion': 3}), e
print('ok')
PY
py=$?
[ "$rc" = 0 ] && [ "$py" = 0 ] && ok "v5 published at rollout 0 with minHostVersion 3 (index constraints)" || { bad "v5 with constraints: exit $rc"; cat "$WORK/check8.txt" "$WORK/c5.log"; }
STORE_PUB_HIDDEN=""; [ -n "${STORE:-}" ] && [ -f "$STORE/keys/ed25519.pub" ] && { mv "$STORE/keys/ed25519.pub" "$WORK/store.pub.hidden"; STORE_PUB_HIDDEN=1; }
env -u KELIVER_PUBLIC_KEY_HEX "$PUBLISH" "$APP" --out "$SITE" --set-rollout 5 --rollout 100 > "$WORK/sr.log" 2>&1; rc=$?
[ -n "$STORE_PUB_HIDDEN" ] && mv "$WORK/store.pub.hidden" "$STORE/keys/ed25519.pub"
python3 -c 'import json,sys; e=json.load(open(sys.argv[1]))["entries"][-1]; sys.exit(0 if e["constraints"]=={"rollout":100,"minHostVersion":3} else 1)' "$SITE/bundles/index.json"; py=$?
[ "$rc" = 0 ] && [ "$py" = 0 ] && grep -q "rollout 0 -> 100%" "$WORK/sr.log" \
  && ok "--set-rollout 5 --rollout 100 with no key anywhere: the rollout raised, the gate kept" || { bad "--set-rollout: exit $rc"; cat "$WORK/sr.log"; }

echo "keliver-publish self-test: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
