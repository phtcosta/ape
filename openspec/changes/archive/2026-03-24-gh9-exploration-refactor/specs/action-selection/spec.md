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

## Invariants

- **INV-SEL-01**: `greedyPickLeastVisited()` SHALL always prefer the action with the lowest `visitedCount`, regardless of priority. Priority is ONLY a tiebreaker, never an override.
- **INV-SEL-02**: When all actions have distinct `visitedCount` values, the behavior SHALL be identical to the pre-change implementation.
- **INV-SEL-03**: The method SHALL remain O(n) — single pass over actions, no sorting.
