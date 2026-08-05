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

### Requirement: Scoring Pipeline Assembly from Config

`ScoringPipeline.fromParams(ScoringParams, ScoringContext)` SHALL be the single point that maps the resolved plan's scoring configuration to the ordered list of enabled passes. It SHALL construct the **seven** passes in the fixed order `MopWidgetPass` → `MenuGatewayPass` → `WtgPass` → `FrontierPass` → `MopFrontierPass` → `CoveragePass` → `FormCompletionPass` (the frontier family contiguous), retain only those whose `isEnabled()` is `true`, and emit exactly one `PIPELINE` sink record (event-sink capability) whose `passes` member lists the retained passes' `name()` values in pipeline order. No other code path SHALL assemble or reorder the pipeline. The former `fromConfig(Config, ScoringContext)` entry point — whose `Config` parameter was decorative (the sole caller passed `null` while the passes read `Config` statics directly) — SHALL be deleted, not shimmed (P3). The class documentation SHALL state the seven-pass roster; the stale "six passes" wording is corrected.

The fixed order SHALL equal the order of the pre-refactor inline scoring blocks in `StatefulAgent.adjustActionsByGUITree()`, which is the order the `mop-guidance` (INV-MOP-05), `ui-coverage`, and `form-completion` specs already require (base → MOP-widget → menu-gateway → WTG → frontier → MOP-frontier → coverage → form). The injection change SHALL NOT alter pass order, boost arithmetic, provenance writes, or the content of the emitted pass list; the sole caller (`StatefulAgent`, at construction) SHALL pass the real `ScoringParams` derived from the `RunSpec` in place of the former `null`.

**The same assembly SHALL also record the candidates it dropped.** Beside the retained list — whose content and format are unchanged — assembly SHALL record every candidate pass with whether it was constructed, carried by the stage-4 `PIPELINE` record's `candidates` member. The census SHALL be produced inside `fromParams`, where the full candidate list is still in scope: the pipeline discards the disabled passes at construction and has no handle on them afterwards, and retaining them in a field only to enumerate them would keep disabled objects alive for telemetry's sake.

The record SHALL be emitted from `fromParams`, where the full candidate list is still in scope: the constructor discards the disabled candidates (`this.passes = enabled`), so a census assembled after construction would have nothing left to enumerate. This is the pass-side counterpart of the decision-pipeline's static stage list, and unlike that one it is data-bearing rather than plan-derivable: `WtgPass`, `FrontierPass` and `MopFrontierPass` each gate on `mopData.hasWtgData()`, which nothing in the plan reveals. The measurement that motivates it — across the decisive campaign's 360 runs the `[APE-ARCH] passes=` line takes exactly three values, split 45/75 in every arm, because **the whole frontier family is never constructed in 25 of the 40 applications**, and the only trace evidence of that today is the absence of three names from a list every analyst read as a configuration echo.

No `reason` field SHALL be added to the entries, and no `disabledReason()` method to the `ScoringPass` interface. Each gate is a conjunction of `mopData != null`, `hasWtgData()` and a weight, and all three conjuncts are already recorded elsewhere in the same trace (`MOP_DATA.status`, `MOP_DATA.wtgEdges`, `RUN_START.params`), so the reason is a lookup rather than a field — and adding it would touch all seven implementations plus the test double for a value that is already there. It would also be unreliable: the three passes do not evaluate their conjuncts in the same order, so a "first failing conjunct" would report source-code order rather than cause, and two passes absent for the same reason would disagree about what it was.

- **INV-ARCH-03**: The pipeline order SHALL equal the order of the pre-refactor inline scoring blocks and SHALL satisfy the pass-order contracts already asserted by `mop-guidance` (INV-MOP-05), `ui-coverage`, and `form-completion`. `MopWidgetPass` SHALL precede `WtgPass`, which SHALL precede `CoveragePass`, which SHALL precede `FormCompletionPass`; `MopFrontierPass` SHALL sit immediately after `FrontierPass`.
- **INV-ARCH-04**: `ScoringPipeline.fromParams` SHALL be the sole assembly point. The `PIPELINE` record's `passes` member SHALL list exactly the enabled passes, in pipeline order, and nothing else SHALL construct a pipeline. The candidate census (`PIPELINE.candidates`) is a **sibling** record member, never a widening of this list: any consumer reading `passes` SHALL continue to see exactly the constructed passes.

#### Scenario: only enabled passes are assembled and logged
- **WHEN** `ScoringPipeline.fromParams(params, ctx)` runs with MopData absent (MOP passes off), `coverageBoostWeight=100`, `formCompletionEnabled=true`
- **THEN** the pipeline SHALL contain `CoveragePass`, `FormCompletionPass` in that order and no MOP/WTG/Frontier pass
- **AND** one `PIPELINE` record SHALL be emitted with `passes:["CoveragePass","FormCompletionPass"]`

#### Scenario: full MOP arm assembles the ordered set
- **WHEN** `fromParams` runs with MopData present, `mopWeightWtg=200`, `frontierBoostWeight=200`, `mopFrontierWeight=200`, `coverageBoostWeight=100`, `formCompletionEnabled=true`
- **THEN** the pipeline order SHALL be `MopWidgetPass, MenuGatewayPass, WtgPass, FrontierPass, MopFrontierPass, CoveragePass, FormCompletionPass`

#### Scenario: empty pipeline under an empty scoring plan
- **WHEN** `fromParams` runs with a plan carrying no scoring feature (all gates off/zero)
- **THEN** the pipeline SHALL contain zero passes
- **AND** the emitted record SHALL carry `passes:[]`

#### Scenario: injection is real — no decorative parameter
- **WHEN** two `ScoringParams` differing only in `mopFrontierWeight` (0 vs 200) are used to assemble pipelines over the same context
- **THEN** the first SHALL exclude `MopFrontierPass` and the second SHALL include it
- **AND** no `Config` mutation SHALL be needed to produce the contrast

### Requirement: MopFrontierPass — Frontier Boost Toward Unvisited MOP Activities

A `MopFrontierPass` (in `com.android.commands.monkey.ape.agent.scoring`, implementing `ScoringPass`) SHALL add `Config.mopFrontierWeight` to the priority of a target-requiring, valid, resolved action when ALL THREE of the following hold for a WTG transition matched to that action:

1. **Widget match** — the action's short resource id equals `WtgTransition.widgetName` (the same resource-id matching used by the WTG-MOP boost and the generic frontier pass), obtained via the pass's own `MopData.getWtgTransitions(activity)` lookup (it SHALL NOT ride `MopScorer.scoreWtg`'s `int` return, which hides the target activity and only fires when MOP-reachable);
2. **Target is MOP-bearing** — `MopData.activityHasMop(WtgTransition.targetActivity) == true`;
3. **Target is unvisited** — `Graph.getActivityNode(WtgTransition.targetActivity) == null` at scoring time (evaluated live each pass; the boost recedes once the target is visited).

The boost SHALL be applied as a `setPriority` increment (`action.setPriority(action.getPriority() + mopFrontierWeight)` — the steering mechanism, unchanged) AND recorded in a **dedicated telemetry field** `ModelAction.mopFrontierBoost` via read-modify-write accumulation (`action.setMopFrontierBoost(action.getMopFrontierBoost() + mopFrontierWeight)`). It SHALL NOT write the `wtgBoost` field. This de-aliases the previous behavior, where `MopFrontierPass` accumulated into the same `wtgBoost` that `WtgPass` and the generic `FrontierPass` write — so `decision_source=WTG` conflated the MOP-frontier mechanism with generic WTG navigation, and the corpus's stacked values (400/600 in the `wtg=` field) could not be decomposed by mechanism. The boost is attributable via the step record's `dec.mopf` field and its own `decision_source` value `MopFrontier` in the largest-boost attribution (`action-selection` capability, "Per-action decision-source telemetry").

`MopFrontierPass.isEnabled()` SHALL be true only when `Config.mopFrontierWeight > 0` AND `MopData` is non-null with WTG data present. With `Config.mopFrontierWeight == 0` (default) the pass SHALL be byte-identical to being absent from the pipeline. The pass is independent of and additive to the generic `frontierBoostWeight` (which requires only unvisited, not MOP) — B is the strictly narrower predicate (unvisited AND MOP).

`Config.mopFrontierWeight` SHALL be declared in `Config.java`, loaded via `ape.mopFrontierWeight`, default `0`, and SHALL be declared in the run-spec `Feature` model as the activation key of the `MOP_FRONTIER` feature, which requires `MOP`: an explicit non-zero weight on a plan without MOP data aborts resolution as a missing dependency, and with the feature absent the pass is not constructed (`run-spec` INV-RUN-05 — the recorded substitute for the dissolved INV-ARCH-06 registry). `ScoringPipeline.fromConfig` SHALL assemble `MopFrontierPass` immediately after the generic `FrontierPass` and before `CoveragePass` — the frontier family stays contiguous and the relative-order contracts of INV-ARCH-03 are preserved.

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
- **AND** its `wtgBoost` SHALL be 400 (WTG-MOP + generic frontier) and its `mopFrontierBoost` SHALL be 200 — the mechanisms are separable in the record, as `dec.wtg` and `dec.mopf`

#### Scenario: disabled
- **WHEN** `ape.mopFrontierWeight=0`
- **THEN** the scoring pipeline SHALL behave exactly as without `MopFrontierPass`, with no boost recorded in either field

The de-aliasing this requirement performed for `mopFrontierBoost` is completed on the other side by `dec.wtgsrc` (event-sink capability): `wtgBoost` remains a sum of two producers — `WtgPass` writes it and `FrontierPass` read-modify-writes on top — and with both weights at 200 the campaign realises `{0, 200, 400}`, leaving 10,231 steps at 200 ambiguous and only 91 at 400 proving both fired. `wtgsrc` stamps the producer at each write site. It is telemetry-only: this requirement's arithmetic, including the `wtgBoost` sum it deliberately does not touch, is unchanged.

## Notes

### Disposition of the former requirement `apePureMode Kill-Switch and Parity`

The requirement itself is REMOVED from this capability by `rearch-02-runspec`, which owns the mechanism deletion — by the time this change lands, it is no longer in the main spec, so no REMOVED operation appears in this delta. The full disposition spans two stages: The `apePureMode` mechanism itself (Properties-overwrite kill-switch, string registry, forced-off flags) is deleted by `rearch-02-runspec`: purity becomes structural — a feature absent from the plan does not exist in the run, and the effective plan is echoed in `RUN_START` (report Sec. 6.6). The telemetry half — **INV-ARCH-01**, the baseline arm's zero-telemetry parity ("zero `[APE-STEP]` lines") — is deliberately dissolved by **this** change (report Sec. 8, owner decision): telemetry becomes universal, identical for all arms, and *provably neutral*; the recorded substitute for the property INV-ARCH-01 protected (the control arm is not contaminated by RV machinery) is INV-SNK-07 and its permanent sink-on/off neutrality test (R7, report Sec. 9.8). Complete deletion, no compatibility shim: `Config.stepTelemetryEnabled`, the kill-switch registries, and the gated emitters are removed with their gates (P3). Upstream-parity comparison, if ever needed again, is anchored on the frozen phase-2 data (owner decision D3), not on a runtime mode.
