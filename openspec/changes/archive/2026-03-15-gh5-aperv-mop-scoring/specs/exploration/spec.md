## MODIFIED Requirements

### Requirement: SataAgent — ABA Graph Navigation

Beyond simple greedy unvisited-action selection, `SataAgent` uses multi-step graph navigation to reach under-explored regions of the app. The ABA pattern (A → B → A) describes moving from the current state A to a "greedy state" B (a state with unvisited actions), executing actions at B, then returning to A. `SataAgent.selectNewActionEarlyStageForABA()` searches for such paths using `Graph.moveToState()`. A state B is considered a greedy state if `getGreedyActions(null, B)` returns a non-empty list. ABA navigation MUST NOT navigate to a saturated dialog state (a state with many in-edges and no forward unsaturated actions).

When multiple candidate greedy states along a forward path have equal visit counts, `SataAgent` SHALL prefer the state with higher MOP density (as computed by `MopScorer.stateMopDensity()`). This tiebreaker only applies when `_mopData` is non-null; when `_mopData` is null, the existing visit-count-only selection is preserved.

#### Scenario: Greedy state reachable in graph

- **WHEN** `SataAgent.selectNewActionEarlyStageForABAInternal()` is called
- **AND** there exists a state B reachable from the current state A via strong transitions (deterministic edges)
- **AND** B has at least one unvisited widget-targeted action (`getGreedyActions(A, B)` is non-empty)
- **AND** B is not a saturated dialog state
- **THEN** the first action on the shortest path from A to B SHALL be returned
- **AND** the remaining path steps SHALL be stored in the `actionBuffer` for subsequent steps

#### Scenario: Two greedy states with equal visit count, different MOP density

- **WHEN** `selectNewActionEarlyStageForABAInternal()` evaluates candidate greedy states B1 and B2 on the forward path
- **AND** `B1.getVisitedCount() == B2.getVisitedCount()`
- **AND** `_mopData` is non-null
- **AND** `MopScorer.stateMopDensity(B1, _mopData) == 5` and `MopScorer.stateMopDensity(B2, _mopData) == 2`
- **THEN** B1 SHALL be selected as the target greedy state

#### Scenario: MOP data null — visit count only

- **WHEN** `selectNewActionEarlyStageForABAInternal()` evaluates candidate greedy states
- **AND** `_mopData` is null
- **THEN** the selection SHALL use visit count only (existing behavior)
- **AND** no calls to `MopScorer.stateMopDensity()` SHALL be made

#### Scenario: No greedy state reachable

- **WHEN** `selectNewActionEarlyStageForABAInternal()` finds no state B satisfying the greedy condition
- **THEN** `null` SHALL be returned and the caller SHALL fall through to the next selection strategy

---

### Requirement: SataAgent — Trivial Activity Detection

`SataAgent` identifies activities that are difficult to explore further as "trivial" and avoids spending excessive time in them. An activity is trivial when it has fewer states than `ape.trivialActivityRankThreshold` (default: `3`) OR when its visited rate is below a threshold relative to the median/mean visit count across all activities. When the current activity is non-trivial and a trivial activity has unvisited actions reachable via strong transitions, `SataAgent` SHALL navigate to that trivial activity to exploit unexplored actions there.

When multiple shortest paths to trivial activities are available and `_mopData` is non-null, `SataAgent` SHALL prefer the path whose target state has higher MOP density (as computed by `MopScorer.stateMopDensity()`). When MOP densities are equal or `_mopData` is null, random selection among paths is preserved.

#### Scenario: Current activity is trivial

- **WHEN** `SataAgent.selectNewActionForTrivialActivity()` is called
- **AND** the current state's activity is itself in the `trivialActivities` set
- **THEN** the method SHALL return `null` (no navigation needed; caller handles action selection locally)

#### Scenario: Multiple trivial activities reachable, MOP tiebreaker

- **WHEN** the current state's activity is not trivial
- **AND** two shortest paths lead to trivial activities with greedy states at states T1 and T2
- **AND** `_mopData` is non-null
- **AND** `MopScorer.stateMopDensity(T1, _mopData) == 3` and `MopScorer.stateMopDensity(T2, _mopData) == 1`
- **THEN** the path to T1 SHALL be selected

#### Scenario: MOP data null — random selection preserved

- **WHEN** multiple shortest paths to trivial activities are available
- **AND** `_mopData` is null
- **THEN** random selection among paths SHALL be used (existing behavior)

#### Scenario: Trivial activity reachable

- **WHEN** the current state's activity is not trivial
- **AND** at least one trivial activity has a state with unvisited actions reachable by a forward (non-BACK) path
- **THEN** the first action of the shortest path to that trivial activity SHALL be returned
