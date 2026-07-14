## ADDED Requirements

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
