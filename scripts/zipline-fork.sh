#!/bin/bash
# Entry point for NF2's Zipline fork (docs/GUEST_PROFILE.md "NF2: the Zipline fork, step 1").
# The fork is a patch set in keliver-compose-gatea-profile/engine/zipline-fork/; see its README.
#   scripts/zipline-fork.sh <command> <work dir> [args]    (commands: apply.sh's usage)
set -euo pipefail
exec bash "$(cd "$(dirname "$0")/.." && pwd)/keliver-compose-gatea-profile/engine/zipline-fork/apply.sh" "$@"
