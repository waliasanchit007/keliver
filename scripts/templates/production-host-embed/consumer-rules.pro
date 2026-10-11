# Applied to your app when it shrinks with R8 (W2.7 checks a minified release).
# Zipline calls its services reflectively by name across the bridge.
-keep class * implements app.cash.zipline.ZiplineService { *; }
-keep interface * extends app.cash.zipline.ZiplineService { *; }
