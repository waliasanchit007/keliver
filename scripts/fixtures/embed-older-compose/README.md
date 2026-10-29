# An existing app on older libraries (K3's consumer smoke)

An Android app that pins what an app a year or two old would: the Compose BOM
2024.06.00 (Compose 1.6.8), activity-compose 1.8.2, core-ktx 1.12.0,
lifecycle 2.6.2, coroutines 1.7.3 and OkHttp 4.12.0. It needs Kotlin 2.2.0 and
app.cash.zipline 1.22.0, as every app embedding the host does (the Kotlin gap is
a separate route).

`scripts/keliver-embed-floor-check.sh` copies it, runs
`keliver-new-production-host.sh --embed` into it (which prints the dependency
floor beside these versions before writing anything), builds it, and checks
that Gradle resolved every library the report lists to at least its floor, and
the ones the report says it raises to exactly the floor. The lines marked
`KELIVER EMBED` are what the scaffolder tells an adopter to add.
