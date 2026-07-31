## MODIFIED Requirements

### Requirement: MopFrontierPass — Frontier Boost Toward Unvisited MOP Activities

A `MopFrontierPass` (in `com.android.commands.monkey.ape.agent.scoring`, implementing `ScoringPass`) SHALL add `Config.mopFrontierWeight` to the priority of a target-requiring, valid, resolved action when ALL THREE of the following hold for a WTG transition matched to that action:

1. **Widget match** — the action's short resource id equals `WtgTransition.widgetName` (the same resource-id matching used by the WTG-MOP boost and the generic frontier pass), obtained via the pass's own `MopData.getWtgTransitions(activity)` lookup (it SHALL NOT ride `MopScorer.scoreWtg`'s `int` return, which hides the target activity and only fires when MOP-reachable);
2. **Target is MOP-bearing** — `MopData.activityHasMop(WtgTransition.targetActivity) == true`;
3. **Target is unvisited** — `Graph.getActivityNode(WtgTransition.targetActivity) == null` at scoring time (evaluated live each pass; the boost recedes once the target is visited).

The boost SHALL be applied as a `setPriority` increment (`action.setPriority(action.getPriority() + mopFrontierWeight)` — the steering mechanism, unchanged) AND recorded in a **dedicated telemetry field** `ModelAction.mopFrontierBoost` via read-modify-write accumulation (`action.setMopFrontierBoost(action.getMopFrontierBoost() + mopFrontierWeight)`). It SHALL NOT write the `wtgBoost` field. This de-aliases the previous behavior, where `MopFrontierPass` accumulated into the same `wtgBoost` that `WtgPass` and the generic `FrontierPass` write — so `decision_source=WTG` conflated the MOP-frontier mechanism with generic WTG navigation, and the corpus's stacked values (400/600 in the `wtg=` field) could not be decomposed by mechanism. The boost is attributable via a new `[APE-STEP] ... mop_frontier=` field and its own `decision_source` value `MopFrontier` in the largest-boost attribution (`action-selection` capability, "Per-action decision-source telemetry").

`MopFrontierPass.isEnabled()` SHALL be true only when `Config.mopFrontierWeight > 0` AND `MopData` is non-null with WTG data present. With `Config.mopFrontierWeight == 0` (default) the pass SHALL be byte-identical to being absent from the pipeline. The pass is independent of and additive to the generic `frontierBoostWeight` (which requires only unvisited, not MOP) — B is the strictly narrower predicate (unvisited AND MOP).

`Config.mopFrontierWeight` SHALL be declared in `Config.java`, loaded via `ape.mopFrontierWeight`, default `0`, and SHALL be registered in the `apePureMode` RV-flag registry (INV-ARCH-06), forced to `0` when `apePureMode=true`. `ScoringPipeline.fromConfig` SHALL assemble `MopFrontierPass` immediately after the generic `FrontierPass` and before `CoveragePass` — the frontier family stays contiguous and the relative-order contracts of INV-ARCH-03 are preserved.

- **INV-MFP-01**: `MopFrontierPass` SHALL add `mopFrontierWeight` to an action **only** when its matched WTG transition target satisfies both `activityHasMop(target) == true` AND `Graph.getActivityNode(target) == null` at scoring time. An action failing either condition SHALL receive nothing from this pass.
- **INV-MFP-02**: The boost SHALL be applied as a `setPriority` increment AND recorded into `mopFrontierBoost` by read-modify-write; because `mopFrontierBoost` is never read by `getPriority()`, the `setPriority` increment is mandatory for the boost to steer. `MopFrontierPass` SHALL NOT write `wtgBoost`; when `mopWeightWtg` and/or `frontierBoostWeight` co-apply to the same action, `wtgBoost` SHALL reflect only those WTG-family contributions and `mopFrontierBoost` only the MOP-frontier contribution.
- **INV-MFP-03**: With `Config.mopFrontierWeight == 0`, the scoring outcome SHALL be identical to the pipeline without `MopFrontierPass`.

#### Scenario: unvisited MOP target boosted into its own field
- **WHEN** widget W's WTG transition targets `com.x.CryptoActivity`, `activityHasMop("com.x.CryptoActivity")==true`, `Graph.getActivityNode("com.x.CryptoActivity")==null`, and `ape.mopFrontierWeight=200`
- **THEN** W's action priority SHALL be increased by 200
- **AND** its `mopFrontierBoost` SHALL be 200 and its `wtgBoost` SHALL be unchanged by this pass

#### Scenario: MOP but already visited — no boost
- **WHEN** the transition target is MOP-bearing but `Graph.getActivityNode(target)` is non-null (visited)
- **THEN** `MopFrontierPass` SHALL add nothing to that action

#### Scenario: unvisited but non-MOP — no boost
- **WHEN** the transition target is unvisited but `activityHasMop(target)==false`
- **THEN** `MopFrontierPass` SHALL add nothing (this is the generic frontier pass's job, not B's)

#### Scenario: co-applying boosts stay decomposable
- **WHEN** the target is MOP-bearing AND unvisited, with `mopWeightWtg=200`, `frontierBoostWeight=200`, `mopFrontierWeight=200`
- **THEN** the action's priority SHALL gain +600 total
- **AND** its `wtgBoost` SHALL be 400 (WTG-MOP + generic frontier) and its `mopFrontierBoost` SHALL be 200 — the mechanisms are separable in the `[APE-STEP]` line

#### Scenario: disabled
- **WHEN** `ape.mopFrontierWeight=0`
- **THEN** the scoring pipeline SHALL behave exactly as without `MopFrontierPass`, with no boost recorded in either field

---

### Requirement: Per-Step Decision Outcome Attribution

When `stepTelemetryEnabled` is true, after a state transition is recorded (`Model.addTransition(source, action, target, ...)` in `StatefulAgent.updateGraph()`), `StatefulAgent` SHALL emit one `[APE-OUTCOME]` line attributing the executed action's result back to the `decision_source` that selected it. The line SHALL be correlated to the action's `[APE-STEP]` line by a shared `step` value, so an offline join on `step` pairs each decision with its outcome without any timestamp reconstruction. The `step=<N>` field contract on `[APE-STEP]` is defined by the `action-selection` capability (`Per-action decision-source telemetry`). LLM routing attempts carry the same `step` on their `[APE-LLM-TEL]`/`[APE-LLM-ERROR]` lines (llm-routing capability), so for LLM-routed decisions the call, the decision, and the outcome all join on one key.

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
| `activity_has_mop` | `1` when `MopData` is non-null AND `MopData.activityHasMop(<target activity>)` is true, else `0` — whether the step **landed on** a MOP screen, the outcome half of the evidential link that `activity_has_mop` on `[APE-STEP]` opens (where the step started) |

`target_state` reports the state observed at the next model update. When fuzzing piggybacks events after the selected action, or a bad-state `EVENT_ACTIVATE` interlude executes, the recorded transition — and hence `target_state` / `new_state` — reflects the selected action plus those trailing events. The `step` join remains exact; offline analysis SHOULD treat the outcome as "state reached by the step", not "immediate post-action state".

Example:
```
[APE-STEP]    step=42 clock=... activity=A state=S1 action=MODEL_CLICK decision_source=LLM ... activity_has_mop=0 ...
[APE-OUTCOME] step=42 decision_source=LLM new_state=true target_state=S7 activity_changed=true activity_has_mop=1
```

- **INV-ARCH-08**: The `[APE-OUTCOME]` line SHALL be gated by `stepTelemetryEnabled` exactly as the `[APE-STEP]` line is. Under `apePureMode=true` — which forces `stepTelemetryEnabled=false` per the `apePureMode Kill-Switch and Parity` requirement (INV-ARCH-06) — zero `[APE-OUTCOME]` lines SHALL be emitted, preserving upstream-APE parity (INV-ARCH-01: zero telemetry lines).
- **INV-ARCH-09**: The `step` on an `[APE-OUTCOME]` line SHALL equal the `step` on the `[APE-STEP]` line of the action whose transition it reports. Every emitted `[APE-OUTCOME]` line SHALL have a matching `[APE-STEP]` line with the same `step`, and at most one `[APE-OUTCOME]` line SHALL be emitted per `step` value. An `[APE-STEP]` line MAY have no matching `[APE-OUTCOME]` line — when the selected action produced no recorded transition (restart, refinement discard, run end) — which is a legitimate, informative absence, not an error.

#### Scenario: LLM decision attributed to a new-state discovery on a MOP screen

- **WHEN** an action with `decision_source=LLM` selected at step 42 executes, the resulting transition reaches a first-visit state, and the target activity is in the MOP-activity set
- **THEN** an `[APE-OUTCOME] step=42 decision_source=LLM new_state=true target_state=<key> activity_changed=<bool> activity_has_mop=1` line SHALL be emitted
- **AND** it SHALL be joinable to the `[APE-STEP] step=42` line by the shared `step=42`

#### Scenario: outcome on a non-MOP screen

- **WHEN** the recorded transition's target activity is not in the MOP-activity set (or `MopData` is null)
- **THEN** the `[APE-OUTCOME]` line SHALL carry `activity_has_mop=0`

#### Scenario: Outcome line suppressed under pure mode

- **WHEN** `apePureMode=true` (so `stepTelemetryEnabled=false`)
- **THEN** zero `[APE-OUTCOME]` lines SHALL be emitted for the run
- **AND** zero `[APE-STEP]` lines SHALL be emitted (INV-ARCH-01 unchanged)

#### Scenario: Selected action with no recorded transition

- **WHEN** an action is selected and emits an `[APE-STEP] step=50` line but the step ends in a restart before any transition is recorded (or it is the run's first step, where `addTransition` returns null)
- **THEN** no `[APE-OUTCOME] step=50` line SHALL be emitted
- **AND** this absence SHALL NOT be treated as an error by offline analysis

#### Scenario: BadStateException retry emits a single outcome

- **WHEN** the transition for the action selected at step 60 is recorded, and action selection then throws `BadStateException`, causing the update to re-run `Model.addTransition` within the same step
- **THEN** exactly one `[APE-OUTCOME] step=60` line SHALL be emitted (the buffer was consumed by the first emission)

#### Scenario: Refinement step still attributed

- **WHEN** the action selected at step 70 executes, and model refinement replaces `currentAction` with the rebuilt model's action object before `updateGraph()` runs
- **THEN** the buffered action SHALL be remapped alongside `currentAction`
- **AND** an `[APE-OUTCOME] step=70` line SHALL be emitted for the recorded transition

## Invariants

- **INV-ARCH-10**: `wtgBoost` and `mopFrontierBoost` SHALL be disjoint accumulators: `wtgBoost` receives only the WTG-MOP and generic-frontier contributions, `mopFrontierBoost` only the MOP-frontier contribution, and the total priority steering from the three passes SHALL equal the sum of the two fields. No pass SHALL write both fields for one contribution.
