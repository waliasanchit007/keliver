# An "existing" Android app that embeds a Keliver screen (W2)

A plain View-based app — an Application, a launcher activity with native
views, and a native second screen — standing in for an adopter's app. The
lines marked `KELIVER EMBED` are exactly what `keliver-new-production-host.sh
--embed` tells an adopter to add. The lines under "The host kit" in
`MainActivity` and `ExistingApp` are the optional part (K4): a deep link's
route (`existing://<path>?tab=…`; only `tab` reaches the guest), the native
view that takes the Keliver view's place when the guest closes it (`onClose`)
or it cannot show (`onUnavailable`), and the app's `RequestPolicy` for the
guest's HostHttp requests (the API base's origin, `GET ping` only, nothing
state-changing; `okhttp` in `app/build.gradle` is there for its `HttpUrl`).
Nothing else here knows about Keliver.

`reference/inventory/ci/prepare.sh` copies this app, gives it the inventory
app's Gradle wrapper, scaffolds `keliver-host/` into it with `--embed` (the
host package is `inventory.host`, from the inventory guest's
`inventory.screens`; the API base is the static server's `/api`), and builds
it. `ci/w2/android-embed.sh` then checks it on
the emulator: the native views and the guest screen on one screen, one signed
load across native navigation and rotation, the rollback floor stored, and a
foreign key refused while the native views stay and the app's fallback takes
the Keliver view's place; then the host kit's routes: a route reaching the
guest without the link's other parameters, the guest closing its screen, the
guest throwing (`screen-failed`), and the app's `RequestPolicy` refusing the
guest's HostHttp requests before the network (X11).
