# MS4-03's size harness: R8 rules for the release builds (as sample/host-android's gateb rules).
# Zipline and Treehouse ship their own consumer rules; this keeps the harness's own Zipline service
# interface, which the guest calls through Zipline's generated adapters.
-keep interface dev.keliver.compose.size.** { *; }
-dontwarn org.slf4j.**
