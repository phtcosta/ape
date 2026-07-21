## Purpose

Delta spec for decision→outcome attribution (fix #2). The `[APE-STEP]` line carries `decision_source` at selection time, before the action executes, so it never records what the action produced. This delta adds an `[APE-OUTCOME]` line emitted after the resulting transition is recorded, correlated to `[APE-STEP]` by a `step` key, turning attribution into a deterministic per-step join. The line is fork telemetry gated by the same `stepTelemetryEnabled` flag; `apePureMode` forces that flag to `false` (per the `apePureMode Kill-Switch and Parity` requirement / INV-ARCH-06), so pure mode emits zero attribution lines and upstream-APE parity is preserved (INV-ARCH-01).

## ADDED Requirements

### Requirement: Per-Step Decision Outcome Attribution

When `stepTelemetryEnabled` is true, after a state transition is recorded (`Model.addTransition(source, action, target, ...)` in `StatefulAgent.updateGraph()`), `StatefulAgent` SHALL emit one `[APE-OUTCOME]` line attributing the executed action's result back to the `decision_source` that selected it. The line SHALL be correlated to the action's `[APE-STEP]` line by a shared `step` value, so an offline join on `step` pairs each decision with its outcome without any timestamp reconstruction. The `step=<N>` field contract on `[APE-STEP]` is defined by the `action-selection` capability (`Per-action decision-source telemetry`; pinned by this change's action-selection delta). LLM routing attempts carry the same `step` on their `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines (llm-routing delta), so for LLM-routed decisions the call, the decision, and the outcome all join on one key.

The `step` carried by `[APE-OUTCOME]` SHALL be the exploration step at which the executed action was **selected** (the `step` value emitted on that action's `[APE-STEP]` line), NOT the step at emission time. Because an action is selected at step N and its resulting transition is only observed during step N+1's processing (the agent timestamp has already advanced), `StatefulAgent` SHALL buffer the selection step and the selected action when the model-action `[APE-STEP]` line is emitted, and read the buffered values back when emitting `[APE-OUTCOME]`.

**Buffer discipline.** The non-model `[APE-STEP]` emission branch (event-level actions, e.g. the stagnation activity launcher) SHALL clear the buffer instead of writing it: non-model actions do not produce transitions under their own identity, and a stale model-action buffer could otherwise be resurrected later by state recovery (`recoverCurrentState()` re-installs the last history action as `currentAction`).

**Emission point and guards.** The `[APE-OUTCOME]` emission SHALL live in `StatefulAgent.updateGraph()`, immediately after `Model.addTransition(...)` returns — NOT inside `Model`/`Graph` — so the refinement rebuild replay (which re-records transitions via the `Graph.addTransition(GUITreeTransition)` overload, bypassing `Model.addTransition`) cannot emit spurious lines. Emission SHALL occur only when ALL of the following hold, and the buffer SHALL be consumed (cleared) upon emission:

1. the `StateTransition` returned by `Model.addTransition` is non-null (`addTransition` returns null on the run's first step, after restarts, and on the stale-ephemeral drop; an unguarded emission would fault on the very first step of every run);
2. a decision is buffered and the buffered action is reference-equal to `currentAction` (state recovery and non-model interludes install a different action object; a mismatch means the recorded transition does not belong to the buffered decision);
3. the buffer has not already been consumed for this decision — single-shot consumption guarantees that a second `addTransition` for the same decision (the `BadStateException` selection-retry path re-enters the update without advancing the timestamp; recovery can re-record the last history action) cannot emit a duplicate `[APE-OUTCOME]`.

**Refinement remap.** Model refinement (`preEvolveModel()` → `updateModel()`) replaces `currentAction` with the corresponding action object of the rebuilt model before `updateGraph()` runs. `updateModel()` SHALL remap the buffered action through the same `model.update(...)` mapping applied to `currentAction`, so refinement steps still emit their `[APE-OUTCOME]`. Without the remap, the reference guard would fail exactly on the non-deterministic steps — a systematic attribution bias against the most informative steps.

The line SHALL carry the following fields:

| Field | Source |
|-------|--------|
| `step` | buffered selection step of the executed action (join key to `[APE-STEP]`) |
| `decision_source` | `executedAction.getDecisionSource().name()` |
| `new_state` | `true` when the target state was visited for the first time (`_isNewState`), else `false` |
| `target_state` | target `State.getStateKey()` |
| `activity_changed` | `true` when the target activity differs from the source activity (negation of the recorded `StateTransition.isSameActivity()`) |

`target_state` reports the state observed at the next model update. When fuzzing piggybacks events after the selected action, or a bad-state `EVENT_ACTIVATE` interlude executes, the recorded transition — and hence `target_state` / `new_state` — reflects the selected action plus those trailing events. The `step` join remains exact; offline analysis SHOULD treat the outcome as "state reached by the step", not "immediate post-action state".

Example:
```
[APE-STEP]    step=42 clock=... activity=A state=S1 action=MODEL_CLICK decision_source=LLM ...
[APE-OUTCOME] step=42 decision_source=LLM new_state=true target_state=S7 activity_changed=false
```

- **INV-ARCH-08**: The `[APE-OUTCOME]` line SHALL be gated by `stepTelemetryEnabled` exactly as the `[APE-STEP]` line is. Under `apePureMode=true` — which forces `stepTelemetryEnabled=false` per the `apePureMode Kill-Switch and Parity` requirement (INV-ARCH-06) — zero `[APE-OUTCOME]` lines SHALL be emitted, preserving upstream-APE parity (INV-ARCH-01: zero telemetry lines).
- **INV-ARCH-09**: The `step` on an `[APE-OUTCOME]` line SHALL equal the `step` on the `[APE-STEP]` line of the action whose transition it reports. Every emitted `[APE-OUTCOME]` line SHALL have a matching `[APE-STEP]` line with the same `step`, and at most one `[APE-OUTCOME]` line SHALL be emitted per `step` value. An `[APE-STEP]` line MAY have no matching `[APE-OUTCOME]` line — when the selected action produced no recorded transition (restart, refinement discard, run end) — which is a legitimate, informative absence, not an error.

#### Scenario: LLM decision attributed to a new-state discovery

- **WHEN** an action with `decision_source=LLM` selected at step 42 executes and the resulting transition reaches a state visited for the first time
- **THEN** an `[APE-OUTCOME] step=42 decision_source=LLM new_state=true target_state=<key> activity_changed=<bool>` line SHALL be emitted
- **AND** it SHALL be joinable to the `[APE-STEP] step=42 ... decision_source=LLM` line by the shared `step=42`

#### Scenario: Outcome line suppressed under pure mode

- **WHEN** `apePureMode=true` (so `stepTelemetryEnabled=false`)
- **THEN** zero `[APE-OUTCOME]` lines SHALL be emitted for the run
- **AND** zero `[APE-STEP]` lines SHALL be emitted (INV-ARCH-01 unchanged)

#### Scenario: Selected action with no recorded transition

- **WHEN** an action is selected and emits an `[APE-STEP] step=50` line but the step ends in a restart before any transition is recorded (or it is the run's first step, where `addTransition` returns null)
- **THEN** no `[APE-OUTCOME] step=50` line SHALL be emitted
- **AND** this absence SHALL NOT be treated as an error by offline analysis (an `[APE-STEP]` without a paired `[APE-OUTCOME]` means "selected, no clean transition")

#### Scenario: BadStateException retry emits a single outcome

- **WHEN** the transition for the action selected at step 60 is recorded, and action selection then throws `BadStateException`, causing the update to re-run `Model.addTransition` within the same step
- **THEN** exactly one `[APE-OUTCOME] step=60` line SHALL be emitted (the buffer was consumed by the first emission)

#### Scenario: Refinement step still attributed

- **WHEN** the action selected at step 70 executes, and model refinement replaces `currentAction` with the rebuilt model's action object before `updateGraph()` runs
- **THEN** the buffered action SHALL be remapped alongside `currentAction`
- **AND** an `[APE-OUTCOME] step=70` line SHALL be emitted for the recorded transition
