# Proposal: llm-tap-injection

## Why

Forensic analysis of the 1086 cmpm campaign traces (`docs/20260720_analise_forense_cmpm_traces.md`)
proved two defects in the `MODEL_LLM_TAP` execution path:

- **A1 (P0):** the dispatch builds a zero-area `Rect(x, y, x, y)` (`MonkeySourceApe.java:960`) and
  hands it to `generateClickEventAt`, whose INV-EXPL-19 guard rejects it deterministically —
  an empty `Rect` can never pass `Rect.contains` and never survives `Rect.intersect` as a non-empty
  intersection. **100% of the campaign's 16,625 LLM taps injected no event** (drop lines pair 1:1
  with tap steps in both arms). The feature has been a costly no-op since birth: each tap consumed
  an LLM call (~1s) plus a full exploration step, froze the screen, and at `temperature=0` induced
  repeat-tap loops (median 2.7 taps per coordinate in the v2 arm, max 70).
- **A2 (P1):** when an ephemeral `LlmTapAction` is the agent's `currentAction` and an action
  refinement rebuilds the model in the same step, the rebuilt `currentState` no longer equals the
  ephemeral action's (removed) anchor state. `StatefulAgent.updateGraph` then feeds the stale pair
  into `Graph.addTransition` → `StateTransition.<init>` throws `IllegalStateException`, killing the
  run (cmpm's only truncation: floflacards v2 rep1, 82s). This is the residual agent-side gap of
  INV-MODEL-16, which quarantined ephemeral edges on the model side only.

## What Changes

- `MODEL_LLM_TAP` dispatch constructs a minimal injectable rect `(x, y, x+1, y+1)` so the tap
  delivers a real touch event at the decided coordinate; coordinates outside the visible bounds are
  still dropped (guard semantics unchanged).
- The transition-recording path refuses a stale ephemeral action: if the action is ephemeral and its
  anchor state is not the transition source, the edge is skipped with an `[APE-RV]` log line instead
  of constructing a `StateTransition` that throws. Callers already tolerate a null
  `currentStateTransition`.
- No config flags added or changed. No behavior change for non-ephemeral actions or for genuine
  off-screen coordinates.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `exploration`: `MODEL_LLM_TAP` dispatch requirement — the tap MUST inject a touch at the decided
  coordinate when it lies within visible bounds (new invariant; INV-EXPL-19 wording extended to
  define the coordinate-action case).
- `model`: transition-recording requirement — extend the ephemeral quarantine (INV-MODEL-16) to the
  `addTransition` path: a stale-anchored ephemeral action yields a skipped edge, never a thrown
  `IllegalStateException`.

## Impact

- `src/main/java/com/android/commands/monkey/MonkeySourceApe.java` (tap dispatch).
- `src/main/java/com/android/commands/monkey/ape/model/Graph.java` (stale-ephemeral guard in
  `addTransition`).
- Tests: new dispatch-rect unit tests; new stale-ephemeral transition test reusing the
  `GraphEphemeralActionTest` harness. Baseline suite 645/0/19 must stay green.
- Experimental interpretation: cmpm H2's "off-tree recovery" becomes a real behavior only after this
  change; the campaign doc §11.4/§11.7 narrative is qualified by the forensic report (rv-android
  side, out of scope here).
- On-device gate (fresh-install + SGLang) required before archive: tap must change the screen;
  no drop lines paired with in-bounds taps; no `IllegalStateException` under refinement with a
  pending ephemeral action.
