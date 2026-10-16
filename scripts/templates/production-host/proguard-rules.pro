# R8 rules for the minified build types (benchmark, and release when you turn it on).
# Zipline calls its services reflectively by name across the bridge.
-keep class * implements app.cash.zipline.ZiplineService { *; }
-keep interface * extends app.cash.zipline.ZiplineService { *; }
