# Sourced by ci/device.sh and ci/ios.sh: what a host's start says it did, under either start
# policy (docs/DEVICE_HOST.md, "Start policy"). Each helper takes the console or logcat file
# first, and holds the parallel route to the same property the network-first line proves:
#
# - network-first (and a parallel start whose lookup named another release) loads the release the
#   lookup named from the network: "loading <url> (index sequence N, channel …, host version …)",
#   then codeLoadSuccess sequence N from the network.
# - a parallel start whose lookup named the cached release, at the sha256 that release was
#   loaded at, shows the cached load and loads nothing else: "start policy: parallel: the lookup
#   confirms the cached release: <url> (index sequence N, …)", one codeLoadSuccess, sequence N
#   from the cache.
#
# A host from before the start policy (a published tools zip's) says only the first.
# Numbers are matched whole: sequence 1 is not sequence 10, floor 4 is not floor 40.

# has FILE STRING: a line holds STRING (as is, not a pattern), not followed by a digit.
has(){
  awk -v s="$2" '{ l = $0; while ((i = index(l, s)) > 0) { if (substr(l, i + length(s), 1) !~ /[0-9]/) { found = 1; exit } l = substr(l, i + 1) } } END { exit !found }' "$1"
}

# took_release FILE URL N [EXTRA]: the lookup named URL, at index sequence N (then EXTRA, e.g.
# ", channel stable"), and that release is what this start runs.
took_release(){
  local f="$1" url="$2" n="$3" extra="${4:-}"
  if has "$f" "loading $url (index sequence $n$extra"; then
    # From the network: a cached load (a parallel start's dropped one, or the W5 fall-back) is not it.
    grep -qE "codeLoadSuccess modules=[0-9]+ sequence=$n source=network" "$f"
  elif has "$f" "start policy: parallel: the lookup confirms the cached release: $url (index sequence $n$extra"; then
    grep -qE "codeLoadSuccess modules=[0-9]+ sequence=$n source=cache" "$f" \
      && ! grep "codeLoadSuccess" "$f" | grep -qv "sequence=$n source=cache"
  else
    return 1
  fi
}

# took_release_floor FILE URL N FLOOR: as took_release (no EXTRA), and the start ran with rollback
# floor FLOOR (network-first: on its "loading" line; parallel: on its "starting from the cached
# bundle" line, the same floor the cached load was held to).
took_release_floor(){
  local f="$1" url="$2" n="$3" floor="$4"
  took_release "$f" "$url" "$n" || return 1
  grep -F "loading $url (" "$f" | grep -qE "; rollback floor $floor([^0-9]|$)" \
    || has "$f" "starting from the cached bundle (last loaded from $url); rollback floor $floor"
}

# cache_start_after_failed_lookup FILE [URL-PREFIX]: the lookup failed and this start went to the
# cached bundle (last loaded from URL-PREFIX…): network-first after its lookup, parallel at once,
# shown because the lookup then failed (its cached load is held to the key and the floor as
# network-first's is).
cache_start_after_failed_lookup(){
  local f="$1" from="${2:-}"
  grep -qF "lookup failed; starting from the cached bundle (last loaded from $from" "$f" && return 0
  grep -qF "starting from the cached bundle (last loaded from $from" "$f" \
    && grep -qF "start policy: parallel: the lookup failed: the cached release, as network-first would show" "$f"
}

# lookups_in FILE: how many lookups named a release to start (one line per such lookup, either
# policy): the host's own "loading <url> (" line, not a reason line that quotes a URL after "loading".
lookups_in(){
  grep -cE "KeliverHost: loading https?://[^ ]+ \(|KeliverHost: start policy: parallel: the lookup confirms the cached release: https?://" "$1"
}
