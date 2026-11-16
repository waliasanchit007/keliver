#!/bin/bash
# Builds the QuickJS command-line engines measured in docs/GUEST_PROFILE.md ("The engine, measured").
# Usage: build-engines.sh <work dir>
# Downloads Bellard's QuickJS 2021-03-27 (the version Zipline 1.28 vendors) and 2026-06-04 into
# <work dir>, applies the two local patches next to this script, and builds `qjs` at several
# optimisation levels, each in its own directory under <work dir>/build. Nothing is installed.
set -euo pipefail
work="${1:?usage: build-engines.sh <work dir>}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$work/dl" "$work/src" "$work/build"
sha256_of() { case "$1" in
  2021-03-27) echo a45bface4c3379538dea8533878d694e289330488ea7028b105f72572fe7fe1a ;;
  2026-06-04) echo b376e839b322978313d929fd20663b11ba58b75df5a46c126dd19ea2fa70ad2a ;;
esac; }
for v in 2021-03-27 2026-06-04; do
  [ -f "$work/dl/quickjs-$v.tar.xz" ] || curl -fsS -m 300 -o "$work/dl/quickjs-$v.tar.xz" "https://bellard.org/quickjs/quickjs-$v.tar.xz"
  # The tarballs measured in docs/GUEST_PROFILE.md; refuse anything else.
  got="$(shasum -a 256 "$work/dl/quickjs-$v.tar.xz" | awk '{print $1}')"
  [ "$got" = "$(sha256_of "$v")" ] || { echo "quickjs-$v.tar.xz: unexpected SHA-256 $got" >&2; exit 1; }
  rm -rf "${work:?}/src/quickjs-$v"
  tar -xJf "$work/dl/quickjs-$v.tar.xz" -C "$work/src"
done
# 2021: the qjs front end does not compile with CONFIG_BIGNUM off (Zipline builds the engine without it).
patch -d "$work/src/quickjs-2021-03-27" -p1 < "$here/patches/quickjs-2021-03-27-qjs-no-bignum.patch"
# 2026: BigInt.asUintN(64, negative) returns the negative value; Kotlin's Long-as-BigInt `ushr` needs 2^64 + a.
rm -rf "${work:?}/src/quickjs-2026-06-04-fix"
cp -R "$work/src/quickjs-2026-06-04" "$work/src/quickjs-2026-06-04-fix"
patch -d "$work/src/quickjs-2026-06-04-fix" -p1 < "$here/patches/quickjs-2026-06-04-asuintn64.patch"
# build-engines-android.sh stops here: it needs the verified, patched sources and cross-compiles them itself.
[ "${KELIVER_ENGINE_SOURCES_ONLY:-}" = 1 ] && { echo "sources ready in $work/src"; exit 0; }
build() { # <name> <source dir suffix> <CONFIG_LTO: y or empty> <optimisation flags>
  rm -rf "${work:?}/build/$1"
  cp -R "$work/src/quickjs-$2" "$work/build/$1"
  (cd "$work/build/$1" && make -j8 qjs CONFIG_LTO="$3" CONFIG_BIGNUM= CFLAGS_OPT="\$(CFLAGS) $4" > build.log 2>&1)
  echo "built $1"
}
build q21-Os 2021-03-27 "" "-Os"
build q21-O2 2021-03-27 "" "-O2"
build q21-O3 2021-03-27 "" "-O3"
build q21-O3lto 2021-03-27 y "-O3 -flto"
build q26-Os 2026-06-04 "" "-Os"
build q26-O2 2026-06-04 "" "-O2"
build q26-O3 2026-06-04 "" "-O3"
build q26-O3lto 2026-06-04 y "-O3 -flto"
build q26fix-Os 2026-06-04-fix "" "-Os"
build q26fix-O2 2026-06-04-fix "" "-O2"
