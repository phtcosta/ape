# action-selection — delta: back-menu-pick-cap

## Purpose

Bound discretionary BACK/MENU re-picks per activity. BACK and MENU are per-state, target-less `ModelAction`s (`State.java:63-66`): every abstract state owns a fresh pair with `visitedCount == 0`. When NamingFactory refinement mints sibling states of the same physical screen, those fresh actions re-arm both the epsilon-greedy unvisited short-circuits (`SataAgent.selectNewActionEpsilonGreedyRandomly`) and the least-visited scan — the same unbounded re-fire pathology the MOP target revisit cap closed for widget-targeting actions, which structurally cannot cover BACK/MENU (`mopPickKey` returns null without a target). The cmpft2 run measured the cost: 25.3% of all executed steps were MODEL_BACK + MODEL_MENU, and MODEL_MENU had the worst interacted/discovered ratio (0.586) of any action type.

This delta adds a per-run cap keyed by `activity + "|" + actionType` on the three discretionary epsilon-greedy consumers (BACK short-circuit, MENU short-circuit, and the shared least-visited/roulette candidate set). Navigation-essential BACK emission is exempt by construction: `selectNewActionBackToActivity`, `backToTrivialActivity`, `checkBackTrack`, and `handleNullAction` do not consult the cap. The cap covers least-visited as well as the short-circuits because a capped-but-unfiltered BACK/MENU would immediately be re-elected by the least-visited scan (sibling states carry `visitedCount == 0`, which wins outright) — capping only the short-circuit would merely relabel the re-pick channel.

## Data Contracts

### Input
- `ape.backMenuPickCap: int` — maximum discretionary BACK/MENU picks per (activity, action type) key per run (default 3; <= 0 = unlimited). Read once at startup like `ape.mopTargetPickCap`.

### Side-Effects
- **[Trace]**: `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` — logged once per key, on the discretionary pick that reaches the cap (`picks == cap` after increment).

## ADDED Requirements

### Requirement: BACK/MENU discretionary pick cap

The agent SHALL count discretionary BACK/MENU picks per run, keyed by `activity + "|" + actionType.name()` — independent of the abstract state the action belongs to, so refinement-minted sibling states cannot re-arm the count. A discretionary pick is any BACK/MENU action returned by `selectNewActionEpsilonGreedyRandomly`: the BACK unvisited short-circuit, the MENU unvisited short-circuit, the least-visited scan, or the priority roulette. When a key's count has reached `ape.backMenuPickCap`, that action type SHALL be excluded from all four discretionary channels for that activity: the short-circuit skips it, and a stable wrapped `ActionFilter` removes the type from the candidate set shared by `greedyPickLeastVisited` and `randomlyPickAction`.

Navigation-essential sites SHALL NOT consult the cap: `selectNewActionBackToActivity`, `backToTrivialActivity`, `checkBackTrack`, and `handleNullAction` keep emitting BACK without limit, preserving the agent's ability to navigate back, backtrack to unsaturated states, and escape action-less screens (the gh9 stuck-loop guard).

With `ape.backMenuPickCap <= 0` the cap SHALL be disabled: selection behavior is identical to the uncapped specification, with no counter updates and no cap log lines.

When a discretionary pick increments a key's count to exactly `ape.backMenuPickCap`, the agent SHALL log `[APE-RV] BACK/MENU capped: activity=<activity> type=<type> picks=<n>` — once per key per run (capped keys are filtered from subsequent discretionary picks, so the reach-the-cap event cannot recur).

- **INV-SEL-NAV-01**: No (activity, BACK/MENU action type) key SHALL be selected by the discretionary epsilon-greedy channels more than `ape.backMenuPickCap` times per run when the cap is positive.
- **INV-SEL-NAV-02**: With `ape.backMenuPickCap <= 0`, action selection SHALL be identical to the uncapped specification with no cap-related side effects.
- **INV-SEL-NAV-03**: The navigation-essential BACK sites SHALL remain unbounded regardless of the cap state.
- **INV-SEL-NAV-04**: The wrapped filter SHALL be stable within one roulette invocation (identical include decisions across the counting and picking passes).

#### Scenario: fourth discretionary MENU pick is filtered
- **WHEN** `ape.backMenuPickCap=3` and the discretionary channels have already returned the MENU action of activity `com.x.A` three times (across any mix of sibling states, the third pick having logged `[APE-RV] BACK/MENU capped` once)
- **THEN** on the next `selectNewActionEpsilonGreedyRandomly` invocation in `com.x.A`, the MENU short-circuit SHALL NOT fire even if the state's MENU action is unvisited
- **AND** the least-visited scan and the roulette SHALL NOT include the MENU action among their candidates

#### Scenario: refinement does not re-arm the count
- **WHEN** a capped BACK key exists for activity `com.x.A` and NamingFactory refinement mints a new sibling state of the same activity whose fresh BACK action is unvisited
- **THEN** the BACK short-circuit SHALL NOT fire for the new sibling (the key is activity-scoped, not state-scoped)

#### Scenario: navigation BACK is never capped
- **WHEN** the BACK key of activity `com.x.A` is capped and the agent needs to return to the target activity via `selectNewActionBackToActivity` (or reaches `handleNullAction` with BACK as the only valid action)
- **THEN** BACK SHALL be emitted normally by those sites

#### Scenario: cap disabled
- **WHEN** `ape.backMenuPickCap=0` (or negative)
- **THEN** all discretionary channels SHALL behave exactly as before this change, with no counter updates and no cap log lines
