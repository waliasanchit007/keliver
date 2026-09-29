# Standalone Android production host: does it compile from published dependencies?

The measurement `docs/PRODUCTION_HOST_FEASIBILITY.md` asked for. It was run on macOS on 2026-09-29, with the Keliver checkout at `b4ba6f219` and the host files taken from `b5615637`.

Paths are shortened to `<work>` (the throwaway project and its disposable Gradle home) and `<keliver-checkout>`.

| file | what |
|---|---|
| `measure.sh` | Generates the project (two variants) outside the checkout and builds it. It uses only public repositories, reads no store, embeds no key and signs nothing. |
| `result.txt` | Its output. Both variants resolve every dependency. Variant A (the three host files unchanged) fails to compile; variant B (plus one by-shape `HostApi`/`PortalPresenter` file) builds an APK. |
| `variant-A-errors.txt` | Every compile error in A. All of them come from the two unresolved names. |
| `variant-B-build.txt` | B's build outcome. |
| `variant-B-debugRuntimeClasspath.txt` | The resolved graph. Where a Keliver module publishes no `androidJvm` variant, it resolves to the `-jvm` artifact. |

Not measured:
- running the APK;
- a release (R8) build;
- the production defaults the note's item 1 lists;
- key embedding.
