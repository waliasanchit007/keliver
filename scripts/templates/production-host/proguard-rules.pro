# R8 rules for the minified build types (benchmark, and release when you turn it on).
# Zipline's services and their interfaces, as the embedded host's consumer rules
# keep them (the reference app's benchmark row runs a bundle through this build).
-keep class * implements app.cash.zipline.ZiplineService { *; }
-keep interface * extends app.cash.zipline.ZiplineService { *; }
# QuickJs.memoryUsage builds MemoryUsage in native code, through JNI, which R8 cannot see: without
# this R8 leaves the class empty and the call throws (M5's qjs reads "-" in the benchmark build).
-keep class app.cash.zipline.MemoryUsage { *; }
