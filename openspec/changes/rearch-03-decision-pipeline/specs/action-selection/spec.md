## MODIFIED Requirements

### Requirement: Per-action decision-source telemetry

Emission of the `[APE-STEP]` line is gated by `stepTelemetryEnabled` (telemetry parameters of the resolved plan; default `true`). When `stepTelemetryEnabled` is `true` (default), `StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by the decision pipeline (`DecisionPipeline.decide()`, invoked from `selectNewActionNonnull()`), after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied. When `stepTelemetryEnabled` is `false`, no `[APE-STEP]` line SHALL be emitted for any action; the `decisionSource` provenance field on `ModelAction` SHALL still be populated for internal use, and no other selection behavior SHALL change. INV-SEL-04 ("exactly one `[APE-STEP]` line per finally-selected action") describes the default configuration.

To attribute LLM and other early-return paths that bypass `logActionSelected`, `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection — which after the decision-pipeline restructuring means: **each `DecisionStage` stamps the provenance of the actions it selects**, exactly as the pre-pipeline pick sites did. The field SHALL be populated on every selection path: the `SataChain` rungs, the three LLM stages, the `Budget` stage's trivial path, and the null-handler rung — regardless of `stepTelemetryEnabled`. The `StageResult.Select` returned by the selecting stage SHALL carry a decision-source label equal to the stamped provenance (`decision-pipeline` INV-DP-04), so the same datum is available to the step-record telemetry without a second vocabulary.

For SATA-chain selections, the `decisionSource` SHALL be set by a boost-attribution rule scoped to the **selection sub-paths that actually consume priority**, not to whole `SataEventType` branches. Attribution applies ONLY when the action is a `ModelAction` chosen by: (a) a priority roulette — `State.randomlyPickAction` in the epsilon-greedy random branch or `RandomHelper.randomPickWithPriority` over the EARLY_STAGE unvisited candidates — or (b) a boost-based deterministic pick — the MOP short-circuit (`selectUnvisitedMopTarget`) or the EARLY_STAGE MOP preference (`pickBestMopTarget` in `findGreedyActionForward`). When attribution applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getMopFrontierBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`/`getFormBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > MopFrontier > WTG > Menu > Form > Coverage`. In all other cases `decisionSource` SHALL remain `SATA` — in particular on the sub-paths that select for reasons other than priority even though they live inside `EARLY_STAGE`/`EPSILON_GREEDY`: graph-navigation and shortest-path picks, the Back-/Menu-unvisited short-circuits, and `greedyPickLeastVisited` (minimum visit count; priority is only its tie-break).

This attribution reports which mechanism most contributed to the chosen action on a sub-path that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome (that claim belongs to the counterfactual fields — "Per-step counterfactual attribution").

The `decision_source` enum SHALL be: `SATA`, `MOP`, `MopFrontier`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`, `Form` — **unchanged by the pipeline restructuring**. Production per stage: `Budget` stage → `Budget`; LLM stages → `LLM`; `MopLauncher` stage → `Component` (non-model action, derived by `nonModelDecisionSource`); `ComponentTrigger` stage → none (side effect, no step decision); `SataChain` → `SATA` or the boost-attribution result. A pick driven by the MOP-frontier boost (`getMopFrontierBoost()` largest) SHALL be attributed `MopFrontier`; a pick driven by the form-completion boost (`getFormBoost()` largest) SHALL be attributed `Form`.

When emitted, the `[APE-STEP]` line SHALL also carry a `clock=<epochMillis>` field (device wall clock at emission), enabling offline temporal joins between the `.trace` and externally collected artifacts without any APE↔logcat coupling — APE SHALL NOT read from or write to logcat.

When emitted, the `[APE-STEP]` line SHALL carry a `step=<N>` field, where `<N>` is the agent exploration timestamp (`getTimestamp()`) at selection time. The timestamp is incremented exactly once per agent step at update-wrapper entry, so `step` is constant across a single selection, and — because at most one `[APE-STEP]` line is emitted per agent step (INV-SEL-04) — `step` values are unique within a run. `step` is the per-step join key consumed by the `[APE-OUTCOME]` attribution line (`Per-Step Decision Outcome Attribution`, scoring-pipeline capability). This is the agent timestamp, not the independent `Graph` timestamp.

**Boost fields.** The per-mechanism boost fields on the line SHALL include `mop_frontier=<N>` (the action's `mopFrontierBoost`, scoring-pipeline capability) alongside the existing `mop=`, `wtg=`, `coverage=`, `menu=`, `form=`; `wtg=` reports the WTG-family boosts only (WTG-MOP + generic frontier) and SHALL NOT include the MOP-frontier contribution.

**MOP-screen bit (`activity_has_mop`).** When emitted, the `[APE-STEP]` line SHALL carry `activity_has_mop=0|1`: `1` when `MopData` is non-null AND `MopData.activityHasMop(<current activity>)` is true, else `0` (including whenever `MopData` is null).

**Selection channel (`pick_channel`).** When emitted, the `[APE-STEP]` line SHALL carry exactly one `pick_channel=<value>` field naming the selection channel that picked the action, from the fixed enum `short_circuit_unvisited | short_circuit_0step | roulette_greedy | roulette_early | launcher | llm | buffer | sata_other`. The channel is provenance carried on the `ModelAction` (like `decisionSource`), set at the pick site inside the owning stage, so early-return paths are covered. The pipeline restructuring SHALL NOT add, remove, or re-map any channel value: the LLM stages stamp `llm`, the `MopLauncher` stage's launch is `launcher`, the `SataChain` rungs stamp their existing channels, and every other path remains `sata_other`.

**Clickability-patch provenance (`patched`).** When emitted, the `[APE-STEP]` line SHALL carry `patched=0|1` for actions with a resolved target: `1` when the target node's clickability was written by `GUITreeBuilder.patchGUITree` rather than read from the `AccessibilityNodeInfo`, else `0`. Actions with no target (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`) SHALL omit the field, consistently with the other target-derived fields. Interpretation rules unchanged: the bit records node provenance, not action causality; offline analysis SHALL condition on `MODEL_CLICK` before reading it causally.

**Line well-formedness.** Every `[APE-STEP]` line SHALL occupy exactly one physical line: widget-derived text interpolated into the line (via `ModelAction.resolvedInfo`) SHALL have `\n` and `\r` flattened to spaces before emission.

#### Scenario: SATA-selected action attributed
- **WHEN** `stepTelemetryEnabled` is `true` and `resolveNewAction()` finalizes an action chosen by the `SataChain` epsilon-greedy rung with all boosts equal to 0
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step=<N>`, `state`, `action`, `activity_has_mop`, `pick_channel`, and per-mechanism boosts including `mop_frontier=`

#### Scenario: Stage-stamped provenance equals the StageResult label
- **WHEN** any stage returns `StageResult.select(action, label)` for a `ModelAction`
- **THEN** `label` SHALL equal `action.getDecisionSource().name()`
- **AND** the emitted `[APE-STEP]` line's `decision_source` SHALL equal the same value

#### Scenario: MOP-boosted action from the EARLY_STAGE roulette attributed to MOP
- **WHEN** the EARLY_STAGE unvisited roulette (or the MOP preference probing it) picks a `ModelAction` whose boosts are `mop=500, mop_frontier=0, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when `stepTelemetryEnabled` is `true` the emitted `[APE-STEP]` line SHALL report `decision_source=MOP`

#### Scenario: Tie precedence MOP>MopFrontier>WTG>Menu>Form>Coverage
- **WHEN** a roulette pick carries boosts `mop=300, mop_frontier=300, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when the tie is instead `mop_frontier=300, wtg=300` the `decisionSource` SHALL be `MopFrontier`

#### Scenario: Boosted action in a priority-blind branch stays SATA
- **WHEN** the buffer rung selects a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `SATA`
- **AND** the line SHALL carry `pick_channel=buffer`

#### Scenario: Budget stage early-return attributed
- **WHEN** the `Budget` stage selects the trivial-activity action on an exhausted budget
- **THEN** that action's `decisionSource` SHALL be `Budget` and its channel `sata_other`
- **AND** when `stepTelemetryEnabled` is `true` exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=Budget`

#### Scenario: LLM early-return attributed with its channel
- **WHEN** an LLM stage returns a non-null action (bypassing `logActionSelected`)
- **THEN** that action's `decisionSource` SHALL be `LLM`
- **AND** when `stepTelemetryEnabled` is `true` exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=LLM pick_channel=llm`

#### Scenario: Launcher step attributed Component
- **WHEN** the `MopLauncher` stage selects an `EVENT_TRIGGER_ACTIVITY` action
- **THEN** the emitted `[APE-STEP]` line SHALL carry `decision_source=Component pick_channel=launcher` (non-model branch, source derived from the action)

#### Scenario: Every step is attributable when telemetry is enabled
- **WHEN** `stepTelemetryEnabled` is `true` and a run completes
- **THEN** every executed action SHALL have exactly one corresponding `[APE-STEP]` line
- **AND** every line SHALL carry exactly one `pick_channel` and exactly one `activity_has_mop` field

#### Scenario: No [APE-STEP] lines when telemetry is disabled
- **WHEN** `stepTelemetryEnabled` is `false` and a run completes
- **THEN** zero `[APE-STEP]` lines SHALL be emitted for the run
- **AND** each executed action's `decisionSource` provenance field SHALL still be populated
- **AND** no other selection behavior SHALL differ from the enabled case
