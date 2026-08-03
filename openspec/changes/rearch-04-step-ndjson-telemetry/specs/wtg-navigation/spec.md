# wtg-navigation Delta Specification

## Purpose

Delta for `rearch-04-step-ndjson-telemetry`, over one sentence.

The frontier-boost requirement explains *why* the term is written into `wtgBoost` in addition to bumping priority, and it justifies that by naming where the value becomes observable: the `[APE-STEP] ... wtg=` field. This change retires that line family, so the justification names a rendering that no longer exists.

The quantity is unaffected — it is carried by the step record's `dec.wtg` field, subject to the defaults-omitted rule (absent when zero). Nothing about the scoring mechanism, INV-WTG-06, or INV-WTG-07 changes; only the name of the place the analyst reads it.

## MODIFIED Requirements

### Requirement: WTG Frontier Boost for Unvisited Activities

When `ape.frontierBoostWeight > 0` and WTG data is present, the WTG scoring pass in `StatefulAgent.adjustActionsByGUITree` SHALL add `Config.frontierBoostWeight` to the priority of every action whose matched WTG transition (same resource-id matching as the existing MOP-reach boost) targets an activity that is currently unvisited — `Graph.getActivityNode(targetActivity) == null` at scoring time. The frontier term SHALL be applied as a `setPriority` increment (`action.setPriority(action.getPriority() + frontierBoostWeight)` — this is the steering mechanism, since `wtgBoost` is a telemetry-only field that never enters `getPriority()`) AND recorded in the action's existing `wtgBoost` field via read-modify-write accumulation (`action.setWtgBoost(action.getWtgBoost() + frontierBoostWeight)`), the same field the MOP-reach WTG boost uses — mirroring the existing WTG-MOP pass, which does both a `setPriority` increment and a `setWtgBoost` write (`StatefulAgent.java:1457-1458`). Recording it in `wtgBoost` (rather than only bumping priority) is what makes the frontier gain detectable downstream and visible in the step record's `dec.wtg` field (`event-sink` capability). Because Lever A needs the transition target (which `MopScorer.scoreWtg`'s `int` return hides, and which only fires when MOP-reachable), the frontier term SHALL NOT ride `scoreWtg`; it SHALL use its own `MopData.getWtgTransitions(activity)` lookup with the same `widgetName`/resource-id match, checking `Graph.getActivityNode(WtgTransition.targetActivity) == null`. The frontier term is independent of MOP reachability and SHALL stack with the existing `Config.mopWeightWtg` boost when the same transition target is both MOP-reachable and unvisited.

The unvisited check SHALL be evaluated live on every scoring pass: once the target activity has been visited, subsequent passes SHALL NOT apply the frontier term for it. With `ape.frontierBoostWeight = 0` the pass SHALL be byte-identical to the frontier term being absent. When at least one action receives the frontier term in a pass, the agent SHALL log `[APE-RV] Frontier boost: state=<activity>#<stateKey>, boosted=<n>/<total>, maxBoost=<b>`.

- **INV-WTG-06**: The frontier term SHALL only ever be applied to actions whose WTG transition target has no `ActivityNode` in the model at scoring time, and SHALL be applied as a `setPriority` increment (the steering mechanism) AND recorded in the action's `wtgBoost` field via read-modify-write accumulation (`setWtgBoost(getWtgBoost() + frontierBoostWeight)`), mirroring the existing WTG-MOP pass. Because `wtgBoost` is telemetry-only (never read by `getPriority()`), applying the term through `wtgBoost` alone would leave priority — and therefore roulette weight — unchanged; the `setPriority` increment is mandatory for the boost to steer.
- **INV-WTG-07**: With `ape.frontierBoostWeight = 0`, scoring SHALL be identical to the pre-change WTG pass. With both boosts applicable, the action's priority gain from the WTG pass SHALL be `mopWeightWtg + frontierBoostWeight` (two `setPriority` increments), and its `wtgBoost` telemetry field SHALL likewise equal `mopWeightWtg + frontierBoostWeight` (accumulated by read-modify-write, not overwritten).

#### Scenario: widget leading to an unvisited activity is boosted
- **WHEN** widget W's WTG transition targets `com.x.DetailActivity`, which has no `ActivityNode` in the graph, and `ape.frontierBoostWeight = 200`
- **THEN** W's action priority SHALL be increased by 200 in the scoring pass
- **AND** one `[APE-RV] Frontier boost:` line SHALL be logged for the pass

#### Scenario: boost disappears after the target is visited
- **WHEN** `com.x.DetailActivity` gains an `ActivityNode` (it was visited) and the same state is re-scored
- **THEN** W's action SHALL NOT receive the frontier term in that pass

#### Scenario: stacking with the MOP-reach WTG boost
- **WHEN** the transition target is both MOP-reachable (`activityHasMop == true`) and unvisited, with `mopWeightWtg = 200` (the default) and `frontierBoostWeight = 200`
- **THEN** the action SHALL receive both terms (+400 total from the WTG pass, accumulated into `wtgBoost`)

#### Scenario: disabled
- **WHEN** `ape.frontierBoostWeight = 0`
- **THEN** the WTG pass SHALL behave exactly as specified before this change, with no frontier log lines
