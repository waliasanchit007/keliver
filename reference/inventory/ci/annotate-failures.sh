#!/usr/bin/env bash
# After a failed step of the reference-app workflows: the FAIL rows of <evidence-dir>/*.results and
# the error lines of the Gradle and scaffolder logs beside them, as ONE GitHub Actions error
# annotation. `gh run view` shows annotations as soon as the job ends; a job's log only once every
# job of the run has ended (the central legs take hours). Always exits 0.
#   ci/annotate-failures.sh <evidence-dir> [title]
EV="${1:?usage: $0 <evidence-dir> [title]}"
TITLE="${2:-failed rows}"
msg="$(
  { grep -h '^FAIL ' "$EV"/*.results 2>/dev/null | head -20
    grep -hE -A4 "What went wrong" "$EV"/*.log 2>/dev/null | head -24
    grep -hE "^e: |^error: |: error: |Could not (find|resolve) " "$EV"/*.log 2>/dev/null | sort -u | head -16
  } | cut -c1-300
)"
[ -n "$msg" ] || msg="no FAIL row and no error line in $EV"
# Workflow commands: %, CR and LF are escaped; the rest is as written.
msg="$(printf '%s' "$msg" | sed -e 's/%/%25/g' -e 's/\r/%0D/g' | awk 'BEGIN { ORS = "%0A" } { print }')"
echo "::error title=$TITLE::$msg"
exit 0
