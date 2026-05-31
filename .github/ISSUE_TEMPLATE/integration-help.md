---
name: Integration help
about: I'm trying to adopt Keliver and something isn't working / I'm stuck.
title: '[help] '
labels: question
---

<!--
READ FIRST: most adoption pain is one of the documented silent-failure
shapes in docs/KNOWN_BUGS.md (U1–U11). Skim it before opening this
issue — there's a good chance the answer is already there with a
ready-made mitigation (Spec.retain, Spec.bindWithTimeout,
Spec.requireSerializerOf, TreehouseDispatchers.ui hop, etc.).

If you've checked KNOWN_BUGS.md and you're still stuck, fill out below.
-->

## What you're trying to do

<!-- "Wire a HostFoo service to call back into a NavController" etc. -->

## Where you're stuck

<!--
Which step is failing? Bind hang? Guest can't reach the service?
UI doesn't react? Crash? "Nothing visible happens" is a real answer —
say so.
-->

## What you've already tried

<!--
Including which KNOWN_BUGS entries you ruled out and why.
-->

## Minimal repro

<!--
The smallest snippet that reproduces the problem. Include both guest
and host side. If you can't isolate a minimal repro, paste your
TreehouseApp.Spec subclass + the relevant @Widget/service interface.
-->

```kotlin
// host
```

```kotlin
// guest
```

## Environment

- Keliver version:
- Host platform (Android / iOS / both):
- Kotlin version:
