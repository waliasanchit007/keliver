# Writing a screen in Keliver

How to structure a server-driven screen so guest code feels like native
Android — a **Repository**, a **Presenter** (the "ViewModel"), and a **Screen**
— while respecting the one hard constraint of the platform.

Worked example: the **Workouts** screen in [`sample/`](../sample) (fetches
workouts from an API, merges favorites from a host database, with live search),
implemented in both styles below and verified on device.

---

## 1. The mental model

You write three things, exactly like native MVVM:

| Layer | Native Android | Keliver guest |
|---|---|---|
| **Data** | `class FooRepository(api, dao)` | `class FooRepository(http, hostStore)` |
| **Logic / state** | `FooViewModel` exposing `StateFlow<UiState>` | `@Composable FooPresenter(events): FooModel` |
| **UI** | `@Composable FooScreen(vm)` | `@Composable FooScreen(model, onEvent)` |

The difference from native is *where the data and the heavy logic live* — and
that's dictated by the runtime.

## 2. The host/guest split (the hard constraint)

The guest is **Kotlin/JS running in QuickJS**. It **cannot**:

- open a database (no SQLite),
- perform authenticated networking or hold secrets/tokens,
- touch platform APIs (files, sensors, keystore).

So the **native host owns data, persistence, networking, and secrets**, and
exposes them to the guest as **Zipline services**. The guest owns **presentation
logic and UI**. This is not a workaround — it's the architecture:

- keeps the guest **thin and fast** (it's downloaded over the wire, so every KB
  and every cold-start millisecond counts),
- keeps **security native** (tokens never enter the sandbox),
- lets you **ship new screens on the fly** while the host stays a stable,
  capability-shaped surface.

> This is exactly how Cash App structures Treehouse: the guest calls back into
> host-provided `ZiplineService`s for everything it can't do itself.

## 3. Data layer

### 3a. API calls — `keliver-http`

The host binds **one** generic, endpoint-agnostic transport; the guest defines
endpoints, request/response types, and parsing. A new endpoint is **guest code
only — no native release.**

```kotlin
// HOST (once): your HttpClient + base URL + auth, behind HostHttpProvider.
class KtorHostHttpProvider(val client: HttpClient, val baseUrl: String) : HostHttpProvider {
  override suspend fun execute(req: HttpRequest): HttpResponse { /* client.request(baseUrl + req.path){…} */ }
}
zipline.bind<HostHttpProvider>("http", KtorHostHttpProvider(client, "https://api.example.com"))

// GUEST: define your own types + endpoints, call them typed.
@Serializable data class Workout(val id: String, val name: String, val durationMin: Int)
val http = KeliverHttp(provider)
val workouts: List<Workout> = http.get("/workouts")
```

> ⚠️ **Gotcha:** the guest module must apply the **`kotlin-serialization`
> compiler plugin** (`alias(libs.plugins.kotlinSerialization)`), or `@Serializable`
> types get no serializer on Kotlin/JS and you'll hit a runtime
> *"Serializer for class X not found"*.

### 3b. Database / persistence — a host Zipline service

The guest can't open SQLite, so expose your host store (SQLDelight / Room /
DataStore) as a `ZiplineService`:

```kotlin
// shared (visible to host + guest)
interface HostFavoritesStore : ZiplineService {
  suspend fun favorites(): FavoritesSnapshot
  suspend fun setFavorite(id: String, favorite: Boolean)
}
@Serializable data class FavoritesSnapshot(val ids: List<String>)
```

> ⚠️ **Gotcha (KNOWN_BUGS U1):** a `suspend` service method must **not** return
> `List<@Serializable T>` — that hangs `bind<>()`. Wrap lists in a single
> snapshot type (as above), the same way `keliver-http` returns a single
> `HttpResponse` with a `String` body. Single objects and `Unit` are fine.

### 3c. Repository — combine the sources (plain, testable)

```kotlin
class WorkoutsRepository(private val http: KeliverHttp, private val favorites: HostFavoritesStore) {
  suspend fun load(query: String): List<WorkoutRow> {
    val all = http.get<List<Workout>>("/workouts")     // API source
    val favIds = favorites.favorites().ids.toSet()      // DB source
    return all.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
              .map { WorkoutRow(it, isFavorite = it.id in favIds) }
  }
  suspend fun toggleFavorite(id: String, fav: Boolean) = favorites.setFavorite(id, fav)
}
```

The repository is a **plain class** — no Compose — so the business logic is
unit-testable without any UI, regardless of which presentation style you pick.
The sample's `WorkoutsRepositoryTest` does exactly that — **3 passing tests**
with fake host services (`HostHttpProvider` + `HostFavoritesStore`), no UI, no
device (`./gradlew :guest:jsTest`).

## 4. Presentation layer — two styles

### Style B (recommended): Presenter + Screen

The native-like separation. The **Presenter** is the ViewModel equivalent —
`events` in, `Model` out; the **Screen** is a pure render.

```kotlin
@Composable
fun WorkoutsPresenter(events: Flow<WorkoutsEvent>, repo: WorkoutsRepository): WorkoutsModel {
  var query by remember { mutableStateOf("") }        // + loading / error / rows / reload
  LaunchedEffect(Unit) { events.collect { e -> when (e) {
    is Search -> query = e.query
    is ToggleFavorite -> { repo.toggleFavorite(e.id, e.favorite); reload++ }
  } } }
  LaunchedEffect(query, reload) { /* load via repo → loading/error/rows */ }
  return WorkoutsModel(query, loading, error, rows)
}

@Composable
fun WorkoutsScreen(model: WorkoutsModel, onEvent: (WorkoutsEvent) -> Unit) { /* pure render of model */ }

// in TreehouseUi.Show():
val events = remember { MutableSharedFlow<WorkoutsEvent>(extraBufferCapacity = 16) }
WorkoutsScreen(WorkoutsPresenter(events, repo)) { events.tryEmit(it) }
```

The screen's whole state machine is an inspectable `Model`, not logic fused into
the view — so it stays clean as the screen grows (loading, error, search,
toggles) and is testable in isolation.

### Style A (escape hatch): inline

Redwood's default — state + logic + UI in one `@Composable`. Fewest lines; fine
for trivial / leaf screens, but a complex screen tangles and you can only test
it by rendering the widget tree.

```kotlin
@Composable
fun WorkoutsScreenInline(repo: WorkoutsRepository) {
  val scope = rememberCoroutineScope()
  var query by remember { mutableStateOf("") }
  var rows by remember { mutableStateOf<List<WorkoutRow>?>(null) }
  LaunchedEffect(query) { rows = repo.load(query) }
  /* … Column { TextField; rows?.forEach { Card { Checkbox(…); Text(…) } } } … */
}
```

### When to use which

**Default to Style B** for any screen with real state (loading/error/inputs).
**Drop to Style A** when a Model + Events pair would be pure ceremony (a static
list, a leaf). They compose — a `Screen` can be inline or delegate to a
`Presenter`.

## 5. What about Molecule? And DI?

- **Molecule is not used in the guest runtime.** Treehouse's own
  `treehouse-guest-compose` already does Molecule's job (it runs the Compose
  runtime to drive the widget tree). Redwood's docs prescribe plain-Compose
  presenters; Cash App's Redwood samples have zero Molecule. Molecule is the
  *native-UI* sibling tool — and it's still handy in **tests** (drive a presenter
  as a `StateFlow` with `moleculeFlow(RecompositionMode.Immediate)`), where
  nothing ships in the bundle.
- **DI:** wire the graph **manually** in the guest's `main()` (Cash App's
  Treehouse sample does exactly this). **kotlin-inject** works on Kotlin/JS if
  the graph grows. **Hilt/Dagger do not work in the guest** (JVM reflection /
  annotation processing).

## 6. The worked example

| File | Role |
|---|---|
| `sample/shared/.../FavoritesStore.kt` | the host DB service (DB source) |
| `sample/guest/.../WorkoutsData.kt` | `Workout`, `WorkoutsRepository`, `WorkoutsModel`, events |
| `sample/guest/.../WorkoutsPresenter.kt` | Style B (Presenter + Screen) |
| `sample/guest/.../WorkoutsInline.kt` | Style A (inline) |
| `sample/guest/src/jsTest/.../WorkoutsRepositoryTest.kt` | the logic, unit-tested (3 green, no UI/device) |
| `sample/host-compose/.../SampleHostServices.kt` | host wiring (Android **+** iOS): serve `/workouts`, bind `HostHttpProvider` + `HostFavoritesStore` |

Run it: `sample/README.md`. The guest's `Show()` renders Style B by default;
swap the one block for `WorkoutsScreenInline(repo)` to see Style A.
