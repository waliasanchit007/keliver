#!/usr/bin/env bash
# After a failed step of the reference-app workflows: the FAIL rows of <evidence-dir>/*.results and
# the error lines of the Gradle and scaffolder logs beside them, as ONE GitHub Actions error
# annotation. `gh run view` shows annotations as soon as the job ends; a job's log only once every
# job of the run has ended (the central legs take hours). Always exits 0.
#   ci/annotate-failures.sh <evidence-dir> [title]
#   ci/annotate-failures.sh --stdin [title]    the message is standard input (its first 40 lines),
#                                              e.g. the local-maven route-test's output
if [ "${1:-}" = --stdin ]; then
  TITLE="${2:-failed}"
  msg="$(head -40 | cut -c1-300)"
  [ -n "$msg" ] || msg="(no output)"
else
  EV="${1:?usage: $0 <evidence-dir> [title] | --stdin [title]}"
  TITLE="${2:-failed rows}"
  msg="$(
    { grep -h '^FAIL ' "$EV"/*.results 2>/dev/null | head -20
      grep -hE -A4 "What went wrong" "$EV"/*.log 2>/dev/null | head -24
      grep -hE "^e: |^error: |: error: |Could not (find|resolve) " "$EV"/*.log 2>/dev/null | sort -u | head -16
    } | cut -c1-300
  )"
  [ -n "$msg" ] || msg="no FAIL row and no error line in $EV"
fi
# Workflow commands: in the message %, CR and LF are escaped; in a property (the title) also ':' and
# ',', or "reference-app, central leg" would end at its comma. Carriage returns (an emulator's
# logcat ends lines with CRLF) are dropped with tr: BSD sed, on the macOS runners, reads '\r' in a
# pattern as a plain 'r'.
msg="$(printf '%s' "$msg" | tr -d '\r' | sed -e 's/%/%25/g' | awk 'BEGIN { ORS = "%0A" } { print }')"
TITLE="$(printf '%s' "$TITLE" | tr -d '\r\n' | sed -e 's/%/%25/g' -e 's/:/%3A/g' -e 's/,/%2C/g')"
echo "::error title=$TITLE::$msg"
exit 0
