# scoring-pipeline Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`: the per-step outcome attribution stops being a separate `[APE-OUTCOME]` line joined to `[APE-STEP]` by a shared `step=` key and becomes the `out` section of the step's own `StepRecord` (event-sink capability) — the join exists by construction. The buffer discipline, emission point, guards, and refinement remap are preserved exactly; only the encoding changes, and the telemetry gate dies: recording is always-on and identical for all arms. INV-ARCH-01 — the baseline arm's telemetry blindness — is **deliberately dissolved** by this change (owner decision, report Sec. 8); its recorded substitute is the neutrality invariant INV-SNK-07 with its permanent sink-on/off parity test. The `[APE-ARCH] passes=[...]` assembly line is replaced by the `PIPELINE` sink record.

## MODIFIED Requirements

### Requirement: Per-Step Decision Outcome Attribution

After a state transition is recorded (`Model.addTransition(source, action, target, ...)` in `StatefulAgent.updateGraph()`), `StatefulAgent` SHALL attribute the executed action's result back to the decision that selected it by attaching the `out` section to that decision's pending `StepRecord` and closing it (event-sink capability). Attribution is unconditional — there is no telemetry gate and no arm-level variation. Call, decision, and outcome share one record: no offline join exists.

The record closed SHALL be the one opened at the exploration step at which the executed action was **selected**. Because an action is selected at step N and its resulting transition is only observed during step N+1's processing (the agent timestamp has already advanced), `StatefulAgent` SHALL buffer the selection step and the selected action at decision time, and consume the buffer at outcome time.

**Buffer discipline.** The non-model decision branch (event-level actions, e.g. the stagnation activity launcher) SHALL clear the buffer instead of writing it: non-model actions do not produce transitions under their own identity, and a stale model-action buffer could otherwise be resurrected later by state recovery (`recoverCurrentState()` re-installs the last history action as `currentAction`). The non-model step's record is closed without an `out` member.

**Emission point and guards.** The outcome closure SHALL live in `StatefulAgent.updateGraph()`, immediately after `Model.addTransition(...)` returns — NOT inside `Model`/`Graph` — so the refinement rebuild replay (which re-records transitions via the `Graph.addTransition(GUITreeTransition)` overload, bypassing `Model.addTransition`) cannot close spurious records. Closure with `out` SHALL occur only when ALL of the following hold, and the buffer SHALL be consumed (cleared) upon closure:

1. the `StateTransition` returned by `Model.addTransition` is non-null (`addTransition` returns null on the run's first step, after restarts, and on the stale-ephemeral drop);
2. a decision is buffered and the buffered action is reference-equal to `currentAction` (state recovery and non-model interludes install a different action object; a mismatch means the recorded transition does not belong to the buffered decision);
3. the buffer has not already been consumed for this decision — single-shot consumption guarantees that a second `addTransition` for the same decision (the `BadStateException` selection-retry path re-enters the update without advancing the timestamp; recovery can re-record the last history action) cannot attach a duplicate outcome.

**Refinement remap.** Model refinement (`preEvolveModel()` → `updateModel()`) replaces `currentAction` with the corresponding action object of the rebuilt model before `updateGraph()` runs. `updateModel()` SHALL remap the buffered action through the same `model.update(...)` mapping applied to `currentAction`, so refinement steps still receive their `out`. Without the remap, the reference guard would fail exactly on the non-deterministic steps — a systematic attribution bias against the most informative steps.

The `out` section SHALL carry:

| Field | Source |
|-------|--------|
| `new_state` | `true` when the target state was visited for the first time (`_isNewState`); omitted when `false` |
| `target` | target state's run-local `STATE` dictionary ID (the state key is on the dictionary entry) |
| `act_changed` | `true` when the target activity differs from the source activity (negation of the recorded `StateTransition.isSameActivity()`); omitted when `false` |

`decision_source` is not repeated in `out` — it is the same record's `dec.src` by construction. The outcome-side `activity_has_mop` is derivable via `out.target → STATE.act → ACT.mop`. `target` reports the state observed at the next model update: when fuzzing piggybacks events after the selected action, or a bad-state `EVENT_ACTIVATE` interlude executes, the recorded transition — and hence `target`/`new_state` — reflects the selected action plus those trailing events; offline analysis SHOULD treat the outcome as "state reached by the step", not "immediate post-action state".

- **INV-ARCH-09**: A `StepRecord`'s `out` section SHALL describe the transition of the action recorded in that same record's `dec` section (enforced by the reference-equality buffer guard), and at most one `out` SHALL ever be attached per record. A record MAY be closed without an `out` member — when the selected action produced no recorded transition (restart, refinement discard, non-model action, run end) — which is a legitimate, informative absence, not an error; it is distinct from the teardown flush encoding `out:{"resolved":false}`.

#### Scenario: LLM decision attributed to a new-state discovery

- **WHEN** an action with `decision_source=LLM` selected at step 42 executes and the resulting transition reaches a state visited for the first time
- **THEN** the `s:42` record SHALL be closed with `out` carrying `new_state:true` and the target state's dictionary ID
- **AND** the LLM call's sub-event, the decision, and the outcome SHALL all be members of that single record — no join key exists or is needed

#### Scenario: Attribution identical in every arm

- **WHEN** the minimal control preset runs the same APK and seed as the full MOP+LLM preset
- **THEN** both runs SHALL attach `out` sections under identical rules (no gate, no arm-level suppression)
- **AND** the neutrality gate (INV-SNK-07) — not telemetry absence — is what protects the control arm's integrity

#### Scenario: Selected action with no recorded transition

- **WHEN** an action is selected at step 50 but the step ends in a restart before any transition is recorded (or it is the run's first step, where `addTransition` returns null)
- **THEN** the `s:50` record SHALL be closed without an `out` member
- **AND** this absence SHALL NOT be treated as an error by offline analysis ("selected, no clean transition")

#### Scenario: BadStateException retry attaches a single outcome

- **WHEN** the transition for the action selected at step 60 is recorded, and action selection then throws `BadStateException`, causing the update to re-run `Model.addTransition` within the same step
- **THEN** the `s:60` record SHALL be closed exactly once with exactly one `out` (the buffer was consumed by the first closure)

#### Scenario: Refinement step still attributed

- **WHEN** the action selected at step 70 executes, and model refinement replaces `currentAction` with the rebuilt model's action object before `updateGraph()` runs
- **THEN** the buffered action SHALL be remapped alongside `currentAction`
- **AND** the `s:70` record SHALL be closed with its `out` for the recorded transition

### Requirement: Parity Configuration Flags

`Config.java` SHALL declare the following flags, loaded from `ape.properties` at class-loading time. Every default SHALL preserve current aperv behavior. Each flag is the activation key of the corresponding `Feature` in the run-spec capability's feature model (per `rearch-02-runspec`); with a flag `false`, the feature is absent from the resolved plan and its mechanism is not constructed.

| Flag | Property Key | Type | Default | Gate |
|------|-------------|------|---------|------|
| `formCompletionEnabled` | `ape.formCompletionEnabled` | boolean | `true` | `FormCompletionPass` + the deterministic-fill branch in `ApeAgent.checkInput()` |
| `modelMenuEnabled` | `ape.modelMenuEnabled` | boolean | `true` | inclusion of the fork `menuAction` in `State.getActions()` |
| `leastVisitedPriorityTiebreak` | `ape.leastVisitedPriorityTiebreak` | boolean | `true` | the priority tiebreak in `State.greedyPickLeastVisited()` |
| `treeEnhancementsEnabled` | `ape.treeEnhancementsEnabled` | boolean | `true` | the three `GUITreeBuilder` perception enhancements (WebView-prune actionable count, AndroidX actionability, ViewPager scrollable) |
| `activityBudgetEnabled` | `ape.activityBudgetEnabled` | boolean | `true` | `ActivityBudgetTracker` instantiation + the budget check in `SataAgent.selectNewActionNonnull()` |

The former `stepTelemetryEnabled` flag is deleted by this change: step recording is always-on (event-sink capability) and no configuration key gates or alters it. The former `apePureMode` row is deleted by `rearch-02-runspec` together with its mechanism.

- **INV-ARCH-07**: With none of these keys set in `ape.properties`, `formCompletionEnabled`, `modelMenuEnabled`, `leastVisitedPriorityTiebreak`, `treeEnhancementsEnabled`, and `activityBudgetEnabled` SHALL be `true`, and the agent's action-selection behavior SHALL be identical to the pre-change aperv.

#### Scenario: defaults preserve current behavior

- **WHEN** `ape.properties` sets none of the parity flags
- **THEN** the five behavior gates SHALL be `true`
- **AND** the pipeline, menu action, tiebreak, tree perception, and activity budget SHALL all be active as before this change

#### Scenario: telemetry has no gate

- **WHEN** `ape.properties` sets `ape.stepTelemetryEnabled=false` (a removed key)
- **THEN** the run SHALL abort at plan validation with an unknown-key error (fail-fast, run-spec capability) — there is no configuration that suppresses step recording

## Notes

### Disposition of the former requirement `apePureMode Kill-Switch and Parity`

The requirement itself is REMOVED from this capability by `rearch-02-runspec`, which owns the mechanism deletion — by the time this change lands, it is no longer in the main spec, so no REMOVED operation appears in this delta. The full disposition spans two stages: The `apePureMode` mechanism itself (Properties-overwrite kill-switch, string registry, forced-off flags) is deleted by `rearch-02-runspec`: purity becomes structural — a feature absent from the plan does not exist in the run, and the effective plan is echoed in `RUN_START` (report Sec. 6.6). The telemetry half — **INV-ARCH-01**, the baseline arm's zero-telemetry parity ("zero `[APE-STEP]` lines") — is deliberately dissolved by **this** change (report Sec. 8, owner decision): telemetry becomes universal, identical for all arms, and *provably neutral*; the recorded substitute for the property INV-ARCH-01 protected (the control arm is not contaminated by RV machinery) is INV-SNK-07 and its permanent sink-on/off neutrality test (R7, report Sec. 9.8). Complete deletion, no compatibility shim: `Config.stepTelemetryEnabled`, the kill-switch registries, and the gated emitters are removed with their gates (P3). Upstream-parity comparison, if ever needed again, is anchored on the frozen phase-2 data (owner decision D3), not on a runtime mode.
