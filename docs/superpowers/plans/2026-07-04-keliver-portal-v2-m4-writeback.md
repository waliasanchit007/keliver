# V2 M4: Surgical Write-Back Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox steps.

**Goal:** Portal/agent ops update the `.kt` file WITHOUT regenerating it — comments, formatting, and RawCode outside the touched nodes survive byte-exact. Closes the M1 "whole-file dual-write" caveat.

**Architecture (S2 recipe, sharpened):** `portal-ingest` gains `NodeEmitter` (DocNode → canonical Kotlin text, the exporter's format) and `WriteBack.merge(fileText, targetDoc): String?`: parse the CURRENT file, recognize with PSI refs retained, match parsed↔target trees (same identity rules as the Reconciler), then apply the *smallest* PSI edit per difference:
- props/modifiers changed → replace ONLY the call's `valueArgumentList` (trailing lambda = children + inner comments + RawCode untouched);
- type changed → full expression replace;
- new child → emitted text inserted at the sibling anchor (statement add in the parent's lambda block);
- removed child → statement delete;
- RawCode text change → expression replace;
- contract changed → replace the `*Bindings` interface declaration (portal-owned members; hand-added member preservation = M4.1 note).
`DocumentService.writeKotlin` tries `merge` first; falls back to whole-file export when the file is missing/unrecognizable (bootstrap). Self-write suppression unchanged.

**Gates:** fidelity corpus test (comments + odd spacing + RawCode preserved byte-exact outside touched nodes); ops→merge→re-ingest→document equality; end-to-end: portal edit while the file carries hand comments → file diff touches ONLY the edited arg list.

**Tasks:** 1) NodeEmitter + tests. 2) Recognizer retains PSI refs (transient map). 3) WriteBack.merge + fidelity tests. 4) DocumentService switch + end-to-end + commit/push.
