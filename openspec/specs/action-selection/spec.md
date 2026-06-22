## Purpose

APE-RV's `State.greedyPickLeastVisited()` selects the action with the lowest `visitedCount`, ignoring `priority` entirely. Since `SataAgent.egreedy()` routes to the greedy path 85-98% of the time (epsilon range 0.02-0.15), all priority boosts computed in `adjustActionsByGUITree()` — MOP (+500/+300/+100), WTG (+200), and coverage boost — are effectively ignored in the vast majority of action selections. Only the random path (2-15% of decisions) uses `randomlyPickAction()`, which performs priority-weighted sampling.

This delta spec adds priority as a tiebreaker to `greedyPickLeastVisited()`. When multiple actions share the same minimum `visitedCount`, the action with the highest `priority` is selected. This makes all priority boosts (MOP, WTG, coverage) influence the greedy path without changing the fundamental least-visited strategy.

## MODIFIED Requirements

### Requirement: State.greedyPickLeastVisited() — Priority Tiebreaker

`State.greedyPickLeastVisited(ActionFilter filter)` SHALL select the action with the lowest `visitedCount`. When multiple actions share the same lowest `visitedCount`, the action with the highest `priority` SHALL be selected. This replaces the current behavior where the first action in array order wins ties.

#### Scenario: Single least-visited action
- **WHEN** actions have visitedCounts [0, 3, 5]
- **THEN** the action with visitedCount=0 SHALL be selected (unchanged behavior)

#### Scenario: Tie broken by priority
- **WHEN** actions have visitedCounts [2, 2, 5] and priorities [32, 532, 52]
- **THEN** the action with visitedCount=2 and priority=532 SHALL be selected
- **AND** the MOP boost (+500) on that action effectively influenced the greedy selection

#### Scenario: All actions have same visitedCount
- **WHEN** all 10 actions have visitedCount=0 and priorities [32, 32, 232, 32, 532, 32, 32, 32, 32, 32]
- **THEN** the action with priority=532 (MOP-boosted) SHALL be selected

#### Scenario: Tie with equal priorities
- **WHEN** actions have visitedCounts [1, 1, 3] and priorities [52, 52, 32]
- **THEN** either of the two tied actions MAY be selected (implementation picks the first encountered)

### Requirement: Per-action decision-source telemetry

`StatefulAgent.resolveNewAction()` SHALL emit one structured `[APE-STEP]` log line for the action returned by `selectNewActionNonnull()` (`StatefulAgent.java:1259`), after the action is finalized and before it is executed. The line SHALL attribute the action to a `decision_source` and include the per-mechanism boosts that applied.

To attribute LLM and other early-return paths that bypass `logActionSelected` (`SataAgent.java:317,328,339,348`), `ModelAction` SHALL carry a `decisionSource` provenance field set at the point of selection. The field SHALL be populated on every return path: SATA strategies, the three LLM hooks (new-state, stagnation, random), the budget-exhausted trivial path, and the null path.

The `decision_source` enum SHALL be: `SATA`, `MOP`, `Coverage`, `LLM`, `Fuzz`, `Menu`, `WTG`, `Component`, `Budget`.

#### Scenario: SATA-selected action attributed
- **WHEN** `resolveNewAction()` finalizes an action chosen by the SATA epsilon-greedy strategy
- **THEN** a single `[APE-STEP]` line SHALL be emitted with `decision_source=SATA`
- **AND** the line SHALL include `step#`, `state`, `action`, and per-mechanism boosts

#### Scenario: LLM early-return attributed
- **WHEN** the new-state LLM hook returns a non-null action at `SataAgent.java:328` (bypassing `logActionSelected`)
- **THEN** that action's `decisionSource` SHALL be `LLM`
- **AND** exactly one `[APE-STEP]` line SHALL be emitted for it with `decision_source=LLM`

#### Scenario: Every step is attributable
- **WHEN** a run completes
- **THEN** every executed action SHALL have exactly one corresponding `[APE-STEP]` line
- **AND** no selection path SHALL produce zero or more than one line for a single action

## Invariants

- **INV-SEL-01**: `greedyPickLeastVisited()` SHALL always prefer the action with the lowest `visitedCount`, regardless of priority. Priority is ONLY a tiebreaker, never an override.
- **INV-SEL-02**: When all actions have distinct `visitedCount` values, the behavior SHALL be identical to the pre-change implementation.
- **INV-SEL-03**: The method SHALL remain O(n) — single pass over actions, no sorting.
- **INV-SEL-04**: Exactly one `[APE-STEP]` line SHALL be emitted per finally-selected action, covering every selection path including the LLM early-returns and budget/trivial early-returns. The line SHALL carry a `decision_source` from a fixed enum, never a free-form string.
