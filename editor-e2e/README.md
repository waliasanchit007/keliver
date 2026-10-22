# Editor end-to-end tests (real input)

These tests drive the web editor (`portal-editor`, in its generic `web-spike`
build) in headless Chrome the way a person does: every click, double-click,
drag, wheel turn and key press goes through the Chrome DevTools protocol's
`Input.dispatchMouseEvent`, `Input.dispatchKeyEvent` and `Input.dispatchDragEvent`.
Page script is used only to READ state: the DOM, element geometry, what the
canvas drew. The tests then check three things:

- the editor's DOM: the selected outline row, the properties header, the
  breadcrumb, the save pill, Undo and Redo, panel widths;
- the canvas pixels, from screenshots. A node is found by the colour the
  fixture gives it, which does not depend on the editor's own hit-testing;
- the fixture's `.kt` files on disk: an edit is surgical, and an edit followed
  by Undo leaves every byte as it was.

There are no npm dependencies. The suite needs Node 22+ (or Node 20/21, run
with `--experimental-websocket`), Chrome or Chromium, and JDK 17 for the relay.

## Run it

```sh
editor-e2e/run.sh                      # build the generic editor + relay, run every test
editor-e2e/run.sh --no-build           # use what this checkout already built
editor-e2e/run.sh --grep 'E14|E15'     # only tests whose "id title" matches
editor-e2e/run.sh --editor-dist DIR --relay BIN   # test another build (e.g. a baseline)
```

`run.sh` does the following, in order:

1. Copies `fixture/` to a temp dir.
2. Starts `portal-relay` on that copy. The relay gets its own `PORTAL_STORE`
   and a free port.
3. Serves the built editor from a free port. `lib/serve.mjs` serves with no
   caching.
4. Runs `run.mjs`.
5. Always stops what it started.

Artifacts go to `build/editor-e2e/` (or `$E2E_OUT`):

- `summary.json`;
- `relay.log`;
- one directory per test, holding:
  - `log.txt`;
  - `console.log` (the page's console and exceptions);
  - the screenshots the test took;
  - on failure, `failure.png` and `dom.json`.

Chrome is found at `$CHROME`, else in the usual macOS and Linux locations,
else on `PATH`.

Each test gets a fresh Chrome on a fresh, empty profile. A reused profile
could run a stale cached loader, because `web-spike.js` has a constant name.

A test must leave the fixture's source as it found it. If a test leaves a
file changed, the runner fails that test and restores the file.

## The fixture (`fixture/`)

A minimal app the relay can serve. There is no Gradle build: the relay ingests
the source. It is built from the structures that broke the editor:

| Structure | Where |
|---|---|
| A root Column holding component instances | `overview`: `SummaryCard`, `ActionCard`, `CardTitle`, `CountBadge` |
| A component whose definition nests components, with definition-local ids that equal screen handles | `SummaryCard` (one `CardTitle` and three `StatTile`s), `ActionCard` (one `CardTitle`). E2 checks that the collision is real. |
| A component with a button inside | `ActionCard` |
| An opaque component (a body outside the grammar) | `CountBadge` |
| A screen taller than the frame | `logbook` (its footer is only reachable by scrolling) |
| A Repeat over rows | `logbook`: `b.entries.forEach { EntryRow(…) }` |
| Conditions (`if`, `if`/`else`) | `logbook` |

Every box the tests look for has a colour unique on its screen; they are
listed in `tests/fixture-map.mjs`. Keep them unique when you change the
fixture. The relay assigns handles in document order, starting at 1 for the
screen's root. A component's definition is numbered separately, so changing
the fixture can move or remove the id collisions. E2 asserts that they exist,
and fails if they do not.

## The tests

| File | Id | What (AUDIT id) |
|---|---|---|
| `00-smoke` | smoke | Loads; the outline lists the screen; the screen picker; no exceptions or console errors |
| `10-canvas-selection` | E1 | A click at the centre of each component instance selects that instance; the overlay matches its drawn bounds |
| | E2 | Clicks inside components (nested components, a button, a grid) never select outside the instance; an edit then lands on it, surgically; Undo is byte-exact |
| `20-canvas-scroll` | E3 | The wheel scrolls a screen taller than the frame; a click after scrolling selects the right node |
| `30-panels` | E7 | Splitter drag resizes; double-click collapses and restores; widths and collapsed state persist across reload |
| `40-undo-writeback` | E30 | Undo and Redo are disabled at depth 0, count their steps, and step one edit each |
| | E31 | The undo key inside a field is the field's own: focus stays |
| | E19 | An insert (a new import) and a new argument, each undone, leave the file byte-identical |
| `50-palette-drop` | E15 | A palette item dragged onto the canvas shows a drop line, inserts there, and the new node is selected |
| `60-components` | E14 | Double-click opens a component: the breadcrumb, and its body in the outline. An internal edit goes to `components/<Name>.kt` only. The crumb returns with the instance selected and drawing the edit. Enter re-opens; Escape steps out. |
| | E14n | A nested component extends the breadcrumb; the crumb and Escape step out one level at a time |
| | E14r | *Known bug.* Returning from a component claims the re-selected instance is "not drawn". |
| | E18 | A refused edit leaves "Change refused" (not "Saved") and no file changed |
| | E14x | `/doc?component=Nope` is a 404. Opening a component deleted on disk shows a hint and stays on the screen. |

The relay keeps its undo stacks per document for its whole life, and the
editor's session is constant. A test that asserts an untouched stack (E30)
therefore uses a screen no earlier test edits. The relay also keeps an opened
component's document, so E14x relies on no earlier test opening `CountBadge`.

## Add a test

Put an `NN-topic.test.mjs` file in `tests/` that default-exports an array:

```js
import { equal, ok } from '../lib/assert.mjs';
import { COLORS } from './fixture-map.mjs';

export default [
  {
    id: 'E99',                      // the AUDIT id, or a short name
    title: 'what the user does and sees',
    // knownBug: 'why it fails today',  // reported as xfail; it fails the run once it passes (XPASS)
    async run({ page, editor, relay, fixtureDir, artifacts, log }) {
      await editor.open('overview', { readyColor: COLORS.summaryCard });
      const box = await editor.findColor(COLORS.actionCard);   // where the canvas drew it
      await page.click(box.cx, box.cy);                        // real input
      equal(await editor.propsHeader(), 'ActionCard #35 · component', 'clicking the card selects it');
    },
  },
];
```

Files run in name order, and the tests in each file run in array order.

What a test's `run()` gets:

- `page` (`lib/browser.mjs`): real input and screenshots:
  - `click`, `dblclick`, `drag`, `dragAndDrop` (HTML5 drag and drop), `wheel`;
  - `press`, `type`, `undoKey`, `caretToEnd`;
  - `screenshot(name)`, `capture(clip)`.
  - It also has the read-only `eval` and `waitFor`.
- `editor` (`lib/editor.mjs`): reads the editor's state and wraps common gestures:
  - state: `outline`, `propsHeader`, `selectedHandle`, `selectionOverlay`,
    `paneWidths`, `undoRedo`, `pill`, `crumbs`, `canvasHint`, `propField`;
  - canvas: `findColor(argb)`, which says where the canvas drew a colour;
  - real-input wrappers: `clickOutlineRow`, `clickOutlineRowOf`, `clickOn`,
    `appendToField`, `clearSelection`, `clickUndo`, `clickRedo`.
- `relay`: the relay's view, for expectations: `doc(screen)` and
  `components()`. Use `setActive` only on this scratch relay.
- `fixtureDir`: the fixture copy the relay writes to. `lib/fixture.mjs` has
  `snapshot`, `changedSources`, `waitForFile` and `expectBytes`.
- `artifacts` and `log(msg)`: evidence that is kept with the test's results.

A few rules for new tests:

- Drive the editor only through `page`'s input methods. Never call
  `element.click()` or set values from script.
- Find canvas content by its fixture colour, not through the editor's own
  geometry, unless that geometry is what the test checks.
- Leave the fixture as you found it: undo your edits, then assert
  `expectBytes`.

## CI

The `editor-e2e` job in `.github/workflows/ci.yml` runs on `ubuntu-latest`
(Chrome, on SwiftShader WebGL). It runs:

- on pushes to main;
- on manual runs;
- on pull requests that touch any of these: the editor, `portal-render`,
  `portal-core`, `portal-document`, `portal-ingest`, `portal-relay`,
  `keliver-material-composeui`, `web-spike`, or `editor-e2e`.

On failure it uploads `build/editor-e2e/` (screenshots and logs) as an
artifact.
