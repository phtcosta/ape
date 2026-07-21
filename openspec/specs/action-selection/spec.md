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

Emission of the `[APE-STEP]` line is gated by `Config.stepTelemetryEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `stepTelemetryEnabled` is `true` (default), `StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by `selectNewActionNonnull()` (`StatefulAgent.java:1259`), after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied. When `stepTelemetryEnabled` is `false` (the `ape_pure` arm), no `[APE-STEP]` line SHALL be emitted for any action; the `decisionSource` provenance field on `ModelAction` SHALL still be populated for internal use, and no other selection behavior SHALL change. INV-SEL-04 ("exactly one `[APE-STEP]` line per finally-selected action") describes the default (`stepTelemetryEnabled=true`) configuration.

To attribute LLM and other early-return paths that bypass `logActionSelected` (`SataAgent.java:317,328,339,348`), `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection. The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path — regardless of `stepTelemetryEnabled`.

For SATA-chain selections, the `decisionSource` SHALL be set by a boost-attribution rule scoped to the **selection sub-paths that actually consume priority**, not to whole `SataEventType` branches. Attribution applies ONLY when the action is a `ModelAction` chosen by: (a) a priority roulette — `State.randomlyPickAction` in the epsilon-greedy random branch (`SataAgent.java:487`) or `RandomHelper.randomPickWithPriority` over the EARLY_STAGE unvisited candidates (`SataAgent.java:1072`) — or (b) a boost-based deterministic pick — the MOP short-circuit (`selectUnvisitedMopTarget`) or the EARLY_STAGE MOP preference (`pickBestMopTarget` in `findGreedyActionForward`). When attribution applies AND the action carries at least one boost greater than 0 among `getMopBoost()`/`getWtgBoost()`/`getMenuBoost()`/`getCoverageBoost()`/`getFormBoost()`, `decisionSource` SHALL be set to the mechanism holding the largest boost. Ties on the largest boost SHALL be resolved by the fixed precedence `MOP > WTG > Menu > Form > Coverage`. In all other cases `decisionSource` SHALL remain `SATA` — in particular on the sub-paths that select for reasons other than priority even though they live inside `EARLY_STAGE`/`EPSILON_GREEDY`: graph-navigation and shortest-path picks (`SataAgent.java:1087,1099`, backward `:1110-1127`), the Back-/Menu-unvisited short-circuits (`:461,468`), and `greedyPickLeastVisited` (`:484`, minimum visit count; priority is only its tie-break).

This attribution reports which mechanism most contributed to the chosen action on a sub-path that actually consumed priority. It SHALL NOT be interpreted as a counterfactual claim that the boost changed the selection outcome.

The `decision_source` enum SHALL be: `SATA`, `MOP`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`, `Form`. A pick driven by the form-completion boost (`getFormBoost()` largest) SHALL be attributed `Form`.

When emitted, the `[APE-STEP]` line SHALL also carry a `clock=<epochMillis>` field (device wall clock at emission), enabling offline temporal joins between the `.trace` and externally collected artifacts without any APE↔logcat coupling — APE SHALL NOT read from or write to logcat.

When emitted, the `[APE-STEP]` line SHALL carry a `step=<N>` field, where `<N>` is the agent exploration timestamp (`getTimestamp()`) at selection time. The timestamp is incremented exactly once per agent step at update-wrapper entry, so `step` is constant across a single selection, and — because at most one `[APE-STEP]` line is emitted per agent step (INV-SEL-04) — `step` values are unique within a run. `step` is the per-step join key consumed by the `[APE-OUTCOME]` attribution line (`Per-Step Decision Outcome Attribution`, scoring-pipeline capability). This is the agent timestamp, not the independent `Graph` timestamp.

#### Scenario: SATA-selected action attributed
- **WHEN** `stepTelemetryEnabled` is `true` and `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step=<N>`, `state`, `action`, and per-mechanism boosts

#### Scenario: MOP-boosted action from the EARLY_STAGE roulette attributed to MOP
- **WHEN** the EARLY_STAGE unvisited roulette (or the MOP preference probing it) picks a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when `stepTelemetryEnabled` is `true` the emitted `[APE-STEP]` line SHALL report `decision_source=MOP`

#### Scenario: Largest boost wins on the epsilon-greedy roulette
- **WHEN** `randomlyPickAction` picks a `ModelAction` whose boosts are `mop=0, wtg=200, menu=0, coverage=100, form=0`
- **THEN** the action's `decisionSource` SHALL be `WTG`
- **AND** the Coverage boost SHALL NOT change the attribution because it is smaller

#### Scenario: Tie precedence MOP>WTG>Menu>Form>Coverage
- **WHEN** a roulette pick carries boosts `mop=300, wtg=300, menu=0, coverage=0, form=0`
- **THEN** the action's `decisionSource` SHALL be `MOP`
- **AND** when the tie is instead `wtg=300, menu=300` the `decisionSource` SHALL be `WTG`

#### Scenario: Form-driven pick attributed to Form
- **WHEN** a roulette pick carries boosts `mop=0, wtg=0, menu=0, coverage=100, form=150`
- **THEN** the action's `decisionSource` SHALL be `Form`

#### Scenario: Boosted action in a priority-blind branch stays SATA
- **WHEN** `logActionSelected(action, USE_BUFFER)` is called for a `ModelAction` whose boosts are `mop=500, wtg=0, menu=0, coverage=0`
- **THEN** the action's `decisionSource` SHALL be `SATA`
- **AND** the boost SHALL NOT be attributed because the `USE_BUFFER` branch does not consume priority

#### Scenario: Least-visited pick stays SATA
- **WHEN** `greedyPickLeastVisited` returns a `ModelAction` carrying `mop=500` (visit-count minimum, priority used only as tie-break)
- **THEN** the action's `decisionSource` SHALL be `SATA`

#### Scenario: [APE-STEP] carries wall clock
- **WHEN** `stepTelemetryEnabled` is `true` and any `[APE-STEP]` line is emitted
- **THEN** it SHALL include a `clock=<epochMillis>` field

#### Scenario: LLM early-return attributed
- **WHEN** the new-state LLM hook returns a non-null action at `SataAgent.java:328` (bypassing `logActionSelected`)
- **THEN** that action's `decisionSource` SHALL be `LLM`
- **AND** when `stepTelemetryEnabled` is `true` exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=LLM`

#### Scenario: Every step is attributable when telemetry is enabled
- **WHEN** `stepTelemetryEnabled` is `true` and a run completes
- **THEN** every executed action SHALL have exactly one corresponding `[APE-STEP]` line
- **AND** no selection path SHALL produce zero or more than one line for a single action

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

## Invariants

- **INV-SEL-01**: When `Config.leastVisitedPriorityTiebreak` is `true` (default), `greedyPickLeastVisited()` SHALL always prefer the action with the lowest `visitedCount`, regardless of priority; priority is ONLY a tiebreaker, never an override. When the flag is `false` (the `ape_pure` arm), ties among the lowest-`visitedCount` actions are broken by array order and no RV priority boost influences the pick (upstream APE) — scoped by `rv-scoring-pipeline`.
- **INV-SEL-02**: When all actions have distinct `visitedCount` values, the behavior SHALL be identical to the pre-change implementation. (Flag-independent: with distinct counts there is no tie, so `leastVisitedPriorityTiebreak` does not apply.)
- **INV-SEL-03**: The method SHALL remain O(n) — single pass over actions, no sorting.
- **INV-SEL-04**: When `Config.stepTelemetryEnabled` is `true` (default), exactly one `[APE-STEP]` line SHALL be emitted per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns. The line SHALL carry a `decision_source` from a fixed enum, never a free-form string. The boost-attribution rule SHALL only change which enum value is carried; it SHALL NOT add, remove, or duplicate `[APE-STEP]` lines, and SHALL NOT modify any boost field. When the flag is `false` (the `ape_pure` arm), zero `[APE-STEP]` lines are emitted while `decisionSource` is still populated internally — scoped by `rv-scoring-pipeline`.
