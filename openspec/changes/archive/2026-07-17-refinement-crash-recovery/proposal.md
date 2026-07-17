# Proposal: refinement-crash-recovery

## Why

At the cmpv2 experiment (LLM arm, 600s), ~45% of runs terminate early: a naming refinement
triggered by a non-deterministic transition rebuilds the model, an exception kills the exploration
loop, and `tearDown` then crashes replaying the action history (`IllegalStateException: Cannot find
widget` from `GUITree.pickNodes`), exiting with code 1. The visible stack is not the loop-killer:
`Monkey.run`'s `finally { tearDown() }` replaces the original in-loop exception with the teardown
one (Java finally semantics), so the primary evidence is destroyed on every crash. The root defect —
post-refinement actions and history records carrying pre-refinement `Name`s that no longer bind in
the re-abstracted `GUITree` — is 2019 upstream code, newly exposed at high rate by the
`MODEL_LLM_TAP` regime (taps navigate into repeating-row substrates → non-determinism → refinement).
Full diagnosis with confidence levels: `docs/20260716_investigacao_truncamento_600s_llm_tap.md`.

A follow-up sweep found the masking pattern is not unique: four sites on the exploration/teardown
path can replace or destroy the original exception, and the teardown chain loses all remaining
persistence steps when any step throws.

## What Changes

- **Un-mask the loop-killer (F-A).** Guard each throw-capable statement in `Monkey.run`'s `finally`
  (`MonkeyRotationEvent.injectEvent`, `MonkeySourceApe.tearDown`) so a teardown failure is logged
  but never replaces the in-flight exception, and the rotation restore can never skip teardown.
- **Make teardown step-isolated.** `MonkeySourceApe.tearDown` (disconnect → agent teardown → writers
  → loggers → timeline) and `StatefulAgent.tearDown` (LLM summary → graph → action history →
  counters → activity nodes → naming dump) each guard their steps individually: one failing step
  logs its own stack and the remaining steps still run. Today `disconnect()` throwing skips the
  entire model save; `saveActionHistory` throwing skips four diagnostic dumps.
- **Fix the `Naming` finally NPE.** `Naming.java` logs `results.getNameSize()` in a `finally` while
  `results` is null when `namingInternal` threw — the NPE replaces the original exception (e.g.
  `"A node has no namelets"`). Move the log inside the success path.
- **Tolerant action-history persistence (F-B).** `Model.saveActionHistory` resolves each
  `ActionRecord` inside a per-record guard: an unresolvable record (stale pre-refinement descriptor)
  is skipped with a warning and counted, instead of aborting the file and crashing the process. A
  summary line reports `skipped=N`.
- **Graceful post-refinement rebind (F-C) — REMOVED at the gate.** The originally proposed guard
  in `validateAllNewActions`/`validateNewAction` bet that the in-loop killer lived there; the
  2026-07-17 analysis predicted otherwise and the F-D gate confirmed it (`RebindFailures total=0`,
  stack entirely upstream). The speculative guard was removed per P1 (design.md D7 outcome 1,
  task 2.3).
- **Confirm the loop-killer identity (F-D) — DONE, and fix it.** With masking removed, the
  deterministic fixture (`app.maskan.chat`, fresh install, crashes in ~20–30s) reported the
  in-loop exception: `IllegalStateException: No such action [MODEL_LLM_TAP]` in `Model.rebuild`'s
  transition replay — a `MODEL_LLM_TAP` quarantine hole (ephemeral edges collected by
  `Graph.remove` with no filter, then re-anchored by `State.getAction(type)`, which cannot
  succeed by INV-MODEL-14). The real fix (task 2.4): exclude ephemeral edges from the rebuild
  replay (purging their tree-history entries) and return ephemeral references unchanged from the
  post-rebuild re-anchor (INV-MODEL-16).

Not in scope: MED/LOW sweep findings outside the main path (LLM side-path swallows are
circuit-breaker-backed; startup `System.exit` sites fire before any teardown exists), and the
rv-android exit-code decoupling fix (other repository).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `exploration`: teardown execution contract — INV-EXPL-16 extended (teardown SHALL NOT mask the
  original exception; steps are individually guarded so persistence survives a failing step).
- `model`: (a) ephemeral quarantine extended through model rebuild — ephemeral edges are excluded
  from the replay and from post-rebuild re-anchoring (INV-MODEL-16, the fix for the proven
  terminator); (b) action-history persistence — per-record resolution failures skip the record
  with telemetry instead of aborting; `ActionRecord` resolution contract documented.

## Impact

- **Components**: `Monkey.run` (finally block), `MonkeySourceApe.tearDown`,
  `StatefulAgent.tearDown`, `Model.saveActionHistory` / `Model.ActionRecord.resolveModelAction`,
  `Model.rebuild`/`Model.update` + `Graph.removeFromTreeHistory` (ephemeral quarantine),
  `Naming.naming` (finally log).
- **Behavior**: crashed runs now exit with the *original* exception visible and full artifacts
  persisted; a refinement that removes states touched by an LLM-tap edge no longer kills the run.
  Runs that previously truncated at 83–350s complete their budget. No behavior change on healthy
  paths.
- **Experiments**: unblocks `cmpv2` (rv-android) — its LLM arm depends on 600s runs surviving
  refinements. Truncation telemetry (`skipped=N`, ephemeral-drop counts) feeds the re-run
  analysis.
- **Dependencies**: none added. Existing JUnit suite extended (model/agent test conventions).
- **Risk**: guards are additive on failure paths only; the main risk is over-broad catching hiding
  a real defect — mitigated by logging every guarded failure with a full stack trace.
