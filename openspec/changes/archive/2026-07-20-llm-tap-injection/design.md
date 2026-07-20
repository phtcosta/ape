# Design: llm-tap-injection

## Context

Two defects proven by trace forensics (`docs/20260720_analise_forense_cmpm_traces.md`):

- **A1**: `MonkeySourceApe.java` `MODEL_LLM_TAP` case builds `Rect(x, y, x, y)`. Android's
  `Rect.intersect` accepts a strictly-interior point-rect but yields an *empty* intersection, and
  `Rect.contains` is always false on an empty rect; an edge/outside point fails `intersect`
  outright. Either way `generateClickEventAt` drops the action (INV-EXPL-19 guard) — 100% of
  16,625 campaign taps, deterministically.
- **A2**: an ephemeral `LlmTapAction` held as `currentAction` across an in-step model rebuild keeps
  its removed anchor state (by INV-MODEL-16 design); `StatefulAgent.updateGraph:981` →
  `Graph.addTransition` → `StateTransition.<init>:47-51` throws `IllegalStateException`
  (`!source.equals(action.getState())`) and terminates the run.

Constraints: P1 simplicity (minimal diff, no new flags); INV-EXPL-19's protection for genuinely
off-screen coordinates must remain; non-ephemeral mismatches must keep failing loudly (they signal
real model corruption); baseline suite 645/0/19 stays green.

## Goals / Non-Goals

**Goals**
1. A `MODEL_LLM_TAP` whose pixel lies inside the visible bounds injects a touch down/up at exactly
   that pixel.
2. A stale-anchored ephemeral action never crashes transition recording; the edge is skipped and
   logged.

**Non-Goals**
- Changing which bounds the guard checks (root-node visible bounds vs display). The forensics saw
  v2 lose ~50% of taps to the `bounds==null` branch; how much of that survives the zero-area fix is
  measured at the device gate, not redesigned here.
- markVisited-before-event-generation reorder (visit crediting of dropped actions) — separate debt.
- Re-anchoring the ephemeral `currentAction` to the rebuilt state (would fabricate an edge the
  model never observed; skip is semantically honest and cheaper).
- rv-android/campaign-doc updates (laudo already records them; other repo).

## Mapping: Spec → Implementation → Test

| Spec item | Implementation | Test |
|---|---|---|
| INV-EXPL-30 / Requirement "LLM Tap Injectable Rect" | `LlmTapAction.toInjectableRect()`; `MonkeySourceApe` MODEL_LLM_TAP case uses it | `LlmTapActionTest` (new cases: rect geometry, non-empty, center; guard-arithmetic simulation with `android.graphics.Rect`) |
| MODIFIED "Off-Screen Action Handling" (coordinate case) | same as above (single construction site) | same; out-of-bounds simulation case |
| INV-MODEL-17 / MODIFIED "Ephemeral Quarantine Through Rebuild" (addTransition path) | guard at top of `Graph.addTransition` | `GraphEphemeralActionTest` (new cases: stale ephemeral → null + no throw; non-ephemeral mismatch → still throws) |

## Decisions

1. **`Rect(x, y, x+1, y+1)` over guard special-casing.** A 1×1 rect flows through the existing
   guard unchanged: interior pixel → non-empty intersection → `contains(x, y)` true → click point
   `(int) exactCenterX() = x`. Edge/outside pixel → `intersect` fails → existing drop line. No new
   branch in `generateClickEventAt`, INV-EXPL-19 preserved verbatim for node actions. Alternative
   (bypass the guard for taps) rejected: loses off-screen protection the LLM does need (v2 emitted
   thousands of edge/outside coordinates).
2. **Rect construction lives in `LlmTapAction.toInjectableRect()`.** Single site, JVM-unit-testable
   (the framework stub jar provides a functional `android.graphics.Rect`), and `MonkeySourceApe`
   (not instantiable in JVM tests) shrinks to a pass-through — mirrors how the forensic gap arose:
   every layer except the dispatch was tested.
3. **A2 guard in `Graph.addTransition`, not `StatefulAgent.updateGraph`.** The graph method is the
   chokepoint every recording path funnels through, is already exercised by
   `GraphEphemeralActionTest`, and the null return is tolerated by the only consumer that reads
   `currentStateTransition` after a step (`checkNonDeterministicTransitions` early-returns on
   null). Guard condition mirrors the ctor invariant exactly: `action.isEphemeral() &&
   !source.equals(action.getState())`.
4. **Skip, don't re-anchor.** An ephemeral edge is observational; INV-MODEL-16 already purges it
   from replay/history. Fabricating a re-anchored edge would record a transition from a state the
   action was never decided in.

## Error Handling

| Error | Source | Strategy | Recovery |
|---|---|---|---|
| Tap pixel outside visible bounds | LLM coordinate | existing drop + log line | agent proceeds to next step (unchanged) |
| Stale ephemeral action at `addTransition` | rebuild during in-flight tap | skip edge, log `[APE-RV] stale ephemeral edge dropped`, return null | step completes; `currentStateTransition == null` path already handled |
| Non-ephemeral source mismatch | model corruption | unchanged `IllegalStateException` | run terminates loudly (desired) |

## Risks / Trade-offs

- [Risk] `bounds==null` drops may still swallow many taps if `getCurrentRootNodeBounds()` is
  smaller than the display (IME/nav insets) → measured at the device gate; if material, a separate
  change decides the correct bounds source.
- [Risk] a skipped ephemeral edge leaves `currentStateTransition == null` in flows other than
  `checkNonDeterministicTransitions` (e.g. `resolveNewActionFromBuffer`'s
  `currentStateTransition.isSameActivity()` at `StatefulAgent.java:468`) → that line is reachable
  only with a non-empty action buffer, a state already possible today (`currentStateTransition` is
  nulled at 525/1280); no new exposure, verified during implementation.
- [Trade-off] the tap's model edge (state→state via LLM_TAP) is unchanged; we fix injection only.
  Coverage effects of actually-working taps are an experimental question (cmpm re-run).

## Testing Strategy

TDD RED→GREEN per task group; JVM suite via `mvn test` (baseline 645/0/19). Device gate before
archive (fresh-install regime): `pm clear` + foreground-first launch + SGLang (v2 model), assert
(i) an in-bounds `MODEL_LLM_TAP` step has **no** paired drop line and the subsequent GUITree
differs, (ii) drop lines appear only for out-of-bounds coordinates, (iii) a refinement step with a
pending ephemeral tap logs the stale-edge skip (or does not occur) and the run reaches its time
budget.
