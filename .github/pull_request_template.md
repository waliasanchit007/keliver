<!--
Thanks for the PR. A few quick things before reviewers can ship it:
-->

## Summary

<!-- 1–3 sentences. What does this change, and why. -->

## Closes / refs

<!--
- Closes #<issue>
- KNOWN_BUGS entry: U?
- Related upstream issue: cashapp/zipline#?
Delete lines that don't apply.
-->

## Wire-format check

<!--
Any change to @Widget / @Modifier / serialized service types?
- [ ] No (skip the rest of this section)
- [ ] Yes — and the change is additive (new property with a default,
      or a new tag at the end). No existing tags were reordered or
      removed. The 1.0.x wire format is committed.
-->

## Checklist

- [ ] CHANGELOG.md `[Unreleased]` section updated (short summary +
      issue / KNOWN_BUGS reference).
- [ ] `./gradlew :keliver-tooling-codegen:test` passes (if codegen
      touched — added a fixture demonstrating the new behavior).
- [ ] `./gradlew compileKotlinJvm compileKotlinIosSimulatorArm64`
      passes locally.
- [ ] Downstream ServerDrivenUI reference compiles against this
      change (or migration note added for adopters).
