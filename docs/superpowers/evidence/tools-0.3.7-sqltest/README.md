# IosSqlHost on Kotlin/Native macOS, rerun for the 0.3.7 candidate

The harness is `../ios-host-i1/sqltest/` (build files and the local wire types).
For this run:
- its `IosSqlHost.kt` was replaced by the template's at the candidate, with only
  the package changed. That includes `cacba781c`'s empty-SQL fix, which the
  i1 run predates;
- one test was added: `emptySqlIsNoRowsNotACrash` (this directory's
  `IosSqlHostTest.kt`).

Result: 6 tests, 0 failures (`TEST-sqltest.IosSqlHostTest.xml`). Run on macOS
arm64 with a disposable Gradle home, user.home and `KONAN_DATA_DIR`.

Note: on macOS, `NSApplicationSupportDirectory` is the real
`~/Library/Application Support`, so the test databases land there. They are
named `sqltest-*.db` and were deleted after the run.
