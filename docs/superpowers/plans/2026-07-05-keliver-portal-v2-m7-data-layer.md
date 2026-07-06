# V2 M7: Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox steps.

**Goal:** Guest-owned data layer over the dumb `HostSqlDriver` wire (S3-proven): schema, queries, and persistence logic ship OTA in the signed bundle; hosts provide one generic SQLite executor. Plus capability gating so bundles never load on hosts missing their services.

**Architecture (design §8 + amendment 4):**
- New module `portal-sql` (jvm+js): the wire — `interface HostSqlDriver : ZiplineService { suspend fun execute(sql, args): SqlRows; suspend fun executeBatch(statements: List<SqlStatement>): SqlRows }` (single-payload rows, U1-safe) + the guest-side SQLDelight-shaped `PortalSqlDriver` (promoted from the S3 spike) + a JVM in-memory `FakeSqlHost` for tests.
- `portal-device-android`: binds `AndroidSqlDriver` (android.database.sqlite, one DB file per app) in `bindServices` alongside HostApi.
- `portal-published-guest/logic/`: `MainPresenter` persists taps in SQLite via a typed query — restart-surviving state, the OTA-data-layer demo.
- **Capability gating:** publish meta gains `"capabilities":["HostSqlDriver@1"]` (declared by the app project via a `capabilities.txt` beside screens/); `/bundles/latest?widgetVersion=W&caps=a@1,b@2` returns the newest bundle whose required capabilities ⊆ host's list. Android host passes its capability list.

**Gates:** portal-sql jvm+js tests (spike test promoted, runs against FakeSqlHost); compile sweep incl. published guest with the SQL-using presenter; publish carries capabilities; `/bundles/latest` gating verified by curl (missing cap → no bundle; present → v(N)); device runtime persistence = verified together with M9's device pass (emulator), noted if deferred.

**Tasks:** 1) portal-sql module + tests. 2) Android host impl + binding. 3) Presenter uses the DB + capabilities.txt + publish metadata. 4) Gating endpoint + curl gates. 5) Sweep/commit/push/memory.
