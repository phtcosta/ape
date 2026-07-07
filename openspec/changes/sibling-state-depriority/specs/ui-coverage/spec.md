# ui-coverage — delta: sibling-state-depriority

## Purpose

Contain the exploration-budget drain caused by state fragmentation, on the agent side. When NamingFactory refinement and organic StateKey growth split one physical screen into many sibling abstract states, each sibling's actions restart at `visitedCount == 0` and the SATA machinery re-exercises the same physical widgets once per sibling. The cmpft2 audit measured 34% of activity rollups ending with 10+ live states. The `coverage-boost-activity-scope` change stopped the coverage *boost* from re-arming across siblings; this delta adds the complementary *penalty*: in activities fragmented beyond the refinement cap, actions that are redundant at activity scope lose roulette weight, shifting the budget to activity-novel widgets and MOP targets.

The pass deliberately keys on the activity's state *count* (order-free — `ActivityNode.states` is an unordered set) and on the cumulative activity-scoped interaction membership (`hasActivityInteraction`, eviction-proof per INV-COV-09), never on a sibling's identity or rank. Naming, refinement, and the model are untouched.

## Data Contracts

### Input
- `ape.siblingStatePenalty: int` — priority subtracted from redundant actions in over-fragmented activities (default 24; 0 = pass disabled). Threshold is the existing `ape.maxStatesPerActivity` (no new threshold flag).

### Side-Effects
- **[Trace]**: `[APE-RV] Sibling depriority: state=<activity>#<stateKey>, penalized=<n>/<total>, siblings=<s>` — once per scoring pass when at least one action was penalized.

## ADDED Requirements

### Requirement: Sibling-Redundancy Deprioritization in Action Scoring

When `ape.siblingStatePenalty > 0` and the current activity's model state count (`Graph.getActivityNode(activity).getStates().size()`) is strictly greater than `Config.maxStatesPerActivity`, the action-scoring pass SHALL subtract `ape.siblingStatePenalty` from the priority of every action of the current state that satisfies ALL of: (a) it requires a target (target-less BACK/MENU are governed by their own cap), (b) its `mopBoost` is 0 (MOP steering exempt), (c) its `wtgBoost` is 0 (WTG/frontier steering exempt), and (d) `UICoverageTracker.hasActivityInteraction(activity, widgetId)` is `true` (the widget was already interacted somewhere in this activity — redundant at activity scope). The resulting priority SHALL be floored at 1 so the action remains visible to the priority roulette.

Activity-novel actions (`hasActivityInteraction == false`) SHALL NOT be penalized under any circumstance — first-touch keeps full priority. With `ape.siblingStatePenalty = 0` the pass SHALL be disabled with no side effects. When at least one action is penalized in a pass, the agent SHALL log `[APE-RV] Sibling depriority: state=<activity>#<stateKey>, penalized=<n>/<total>, siblings=<s>`.

- **INV-COV-10**: No penalty SHALL be applied while the activity's state count is <= `Config.maxStatesPerActivity`.
- **INV-COV-11**: Actions that are activity-novel (`hasActivityInteraction == false`), carry `mopBoost > 0`, carry `wtgBoost > 0`, or are target-less (`requireTarget() == false`) SHALL never be penalized by this pass.
- **INV-COV-12**: Penalized priorities SHALL be >= 1; with `ape.siblingStatePenalty = 0` the scoring output SHALL be byte-identical to the pass being absent.

#### Scenario: redundant action penalized in an over-fragmented activity
- **WHEN** activity `com.x.A` has 11 model states (`maxStatesPerActivity` = 10), widget W was interacted in some sibling (`hasActivityInteraction("com.x.A", W) == true`), and the current state's click action on W carries priority 32 with `mopBoost == 0`
- **THEN** after the scoring pass the action's priority SHALL be 8 (32 − 24)
- **AND** one `[APE-RV] Sibling depriority:` line SHALL be logged for the pass

#### Scenario: novel widget keeps full priority
- **WHEN** the same over-fragmented activity contains an action on widget V with `hasActivityInteraction("com.x.A", V) == false`
- **THEN** the pass SHALL NOT modify that action's priority

#### Scenario: below threshold, pass inert
- **WHEN** activity `com.x.B` has exactly 10 model states (== `maxStatesPerActivity`)
- **THEN** no action of its states SHALL be penalized and no `Sibling depriority` line SHALL be logged

#### Scenario: MOP target exempt
- **WHEN** an action in an over-fragmented activity carries `mopBoost == 300` and its widget is activity-redundant
- **THEN** the pass SHALL NOT modify that action's priority

#### Scenario: WTG/frontier target exempt
- **WHEN** an action in an over-fragmented activity carries `wtgBoost > 0` (a WTG-MOP or `activity-frontier` steering signal) and its widget is activity-redundant
- **THEN** the pass SHALL NOT modify that action's priority

#### Scenario: floor at 1
- **WHEN** a redundant action's priority is 8 and `ape.siblingStatePenalty` = 24
- **THEN** after the pass its priority SHALL be 1 (not negative, not 0 — it stays roulette-visible)
