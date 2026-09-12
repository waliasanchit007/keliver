# keliver-init: @Serializable failed at runtime, not compile time (2026-09-06)

## Reproduction, before the fix

A clean `keliver-init` scaffold with one `@Serializable data class` and
`kotlinx-serialization-json` added:

- `./gradlew compileKotlinJs` → **BUILD SUCCESSFUL**. The annotation alone
  needs no plugin, so nothing is red.
- `Json.decodeFromString<Item>(...)` in a jsTest → **FAILS**:

```
SerializationException: Serializer for class 'Item' is not found.
Please ensure that class is marked as '@Serializable' and that the
serialization compiler plugin is applied.
```

That is the shape that costs time: it compiles, so the mistake surfaces only
when the code runs, and the message points at the annotation the adopter has
already written.

## Fix — three lines in `scripts/keliver-init`

1. `settings.gradle` `pluginManagement.plugins`:
   `id 'org.jetbrains.kotlin.plugin.serialization' version '2.2.0'`
   (pinned to the same Kotlin version as the other plugins)
2. `build.gradle` `plugins { }`: `id 'org.jetbrains.kotlin.plugin.serialization'`
3. `jsMain` deps: `kotlinx-serialization-json:1.9.0`

Enabled rather than commented out: a commented hint does not prevent a runtime
failure the compiler will not catch.

## Verification, after the fix

| check | result |
|---|---|
| plain scaffold still compiles, no serialization used | **PASS** (exit 0) |
| `@Serializable` decode **and** encode round-trip in ChromeHeadless | **PASS** |
| plugin version consistent with `kotlin.multiplatform` / `plugin.compose` (2.2.0) | **PASS** |

Both plugin declaration sites are covered — adding only the `build.gradle`
entry fails with `plugin dependency must include a version number`, which was
the second half of the original friction.
