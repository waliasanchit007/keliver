# R8 rules for the minified build types (benchmark, and release when you turn it on).
# Zipline's services and their interfaces, as the embedded host's consumer rules
# keep them (the reference app's benchmark row runs a bundle through this build).
-keep class * implements app.cash.zipline.ZiplineService { *; }
-keep interface * extends app.cash.zipline.ZiplineService { *; }
