#!/usr/bin/env bash
#
# keliver new-production-host — give a keliver-init app its own PRODUCTION
# Android host: a standalone Gradle build in <app>/host-android that renders your
# keliver-material screens from signed bundles, verified against your portal's
# public key, using only published dependencies (Maven Central).
#
#   keliver-new-production-host.sh --bundle-server URL
#                                  [--api-base-url URL]
#                                  [--application-id ID]
#                                  [--public-key-file PATH]
#                                  [--channel NAME]
#   keliver-new-production-host.sh --embed --into DIR [--module NAME]
#                                  --bundle-server URL [--api-base-url URL]
#                                  [--public-key-file PATH] [--channel NAME]
#
# Run from the APP repo root (the directory holding keliver.portal.json).
#
#   --bundle-server    where published bundles are served: the portal relay
#                      (http://<host>:8077) or anything serving its /bundles API.
#                      http:// allows cleartext traffic; use https:// for real.
#   --api-base-url     optional: the base URL guests' HostHttp requests go to.
#                      Without it the host provides no HostHttp.
#   --application-id   default: <your app package>.host
#   --channel          default: stable. The release channel this host takes from
#                      bundles/index.json besides stable (W4.4), e.g. beta for
#                      testers: lower-case letters, digits and '-'.
#   --embed            W2: write the host as a LIBRARY module into an EXISTING
#                      Android app instead: DIR/NAME (default keliver-host),
#                      with KeliverHost, KeliverScreen and KeliverView, the
#                      same Kotlin as the standalone host. DIR is that app's
#                      root (it has a settings.gradle). Nothing of the existing
#                      app is edited: this prints the lines to add. Your app
#                      supplies the plugins: Kotlin 2.2.0 with its Compose
#                      plugin and app.cash.zipline 1.22.0.
#   --public-key-file  default: keys/ed25519.pub in this app's portal store,
#                      found by keliver-store-path.sh. The key is copied into
#                      the module (it is public and belongs in git), so building
#                      the host needs no store.
#
#                      A private key has the same format as a public one, so a
#                      file cannot prove which it is. This refuses a file whose
#                      real path (links resolved) ends in .priv, and — whenever
#                      this app's store resolves — a key that is not that
#                      store's ed25519.pub. Without a store it cannot tell: pass
#                      only your ed25519.pub. Nothing here opens ed25519.priv.
#
# The host is production-ONLY: it verifies every bundle's signature and has no
# development path — the tools bundle's generic development host is for that,
# and it keeps refusing production. Build with
#   ./gradlew -p host-android assembleDebug
# assembleRelease needs https:// servers and YOUR signing config in
# host-android/build.gradle before the APK will install.
#
# Version overrides (normally unset): KELIVER_HOST_KELIVER_VERSION (default: the
# app's appRuntime.keliverVersion, else 0.4.1), KELIVER_HOST_ZIPLINE_VERSION,
# KELIVER_HOST_KOTLIN_VERSION, KELIVER_HOST_COMPOSE_VERSION, KELIVER_HOST_AGP_VERSION.
#
# --embed prints the module's dependency floor (K3) beside the versions the
# existing app declares, before writing anything: from
# templates/production-host-embed/dependency-floor.json, which the tools bundle
# carries; KELIVER_EMBED_FLOOR names another file, or "none" to skip it.
#
# ALL-OR-NOTHING: everything is validated and staged before anything is written.
# A rejected input leaves the app byte-identical.
set -euo pipefail

# Prefixed on purpose: KOTLIN_VERSION and friends are common on CI images, and
# an unrelated one silently changed the generated build.
ZIPLINE_VERSION="${KELIVER_HOST_ZIPLINE_VERSION:-1.22.0}"
KOTLIN_VERSION="${KELIVER_HOST_KOTLIN_VERSION:-2.2.0}"
COMPOSE_VERSION="${KELIVER_HOST_COMPOSE_VERSION:-1.8.2}"
AGP_VERSION="${KELIVER_HOST_AGP_VERSION:-8.12.0}"
# Resolve a symlinked invocation to the real script, so the templates are found.
SELF="${BASH_SOURCE[0]}"
while [ -L "$SELF" ]; do
  link="$(readlink "$SELF")"; case "$link" in /*) SELF="$link" ;; *) SELF="$(dirname "$SELF")/$link" ;; esac
done
HERE="$(cd "$(dirname "$SELF")" && pwd -P)"

BUNDLE_SERVER=""; API_BASE_URL=""; APPLICATION_ID=""; KEY_FILE=""; CHANNEL="stable"
EMBED=false; INTO=""; MODULE="keliver-host"
need() { [ "$2" -ge 2 ] || { echo "$1 needs a value" >&2; exit 2; }; }
while [ $# -gt 0 ]; do
  case "$1" in
    --bundle-server)   need "$1" $#; BUNDLE_SERVER="$2"; shift 2 ;;
    --api-base-url)    need "$1" $#; API_BASE_URL="$2"; shift 2 ;;
    --application-id)  need "$1" $#; APPLICATION_ID="$2"; shift 2 ;;
    --public-key-file) need "$1" $#; KEY_FILE="$2"; shift 2 ;;
    --channel)         need "$1" $#; CHANNEL="$2"; shift 2 ;;
    --embed)           EMBED=true; shift ;;
    --into)            need "$1" $#; INTO="$2"; shift 2 ;;
    --module)          need "$1" $#; MODULE="$2"; shift 2 ;;
    -h|--help)         sed -n '2,65p' "$SELF"; exit 0 ;;
    *)                 echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

fail() { echo "keliver-new-production-host: $*" >&2; echo "Nothing was written." >&2; exit 1; }

APP="$(pwd -P)"
[ -f "$APP/keliver.portal.json" ] || fail "no keliver.portal.json here — run from the app root."
if $EMBED; then
  [ -n "$INTO" ] || fail "--embed needs --into <the existing Android app's root>."
  [ -z "$APPLICATION_ID" ] || fail "--application-id is for the standalone host; an embedded host is a library in YOUR app."
  [[ "$MODULE" =~ ^[A-Za-z][A-Za-z0-9_-]{0,63}$ ]] || fail "--module must be a plain module name (got '$MODULE')."
  [ -d "$INTO" ] || fail "--into $INTO is not a directory."
  INTO="$(CDPATH= cd -- "$INTO" && pwd -P)"
  [ -f "$INTO/settings.gradle" ] || [ -f "$INTO/settings.gradle.kts" ] \
    || fail "$INTO has no settings.gradle(.kts): --into must be the existing app's Gradle root."
  TARGET="$INTO/$MODULE"
else
  [ -z "$INTO" ] || fail "--into is only for --embed."
  TARGET="$APP/host-android"
fi
[ -e "$TARGET" ] && fail "$TARGET already exists — refusing to overwrite."

# The templates sit beside this script in the repository (scripts/templates) and
# one level up in the tools bundle (templates/).
TEMPLATES=""
for t in "$HERE/templates/production-host" "$HERE/../templates/production-host"; do
  [ -f "$t/build.gradle" ] && { TEMPLATES="$(cd "$t" && pwd -P)"; break; }
done
[ -n "$TEMPLATES" ] || fail "the production-host templates are missing (looked in $HERE/templates and $HERE/../templates)."
EMBED_TEMPLATES="$(dirname "$TEMPLATES")/production-host-embed"
if $EMBED && [ ! -f "$EMBED_TEMPLATES/build.gradle" ]; then
  fail "the embed templates are missing ($EMBED_TEMPLATES)."
fi

# ASCII URL characters only (gradle.properties is ISO-8859-1 with escapes); the
# bundle server takes no query or fragment, since the host appends a path.
SERVER_RE='^https?://[][A-Za-z0-9._~:/@!$&()*+,;=%-]+$'
API_RE='^https?://[][A-Za-z0-9._~:/?#@!$&()*+,;=%-]+$'
[ -n "$BUNDLE_SERVER" ] || fail "--bundle-server is required (e.g. http://10.0.2.2:8077 for an emulator reaching this machine's relay)."
[[ "$BUNDLE_SERVER" =~ $SERVER_RE ]] || fail "--bundle-server must be an http:// or https:// URL of plain ASCII, without a query (got '$BUNDLE_SERVER')."
[ -z "$API_BASE_URL" ] || [[ "$API_BASE_URL" =~ $API_RE ]] || fail "--api-base-url must be an http:// or https:// URL of plain ASCII (got '$API_BASE_URL')."
[[ "$CHANNEL" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || fail "--channel must be lower-case letters, digits and '-', at most 32 (got '$CHANNEL')."
VERSION_RE='^[0-9A-Za-z.+-]+$'
for v in "$ZIPLINE_VERSION" "$KOTLIN_VERSION" "$COMPOSE_VERSION" "$AGP_VERSION"; do
  [[ "$v" =~ $VERSION_RE ]] || fail "a KELIVER_HOST_*_VERSION override is not a version: '$v'."
done

# The app's package: every screen declares <root>.screens (the keliver-init
# layout, the same rule keliver-new-device-target.sh uses).
ROOT_PKG="$(python3 - "$APP" <<'PY'
import glob, os, re, sys
app = sys.argv[1]
pkgs = set()
for f in glob.glob(os.path.join(app, 'src/jsMain/kotlin/screens/*.kt')):
    m = re.search(r'^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$', open(f, encoding='utf-8').read(), re.M)
    if m: pkgs.add(m.group(1))
if len(pkgs) != 1 or not next(iter(pkgs)).endswith('.screens'):
    print("ERROR screens under src/jsMain/kotlin/screens/ must all declare one '<package>.screens' (found: %s)" % (sorted(pkgs) or 'none'))
    raise SystemExit(3)
print(next(iter(pkgs))[: -len('.screens')])
PY
)" || fail "${ROOT_PKG#ERROR }"
PACKAGE="$ROOT_PKG.host"
# The Keliver version the GUEST uses, so host and guest do not drift apart.
KELIVER_VERSION="${KELIVER_HOST_KELIVER_VERSION:-$(python3 -c "import json,sys
try:
    v = json.load(open(sys.argv[1]))['appRuntime']['keliverVersion']
    print(v if isinstance(v, str) and v.strip() else '0.4.1')
except Exception: print('0.4.1')" "$APP/keliver.portal.json")}"
[[ "$KELIVER_VERSION" =~ $VERSION_RE ]] || fail "the Keliver version '$KELIVER_VERSION' is not a version."
[ -n "$APPLICATION_ID" ] || APPLICATION_ID="$PACKAGE"
ID_RE='^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$'
[[ "$APPLICATION_ID" =~ $ID_RE ]] || fail "--application-id must look like com.example.app (got '$APPLICATION_ID')."
NAME=""
for f in "$APP/settings.gradle" "$APP/settings.gradle.kts"; do
  [ -f "$f" ] || continue
  NAME="$( { sed -n "s/^[[:space:]]*rootProject\.name[[:space:]]*=[[:space:]]*['\"]\([^'\"]*\)['\"].*/\1/p" "$f" || true; } | head -1)"
  [ -n "$NAME" ] && break
done
[ -n "$NAME" ] || NAME="$(basename "$APP")"
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || NAME="app"

# The public key: an explicit file, or this app's store's ed25519.pub.
STORE_PUB=""; RESOLVE_WHY=""; RESOLVE_ERR="$(mktemp "${TMPDIR:-/tmp}/keliver-resolve.XXXXXX")"
RESOLVE="$HERE/keliver-store-path.sh"
STORE=""
if [ -x "$RESOLVE" ]; then
  # Status checked: a refusal or an error leaves STORE empty, never a guess.
  STORE="$("$RESOLVE" "$APP" 2>"$RESOLVE_ERR")" || STORE=""
fi
case "$STORE" in
  /*) [ -f "$STORE/keys/ed25519.pub" ] && STORE_PUB="$STORE/keys/ed25519.pub"
      [ -n "$STORE_PUB" ] || RESOLVE_WHY="the store ($STORE) has no keys/ed25519.pub yet" ;;
  *)  RESOLVE_WHY="the store did not resolve: $(tr '\n' ' ' < "$RESOLVE_ERR" 2>/dev/null | cut -c1-300)" ;;
esac
rm -f "$RESOLVE_ERR"
if [ -z "$KEY_FILE" ]; then
  [ -n "$STORE_PUB" ] || fail "no public key from this app's portal store ($RESOLVE_WHY). Start this app's portal once (keliver-portal) so it creates its signing identity, or pass --public-key-file."
  KEY_FILE="$STORE_PUB"
fi
[ -f "$KEY_FILE" ] || fail "no such file: $KEY_FILE"
REAL_KEY="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$KEY_FILE")"
case "$(basename "$KEY_FILE")|$(basename "$REAL_KEY")" in
  *.priv\|*|*\|*.priv) fail "refusing a private key ($KEY_FILE -> $REAL_KEY); the host needs the PUBLIC key." ;;
esac
KEY_HEX="$(tr -d '[:space:]' < "$KEY_FILE")"
[[ "$KEY_HEX" =~ ^[0-9a-fA-F]{64}$ ]] || fail "$KEY_FILE is not a 64-hex-digit Ed25519 public key."
KEY_ORIGIN="$KEY_FILE"
if [ -n "$STORE_PUB" ]; then
  if [ "$KEY_HEX" != "$(tr -d '[:space:]' < "$STORE_PUB")" ]; then
    fail "$KEY_FILE is not this app's public key ($STORE_PUB differs). A host must trust the key this app's portal signs with — and a file that is not ed25519.pub may be a private key under another name."
  fi
  KEY_ORIGIN="$KEY_FILE (matches this app's store)"
else
  echo "note: $KEY_FILE could not be checked against this app's portal store ($RESOLVE_WHY)." >&2
  echo "      A private key looks exactly like a public one; make sure this is ed25519.pub." >&2
  KEY_ORIGIN="$KEY_FILE (NOT checked against a store)"
fi

# K3: what the embedded host makes the existing app depend on, beside what that
# app declares, printed BEFORE anything is written. Gradle resolves one version
# per module, so an app that declares an older one gets the floor instead.
# The tools bundle carries templates/production-host-embed/dependency-floor.json
# (build-portal-tools.sh writes it with keliver-embed-floor.sh);
# KELIVER_EMBED_FLOOR names another file, or "none".
if $EMBED; then
  FLOOR="${KELIVER_EMBED_FLOOR:-$EMBED_TEMPLATES/dependency-floor.json}"
  if [ "$FLOOR" = none ]; then
    :
  elif [ -f "$FLOOR" ]; then
    # Exit 3: the floor file itself is unreadable (refused: nothing written).
    # Anything else going wrong in the report is a warning; the scaffold goes on.
    FLOOR_RC=0
    python3 - "$FLOOR" "$INTO" "$KELIVER_VERSION" "$KOTLIN_VERSION" "$ZIPLINE_VERSION" "$COMPOSE_VERSION" <<'PY' || FLOOR_RC=$?
import json, os, re, sys
floor_path, into, want_keliver, want_kotlin, want_zipline, want_compose = sys.argv[1:7]
try:
    floor = json.load(open(floor_path, encoding='utf-8'))
    for r in floor['floor']:
        r['name'], r['module'], r['version'], list(r['declaredAs'])
    floor['plugins']['kotlin'], floor['plugins']['zipline'], floor['minSdk'], floor['compileSdk']
except Exception as e:
    print('%s: %s' % (type(e).__name__, e), file=sys.stderr)
    sys.exit(3)

KOTLIN_IDS = ('org.jetbrains.kotlin.android', 'org.jetbrains.kotlin.jvm', 'org.jetbrains.kotlin.multiplatform')
VER = r"""['"]([0-9][\w.+-]*)['"]"""
SKIPPED = []

def strip_comments(t):
    # One left-to-right pass: a // or /* inside a string ('META-INF/*', "a//b",
    # "https://") is not a comment, and whichever opens first wins.
    out, i, n, quote = [], 0, len(t), None
    while i < n:
        c = t[i]
        if quote in ('"""', "'''"):  # a triple-quoted string may span lines
            j = t.find(quote, i)
            j = n if j < 0 else j + 3
            out.append(t[i:j]); i = j; quote = None
        elif quote:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(t[i + 1]); i += 2; continue
            if c == quote or c == '\n':  # build-file strings are one line; a stray quote ends there
                quote = None
            i += 1
        elif t.startswith('"""', i) or t.startswith("'''", i):
            quote = t[i:i + 3]; out.append(quote); i += 3
        elif c in '"\'':
            quote = c; out.append(c); i += 1
        elif t.startswith('//', i):
            j = t.find('\n', i); i = n if j < 0 else j
        elif t.startswith('/*', i):
            j = t.find('*/', i + 2); i = n if j < 0 else j + 2
            out.append(' ')
        else:
            out.append(c); i += 1
    return ''.join(out)

def read_build_files():
    # The existing app's build files, at most four directories deep (nested
    # modules, build-logic/), never build output.
    texts = {}
    for d, dirs, files in os.walk(into):
        rel_d = os.path.relpath(d, into)
        depth = 0 if rel_d == '.' else rel_d.count(os.sep) + 1
        dirs[:] = sorted(x for x in dirs if not x.startswith('.') and x not in ('build', 'node_modules') and depth < 4)
        for f in sorted(files):
            if f in ('settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts') \
               or (f == 'gradle.properties' and depth == 0) \
               or (f.endswith('.versions.toml') and os.path.basename(d) == 'gradle'):
                rel = os.path.normpath(os.path.join(rel_d, f))
                try:
                    t = open(os.path.join(d, f), encoding='utf-8', errors='replace').read()
                except OSError:
                    SKIPPED.append(rel)
                    continue
                texts[rel] = t if f.endswith(('.toml', '.properties')) else strip_comments(t)
    return texts

def report():
    texts = read_build_files()
    # Plain version variables: gradle.properties; val/var/def, ext.x, ext { x = },
    # set(...), extra[...] and `by extra(...)` of a quoted version.
    props = {}
    for rel, t in texts.items():
        if rel == 'gradle.properties':
            for m in re.finditer(r'^\s*([A-Za-z_][\w.]*)\s*[=:]\s*([0-9][\w.+-]*)\s*$', t, re.M):
                props[m.group(1)] = m.group(2)
        elif not rel.endswith('.toml'):
            for pat in (r"""(?:\b(?:val|var|def)\s+|ext\.|\bset\(\s*|extra\[\s*)['"]?([A-Za-z_]\w*)['"]?\s*\]?\s*(?::\s*String\s*)?[=,]\s*""" + VER,
                        r"""\bval\s+([A-Za-z_]\w*)\s*(?::\s*String\s*)?by\s+extra\(\s*""" + VER):
                for m in re.finditer(pat, t, re.M):
                    props.setdefault(m.group(1), m.group(2))
            # `name = 'ver'` alone only inside an ext { } block (elsewhere it is
            # a named argument or another block's property).
            for e in re.finditer(r'\bext\s*\{', t):
                depth, k = 1, e.end()
                while k < len(t) and depth:
                    depth += {'{': 1, '}': -1}.get(t[k], 0); k += 1
                for m in re.finditer(r"""^\s*([A-Za-z_]\w*)\s*=\s*""" + VER, t[e.end():k], re.M):
                    props.setdefault(m.group(1), m.group(2))

    def resolve(v):
        if '$' not in v:
            return v
        m = re.fullmatch(r'\$\{?([^}]*)\}?', v)
        words = re.findall(r'[A-Za-z_]\w*', m.group(1)) if m else []
        return props.get(words[-1]) if words else None

    declared = {}  # 'group:name' -> [(version or None, where, strict)]
    def note(coord, version, where, strict=False):
        declared.setdefault(coord, []).append((version, where, strict))
    kotlin_plugin = []
    for rel, t in texts.items():
        if rel.endswith('.toml'):
            section, versions, entries = None, {}, []
            for line in t.splitlines():
                line = line.split('#', 1)[0].strip()
                s = re.fullmatch(r'\[([\w.-]+)\]', line)
                if s:
                    section = s.group(1); continue
                m = re.fullmatch(r'([\w.-]+)\s*=\s*(.+)', line)
                if not m:
                    continue
                key, val = m.group(1), m.group(2)
                if section == 'versions':
                    q = re.fullmatch(r"""["']([^"']+)["']""", val)
                    st = re.search(r"""strictly\s*=\s*["']([^"']+)["']""", val)
                    rq = re.search(r"""(?:require|prefer)\s*=\s*["']([^"']+)["']""", val)
                    if q: versions[key] = (q.group(1), False)
                    elif st: versions[key] = (st.group(1), True)
                    elif rq: versions[key] = (rq.group(1), False)
                elif section in ('libraries', 'plugins'):
                    entries.append((section, val))
            for section, val in entries:
                q = re.fullmatch(r"""["']([\w.-]+):([\w.-]+)(?::([^"']+))?["']""", val)
                if q and section == 'libraries':
                    v = q.group(3)
                    note(q.group(1) + ':' + q.group(2), v[:-2] if v and v.endswith('!!') else v, rel, bool(v and v.endswith('!!')))
                    continue
                if section == 'plugins':
                    sq = re.fullmatch(r"""["']([\w.-]+):([^"']+)["']""", val)
                    if sq:
                        if sq.group(1) in KOTLIN_IDS:
                            kotlin_plugin.append((sq.group(2), rel, False))
                        continue
                g = lambda k: (re.search(r'\b' + k + r"""\s*=\s*["']([^"']+)["']""", val) or [None, None])[1]
                ref = g(r'version\.ref')
                if ref:
                    ver, strict = versions.get(ref, (None, False))
                else:
                    ver, strict = g('version'), False
                    st = re.search(r"""strictly\s*=\s*["']([^"']+)["']""", val)
                    if st:
                        ver, strict = st.group(1), True
                if section == 'libraries':
                    mod = g('module') or (g('group') and g('name') and g('group') + ':' + g('name'))
                    if mod:
                        note(mod, ver, rel, strict)
                elif g('id') in KOTLIN_IDS and ver:
                    kotlin_plugin.append((ver, rel, False))
            continue
        # force(a, b) and Groovy's force a, b (to the end of the statement).
        forced = [(f.start(), f.end()) for f in re.finditer(r'\bforce\s*(?:\(([^()]*)\)|[ \t]+[^\n(;}]*)', t)]
        for m in re.finditer(r"""['"]([\w.-]+):([\w.-]+)(?::(\$\{[^}]*\}|[^'"\s@]+))?(?:@\w+)?['"]""", t):
            grp, name, v = m.group(1), m.group(2), m.group(3)
            if not re.fullmatch(r'[a-z][\w-]*(\.[\w-]+)+', grp):
                continue
            before = t[max(0, m.start() - 40):m.start()]
            strict = any(a <= m.start() < b for a, b in forced) \
                or bool(re.search(r'\b(?:force|enforcedPlatform)\s*\(?\s*$', before)) \
                or bool(re.match(r'\s*\)?\s*\{[^}]*\b(?:force|isForce)\s*=\s*true', t[m.end():m.end() + 120]))
            if v is None:
                st = re.match(r"""\s*\)?\s*\{[^}]*?strictly\s*\(?\s*['"]([^'"]+)['"]""", t[m.end():m.end() + 200])
                if not st:
                    note(grp + ':' + name, None, rel)
                    continue
                v, strict = st.group(1), True
            if v.endswith('!!'):
                v, strict = v[:-2], True
            if grp == 'org.jetbrains.kotlin' and name == 'kotlin-gradle-plugin':
                rv = resolve(v)
                if rv:
                    kotlin_plugin.append((rv, rel, False))
            else:
                note(grp + ':' + name, resolve(v), rel, strict)
        for m in re.finditer(r"""\bgroup\s*[:=]\s*['"]([\w.-]+)['"]\s*,\s*name\s*[:=]\s*['"]([\w.-]+)['"]\s*,\s*version\s*[:=]\s*['"]([^'"]+)['"]""", t):
            note(m.group(1) + ':' + m.group(2), resolve(m.group(3)), rel)
        for m in re.finditer(r"""(?:id\s*\(?\s*['"]org\.jetbrains\.kotlin\.(?:android|jvm|multiplatform)['"]\s*\)?|kotlin\(\s*"(?:android|jvm|multiplatform)"\s*\))\s*version\s*\(?\s*['"]([^'"]+)['"]""", t):
            rv = resolve(m.group(1))
            if rv:
                kotlin_plugin.append((rv, rel, False))

    # A BOM versions the modules of its own group with its own version, except
    # the Compose BOM, whose versions are dates.
    boms = [(c, v, w, st) for c, lst in declared.items() if re.search(r'-bom(?:-\w+)?$', c.split(':')[1]) for v, w, st in lst]

    def key(v):
        m = re.match(r'(\d+(?:\.\d+)*)(.*)', v)
        if not m:
            return ([0, 0, 0, 0], 0, 0, 0)
        nums = [int(x) for x in m.group(1).split('.')]
        pre = m.group(2).lstrip('-.').lower()
        if not pre:
            return (nums + [0] * (4 - len(nums)), 1, 0, 0)
        q = re.match(r'(dev|snapshot|alpha|a|beta|b|m|milestone|rc|cr)?\D*(\d*)', pre)
        rank = {'dev': 0, 'snapshot': 0, 'alpha': 1, 'a': 1, 'beta': 2, 'b': 2, 'm': 2, 'milestone': 2, 'rc': 3, 'cr': 3}.get(q.group(1), 0)
        return (nums + [0] * (4 - len(nums)), 0, rank, int(q.group(2) or 0))
    def dynamic(v):
        return bool(re.search(r'[\[\](),+]|latest\.', v))

    # An older Kotlin plugin decides it, whatever a BOM or a stdlib line says.
    old_kotlin = [x for x in kotlin_plugin if key(x[0]) < key(floor['plugins']['kotlin'])]
    rows, raised, pinned, blocking = [], 0, 0, 0
    for r in floor['floor']:
        fv = r['version']
        found = [x for c in r['declaredAs'] for x in declared.get(c, []) if x[0]]
        groups = {c.split(':')[0] for c in r['declaredAs']}
        via_compose_bom = None
        if not found:
            for c, v, w, strict_bom in boms:
                bg, bn = c.split(':')
                if 'compose-bom' in bn and any(gr.startswith('androidx.compose.') for gr in groups):
                    via_compose_bom = (v, w)
                elif v and any(m.split(':')[0] == bg and m.split(':')[1].startswith(bn[:-len('-bom')])
                               for m in r['declaredAs']):
                    found.append((v, '%s, %s' % (bn, w), strict_bom))
        plugin_row = False
        if r['module'] == 'org.jetbrains.kotlin:kotlin-stdlib' and kotlin_plugin and (not found or old_kotlin):
            found, plugin_row = [(v, w + ', the Kotlin plugin', s) for v, w, s in (old_kotlin or kotlin_plugin)], True
        if not found:
            if via_compose_bom:
                rows.append((r['name'], fv, 'Compose BOM %s (%s)' % (via_compose_bom[0] or '?', via_compose_bom[1]),
                             "check this BOM's Compose version"))
            else:
                unread = [declared[c][0][1] for c in r['declaredAs'] if declared.get(c)]
                rows.append((r['name'], fv, 'a version this cannot read (%s)' % unread[0] if unread else '-',
                             'check it' if unread else ''))
            continue
        fixed = [x for x in found if not dynamic(x[0])]
        if not fixed:
            rows.append((r['name'], fv, '%s%s (%s)' % (found[0][0], ', strict' if found[0][2] else '', found[0][1]),
                         'a range or dynamic version: check it'))
            continue
        v, w, strict = min(fixed, key=lambda x: key(x[0]))
        shown = '%s%s (%s)' % (v, ', strict' if strict else '', w)
        if key(v) >= key(fv):
            rows.append((r['name'], fv, shown, 'ok'))
        elif plugin_row:
            blocking += 1
            rows.append((r['name'], fv, shown, 'the module needs Kotlin %s: it will not compile' % floor['plugins']['kotlin']))
        elif strict:
            pinned += 1
            rows.append((r['name'], fv, shown, 'PINNED below the floor: Gradle fails, or keeps %s' % v))
        else:
            raised += 1
            major = ', a new major version' if key(v)[0][0] != key(fv)[0][0] else ''
            rows.append((r['name'], fv, shown, 'RAISED to %s%s' % (fv, major)))

    p = floor['plugins']
    print('dependency floor of the embedded host (keliver %s, its %s):' % (floor['keliverVersion'], floor['configuration']))
    print('  %-22s %-10s %-50s %s' % ('library', 'floor', 'this app declares', ''))
    for name, fv, dv, verdict in rows:
        print('  %-22s %-10s %-50s %s' % (name, fv, dv, verdict))
    print('  the module needs: Kotlin %s with its Compose plugin, app.cash.zipline %s, compileSdk %s or newer, minSdk %s or newer (built with AGP %s)'
          % (p['kotlin'], p['zipline'], floor['compileSdk'], floor['minSdk'], p['androidGradlePlugin']))
    for rel, t in texts.items():
        for m in re.finditer(r'\bminSdk(?:Version)?\s*(?:=|\(|\s)\s*(\d+)', t):
            if int(m.group(1)) < floor['minSdk']:
                print("  %s: minSdk %s is below the module's %s: the manifest merge will fail" % (rel, m.group(1), floor['minSdk']))
    if blocking:
        print('  This app builds with an older Kotlin: the module will not compile in it until the app moves to Kotlin %s.' % p['kotlin'])
    if pinned:
        print('  %d strict or forced version(s) below the floor: lift them first, or Gradle fails or keeps a version the module was not built with.' % pinned)
    if raised:
        print("  %d of this app's declared versions will be RAISED when it adds the module: build and test the app with them." % raised)
    for rel in SKIPPED:
        print('  (could not read %s: skipped)' % rel)
    print("  '-': not declared in the build files this reads (it may come from a BOM, a plugin or a convention plugin).")
    built_for = (floor['keliverVersion'], p['kotlin'], p['zipline'], floor.get('composeVersion', '?'))
    writing = (want_keliver, want_kotlin, want_zipline, want_compose)
    if built_for != writing:
        print('  note: this floor was resolved for keliver %s, Kotlin %s, Zipline %s, Compose %s; this module is written for %s, %s, %s, %s.'
              % (built_for + writing))
        print('        Its real floor may differ: scripts/keliver-embed-floor.sh in a Keliver checkout resolves it.')
    print('  The full resolved list: %s' % floor_path)

try:
    report()
except Exception as e:
    print('warning: the dependency-floor report stopped (%s: %s); the floor is in %s.' % (type(e).__name__, e, floor_path))
PY
    [ "$FLOOR_RC" = 3 ] && fail "could not read the dependency floor ($FLOOR)."
    [ "$FLOOR_RC" = 0 ] || echo "warning: the dependency-floor report failed (exit $FLOOR_RC); the floor is in $FLOOR." >&2
  else
    echo "note: no dependency-floor report in this copy of the templates ($FLOOR); the tools bundle carries one, and scripts/keliver-embed-floor.sh writes it." >&2
  fi
fi

# --- stage everything, then move it into place in one step -----------------
for v in "$NAME" "$PACKAGE" "$APPLICATION_ID" "$BUNDLE_SERVER" "$API_BASE_URL"; do
  case "$v" in *@@*) fail "a value contains '@@', which the templates use as placeholders: '$v'." ;; esac
done
STAGE="$(mktemp -d "$(dirname "$TARGET")/.$(basename "$TARGET").XXXXXX")"
chmod 755 "$STAGE"
trap 'rm -rf "$STAGE"' EXIT
PKG_PATH="${PACKAGE//.//}"
subst() { # <src> <dst>
  mkdir -p "$(dirname "$2")"
  python3 - "$1" "$2" "$NAME" "$PACKAGE" "$APPLICATION_ID" "$BUNDLE_SERVER" "$API_BASE_URL" \
    "$KELIVER_VERSION" "$ZIPLINE_VERSION" "$KOTLIN_VERSION" "$COMPOSE_VERSION" "$AGP_VERSION" "$CHANNEL" <<'PY'
import sys
src, dst, name, pkg, appid, server, api, kv, zv, ktv, cv, agp, channel = sys.argv[1:14]
s = open(src, encoding='utf-8').read()
for k, v in {'NAME': name, 'PACKAGE': pkg, 'APPLICATION_ID': appid, 'BUNDLE_SERVER': server,
             'API_BASE_URL': api, 'KELIVER_VERSION': kv, 'ZIPLINE_VERSION': zv,
             'KOTLIN_VERSION': ktv, 'COMPOSE_VERSION': cv, 'AGP_VERSION': agp, 'CHANNEL': channel}.items():
    s = s.replace('@@' + k + '@@', v)
assert '@@' not in s, 'unsubstituted placeholder in ' + src
open(dst, 'w', encoding='utf-8').write(s)
PY
}
if $EMBED; then
  for f in build.gradle keliver.properties consumer-rules.pro src/main/AndroidManifest.xml; do
    subst "$EMBED_TEMPLATES/$f" "$STAGE/$f"
  done
else
  for f in settings.gradle gradle.properties build.gradle proguard-rules.pro src/main/AndroidManifest.xml; do
    subst "$TEMPLATES/$f" "$STAGE/$f"
  done
fi
# Timing marks (M0) and the guest frame profile (M4): the real ones for debug and benchmark, a
# no-op for every other build type.
for set in timing notiming; do
  for f in "$TEMPLATES"/src/$set/kotlin/*.kt; do
    subst "$f" "$STAGE/src/$set/kotlin/$PKG_PATH/$(basename "$f")"
  done
done
# The same Kotlin in both modes; only the app shell (its Application and its
# activity) stays out of the library.
for f in "$TEMPLATES"/src/main/kotlin/*.kt; do
  if $EMBED; then case "$(basename "$f")" in HostApp.kt|MainActivity.kt) continue ;; esac; fi
  subst "$f" "$STAGE/src/main/kotlin/$PKG_PATH/$(basename "$f")"
done
# The host kit's JVM tests, in the standalone host (its build declares their dependencies).
if ! $EMBED; then
  for f in "$TEMPLATES"/src/test/kotlin/*.kt; do
    subst "$f" "$STAGE/src/test/kotlin/$PKG_PATH/$(basename "$f")"
  done
  # The frame profile's window exists only in the debug and benchmark builds (src/timing).
  for f in "$TEMPLATES"/src/testDebug/kotlin/*.kt; do
    subst "$f" "$STAGE/src/testDebug/kotlin/$PKG_PATH/$(basename "$f")"
  done
fi
mkdir -p "$STAGE/src/main/assets/keliver"
printf '%s\n' "$KEY_HEX" > "$STAGE/src/main/assets/keliver/portal_ed25519.pub"
cat > "$STAGE/.gitignore" <<'EOF'
/build/
/.gradle/
/local.properties
EOF
# mkdir is atomic: if the target appeared since the check above, this fails
# instead of moving the stage INSIDE it.
mkdir "$TARGET" 2>/dev/null || fail "$TARGET appeared while scaffolding — refusing to overwrite."
( cd "$STAGE" && tar cf - . ) | ( cd "$TARGET" && tar xf - ) \
  || { rm -rf "$TARGET"; fail "could not write $TARGET."; }
rm -rf "$STAGE"
trap - EXIT

if $EMBED; then
  # What the existing app must supply; warned, not refused (its build may declare
  # them in ways this cannot see).
  found(){ grep -rqsE "$1" "$INTO"/settings.gradle* "$INTO"/build.gradle* "$INTO"/gradle/libs.versions.toml 2>/dev/null; }
  found 'app\.cash\.zipline' || echo "warning: no app.cash.zipline plugin found in $INTO's settings, build or version catalog: the library needs it (1.22.0 for Kotlin 2.2.0)." >&2
  found 'kotlin\.plugin\.compose|plugin-compose|compose-compiler' || echo "warning: no Kotlin Compose compiler plugin found in $INTO's build: the library needs org.jetbrains.kotlin.plugin.compose." >&2
  found '2\.2\.0([^0-9]|$)' || echo "warning: no Kotlin 2.2.0 found in $INTO's build: Zipline 1.22.0's compiler plugin needs Kotlin 2.2.0." >&2
  echo "created $MODULE/ in $INTO — this app's Keliver host, as a library module"
  echo "  package          $PACKAGE"
  echo "  bundle server    $BUNDLE_SERVER"
  echo "  channel          $CHANNEL$([ "$CHANNEL" = stable ] || echo ' (and stable)')"
  echo "  HostHttp         ${API_BASE_URL:-not provided (no --api-base-url)}"
  echo "  trusts the key   ${KEY_HEX:0:8}… from $KEY_ORIGIN"
  echo "                   commit $MODULE/src/main/assets/keliver/portal_ed25519.pub"
  echo "next, in YOUR app (nothing of it was changed):"
  echo "  settings.gradle:      include ':$MODULE'"
  echo "  app/build.gradle:     implementation project(':$MODULE')"
  echo "  your Application:     val keliver by lazy { $PACKAGE.KeliverHost.create(this) }   (one per process)"
  echo "  a Compose screen:     $PACKAGE.KeliverScreen(keliver, Modifier.fillMaxSize())"
  echo "  or a View layout:     $PACKAGE.KeliverView(context).apply { host = keliver }"
  [ -z "$API_BASE_URL" ] || echo "  HostHttp, your client: $PACKAGE.KeliverHost.create(this, $PACKAGE.KeliverConfig.fromBuild(this, requestPolicy = $PACKAGE.RequestPolicy(...), httpClient = yourOkHttpClient))"
  echo "  settings: $MODULE/keliver.properties (or -Pkeliver.bundleServer=...); an http:// server needs YOUR app's cleartext permission in debug"
  exit 0
fi

echo "created host-android/ — this app's production Android host"
echo "  package          $PACKAGE"
echo "  application id   $APPLICATION_ID"
echo "  bundle server    $BUNDLE_SERVER"
echo "  channel          $CHANNEL$([ "$CHANNEL" = stable ] || echo ' (and stable)')"
echo "  HostHttp         ${API_BASE_URL:-not provided (no --api-base-url)}"
echo "  trusts the key   ${KEY_HEX:0:8}… from $KEY_ORIGIN"
echo "                   commit host-android/src/main/assets/keliver/portal_ed25519.pub"
echo "next:"
echo "  ./gradlew -p host-android assembleDebug     (needs an Android SDK: ANDROID_HOME, or host-android/local.properties)"
echo "  a release build needs https:// servers and your own signing config in host-android/build.gradle"
echo "  publish a bundle (the relay's POST /publish), then install and launch the host."
