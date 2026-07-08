# action-selection — delta: rv-scoring-pipeline

## Purpose

Gate two fork-only action-selection behaviors behind parity flags declared by the `scoring-pipeline` capability, so the `ape_pure` arm reproduces upstream APE:

- `leastVisitedPriorityTiebreak` (default `true`) gates the priority tiebreak in `State.greedyPickLeastVisited()`. Upstream APE breaks equal-`visitedCount` ties by array order; with the flag off, the fork reverts to that.
- `stepTelemetryEnabled` (default `true`) gates the `[APE-STEP]` per-step line. Upstream APE emits no such line; with the flag off, none is emitted.

Both defaults preserve current behavior. `INV-SEL-01`, `INV-SEL-02`, and `INV-SEL-04` describe the default (flag-on) configuration; this delta scopes them to that configuration in the requirement prose and does not restate the global invariants. Conditioning the invariant text itself is tracked as a sync/archive task (following the repo pattern for `INV-EXPL-05` in `activity-frontier`).

## MODIFIED Requirements

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

#### Scenario: SATA-selected action attributed
- **WHEN** `stepTelemetryEnabled` is `true` and `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy with all boosts equal to 0
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step#`, `state`, `action`, and per-mechanism boosts

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
