# keliver-material Batch 0 (Foundation + Seed) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the published `keliver-material` widget library (7-module group) and ship its first 11 Material3 widgets, rendering on Android + iOS, consumable from `mavenLocal`/Central — the foundation for A-to-Z Compose parity (see the design spec `docs/superpowers/specs/2026-06-10-keliver-material-widget-parity-design.md`).

**Architecture:** Clone keliver's proven `keliver-ui-basic-*` 7-module pattern into `keliver-material-*` (the codegen wiring is fiddly; cloning is far more reliable than hand-authoring). Re-point its `@Schema` to a new `KeliverMaterial` type, strip the inherited widgets, then add the 11 seed widgets — each as a `@Widget` schema data class (auto-generates guest composable + host widget interface) plus a hand-written `ComposeUi<Widget>` host renderer ported from ServerDrivenUI's proven `composeApp/.../shared/Protocol.kt`.

**Tech Stack:** Kotlin 2.2.0 MP, keliver gradle codegen plugins (`dev.keliver.schema` / `dev.keliver.generator.*`), JetBrains Compose Multiplatform **material3**, Coil 3 (AsyncImage). Build with `JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

**Branch:** `feature/keliver-material` (already created).

**Seed widgets (11):** `AsyncImage, Card, Switch, Checkbox, TextField, StyledText, Divider, Chip, IconButton, ScrollableColumn, BottomSheet`.

---

## Phase 1 — Foundation: clone ui-basic → material

### Task 1: Clone the 7 modules

**Files:** Create `keliver-material-{api,schema,compose,widget,composeui,modifiers,testing}/` by copying ui-basic.

- [ ] **Step 1: Copy + git-track the 7 module dirs**

```bash
cd /Users/sanchitwalia/AndroidStudioProjects/konduit
for m in api schema compose widget composeui modifiers testing; do
  cp -r "keliver-ui-basic-$m" "keliver-material-$m"
  rm -rf "keliver-material-$m/build" "keliver-material-$m/.gradle"
done
# move package dirs dev/keliver/ui/basic -> dev/keliver/material in every srcset
find keliver-material-* -type d -path '*/dev/keliver/ui/basic' | while read d; do
  new="${d%/ui/basic}/material"; mkdir -p "$(dirname "$new")"; git mv 2>/dev/null "$d" "$new" || mv "$d" "$new"
done
```

- [ ] **Step 2: Rename identifiers inside the copies (ORDER MATTERS)**

```bash
cd /Users/sanchitwalia/AndroidStudioProjects/konduit
# RedwoodUiBasic -> KeliverMaterial FIRST (before the generic UiBasic->Material),
# so it doesn't become RedwoodMaterial. Leave RedwoodLayout/RedwoodLazyLayout intact.
find keliver-material-* -type f \( -name '*.gradle' -o -name '*.kt' \) -print0 | xargs -0 sed -i '' \
  -e 's/RedwoodUiBasic/KeliverMaterial/g' \
  -e 's/dev\.keliver\.ui\.basic/dev.keliver.material/g' \
  -e 's/keliverUiBasic/keliverMaterial/g' \
  -e 's/ui\.basic\.composeui/material.composeui/g' \
  -e 's/UiBasic/Material/g'
# rename the schema + factory source files
git mv keliver-material-schema/src/main/kotlin/dev/keliver/material/KeliverMaterial.kt 2>/dev/null \
  keliver-material-schema/src/main/kotlin/dev/keliver/material/KeliverMaterial.kt || true
f=$(find keliver-material-composeui -name 'ComposeUiRedwoodBasicWidgetFactory.kt'); [ -n "$f" ] && git mv "$f" "$(dirname "$f")/ComposeUiKeliverMaterialWidgetFactory.kt"
```

- [ ] **Step 3: Register the 7 modules in settings.gradle**

Add after the `keliver-ui-basic-*` include block (around line 165):

```groovy
include ':keliver-material-api'
include ':keliver-material-compose'
include ':keliver-material-composeui'
include ':keliver-material-modifiers'
include ':keliver-material-schema'
include ':keliver-material-testing'
include ':keliver-material-widget'
```

- [ ] **Step 4: Verify Gradle can configure the new projects**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :keliver-material-schema:help -q`
Expected: configures without error (BUILD SUCCESSFUL).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(material): scaffold keliver-material 7-module group (cloned from ui-basic)"
```

### Task 2: Reduce the schema to a clean KeliverMaterial base

**Files:** Modify `keliver-material-schema/src/main/kotlin/dev/keliver/material/KeliverMaterial.kt`

- [ ] **Step 1: Strip inherited widgets, keep the schema shell**

Replace the file body with the `@Schema` interface that depends on layout + lazylayout + ui-basic, no members yet (widgets added in Phase 2). Keep the `Reuse` reserved modifier.

```kotlin
package dev.keliver.material

import dev.keliver.layout.RedwoodLayout
import dev.keliver.lazylayout.RedwoodLazyLayout
import dev.keliver.ui.basic.RedwoodUiBasic
import dev.keliver.schema.Modifier
import dev.keliver.schema.Schema
import dev.keliver.schema.Schema.Dependency

@Schema(
  members = [
    // widgets added in Phase 2
  ],
  dependencies = [
    Dependency(1, RedwoodLayout::class),
    Dependency(2, RedwoodLazyLayout::class),
    Dependency(3, RedwoodUiBasic::class),
  ],
)
public interface KeliverMaterial

@Modifier(-4_543_827) // reserved tag, same as ui-basic Reuse
public object Reuse
```

- [ ] **Step 2: Add the ui-basic-schema dep to the material-schema build**

Modify `keliver-material-schema/build.gradle` — add `api projects.keliverUiBasicSchema` to dependencies (it already has layout + lazylayout from the clone).

- [ ] **Step 3: Strip the cloned ComposeUi widget impls + empty the factory**

Delete the cloned `ComposeUiText/Button/Image/TextInput.kt` in `keliver-material-composeui`; in `ComposeUiKeliverMaterialWidgetFactory.kt` leave the class with no widget mappings yet (it will fail to satisfy the generated factory interface until Phase 3 — that's expected; we build per-widget).

- [ ] **Step 4: Verify schema + codegen build**

Run: `./gradlew :keliver-material-schema:build :keliver-material-widget:build :keliver-material-compose:build -q`
Expected: BUILD SUCCESSFUL (empty-but-valid generated guest/host code).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(material): reduce schema to KeliverMaterial base (deps: layout, lazylayout, ui-basic)"
```

### Task 3: Switch composeui to Material3 + register in the BOM

**Files:** Modify `keliver-material-composeui/build.gradle`, `gradle/libs.versions.toml` (if no material3 alias), the keliver BOM module.

- [ ] **Step 1: Ensure a `jetbrains-compose-material3` catalog alias exists**

Check `gradle/libs.versions.toml` for `jetbrains-compose-material3`; if absent add:
```toml
jetbrains-compose-material3 = { module = "org.jetbrains.compose.material3:material3", version.ref = "jbCompose" }
```

- [ ] **Step 2: Point composeui at material3**

In `keliver-material-composeui/build.gradle` replace `api libs.jetbrains.compose.material` with `api libs.jetbrains.compose.material3`, and fix the composeui android namespace to `dev.keliver.material.composeui`.

- [ ] **Step 3: Add keliver-material-* to the BOM**

Find the BOM module (`grep -rl "constraints" keliver-bom* 2>/dev/null` or the module that lists published modules) and add the 7 `keliver-material-*` artifacts alongside the ui-basic entries.

- [ ] **Step 4: Verify**

Run: `./gradlew :keliver-material-composeui:compileKotlinMetadata -q` (after Phase 3 has ≥1 widget; for now just `:keliver-material-composeui:help -q`).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(material): composeui on Material3 + BOM entries"
```

---

## Phase 2 — Add the 11 seed widgets to the schema

### Task 4: Define all 11 `@Widget` data classes

**Files:** Modify `keliver-material-schema/src/main/kotlin/dev/keliver/material/KeliverMaterial.kt`

Source the property shapes from ServerDrivenUI's `schema/src/main/kotlin/com/example/serverdrivenui/schema/Schema.kt` (the proven definitions for `SduiCard`, `SduiSwitch`, `AsyncImage`, `Divider`, `Chip`, `IconButton`, `ScrollableColumn`, `BottomSheet`, `StyledText`, `SduiTextField`) and the sample's `Checkbox`.

- [ ] **Step 1: Add the 11 members + their data classes**

Append to `KeliverMaterial.kt` and add each to the `@Schema(members=[…])` list, IDs 1–11 (wire-stable, contiguous). Example shapes (refine each against the proven source before coding):

```kotlin
@Widget(1)
public data class StyledText(
  @Property(1) val text: String,
  @Property(2) val fontSize: Int = 14,
  @Property(3) val bold: Boolean = false,
  @Property(4) val colorArgb: Int = 0,       // 0 => default
  @Property(5) val align: Int = 0,           // 0 start, 1 center, 2 end
)

@Widget(2)
public data class Card(
  @Property(1) val children: dev.keliver.schema.Children<*>? = null,
)
// … Switch, Checkbox, Divider, Chip, IconButton, AsyncImage, TextField,
//    ScrollableColumn, BottomSheet — each ported from the proven source.
```

> Note: `Children` slots use the schema's `Children` widget pattern (see how ui-basic/layout declare children); port the exact slot/property shapes from the proven ServerDrivenUI definitions. Lambdas use `((T) -> Unit)?` for events (proven to work on JS for `@Property`, per KNOWN_BUGS — NOT for `@Modifier`).

- [ ] **Step 2: Verify schema + generated guest/host code compiles**

Run: `./gradlew :keliver-material-schema:build :keliver-material-widget:build :keliver-material-compose:build -q`
Expected: BUILD SUCCESSFUL; generated `dev.keliver.material.widget.<Widget>` interfaces + `dev.keliver.material.compose.<Widget>` composables exist under each module's `build/generated`.

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat(material): declare the 11 seed widgets in the schema"
```

---

## Phase 3 — Host renderers (ComposeUi impls), one widget at a time

**Repeatable loop per widget** (exemplar = Divider, fully coded; the other 10 follow the identical shape, porting the render body from ServerDrivenUI's `Protocol.kt`):

### Task 5: Divider (exemplar — fully coded)

**Files:** Create `keliver-material-composeui/src/commonMain/kotlin/dev/keliver/material/composeui/ComposeUiDivider.kt`; Modify the factory.

- [ ] **Step 1: Write the ComposeUi impl**

```kotlin
package dev.keliver.material.composeui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.keliver.Modifier as RedwoodModifier
import dev.keliver.material.widget.Divider

internal class ComposeUiDivider : Divider<@Composable (Modifier) -> Unit> {
  private var thickness by mutableStateOf(1)
  override var modifier: RedwoodModifier = RedwoodModifier
  override val value: @Composable (Modifier) -> Unit = { m ->
    HorizontalDivider(modifier = m.fillMaxWidth(), thickness = thickness.dp)
  }
  override fun thickness(thickness: Int) { this.thickness = thickness }
}
```

- [ ] **Step 2: Register Divider in `ComposeUiKeliverMaterialWidgetFactory`**

Add the `override fun Divider() = ComposeUiDivider()` (match the generated factory interface signature) to `ComposeUiKeliverMaterialWidgetFactory.kt`.

- [ ] **Step 3: Build the composeui module**

Run: `./gradlew :keliver-material-composeui:compileKotlinMetadata -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat(material): Divider host renderer"
```

### Tasks 6–15: the remaining 10 widgets (same loop)

For each of `StyledText, Card, Switch, Checkbox, TextField, Chip, IconButton, AsyncImage, ScrollableColumn, BottomSheet`:

- [ ] Port the render body from ServerDrivenUI `composeApp/.../shared/Protocol.kt` (the proven impl) into `ComposeUi<Widget>.kt`, adapting to the `ComposeUi<W> : <W><@Composable (Modifier)->Unit>` shape (props via `mutableStateOf`, render in `value`, setters override the generated methods). Use Material3 components (`Card`, `Switch`, `Checkbox`, `OutlinedTextField`/`TextField`, `AssistChip`, `IconButton`, `Icon`, `Text`, `Column`+`verticalScroll`, `ModalBottomSheet`). `AsyncImage` uses Coil 3 (`coil3.compose.AsyncImage`).
- [ ] Register it in `ComposeUiKeliverMaterialWidgetFactory`.
- [ ] Build `:keliver-material-composeui:compileKotlinMetadata` → SUCCESSFUL.
- [ ] Commit `feat(material): <Widget> host renderer`.

> Each widget is independent once the factory + schema exist, so Tasks 6–15 can be parallelized across subagents (one widget each) and serialized only at the factory-registration + final build.

---

## Phase 4 — Verify + dogfood + publish

### Task 16: Full build + API baselines

- [ ] **Step 1:** `./gradlew :keliver-material-composeui:build :keliver-material-widget:build :keliver-material-compose:build :keliver-material-schema:build` → SUCCESSFUL.
- [ ] **Step 2:** `./gradlew :keliver-material-schema:apiDump :keliver-material-widget:apiDump :keliver-material-compose:apiDump :keliver-material-composeui:apiDump` to generate baselines; commit them.
- [ ] **Step 3:** `./gradlew :keliver-material-composeui:apiCheck …` → SUCCESSFUL.
- [ ] **Step 4:** Commit `feat(material): API baselines for keliver-material`.

### Task 17: Dogfood in `sample/` + render-verify on an emulator

- [ ] **Step 1:** In `sample/guest`, add `dev.keliver:keliver-material-compose` and author a screen using ≥4 new widgets (Card, AsyncImage, Switch, Divider). In `sample/host-compose`, add `keliver-material-composeui` and register its factory in the combined `WidgetSystem`.
- [ ] **Step 2:** `publishToMavenLocal` the keliver-material modules so the sample (separate build) can resolve them; point sample's catalog at the snapshot via `mavenLocal()`.
- [ ] **Step 3:** Build the sample debug APK + launch on an emulator (`JAVA_HOME` 17). Confirm the new widgets render. (Real-proof verification — run it, don't assert.)
- [ ] **Step 4:** Commit the sample dogfood.

### Task 18: Publish keliver-material to Maven Central (with 0.2.0 line)

- [ ] **Step 1:** Confirm the 7 modules are wired into the Central publishing config (they inherit `redwoodBuild { publishing() }` from the clone) + the BOM.
- [ ] **Step 2:** Defer the actual Central release to the 0.2.0 cut; `publishToMavenLocal` is sufficient to unblock Track B (Stashfin) now.

---

## Self-review notes
- **Spec coverage:** §3 module structure → Task 1–3; §4 seed inventory → Task 4 + 5–15; §7 verification (apiDump, dogfood) → Task 16–17; §6 styling (Material3 defaults) → Task 3 + Phase 3. ✅
- **Known risks baked in:** event lambdas only as `@Property` (KNOWN_BUGS), Material 2→3 swap, factory must satisfy the generated interface (build per-widget catches mismatches early), Kotlin/JVM17 env.
- **Per-widget code:** ported from the *proven* ServerDrivenUI `Protocol.kt` (named source), validated by the build — not invented here.
