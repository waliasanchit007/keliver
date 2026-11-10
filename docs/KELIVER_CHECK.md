# keliver-check output (schema 1)

`keliver-check [app-dir] [--skip-compile] [--only LIST] [--strict]` (PLAN_2026-11 WS5) ships in the
tools bundle's `bin/`. It prints one JSON object per line on stdout and a short human summary on stderr.
This page lists every key. The format version is the summary line's `schema`, which is currently 1.
A new key does not bump it. A key removed or changed in meaning does.

## What it touches

- **compile** runs the app's own `./gradlew <task>`. That writes `build/` in the app and may download
  dependencies.
- **editor**, **api**, **budget** and **goldens** only read files. They use no network and write nothing
  into the app.
- Nothing is published.

## Exit status

| Exit | Meaning |
|---|---|
| 0 | No errors. Warnings are allowed unless `--strict` is given. |
| 1 | At least one error, or with `--strict` at least one warning. |
| 2 | A usage or environment problem: no `keliver.portal.json`, no relay install, a compile task that is not a Gradle task path, or the file checks crashed. A crash never reads as a pass. The JVM half ends its stream with `{"check":"jvm-done"}`, and the wrapper returns 2 when that line is missing. The marker is removed from the output. |

## Keys

| Key | On | Meaning |
|---|---|---|
| `check` | every line | `compile`, `editor`, `api`, `budget`, `goldens`, or `summary` (always the last line) |
| `severity` | every line | `error`, `warning`, `info`, `pass` or `skip` (see below) |
| `message` | every line except `summary` | Human text. Do not parse it. |
| `file` | findings | Path relative to the app dir. For budget lines it is `publishOutput`. |
| `line` | findings | 1-based line number. Can be missing (see the RawCode limits below). |
| `task` | compile | The Gradle task that ran |
| `text` | editor, api | The first line of the offending source, at most 160 chars |
| `kindHint` | editor RawCode | The recognizer's display-only guess at what the code is |
| `rule` | api | Rule id: `android`, `androidx`, `ios-platform`, `java`, `jvm-reflection`, `kotlin-jvm`, `jvm-system`, `blocking`, `di-reflection`, `http-client`, `db-driver`, `file-io` |
| `bytes` | budget | Total size of every file under `publishOutput` |
| `budgetBytes` | budget | `check.bundleBudgetBytes` |
| `files` | budget | Number of files measured |
| `largest` | budget | `"<path> <N> B"` for the largest file |
| `rebuilt` | budget | `true` if this run's compile wrote the bundle. `false` if the compile left it untouched (the task was up to date, or `check.compileTask` does not write `publishOutput`). Missing if compile did not run. |
| `schema` | summary | Format version (1) |
| `errors`, `warnings` | summary | Counts over all lines |
| `exit` | summary | The exit status |

### Severities

- **Per finding:** `error` or `warning`.
- **Per-check aggregate line:** `pass` when that check found nothing. `info` when it found problems:
  - the editor's `N problem(s)` line;
  - the api scan's `N warning(s)` line.

  Count findings by `severity`, not by aggregate lines.
- **`skip`:** the check did not run. This happens with `--skip-compile`, when there is no budget, when
  the compile failed (so the old bundle is not measured), and for `goldens`, which is not implemented yet.

## Config (`keliver.portal.json`)

```json
"check": { "compileTask": ":guest:compileProductionZipline", "bundleBudgetBytes": 600000 }
```

- `compileTask` defaults to `publishTask`. It must be a task that writes `publishOutput`, because the
  budget measures that directory. It must also be a Gradle task path: letters, digits, `:_.-`, and not
  starting with `-`.
- Screens and components are read flat from their directories, as the relay reads them. The api scan
  walks the guest's screens, components, flows and logic directories recursively.

## Limits

- **RawCode `line`:** found by searching the file for the RawCode's text, not from a source position.
  - Repeated identical snippets get successive lines.
  - If the search from the previous match finds nothing, the first match anywhere in the file is used. So
    a snippet that also appears earlier (in a comment, a string, or an identical statement out of tree
    order) can get the earlier line.
  - If the text is not in the file verbatim, `line` is missing.
  - Follow-up: carry the PSI offset on `DocNode.RawCode`.
- **api:** works line by line on source with comments removed. Code inside multi-line raw strings, and
  strings containing rule text, can give false positives. Every match is a warning to look at, not proof.
- **Components:** the editor check reads each component's body the way the relay opens a component
  document (`Recognizer.recognize(asComponent = true)`). Each of these is an error:
  - a file with no `@Composable`;
  - a component whose body is not recognized;
  - a component whose first `@Composable` is not the registered component, which the relay refuses to open.
