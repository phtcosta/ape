# Delta: Action Selection (llm-coordinate-single-base)

## Purpose

Part B of `llm-coordinate-single-base` changes how the deterministic MOP shortcut ranks its candidates (design D21, plan decision 9). The shortcut has two sites, the epsilon-greedy `selectUnvisitedMopTarget` (`SataAgent.java:621-628`, called at `:571`) and the 0-step probe of `findGreedyActionForward` (`:1443`). Both go through `pickCappedMopTarget` → `pickBestMopTarget` (`:642-660`), which today takes the largest `mopBoost` and breaks ties by priority. Its candidates are unvisited *in the current abstract state*, which is weaker than it sounds: a refinement mints near-duplicate states in which a widget the agent already clicked counts as unvisited again, the re-arming the revisit cap exists to bound ("MOP target revisit cap"). And on the median E6 screen 67 % of the widgets carry a MOP mark (rv-android `docs/20261005_verificacao_plano_gator_compose.md` §3 table), so the boost alone rarely separates a widget the activity has already seen from one it has not.

The ranking now puts first the candidates whose widget has had no interaction yet anywhere in the activity, which is the coverage tracker's per-activity test `UICoverageTracker.hasActivityInteraction` (`UICoverageTracker.java:260`). The boost comes second. Under the new `distance` scoring mode (`mop-guidance` "MopScorer — Priority Boost", design D18) a third key separates candidates of equal boost by how many distinct unretired targets they lie near. Priority stays last. The eligibility rules, the cap and both sites are unchanged. The order applies under `flag` too, because both campaign arms carry it (author, 2026-10-05, verification §1 decision 9); a run of this jar under `flag` therefore does not reproduce the E6 shortcut, which jar `e93dea86` reproduces.

The requirement's text also stops naming `+500`/`+300` as the boost: under `distance` the weights are `ape.mopWeightD1..3` (500/400/300), split among tied actions with a floor at the next level's weight. The ranking reads the weight before that split (`mopWeight`), because the floor makes a split weight equal to the next level's and the target-count key would then prefer a farther widget (author, 2026-10-07 night).

## MODIFIED Requirements

### Requirement: MOP-target greedy short-circuit in epsilon-greedy selection

When `SataAgent.selectNewActionEpsilonGreedyRandomly()` runs and a valid, enabled, unvisited action carries a discriminative MOP boost (`ModelAction.getMopBoost() > 0`), the agent SHALL select such an action before the epsilon-greedy / roulette step. The check SHALL run after the existing Back-unvisited and Menu-unvisited short-circuits and before `egreedy()` (call site `SataAgent.java:571`). The selected action SHALL be attributed through `logActionSelected(action, EPSILON_GREEDY)` so the per-step telemetry remains consistent.

Among several eligible actions, the agent SHALL choose by the following keys, in order, each key consulted only to break a tie on the keys before it (design D21):

1. **Novelty in the activity.** An action whose widget has no recorded interaction in the state's activity (`UICoverageTracker.hasActivityInteraction(activity, UICoverageTracker.widgetId(action))` is false) ranks before every action whose widget has one. The test is per activity, not per abstract state, so a widget already exercised through another state of the same activity is not new. It is per (widget, action type): `widgetId` is `xpath|ActionType` (`UICoverageTracker.java:240-250`), so a long-click on a widget already clicked in the activity is new.
2. **Larger undivided MOP weight, `ModelAction.getMopWeight()`** (`mop-guidance` "MopScorer — Priority Boost"): the weight before the tie split of `distance`, equal to `mopBoost` under `flag`. The split `mopBoost` is not read here: three actions at `d ≤ 1` split to 400 each and would tie with an action at `d = 2` (400), and key 3 could then choose the farther one.
3. **Under `ape.mopScoring=distance` only: larger number of distinct unretired targets within `d + 1`**, where `d` is the distance behind the action's MOP weight (the smallest unretired distance of its lookup, `mop-guidance` "MopScorer — Priority Boost") and the count is taken over the pairs of the same lookup that produced that weight (`mop-guidance` "Handler Stamp Lookup", with the scoring pass's containment), skipping retired targets (`mop-guidance` "Target Retirement"). The plan admits this key "só como desempate" (plan §6.7). Under `flag` this key does not exist and the ranking goes from the boost to the priority.
4. **Larger `priority`.**

Actions equal on every key keep today's resolution: the first in candidate order wins.

The same ranking SHALL apply at the second deterministic site, the 0-step probe in `findGreedyActionForward` (`SataAgent.java:1443`; "MOP preference in the EARLY_STAGE unvisited roulette"), because both sites call the one picker. `pickBestMopTarget` SHALL remain a pure function of its inputs: it receives the coverage tracker (read-only), the activity, and, under `distance`, the retirement state, and it updates nothing. Candidate filtering is unchanged: only `mopBoost > 0` actions are candidates, the form-submit exclusion (INV-FORM-06) applies, and the revisit cap of "MOP target revisit cap" filters the candidates before the ranking.

This gives the discriminative boost a deterministic path to the monitored widget, which priority-weighted roulette reaches only probabilistically. The boost is `ape.mopWeightDirect`/`ape.mopWeightTransitive` (500/300) under `ape.mopScoring=flag` and `ape.mopWeightD1..3` (500/400/300, divided among the actions tied at the state's best weight level, with a floor at the next level's weight) under `distance`. The branch is bounded to unvisited targets so it does not override the least-visited strategy for already-visited actions, and the novelty key spends it on widgets the activity has not seen before it returns to ones it has. The discriminative `mopBoost` is produced only after the activity-level fallback is removed (see `mop-guidance` "MopScorer — Priority Boost"); a uniform boost would make this branch select an arbitrary widget, which is why this change and the fallback removal ship together.

- **INV-SEL-MOP-01**: The short-circuit SHALL only select actions that are valid, enabled, unvisited, and have `mopBoost > 0`. A visited MOP target SHALL NOT be force-selected by this branch.
- **INV-SEL-MOP-02**: When no eligible unvisited MOP-boosted action exists, the branch SHALL be a no-op and selection SHALL proceed identically to the pre-change behavior.
- **INV-SEL-MOP-06**: Whenever the deterministic MOP sites choose among two or more eligible candidates, an action whose (widget, action type) pair (`UICoverageTracker.widgetId`, `xpath|ActionType`) has no interaction in the activity SHALL be chosen over every action whose pair has one, whatever their boosts. The ranking SHALL read the coverage tracker and the retirement state and SHALL write neither.

#### Scenario: Unvisited MOP target selected ahead of roulette
- **WHEN** the current state has an unvisited valid action with `mopBoost=500` alongside other unvisited non-MOP actions
- **THEN** `selectNewActionEpsilonGreedyRandomly()` SHALL return the `mopBoost=500` action
- **AND** it SHALL be attributed via `logActionSelected(action, EPSILON_GREEDY)`

#### Scenario: Highest mopBoost wins among MOP targets
- **WHEN** two unvisited valid actions have `mopBoost=500` and `mopBoost=300`, and neither widget has an interaction in the activity
- **THEN** the `mopBoost=500` action SHALL be selected

#### Scenario: Visited MOP target not force-selected
- **WHEN** the only action with `mopBoost>0` is already visited
- **THEN** the short-circuit SHALL NOT select it
- **AND** selection SHALL proceed to the existing epsilon-greedy / roulette path

#### Scenario: No MOP target is a no-op
- **WHEN** no action in the state has `mopBoost>0`
- **THEN** the short-circuit SHALL be a no-op
- **AND** selection SHALL proceed identically to the pre-change behavior

#### Scenario: widget new to the activity wins over a larger boost
- **WHEN** the state of activity `A` has two unvisited valid actions, X with `mopBoost=500` whose widget was clicked earlier in another state of `A`, and Y with `mopBoost=300` whose widget has no interaction in `A`
- **THEN** the short-circuit SHALL select Y, under `ape.mopScoring=flag` and under `distance` alike

#### Scenario: target count breaks a boost tie under distance
- **WHEN** `ape.mopScoring=distance`, the state has a third action at `d = 1` that is already visited, so it is not a candidate, and alone holds the state's best level (500); two unvisited actions new to the activity both carry `mopBoost=400` from a nearest unretired target at `d = 2`, undivided because the `d = 2` level is not the state's best; X's lookup has unretired targets at distances `[2, 3, 3]` (three distinct targets within `d + 1 = 3`) and Y's at `[2, 5]` (one), and Y has the higher priority
- **THEN** the short-circuit SHALL select X

#### Scenario: a split tie does not invert the ranking under distance
- **WHEN** `ape.mopScoring=distance` and the state's unvisited actions new to the activity are X, Y and Z at `d = 1` and W at `d = 2`, so X, Y and Z split the `d ≤ 1` level to a `mopBoost` of 400 each (`max(500 / 3, 400)`) and W keeps 400 undivided; W's lookup has three distinct unretired targets within `d + 1 = 3` and X's one within `2`
- **THEN** the short-circuit SHALL select one of X, Y, Z (undivided weight 500), never W (400)

#### Scenario: target count does not exist under flag
- **WHEN** `ape.mopScoring=flag` and two unvisited actions new to the activity both carry `mopBoost=500`, X with the larger number of distance pairs and Y with the higher priority
- **THEN** the short-circuit SHALL select Y

#### Scenario: the 0-step probe ranks the same way
- **WHEN** `findGreedyActionForward` collects two unvisited candidates of activity `A`, X with `mopBoost=500` whose widget already has an interaction in `A` and Y with `mopBoost=300` whose widget has none
- **THEN** the probe SHALL return Y without consulting the roulette, with `dec.ch:"short_circuit_0step"`

#### Scenario: the revisit cap still filters before the ranking
- **WHEN** `ape.mopTargetPickCap=3`, widget Y's key has reached the cap, and Y is the only candidate new to the activity while X (`mopBoost=300`) is not new
- **THEN** Y SHALL NOT be a candidate and the short-circuit SHALL select X
