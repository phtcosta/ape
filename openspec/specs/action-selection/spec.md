## Purpose

APE-RV's `State.greedyPickLeastVisited()` selects the action with the lowest `visitedCount`, ignoring `priority` entirely. Since `SataAgent.egreedy()` routes to the greedy path 85-98% of the time (epsilon range 0.02-0.15), all priority boosts computed in `adjustActionsByGUITree()` — MOP (+500/+300/+100), WTG (+200), and coverage boost — are effectively ignored in the vast majority of action selections. Only the random path (2-15% of decisions) uses `randomlyPickAction()`, which performs priority-weighted sampling.

This delta spec adds priority as a tiebreaker to `greedyPickLeastVisited()`. When multiple actions share the same minimum `visitedCount`, the action with the highest `priority` is selected. This makes all priority boosts (MOP, WTG, coverage) influence the greedy path without changing the fundamental least-visited strategy.

## Data Contracts

### Input
- `ape.mopTargetPickCap: int` — maximum deterministic MOP picks per (widget, action type, activity) key per run (default 3; <= 0 = unlimited). Read once at startup like the `mopWeight*` flags.

### Side-Effects
- **[Trace]**: `[APE-RV] MOP target capped: activity=<activity> widget=<xpath> picks=<n>` — logged once per key, on the deterministic pick that reaches the cap (`picks == cap` after increment).
## Requirements
### Requirement: State.greedyPickLeastVisited() — Priority Tiebreaker

`State.greedyPickLeastVisited(ActionFilter filter)` SHALL select the action with the lowest `visitedCount`. Tie-breaking among actions that share the same lowest `visitedCount` is gated by `Config.leastVisitedPriorityTiebreak` (declared by the `scoring-pipeline` capability; default `true`):

- When `leastVisitedPriorityTiebreak` is `true` (default): when multiple actions share the same lowest `visitedCount`, the action with the highest `priority` SHALL be selected. This makes all priority boosts (MOP, WTG, coverage) influence the greedy path (INV-SEL-01: priority is only a tiebreaker, never an override).
- When `leastVisitedPriorityTiebreak` is `false` (the `ape_pure` arm): ties among the lowest-`visitedCount` actions SHALL be broken by array order (the first such action encountered wins), reproducing upstream APE. No RV priority boost SHALL influence the greedy pick.

INV-SEL-01 and INV-SEL-02 describe the default (`leastVisitedPriorityTiebreak=true`) behavior.

#### Scenario: Single least-visited action
- **WHEN** actions have visitedCounts [0, 3, 5]
- **THEN** the action with visitedCount=0 SHALL be selected (unchanged behavior, independent of the flag)

#### Scenario: Tie broken by priority (flag on)
- **WHEN** `Config.leastVisitedPriorityTiebreak` is `true` and actions have visitedCounts [2, 2, 5] and priorities [32, 532, 52]
- **THEN** the action with visitedCount=2 and priority=532 SHALL be selected
- **AND** the MOP boost (+500) on that action effectively influenced the greedy selection

#### Scenario: All actions have same visitedCount (flag on)
- **WHEN** `Config.leastVisitedPriorityTiebreak` is `true` and all 10 actions have visitedCount=0 and priorities [32, 32, 232, 32, 532, 32, 32, 32, 32, 32]
- **THEN** the action with priority=532 (MOP-boosted) SHALL be selected

#### Scenario: Tie with equal priorities
- **WHEN** actions have visitedCounts [1, 1, 3] and priorities [52, 52, 32]
- **THEN** either of the two tied actions MAY be selected (implementation picks the first encountered)

#### Scenario: Tie broken by array order when the flag is off
- **WHEN** `Config.leastVisitedPriorityTiebreak` is `false` and actions have visitedCounts [2, 2, 5] and priorities [32, 532, 52]
- **THEN** the first action with visitedCount=2 in array order SHALL be selected (priority=32), NOT the priority=532 action
- **AND** no RV priority boost SHALL influence the greedy pick (upstream APE behavior)

---

### Requirement: Per-action decision-source telemetry

Emission of the `[APE-STEP]` line is gated by `Config.stepTelemetryEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `stepTelemetryEnabled` is `true` (default), `StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by `selectNewActionNonnull()`, after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied. When `stepTelemetryEnabled` is `false` (the `ape_pure` arm), no `[APE-STEP]` line SHALL be emitted for any action; the `decisionSource` provenance field on `ModelAction` SHALL still be populated for internal use, and no other selection behavior SHALL change. INV-SEL-04 ("exactly one `[APE-STEP]` line per finally-selected action") describes the default (`stepTelemetryEnabled=true`) configuration.

To attribute LLM and other early-return paths that bypass `logActionSelected`, `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection. The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path — regardless of `stepTelemetryEnabled`.

For SATA-chain selections, the `decisionSource` SHALL be set by a boost-attribution rule scoped to the **selection sub-paths that actually consume priority**, not to whole `SataEventType` branches. Attribution applies ONLY when the action is a `ModelAction` chosen by: (a) a priority roulette — `State.randomlyPickAction` in the epsilon-greedy random branch (`SataAgent.java:607`) or `RandomHelper.randomPickWithPriority` over the EARLY_STAGE unvisited candidates (`SataAgent.java:1558`) — or (b) a boost-based deterministic pick — the MOP short-circuit (`selectUnvisitedMopTarget`, `SataAgent.java:575-587`) or the EARLY_STAGE MOP preference (`pickBestMopTarget` in `findGreedyActionForward`, `SataAgent.java:1544-1552`). When attribution applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getMopFrontierBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`/`getFormBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > MopFrontier > WTG > Menu > Form > Coverage` (`MopFrontier` sits next to `MOP` because it is a MOP mechanism — the de-aliasing exists precisely so it cannot launder as WTG). In all other cases `decisionSource` SHALL remain `SATA` — in particular on the sub-paths that select for reasons other than priority even though they live inside `EARLY_STAGE`/`EPSILON_GREEDY`: graph-navigation and shortest-path picks, the Back-/Menu-unvisited short-circuits, and `greedyPickLeastVisited` (minimum visit count; priority is only its tie-break).

This attribution reports which mechanism most contributed to the chosen action on a sub-path that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome (that claim belongs to the counterfactual fields — "Per-step counterfactual attribution").

The `decision_source` enum SHALL be: `SATA`, `MOP`, `MopFrontier`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`, `Form`. A pick driven by the MOP-frontier boost (`getMopFrontierBoost()` largest) SHALL be attributed `MopFrontier`; a pick driven by the form-completion boost (`getFormBoost()` largest) SHALL be attributed `Form`.

When emitted, the `[APE-STEP]` line SHALL also carry a `clock=<epochMillis>` field (device wall clock at emission), enabling offline temporal joins between the `.trace` and externally collected artifacts without any APE↔logcat coupling — APE SHALL NOT read from or write to logcat.

When emitted, the `[APE-STEP]` line SHALL carry a `step=<N>` field, where `<N>` is the agent exploration timestamp (`getTimestamp()`) at selection time. The timestamp is incremented exactly once per agent step at update-wrapper entry, so `step` is constant across a single selection, and — because at most one `[APE-STEP]` line is emitted per agent step (INV-SEL-04) — `step` values are unique within a run. `step` is the per-step join key consumed by the `[APE-OUTCOME]` attribution line (`Per-Step Decision Outcome Attribution`, scoring-pipeline capability). This is the agent timestamp, not the independent `Graph` timestamp.

**Boost fields.** The per-mechanism boost fields on the line SHALL include `mop_frontier=<N>` (the action's `mopFrontierBoost`, scoring-pipeline capability) alongside the existing `mop=`, `wtg=`, `coverage=`, `menu=`, `form=`; `wtg=` reports the WTG-family boosts only (WTG-MOP + generic frontier) and SHALL NOT include the MOP-frontier contribution.

**MOP-screen bit (`activity_has_mop`).** When emitted, the `[APE-STEP]` line SHALL carry `activity_has_mop=0|1`: `1` when `MopData` is non-null AND `MopData.activityHasMop(<current activity>)` is true, else `0` (including whenever `MopData` is null). This closes the third link of the evidential chain — whether the step happened on a MOP screen — using the O(1) pre-computed set lookup already specified by `mop-guidance`; today the bit is not in the trace and MOP-screen presence must be reconstructed offline.

**Selection channel (`pick_channel`).** When emitted, the `[APE-STEP]` line SHALL carry exactly one `pick_channel=<value>` field naming the selection channel that picked the action, from the fixed enum:

| Value | Channel |
|-------|---------|
| `short_circuit_unvisited` | the unvisited-MOP-target short-circuit in `selectNewActionEpsilonGreedyRandomly` (`SataAgent.java:575-587`) |
| `short_circuit_0step` | the 0-step MOP probe in `findGreedyActionForward` (`SataAgent.java:1544-1552`) |
| `roulette_greedy` | the epsilon-greedy priority roulette (`State.randomlyPickAction`, `SataAgent.java:607`) |
| `roulette_early` | the EARLY_STAGE unvisited-candidates roulette (`RandomHelper.randomPickWithPriority`, `SataAgent.java:1558`) |
| `launcher` | the stagnation activity launcher (`SataAgent.java:460-483`) |
| `llm` | any of the three LLM hooks (`SataAgent.java:422-453`) |
| `buffer` | `selectNewActionFromBuffer` |
| `sata_other` | every other selection path (least-visited scan, graph navigation, back-tracking, budget/trivial, null-recovery) |

Measured motivation: the short-circuit channel yields 15.1% new states while the MOP-boosted roulette yields 1.4% — aggregating them under `decision_source=MOP` mixes mechanism with noise, and today the separation is only recoverable by regex over free-text log lines. `pick_channel` is provenance carried on the `ModelAction` (like `decisionSource`), set at the pick site, so early-return paths are covered.

**Clickability-patch provenance (`patched`).** When emitted, the `[APE-STEP]` line SHALL carry `patched=0|1` for actions with a resolved target: `1` when the target node's clickability was written by `GUITreeBuilder.patchGUITree` rather than read from the `AccessibilityNodeInfo`, else `0`. Actions with no target (`MODEL_BACK`, `MODEL_MENU`, `MODEL_LLM_TAP`) SHALL omit the field, consistently with the other target-derived fields.

The patch is on by default (`Config.patchGUITree`), is not exposed through any experimental arm's configuration, and rewrites both `GUITreeNode.clickable` and the DOM mirror while leaving the `AccessibilityNodeInfo` untouched; `ActionPatchNamer.generatePatch` emits a property only when true and prints the already-patched value. The result is that **no recorded artifact today distinguishes a click on a natively clickable widget from a click on one the patch fabricated** — offline reconstruction can only estimate the share via a `(class, resource-id)` *type* proxy, which yields a point estimate of 36.0% at type level. That is not an interval: 19.4% rests on a different denominator, and no lower bound above 0 is derivable from this corpus, so `[19.4%; 36.0%]` SHALL NOT be reported as a range containing the true value. The estimate is further conditional on the patch log being complete (64 of 800 calibration runs carry 6,561 `MODEL_CLICK` steps and no patch log line at all). The bit makes the quantity exact and removes both the proxy and the condition.

Interpretation rules, part of this contract: `patched=1` records the **node's** provenance, not the action's causality. For `MODEL_CLICK` it does imply the action would not exist without the patch (the action is derived from `clickable || checkable`); for scroll and long-click actions on the same node it does not, so offline analysis SHALL condition on `MODEL_CLICK` before reading it causally. And where the action's `Name` resolves to more than one node, the bit describes the node the line prints — exact for that node, a sample for its siblings.

**Line well-formedness.** Every `[APE-STEP]` line SHALL occupy exactly one physical line: widget-derived text interpolated into the line (via `ModelAction.resolvedInfo`, which embeds the resolved node's text) SHALL have `\n` and `\r` flattened to spaces before emission. Measured defect: 752 of 166,359 `[APE-STEP]` lines were broken by embedded newlines in widget text, with non-uniform distribution across arms (32–116 per arm) — a 0.45% bias on every `decision_source` count.

#### Scenario: SATA-selected action attributed
- **WHEN** `stepTelemetryEnabled` is `true` and `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step=<N>`, `state`, `action`, `activity_has_mop`, `pick_channel`, and per-mechanism boosts including `mop_frontier=`

#### Scenario: MOP-boosted action from the EARLY_STAGE roulette attributed to MOP
- **WHEN** the EARLY_STAGE unvisited roulette (or the MOP preference probing it) picks a `ModelAction` whose boosts are `mop=500, mop_frontier=0, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when `stepTelemetryEnabled` is `true` the emitted `[APE-STEP]` line SHALL report `decision_source=MOP`

#### Scenario: MOP-frontier-driven pick attributed to MopFrontier, not WTG
- **WHEN** a roulette pick carries boosts `mop=0, mop_frontier=200, wtg=0, menu=0, coverage=100, form=0`
- **THEN** the action's `decisionSource` SHALL be `MopFrontier`
- **AND** the line SHALL report `mop_frontier=200 wtg=0`

#### Scenario: Tie precedence MOP>MopFrontier>WTG>Menu>Form>Coverage
- **WHEN** a roulette pick carries boosts `mop=300, mop_frontier=300, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when the tie is instead `mop_frontier=300, wtg=300` the `decisionSource` SHALL be `MopFrontier`

#### Scenario: Boosted action in a priority-blind branch stays SATA
- **WHEN** `logActionSelected(action, USE_BUFFER)` is called for a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `SATA`
- **AND** the line SHALL carry `pick_channel=buffer`

#### Scenario: click on a patch-fabricated widget is marked
- **WHEN** `patchGUITree` sets `clickable=true` on a child that the `AccessibilityNodeInfo` reported as non-clickable, and a later step selects the `MODEL_CLICK` derived from it
- **THEN** the emitted `[APE-STEP]` line SHALL carry `patched=1`
- **AND** a `MODEL_CLICK` on a node whose clickability came from the `AccessibilityNodeInfo` SHALL carry `patched=0`

#### Scenario: targetless actions omit the patch bit
- **WHEN** the selected action is `MODEL_BACK`, `MODEL_MENU` or `MODEL_LLM_TAP`
- **THEN** the emitted `[APE-STEP]` line SHALL NOT carry a `patched` field

#### Scenario: [APE-STEP] carries the MOP-screen bit
- **WHEN** `stepTelemetryEnabled` is `true`, `MopData` is present, and the current activity is in the pre-computed MOP-activity set
- **THEN** the emitted `[APE-STEP]` line SHALL carry `activity_has_mop=1`
- **AND** on an activity outside the set it SHALL carry `activity_has_mop=0`

#### Scenario: MOP-off arm always reports activity_has_mop consistent with MopData
- **WHEN** a run executes with `MopData` null
- **THEN** every `[APE-STEP]` line SHALL carry `activity_has_mop=0`

#### Scenario: pick_channel discriminates short-circuit from roulette
- **WHEN** one step is selected by the unvisited-MOP short-circuit and a later step by the epsilon-greedy roulette
- **THEN** the first line SHALL carry `pick_channel=short_circuit_unvisited`
- **AND** the second SHALL carry `pick_channel=roulette_greedy`
- **AND** both MAY carry `decision_source=MOP` (channel and source are independent axes)

#### Scenario: LLM early-return attributed with its channel
- **WHEN** the new-state LLM hook returns a non-null action (bypassing `logActionSelected`)
- **THEN** that action's `decisionSource` SHALL be `LLM`
- **AND** when `stepTelemetryEnabled` is `true` exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=LLM pick_channel=llm`

#### Scenario: widget text with a newline stays on one line
- **WHEN** the selected action's resolved node text is `"Sign\nIn"`
- **THEN** the emitted `[APE-STEP]` line SHALL contain `"Sign In"` and SHALL occupy exactly one physical line

#### Scenario: Every step is attributable when telemetry is enabled
- **WHEN** `stepTelemetryEnabled` is `true` and a run completes
- **THEN** every executed action SHALL have exactly one corresponding `[APE-STEP]` line
- **AND** every line SHALL carry exactly one `pick_channel` and exactly one `activity_has_mop` field

#### Scenario: No [APE-STEP] lines when telemetry is disabled
- **WHEN** `stepTelemetryEnabled` is `false` and a run completes
- **THEN** zero `[APE-STEP]` lines SHALL be emitted for the run
- **AND** each executed action's `decisionSource` provenance field SHALL still be populated
- **AND** no other selection behavior SHALL differ from the enabled case

### Requirement: MOP-target greedy short-circuit in epsilon-greedy selection

When `SataAgent.selectNewActionEpsilonGreedyRandomly()` runs and a valid, enabled, unvisited action carries a discriminative MOP boost (`ModelAction.getMopBoost() > 0`), the agent SHALL select that action before the epsilon-greedy / roulette step. The check SHALL run after the existing Back-unvisited and Menu-unvisited short-circuits (`SataAgent.java:456-488`) and before `egreedy()`. Among multiple eligible actions, the agent SHALL choose the one with the highest `mopBoost`, breaking ties by highest `priority`. The selected action SHALL be attributed through `logActionSelected(action, EPSILON_GREEDY)` so the per-step telemetry remains consistent.

This gives the discriminative `+500`/`+300` boost a deterministic path to the monitored widget, which priority-weighted roulette reaches only probabilistically. It is bounded to unvisited targets so it fires at most once per MOP-reachable widget and does not override the least-visited strategy for already-visited actions. The discriminative `mopBoost` is produced only after the activity-level fallback is removed (see `mop-guidance` "MopScorer — Priority Boost"); a uniform boost would make this branch select an arbitrary widget, which is why this change and the fallback removal ship together.

- **INV-SEL-MOP-01**: The short-circuit SHALL only select actions that are valid, enabled, unvisited, and have `mopBoost > 0`. A visited MOP target SHALL NOT be force-selected by this branch.
- **INV-SEL-MOP-02**: When no eligible unvisited MOP-boosted action exists, the branch SHALL be a no-op and selection SHALL proceed identically to the pre-change behavior.

#### Scenario: Unvisited MOP target selected ahead of roulette
- **WHEN** the current state has an unvisited valid action with `mopBoost=500` alongside other unvisited non-MOP actions
- **THEN** `selectNewActionEpsilonGreedyRandomly()` SHALL return the `mopBoost=500` action
- **AND** it SHALL be attributed via `logActionSelected(action, EPSILON_GREEDY)`

#### Scenario: Highest mopBoost wins among MOP targets
- **WHEN** two unvisited valid actions have `mopBoost=500` and `mopBoost=300`
- **THEN** the `mopBoost=500` action SHALL be selected

#### Scenario: Visited MOP target not force-selected
- **WHEN** the only action with `mopBoost>0` is already visited
- **THEN** the short-circuit SHALL NOT select it
- **AND** selection SHALL proceed to the existing epsilon-greedy / roulette path

#### Scenario: No MOP target is a no-op
- **WHEN** no action in the state has `mopBoost>0`
- **THEN** the short-circuit SHALL be a no-op
- **AND** selection SHALL proceed identically to the pre-change behavior

---

### Requirement: MOP preference in the EARLY_STAGE unvisited roulette

The deterministic MOP preference SHALL also apply where unvisited actions are actually consumed: in `SataAgent.findGreedyActionForward` (`SataAgent.java:1066-1102`), before the `RandomHelper.randomPickWithPriority(actions)` roulette over the unvisited greedy candidates, the agent SHALL first probe the candidate list with `pickBestMopTarget` and return its pick when non-null. The form-completion submit exclusion (INV-FORM-06) applies here exactly as in the epsilon-greedy short-circuit.

Verified motivation: the epsilon-greedy short-circuit alone is shadowed — the selection chain (`selectNewActionNonnull`, `SataAgent.java:409-439`) runs EARLY_STAGE forward before epsilon-greedy, and `getGreedyActions` collects precisely the enabled/valid/unvisited actions, so an unvisited MOP target is typically consumed by the EARLY_STAGE roulette (boost-weighted but probabilistic) before the deterministic branch is ever reached. Trace mining measured EARLY_STAGE at 57.6% of decisions with the short-circuit operating in under 1% of them. Adding the preference inside `findGreedyActionForward` (rather than hoisting the short-circuit above EARLY_STAGE) keeps the SATA chain order intact — ABA, back-tracking, and trivial-path bookkeeping are unaffected; only the pick among already-collected unvisited candidates becomes deterministic when a MOP target is present.

- **INV-SEL-MOP-03**: In every selection path that draws from a set of unvisited candidate actions by priority (EARLY_STAGE forward roulette, epsilon-greedy short-circuit), an eligible unvisited `mopBoost > 0` action SHALL win deterministically over non-MOP candidates, subject to the form-completion submit exclusion.

#### Scenario: MOP target wins the EARLY_STAGE roulette deterministically
- **WHEN** `findGreedyActionForward` collects three unvisited candidates, one with `mopBoost=500` and two without
- **THEN** the `mopBoost=500` action SHALL be returned without consulting the roulette

#### Scenario: no MOP candidate leaves the roulette unchanged
- **WHEN** no collected candidate has `mopBoost > 0`
- **THEN** `randomPickWithPriority` SHALL run exactly as before

#### Scenario: excluded submit falls through to the roulette
- **WHEN** the only `mopBoost>0` candidate is the form submit and the form-completion context holds
- **THEN** `pickBestMopTarget` SHALL skip it (INV-FORM-06)
- **AND** the roulette SHALL run over the remaining candidates (submit excluded)

---

### Requirement: MOP target revisit cap

The agent SHALL count deterministic MOP picks per physical widget and action type across the whole run, keyed by `target.toXPath() + "|" + actionType + "|" + activity` — independent of the abstract state the action belongs to. MOP boosts are event-type-specific (a widget's click and long-click carry independent boosts — `MopScorer.eventTypeOf`), so the cap is per (widget, action type, activity); the key matches the existing `UICoverageTracker.widgetId` convention (xpath|type) scoped by activity. Both deterministic MOP selection paths (the epsilon-greedy path through `selectUnvisitedMopTarget` and the EARLY_STAGE path around `pickBestMopTarget`) SHALL exclude a key whose count has reached `ape.mopTargetPickCap` from the deterministic override, exactly as if its `mopBoost` were 0 for short-circuit purposes — the candidate set is filtered at the two instance call sites; the static picker itself remains pure. The count is incremented on every action the deterministic paths select. Ineligibility SHALL NOT alter the action's `mopBoost` value itself: the boost continues to participate in priority-weighted roulette selection.

With `ape.mopTargetPickCap` <= 0 (zero or negative) the cap SHALL be disabled and both sites SHALL behave exactly as specified by `mop-discriminative-boost`.

When a deterministic pick increments a key's count to exactly `ape.mopTargetPickCap` (the pick that reaches the cap), the agent SHALL log `[APE-RV] MOP target capped: activity=<activity> widget=<xpath> picks=<n>` — once per key per run. Because capped keys are filtered before the next pick, this reach-the-cap event is the only occasion to log.

- **INV-SEL-MOP-04**: No (widget XPath + action type + activity) key SHALL be selected by the deterministic MOP sites more than `ape.mopTargetPickCap` times per run when the cap is positive.
- **INV-SEL-MOP-05**: With `ape.mopTargetPickCap` <= 0, action selection SHALL be identical to the uncapped specification with no cap-related side effects (no counter updates, no cap log lines).

#### Scenario: fourth pick of the same widget falls through to the roulette
- **WHEN** `ape.mopTargetPickCap=3` and the deterministic MOP sites have already selected actions of the same type targeting widget X (same XPath, same action type, same activity, across three different abstract states) — the third pick having logged `[APE-RV] MOP target capped` once as it reached the cap
- **THEN** on the next state where such an action targeting X is the only `mopBoost>0` candidate, `pickBestMopTarget` SHALL return null
- **AND** selection SHALL proceed to the roulette with X's `mopBoost` still in its priority

#### Scenario: distinct widgets are counted independently
- **WHEN** widget X has reached the cap and widget Y (different XPath) carries `mopBoost=300` unvisited
- **THEN** the deterministic sites SHALL still select Y

#### Scenario: cap disabled
- **WHEN** `ape.mopTargetPickCap=0`
- **THEN** the deterministic sites SHALL ignore pick counts entirely

### Requirement: BACK/MENU discretionary pick cap

The agent SHALL count discretionary BACK/MENU picks per run, keyed by `activity + "|" + actionType.name()` — independent of the abstract state the action belongs to, so refinement-minted sibling states cannot re-arm the count. A discretionary pick is any BACK/MENU action returned by the two EARLY_STAGE greedy phases (`findGreedyActionForward`, `findGreedyActionBackward`) or by `selectNewActionEpsilonGreedyRandomly` (the BACK unvisited short-circuit, the MENU unvisited short-circuit, the least-visited scan, or the priority roulette). When a key's count has reached `ape.backMenuPickCap`, that action type SHALL be excluded from all six discretionary channels for that activity: the EARLY_STAGE backward direct unvisited-BACK pick skips it (falling through to backtrack), the EARLY_STAGE forward roulette drops it from its `randomPickWithPriority` candidates, the epsilon-greedy short-circuits skip it, and a stable wrapped `ActionFilter` removes the type from the candidate set shared by `greedyPickLeastVisited` and `randomlyPickAction`.

Navigation-essential sites SHALL NOT consult the cap: `selectNewActionBackToActivity`, `backToTrivialActivity`, `checkBackTrack`, and `handleNullAction` keep emitting BACK without limit, preserving the agent's ability to navigate back, backtrack to unsaturated states, and escape action-less screens (the gh9 stuck-loop guard).

With `ape.backMenuPickCap <= 0` the cap SHALL be disabled: selection behavior is identical to the uncapped specification, with no counter updates and no cap log lines.

When a discretionary pick increments a key's count to exactly `ape.backMenuPickCap`, the agent SHALL log `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` — once per key per run (capped keys are filtered from subsequent discretionary picks, so the reach-the-cap event cannot recur).

- **INV-SEL-NAV-01**: No (activity, BACK/MENU action type) key SHALL be selected by the discretionary channels (the two EARLY_STAGE greedy phases and the epsilon-greedy short-circuits/least-visited/roulette) more than `ape.backMenuPickCap` times per run when the cap is positive.
- **INV-SEL-NAV-02**: With `ape.backMenuPickCap <= 0`, action selection SHALL be identical to the uncapped specification with no cap-related side effects.
- **INV-SEL-NAV-03**: The navigation-essential BACK sites SHALL remain unbounded regardless of the cap state.
- **INV-SEL-NAV-04**: The wrapped filter SHALL be stable within one roulette invocation (identical include decisions across the counting and picking passes).
- **INV-SEL-NAV-05**: The two EARLY_STAGE greedy phases SHALL consult the same `activity + "|" + actionType` count as the epsilon-greedy channels: the `findGreedyActionBackward` direct unvisited-BACK pick SHALL be skipped when the BACK key is capped, and capped target-less BACK/MENU SHALL be excluded from the `findGreedyActionForward` roulette candidates; a BACK/MENU winning either EARLY_STAGE phase SHALL count toward the cap.

#### Scenario: fourth discretionary MENU pick is filtered
- **WHEN** `ape.backMenuPickCap=3` and the discretionary channels have already returned the MENU action of activity `com.x.A` three times (across any mix of sibling states, the third pick having logged `[APE-RV] BACK/MENU capped` once)
- **THEN** on the next `selectNewActionEpsilonGreedyRandomly` invocation in `com.x.A`, the MENU short-circuit SHALL NOT fire even if the state's MENU action is unvisited
- **AND** the least-visited scan and the roulette SHALL NOT include the MENU action among their candidates

#### Scenario: EARLY_STAGE backward — capped BACK is not directly picked
- **WHEN** `ape.backMenuPickCap=3`, the BACK key of activity `com.x.A` has reached the cap, and `findGreedyActionBackward` runs on a state of `com.x.A` whose BACK action is unvisited (`ENABLED_VALID_UNVISITED.include(back)` is true)
- **THEN** the direct unvisited-BACK pick (`SataAgent.java:1278-1283`) SHALL NOT fire
- **AND** the method SHALL fall through to the backtrack path instead

#### Scenario: EARLY_STAGE forward — capped BACK/MENU is excluded from the roulette candidates
- **WHEN** `ape.backMenuPickCap=3`, the MENU key of activity `com.x.A` has reached the cap, and `findGreedyActionForward` runs on a state of `com.x.A` whose greedy candidates include the MENU action
- **THEN** the MENU action SHALL be excluded from the `randomPickWithPriority` candidate list
- **AND** a BACK or MENU action still below the cap that wins the roulette SHALL be recorded against its `activity + "|" + actionType` key (and SHALL log `[APE-RV] BACK/MENU capped` if the record reaches the cap)

#### Scenario: refinement does not re-arm the count
- **WHEN** a capped BACK key exists for activity `com.x.A` and NamingFactory refinement mints a new sibling state of the same activity whose fresh BACK action is unvisited
- **THEN** the BACK short-circuit SHALL NOT fire for the new sibling (the key is activity-scoped, not state-scoped)

#### Scenario: navigation BACK is never capped
- **WHEN** the BACK key of activity `com.x.A` is capped and the agent needs to return to the target activity via `selectNewActionBackToActivity` (or reaches `handleNullAction` with BACK as the only valid action)
- **THEN** BACK SHALL be emitted normally by those sites

#### Scenario: cap disabled
- **WHEN** `ape.backMenuPickCap=0` (or negative)
- **THEN** all discretionary channels SHALL behave exactly as before this change, with no counter updates and no cap log lines

### Requirement: Per-step counterfactual attribution

When `stepTelemetryEnabled` is `true` and the step's action was picked by one of the four MOP-sensitive channels (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`), the `[APE-STEP]` line SHALL additionally carry `cf_action=<action>` and `cf_changed=0|1`: the action the same channel would have selected with `mopBoost` and `mopFrontierBoost` zeroed on every candidate (all other boosts and the candidate set unchanged), and whether that counterfactual pick differs from the factual one. `cf_action` SHALL use the same action rendering as the line's `action` field. Lines from other channels (LLM, launcher, buffer, `sata_other`) SHALL NOT carry the counterfactual fields — MOP boosts do not participate in those picks.

Channel semantics:
- The two short-circuits exist only because of MOP boosts: their counterfactual is the pick of the channel they short-circuit (the roulette/least-visited fall-through with MOP weights zeroed), and `cf_changed=1` whenever the short-circuit fired on a target the fall-through would not have picked.
- The two roulettes recompute the weighted pick over the same candidates with per-action priority reduced by that action's `mopBoost + mopFrontierBoost`.

**RNG stream isolation (hard constraint).** The counterfactual recomputation SHALL NOT advance or otherwise perturb the seeded RNG stream that drives selection (INV-EXPL-14 — the seeded run's action sequence must be bit-identical with the counterfactual computation on or off). The roulette counterfactual SHALL reuse the factual pick's recorded draw (as a fraction of total weight) rather than drawing again; the short-circuit counterfactuals are deterministic and draw nothing. A dedicated test SHALL assert sequence identity under a fixed seed with the counterfactual enabled and disabled — perturbing the stream would be a silent bias of the exact class the calibration autopsy catalogued.

**Honest caveat (interpretation rule, part of this contract):** the counterfactual is 1-step myopic. It establishes the *divergence point* — "did the MOP boost change THIS pick?" — not the cumulative trajectory effect, which only an arm-level contrast (MOP-off arm, rv-android side) can measure. Offline analysis SHALL NOT sum `cf_changed` into a claim about end-of-run coverage.

**Failure containment:** if the recomputation fails for any reason, the line SHALL carry `cf_changed=0` and selection SHALL be unaffected (the factual pick was already made before the counterfactual runs).

#### Scenario: MOP short-circuit divergence recorded
- **WHEN** the unvisited-MOP short-circuit selects action A (`mopBoost=500`) and the fall-through roulette with MOP weights zeroed would have picked action B
- **THEN** the `[APE-STEP]` line SHALL carry `pick_channel=short_circuit_unvisited cf_action=<B> cf_changed=1`

#### Scenario: roulette pick unchanged by MOP weights
- **WHEN** the epsilon-greedy roulette picks action C and the recomputation with `mopBoost`/`mopFrontierBoost` zeroed — replaying the same recorded draw fraction — also picks C
- **THEN** the line SHALL carry `cf_changed=0`
- **AND** `cf_action` SHALL equal the line's `action`

#### Scenario: MOP-off arm is counterfactually inert
- **WHEN** a run executes with all MOP weights zero (MOP-off arm)
- **THEN** every emitted counterfactual field SHALL be `cf_changed=0`
- **AND** any `cf_changed=1` in such a run SHALL be treated as a defect in the counterfactual implementation (smoke-gate invariant)

#### Scenario: seeded sequence identical with counterfactual on and off
- **WHEN** two runs execute with the same seed, APK, and configuration, one with the counterfactual computation enabled and one with it disabled
- **THEN** the sequence of selected actions SHALL be identical (the live RNG stream consumed exactly the same draws)

#### Scenario: non-MOP channels carry no counterfactual fields
- **WHEN** a step is picked by the LLM hook or the buffer
- **THEN** its `[APE-STEP]` line SHALL NOT contain `cf_action` or `cf_changed`

## Invariants

- **INV-SEL-01**: When `Config.leastVisitedPriorityTiebreak` is `true` (default), `greedyPickLeastVisited()` SHALL always prefer the action with the lowest `visitedCount`, regardless of priority; priority is ONLY a tiebreaker, never an override. When the flag is `false` (the `ape_pure` arm), ties among the lowest-`visitedCount` actions are broken by array order and no RV priority boost influences the pick (upstream APE) — scoped by `rv-scoring-pipeline`.
- **INV-SEL-02**: When all actions have distinct `visitedCount` values, the behavior SHALL be identical to the pre-change implementation. (Flag-independent: with distinct counts there is no tie, so `leastVisitedPriorityTiebreak` does not apply.)
- **INV-SEL-03**: The method SHALL remain O(n) — single pass over actions, no sorting.
- **INV-SEL-04**: When `Config.stepTelemetryEnabled` is `true` (default), exactly one `[APE-STEP]` line SHALL be emitted per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns. The line SHALL carry a `decision_source` from a fixed enum, never a free-form string. The boost-attribution rule SHALL only change which enum value is carried; it SHALL NOT add, remove, or duplicate `[APE-STEP]` lines, and SHALL NOT modify any boost field. When the flag is `false` (the `ape_pure` arm), zero `[APE-STEP]` lines are emitted while `decisionSource` is still populated internally — scoped by `rv-scoring-pipeline`.
- **INV-SEL-05**: When `stepTelemetryEnabled` is `true`, every `[APE-STEP]` line SHALL carry exactly one `pick_channel` field whose value is a member of the fixed enum (`short_circuit_unvisited`, `short_circuit_0step`, `roulette_greedy`, `roulette_early`, `launcher`, `llm`, `buffer`, `sata_other`) — never a free-form string; the channel is provenance set at the pick site, covering every early-return path.
- **INV-SEL-06**: On every emitted `[APE-STEP]` line, `activity_has_mop` SHALL equal `MopData.activityHasMop(<current activity>)` when `MopData` is non-null, and `0` whenever `MopData` is null. The field is 0/1, never absent when the line is emitted.
- **INV-SEL-07**: Every `[APE-STEP]` line SHALL occupy exactly one physical line; no widget-derived text SHALL introduce an unescaped `\n`/`\r` into the line.
- **INV-SEL-08**: Counterfactual fields (`cf_action`, `cf_changed`) SHALL appear on exactly the lines whose `pick_channel` is one of the four MOP-sensitive channels, and on no others. In a run where every MOP weight is zero, every emitted `cf_changed` SHALL be `0`.
- **INV-SEL-09**: The counterfactual recomputation SHALL consume zero draws from the live seeded RNG stream: for a fixed seed, APK, and configuration, the selected-action sequence SHALL be identical with the counterfactual computation enabled or disabled.
- **INV-SEL-10**: Every emitted `[APE-STEP]` line for an action with a resolved target SHALL carry exactly one `patched=0|1` field reporting whether that target's clickability was written by `patchGUITree`; lines for targetless actions SHALL omit it. The bit SHALL be set at the patch sites and never inferred at emission time from post-patch attributes, which are indistinguishable from native ones by construction. `patched=1` records that the patch *wrote* the node's clickability, in either direction — it is set both where the patch grants clickability to a child and where it removes it from the parent — so a causal reading ("this action exists only because of the patch") holds for `MODEL_CLICK` and SHALL NOT be extended to other action types on a patched node.
