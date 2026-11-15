#!/usr/bin/env bash
# start-lines.sh's helpers, replayed against log lines in the hosts' own formats (KeliverHost.kt and
# MainViewController.kt), under both start policies, with the cases each must refuse. Run by
# ci/device.sh and ci/ios.sh before any device step: a helper that passed something it should not
# would hide a failed check. Prints one line; exit 0 when every case holds.
#   bash ci/start-lines-test.sh
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/start-lines.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
pass=0; fail=0; failed=""
case_ok(){ pass=$((pass+1)); }
case_bad(){ fail=$((fail+1)); failed="$failed; $1"; }
U4=https://10.0.2.2:8443/bundles/v4/manifest.zipline.json
U5=https://10.0.2.2:8443/bundles/v5/manifest.zipline.json
SRC4="(index sequence 4, channel stable (host: stable), host version 1, manifest sha256 0123456789ab…)"

# Network-first (a host from before the start policy, and a parallel start whose lookup named another release).
cat > "$T/nf" <<EOF
11-15 03:22:12.000 1 1 D KeliverHost: loading $U4 $SRC4; rollback floor 4
11-15 03:22:12.137 1 2 D KeliverHost: codeLoadSuccess modules=42 sequence=4 source=network
EOF
took_release "$T/nf" "$U4" 4 && case_ok || case_bad "network-first took v4"
took_release "$T/nf" "$U4" 4 ", channel stable" && case_ok || case_bad "network-first took v4 on stable"
took_release "$T/nf" "$U5" 5 && case_bad "network-first: v5 was not named" || case_ok
took_release_floor "$T/nf" "$U4" 4 4 && case_ok || case_bad "network-first floor 4"
took_release_floor "$T/nf" "$U4" 4 5 && case_bad "network-first: the floor was not 5" || case_ok
[ "$(lookups_in "$T/nf")" = 1 ] && case_ok || case_bad "network-first: one lookup"

# Parallel, the lookup confirming the cached release.
cat > "$T/par" <<EOF
11-15 03:22:11.000 1 1 I KeliverHost: start policy: parallel (the default); this start: parallel, the cached release loads while the lookup runs; it shows once the lookup confirms it or fails
11-15 03:22:11.001 1 1 D KeliverHost: starting from the cached bundle (last loaded from $U4); rollback floor 4
11-15 03:22:12.137 1 2 D KeliverHost: codeLoadSuccess modules=42 sequence=4 source=cache
11-15 03:22:12.199 1 1 I KeliverHost: start policy: parallel: the lookup confirms the cached release: $U4 $SRC4
EOF
took_release "$T/par" "$U4" 4 && case_ok || case_bad "parallel took v4"
took_release "$T/par" "$U4" 4 ", channel stable" && case_ok || case_bad "parallel took v4 on stable"
took_release "$T/par" "$U4" 4 ", channel beta" && case_bad "parallel: not beta" || case_ok
took_release "$T/par" "$U5" 5 && case_bad "parallel: v5 was not named" || case_ok
took_release_floor "$T/par" "$U4" 4 4 && case_ok || case_bad "parallel floor 4"
took_release_floor "$T/par" "$U4" 4 9 && case_bad "parallel: the floor was not 9" || case_ok
[ "$(lookups_in "$T/par")" = 1 ] && case_ok || case_bad "parallel: one lookup"
# Something else loaded too, or the cache held another sequence than the one named: not taken.
{ cat "$T/par"; echo "11-15 03:22:13.000 1 2 D KeliverHost: codeLoadSuccess modules=42 sequence=5 source=network"; } > "$T/par-more"
took_release "$T/par-more" "$U4" 4 && case_bad "parallel: another load besides the confirmed cache" || case_ok
sed 's/sequence=4 source=cache/sequence=3 source=cache/' "$T/par" > "$T/par-other"
took_release "$T/par-other" "$U4" 4 && case_bad "parallel: the cache ran sequence 3" || case_ok
# No confirmation (the line 8ad28f1da's hosts logged): not taken.
grep -v "confirms" "$T/par" > "$T/par-none"
took_release "$T/par-none" "$U4" 4 && case_bad "parallel: nothing confirmed" || case_ok

# A failed lookup, then the cache: network-first and parallel.
echo "KeliverHost: lookup failed; starting from the cached bundle (last loaded from $U4); rollback floor 9" > "$T/fail-nf"
printf '%s\n' "KeliverHost: starting from the cached bundle (last loaded from $U4); rollback floor 9" \
  "KeliverHost: start policy: parallel: the lookup failed: the cached release, as network-first would show" > "$T/fail-par"
cache_start_after_failed_lookup "$T/fail-nf" && case_ok || case_bad "network-first: failed lookup"
cache_start_after_failed_lookup "$T/fail-nf" "https://10.0.2.2:8443/bundles/v4/" && case_ok || case_bad "network-first: failed lookup, v4"
cache_start_after_failed_lookup "$T/fail-par" "https://10.0.2.2:8443/bundles/v4/" && case_ok || case_bad "parallel: failed lookup, v4"
cache_start_after_failed_lookup "$T/fail-par" "https://10.0.2.2:8443/bundles/v6/" && case_bad "parallel: the cache was not v6" || case_ok
cache_start_after_failed_lookup "$T/par" && case_bad "parallel: a confirmed lookup did not fail" || case_ok

echo "start-lines: $pass cases passed, $fail failed${failed:+ ($failed)}"
[ "$fail" = 0 ]
