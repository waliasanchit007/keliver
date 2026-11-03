# Sourced by ci/prepare.sh (Android) and ci/w3/ios-static.sh (iOS). B5's third device scenario
# (docs/BRIDGE_CODEGEN_DESIGN.md, "B5" §4; S17c and S17d): the static route's host declares one
# app bridge, CiStub@1 at shape $W3_BRIDGE_SHAPE, and binds a one-function ZiplineService under
# that name (§1 requires the declared name to be bound). No guest calls it. Every load of that
# host therefore runs the app's binding first, and logs it (debug builds only).
# A host from before B5a (no KeliverBridgeBinding in its Bridges.kt) is left as it is.

W3_BRIDGE_SHAPE=0123456789abcdef        # the shape this host declares
W3_BRIDGE_OTHER_SHAPE=fedcba9876543210  # a bundle built against another CiStub@1 contract

# w3_bridge_stub <the host's Kotlin source root>: writes CiBridge.kt next to Bridges.kt, and
# prints its path; fails (writing nothing) for a host from before B5a.
w3_bridge_stub(){
  local bridges pkg out
  bridges="$(grep -rls 'class KeliverBridgeBinding' "$1" | head -1)"
  [ -n "$bridges" ] || return 1
  pkg="$(sed -n 's/^package //p' "$bridges" | head -1)"
  out="$(dirname "$bridges")/CiBridge.kt"
  cat > "$out" <<EOF
// CI only (the reference app's static route, S17c and S17d): written by ci/w3/bridge-stub.sh
// for one build and removed again; not part of the scaffolded host.
package $pkg

import app.cash.zipline.ZiplineService

internal interface CiStub : ZiplineService {
  fun ping(): String
}

private class CiStubService : CiStub {
  override fun ping(): String = "pong"
}

/** The bridges this CI host serves: CiStub@1 at shape $W3_BRIDGE_SHAPE. */
public fun ciBridges(): List<KeliverBridgeBinding> = listOf(
  KeliverBridgeBinding("CiStub", 1, "$W3_BRIDGE_SHAPE") {
    it.log("CiStub: binding")
    it.zipline.bind<CiStub>("CiStub", CiStubService())
  },
)
EOF
  echo "$out"
}
