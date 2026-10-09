# An "existing" Android app that embeds a Keliver screen (W2)

A plain View-based app — an Application, a launcher activity with native
views, and a native second screen — standing in for an adopter's app. The
lines marked `KELIVER EMBED` are exactly what `keliver-new-production-host.sh
--embed` tells an adopter to add; nothing else here knows about Keliver.

`reference/inventory/ci/prepare.sh` copies this app, gives it the inventory
app's Gradle wrapper, scaffolds `keliver-host/` into it with `--embed` (the
host package is `inventory.host`, from the inventory guest's
`inventory.screens`), and builds it. `ci/w2/android-embed.sh` then checks it on
the emulator: the native views and the guest screen on one screen, one signed
load across native navigation and rotation, the rollback floor stored, and a
foreign key refused inside the Keliver view while the native views stay.
