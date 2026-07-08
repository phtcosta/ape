# scoring-pipeline — delta: mop-reach-strategies

## Purpose

Add the reach lever with the highest ceiling (B): a scoring pass that rewards widgets opening an **unvisited MOP-bearing** screen — the intersection that neither existing frontier pass covers (`MopScorer.scoreWtg` boosts MOP targets but ignores visitation; the generic `FrontierPass` from `activity-frontier` boosts any unvisited target but ignores MOP). 52.5% of the 657 missed MOP screens are navigable on the static WTG, and this pass is the steering that reaches them.

> The `scoring-pipeline` capability (the `ScoringPass` interface and `ScoringPipeline.fromConfig(Config)` assembly point) is introduced by the sibling change **`rv-scoring-pipeline`**. This delta ADDS one pass to that capability and MUST be archived after it. B is authored as a `ScoringPass` per the separation-architecture design (§2.1); its current inline touchpoint is the WTG loop `StatefulAgent.java:1539-1564`, which `rv-scoring-pipeline` extracts into passes.

## ADDED Requirements

### Requirement: MopFrontierPass — Frontier Boost Toward Unvisited MOP Activities

A `MopFrontierPass` (in `com.android.commands.monkey.ape.agent.scoring`, implementing `ScoringPass`) SHALL add `Config.mopFrontierWeight` to the priority of a target-requiring, valid, resolved action when ALL THREE of the following hold for a WTG transition matched to that action:

1. **Widget match** — the action's short resource id equals `WtgTransition.widgetName` (the same resource-id matching used by the WTG-MOP boost and the generic frontier pass), obtained via the pass's own `MopData.getWtgTransitions(activity)` lookup (it SHALL NOT ride `MopScorer.scoreWtg`'s `int` return, which hides the target activity and only fires when MOP-reachable);
2. **Target is MOP-bearing** — `MopData.activityHasMop(WtgTransition.targetActivity) == true`;
3. **Target is unvisited** — `Graph.getActivityNode(WtgTransition.targetActivity) == null` at scoring time (evaluated live each pass; the boost recedes once the target is visited).

The boost SHALL be applied as a `setPriority` increment (`action.setPriority(action.getPriority() + mopFrontierWeight)` — the steering mechanism, since `wtgBoost` is telemetry-only and never enters `getPriority()`) AND recorded in the action's existing `wtgBoost` telemetry field via read-modify-write accumulation (`action.setWtgBoost(action.getWtgBoost() + mopFrontierWeight)`), the same field the WTG-MOP boost and the generic frontier boost use. It therefore accumulates with `mopWeightWtg` and `frontierBoostWeight` when they co-apply, and remains attributable via the `[APE-STEP] ... wtg=` field. No new `decision_source` value is introduced.

`MopFrontierPass.isEnabled()` SHALL be true only when `Config.mopFrontierWeight > 0` AND `MopData` is non-null with WTG data present. With `Config.mopFrontierWeight == 0` (default) the pass SHALL be byte-identical to being absent from the pipeline. The pass is independent of and additive to the generic `frontierBoostWeight` (which requires only unvisited, not MOP) — B is the strictly narrower predicate (unvisited AND MOP).

`Config.mopFrontierWeight` SHALL be declared in `Config.java`, loaded via `ape.mopFrontierWeight`, default `0`, and SHALL be registered in the `apePureMode` RV-flag registry (INV-ARCH-06 of `scoring-pipeline`), forced to `0` when `apePureMode=true`. `ScoringPipeline.fromConfig` SHALL assemble `MopFrontierPass` immediately after the generic `FrontierPass` and before `CoveragePass` — the frontier family stays contiguous and the relative-order contracts of INV-ARCH-03 are preserved.

- **INV-MFP-01**: `MopFrontierPass` SHALL add `mopFrontierWeight` to an action **only** when its matched WTG transition target satisfies both `activityHasMop(target) == true` AND `Graph.getActivityNode(target) == null` at scoring time. An action failing either condition SHALL receive nothing from this pass.
- **INV-MFP-02**: The boost SHALL be applied as a `setPriority` increment AND recorded into `wtgBoost` by read-modify-write; because `wtgBoost` is never read by `getPriority()`, the `setPriority` increment is mandatory for the boost to steer. When `mopWeightWtg` and/or `frontierBoostWeight` co-apply to the same action, the resulting `wtgBoost` SHALL equal their accumulated sum, not an overwrite.
- **INV-MFP-03**: With `Config.mopFrontierWeight == 0`, the scoring outcome SHALL be identical to the pipeline without `MopFrontierPass`.

#### Scenario: unvisited MOP target boosted
- **WHEN** widget W's WTG transition targets `com.x.CryptoActivity`, `activityHasMop("com.x.CryptoActivity")==true`, `Graph.getActivityNode("com.x.CryptoActivity")==null`, and `ape.mopFrontierWeight=200`
- **THEN** W's action priority SHALL be increased by 200 and its `wtgBoost` SHALL include 200

#### Scenario: MOP but already visited — no boost
- **WHEN** the transition target is MOP-bearing but `Graph.getActivityNode(target)` is non-null (visited)
- **THEN** `MopFrontierPass` SHALL add nothing to that action

#### Scenario: unvisited but non-MOP — no boost
- **WHEN** the transition target is unvisited but `activityHasMop(target)==false`
- **THEN** `MopFrontierPass` SHALL add nothing (this is the generic frontier pass's job, not B's)

#### Scenario: stacks with WTG-MOP and generic frontier
- **WHEN** the target is MOP-bearing AND unvisited, with `mopWeightWtg=200`, `frontierBoostWeight=200`, `mopFrontierWeight=200`
- **THEN** the action's `wtgBoost` SHALL accumulate all three (+600 total from the WTG/frontier passes), each also applied as a `setPriority` increment

#### Scenario: disabled
- **WHEN** `ape.mopFrontierWeight=0`
- **THEN** the scoring pipeline SHALL behave exactly as without `MopFrontierPass`, with no boost recorded
