#!/usr/bin/env bash
# Drive one replicate: readiness, then the participant, then file the artifacts
# under <arm>-r<N>- so the next replicate starts from a clean report slot.
set -uo pipefail
D="$(cd "$(dirname "$0")" && pwd -P)"
K=/Users/sanchit.walia/NonWork/Keliver
ARM="$1"; REP="$2"
# One driver at a time. Overlapping drivers were how a readiness step of one
# run reached into another run.
LOCK="$D/.drive.lock"
if ! mkdir "$LOCK" 2>/dev/null; then echo "another drive.sh holds $LOCK — refusing"; exit 1; fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT
export FROZEN_SCREEN="$(dirname "$D")/case2/qualify/defective.kt"
echo "######## arm $ARM replicate $REP ########"
"$D/readiness.sh" "$ARM" || { echo "READINESS FAILED"; exit 1; }
EXTRA=(); [ "$ARM" = c ] && EXTRA=(--listed)
( "$K/scripts/m4-run-participant.sh" "$D" "$ARM" --model sonnet \
    --mcp "$D/runtime/mcp-config-$ARM.json" ${EXTRA[@]+"${EXTRA[@]}"} \
    > "$D/reports/run-$ARM-r$REP.log" 2>&1 ) & RP=$!
( sleep 2700; kill -9 $RP 2>/dev/null ) & W=$!
wait $RP; rc=$?; kill $W 2>/dev/null
grep -E "listing:|resolved model|exit " "$D/reports/run-$ARM-r$REP.log"
for f in report toolcalls config stream err; do
  ext=txt; [ "$f" = config ] && ext=json; [ "$f" = stream ] && ext=jsonl
  [ -e "$D/reports/$ARM-$f.$ext" ] && mv "$D/reports/$ARM-$f.$ext" "$D/reports/$ARM-r$REP-$f.$ext"
done
echo "runner rc=$rc"
