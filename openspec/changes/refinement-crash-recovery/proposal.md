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
- **Graceful post-refinement rebind (F-C).** After a naming refinement rebuilds the model,
  `validateAllNewActions`/`validateNewAction` treat a rebind failure (`Cannot find widget`) as
  "action invalidated" (`setValid(false)` — the only signal every action filter honours; leaving it
  merely unresolved would keep it selectable with a stale resolved node) — logged and counted,
  never an exploration-loop abort. This targets the most likely in-loop killer.
- **Confirm the loop-killer identity (F-D).** With masking removed, reproduce on the deterministic
  fixture (`app.maskan.chat`, crashes in ~20–30s) and verify the logged in-loop exception matches
  F-C's fix; extend if a different terminator appears.

Not in scope: MED/LOW sweep findings outside the main path (LLM side-path swallows are
circuit-breaker-backed; startup `System.exit` sites fire before any teardown exists), and the
rv-android exit-code decoupling fix (other repository).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `exploration`: teardown execution contract — INV-EXPL-16 extended (teardown SHALL NOT mask the
  original exception; steps are individually guarded so persistence survives a failing step);
  post-refinement action revalidation becomes tolerant of rebind failures.
- `model`: action-history persistence — per-record resolution failures skip the record with
  telemetry instead of aborting; `ActionRecord` resolution contract documented.

## Impact

- **Components**: `Monkey.run` (finally block), `MonkeySourceApe.tearDown`,
  `StatefulAgent.tearDown`, `Model.saveActionHistory` / `Model.ActionRecord.resolveModelAction`,
  `StatefulAgent.validateNewAction`/`validateAllNewActions`, `Naming.naming` (finally log).
- **Behavior**: crashed runs now exit with the *original* exception visible and full artifacts
  persisted; refinement-heavy runs no longer die at the first stale rebind. Runs that previously
  truncated at 83–350s complete their budget. No behavior change on healthy paths.
- **Experiments**: unblocks `cmpv2` (rv-android) — its LLM arm depends on 600s runs surviving
  refinements. Truncation telemetry (`skipped=N`, rebind-failure counts) feeds the re-run analysis.
- **Dependencies**: none added. Existing JUnit suite extended (model/agent test conventions).
- **Risk**: guards are additive on failure paths only; the main risk is over-broad catching hiding
  a real defect — mitigated by logging every guarded failure with a full stack trace.
