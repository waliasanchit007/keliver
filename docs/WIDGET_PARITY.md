# keliver-material — widget parity audit

Goal: authoring a keliver screen should feel like writing native Compose
Material3. This is the living audit of how close we are (last full pass:
2026-07-12, schema batch 15).

## Coverage — 76 widgets in the schema, 64 in the portal

| Group | Widgets |
|---|---|
| Text & media | Text, StyledText (full text-style parity: size/weight/italic/underline/strike/align/maxLines/overflow/lineHeight/letterSpacing/colorRole), RichText†, Image, AsyncImage, **Icon** (name-based, curated core set), Badge |
| Buttons | Button, ElevatedButton, FilledTonalButton, OutlinedButton, TextButton, IconButton, FloatingActionButton, ExtendedFloatingActionButton |
| Inputs & selection | TextField, OutlinedTextField, TextInput, Checkbox, Switch, RadioButton, Slider, DropdownMenu, SegmentedButtonRow, Chip, FilterChip, InputChip, SuggestionChip |
| Containers | Card, ElevatedCard, OutlinedCard, StyledBox, Surface, Scaffold†, BottomSheet, Dialog, AlertDialog, Tooltip, Clickable |
| Layout & scroll | Column/Row/Box/Spacer (layout schema), FlowRow, FlowColumn, ScrollableColumn, HorizontalPager, VerticalPager, LazyVerticalGrid, LazyHorizontalGrid, LazyColumn/LazyRow (lazylayout schema)‡, Divider, VerticalDivider, **ListItem** |
| Navigation & structure | TopAppBar, BottomAppBar, NavigationBar, NavigationRail, TabRow, Tab |
| Feedback & motion | Snackbar, CircularProgressIndicator, LinearProgressIndicator, Shimmer, AnimatedVisibility, AnimatedBorder |
| Theme | Theme (Material3 color roles, resolved by colorRole props) |
| Logic (portal) | Condition (`if`), Repeat (`forEach`, per-item binds) |

**Universal modifiers (19):** Padding(Each), Size, FillMaxWidth/Height/Size,
Background, GradientBackground, Border, CornerRadius(Each), Shadow, Alpha,
Blur, Offset, Rotate, Scale, AspectRatio, AnimateContentSize.

† = guest/host only, not in the portal editor: RichText (complex span type),
Scaffold (two children slots — the portal supports one slot per node).
‡ = lazylayout widgets are available to guests; the portal codegen parses the
material + layout schemas only (ScrollableColumn covers the common case).

## Batch 15 (this audit's additions)

- **Icon** — `Icon(name = "Search", sizeDp = 24, tintArgb = …)`. The most-used
  Material composable was missing entirely. Names map to the material-icons
  CORE set (~50, see `keliver-material-composeui/.../MaterialIcons.kt`);
  unknown names render a neutral placeholder, never crash.
- **ListItem** — `ListItem(headline, supporting, overline, leadingIcon,
  trailingIcon, onClick)` with name-based icons.
- **DropdownMenu + SegmentedButtonRow** joined the *portal* (they always worked
  on devices): `List<String>` props now ride the wire (`options = listOf("Day",
  "Week")`, editable in the editor as pipe-separated text).

## Deliberate gaps (ranked, with reasons)

1. **RangeSlider** — its event carries two values; the portal's action grammar
   is zero-or-one arg. Add when a real screen needs it (protocol supports it).
2. **DatePicker / TimePicker** — large state surfaces (selection models);
   needs a dedicated design pass, not a quick add.
3. **SearchBar** — TextField + Icon compose the same UX today.
4. **ModalNavigationDrawer / PullToRefresh** — container-level gestures;
   planned with the navigation story.
5. **TriStateCheckbox, PlainTooltip variants, Menus beyond DropdownMenu** — low
   demand; add on request.
6. **Icons beyond the core set** — `material-icons-extended` is ~11MB; the
   curated map keeps bundles small. Extend the map as real apps need names.

## How to add a widget (the 30-minute recipe)

1. Define the `@Widget(nextId)` data class in
   `keliver-material-schema/.../KeliverMaterial.kt` and register it in the
   `@Schema(members = […])` list. **Ids and property tags are wire-stable:
   never renumber, only append.**
2. Implement the host renderer in `keliver-material-composeui` (a ~30-line
   class following e.g. `ComposeUiSlider`) + one factory override — the
   generated factory interface makes missing impls a compile error.
3. `./gradlew apiDump :portal-schema-codegen:generatePortalCode` — the guest
   `@Composable`, protocol adapters, portal catalog/exporter/renderer are all
   generated.
4. Gates: `apiCheck`, `:portal-schema-codegen:checkPortalCode` (kitchen-sink
   export→compile), the portal test suites.

Prop-kind support (what a `@Property` type may be for portal editing): String,
Int, Boolean, Double/Float, Dp, List<Int>, List<Float>, List<String>, layout
enums (Constraint/alignments/Overflow). Events: zero-or-one parameter of
String/Int/Boolean/Float/Double/Long.
