# Applied to your app when it shrinks with R8 (W2.7 checks a minified release).
# Zipline calls its services reflectively by name across the bridge.
-keep class * implements app.cash.zipline.ZiplineService { *; }
-keep interface * extends app.cash.zipline.ZiplineService { *; }
# QuickJs.memoryUsage builds MemoryUsage in native code, through JNI, which R8 cannot see: without
# this R8 leaves the class empty and the call throws (M5's qjs reads "-" in your benchmark build).
-keep class app.cash.zipline.MemoryUsage { *; }
