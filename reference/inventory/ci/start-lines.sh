# Sourced by ci/device.sh and ci/ios.sh: what a host's start says it did, under either start
# policy (docs/DEVICE_HOST.md, "Start policy"). Each helper takes the console or logcat file
# first, and holds the parallel route to the same property the network-first line proves:
#
# - network-first (and a parallel start whose lookup named another release) loads the release the
#   lookup named from the network: "loading <url> (index sequence N, channel …, host version …)".
# - a parallel start whose lookup named the cached release, at the sha256 that release was
#   loaded at, shows the cached load and loads nothing else: "start policy: parallel: the lookup
#   confirms the cached release: <url> (index sequence N, …)", one codeLoadSuccess, sequence N
#   from the cache.
#
# A host from before the start policy (a published tools zip's) says only the first.

# took_release FILE URL N [EXTRA]: the lookup named URL, at index sequence N (then EXTRA, e.g.
# ", channel stable"), and that release is what this start runs.
took_release(){
  local f="$1" url="$2" n="$3" extra="${4:-}"
  if grep -qF "loading $url (index sequence $n$extra" "$f"; then
    grep -q "codeLoadSuccess" "$f"
  elif grep -qF "start policy: parallel: the lookup confirms the cached release: $url (index sequence $n$extra" "$f"; then
    grep -q "codeLoadSuccess modules=[0-9]* sequence=$n source=cache" "$f" \
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
  if grep -q "loading $url (.*); rollback floor $floor" "$f"; then
    grep -q "codeLoadSuccess modules=[0-9]* sequence=$n" "$f"
  else
    grep -qF "starting from the cached bundle (last loaded from $url); rollback floor $floor" "$f" \
      && took_release "$f" "$url" "$n"
  fi
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

# lookups_in FILE: how many lookups named a release to start (one line per such lookup, either policy).
lookups_in(){
  grep -cE "loading https?://|start policy: parallel: the lookup confirms the cached release: https?://" "$1"
}
