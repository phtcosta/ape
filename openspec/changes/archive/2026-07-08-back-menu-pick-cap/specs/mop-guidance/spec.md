# mop-guidance — delta: back-menu-pick-cap

## Purpose

Gate the OPTIONSMENU gateway boost by the new BACK/MENU discretionary pick cap. Without the gate, a MENU action whose activity key is capped would keep its +250 priority (`ape.mopWeightOpenMenu`) in the roulette and re-dominate selection through the boost channel (`decision_source=Menu`), defeating the cap on the exact activities where the boost applies. The gate suppresses the boost only after the activity's MENU key reaches `ape.backMenuPickCap`; below the cap, gh13 T1.2 semantics are unchanged.

## MODIFIED Requirements

### Requirement: MopScorer — OPTIONSMENU-Aware Menu Boost

`MopScorer.scoreOpenMenu(String activity, MopData data)` SHALL return `Config.mopWeightOpenMenu` (default 250) when `data.activityHasMopOptionsMenu(activity)` is `true`, else `0`. The lookup SHALL be O(1) over the precomputed `activitiesWithMopOptionsMenu` set (INV-MOP-13).

`StatefulAgent`'s action-priority pass SHALL apply this boost to the `MODEL_MENU` action of the current state when `_mopData != null` AND the activity's MENU pick key is still eligible under `ape.backMenuPickCap`. Eligibility is consulted through the protected hook `StatefulAgent.menuPickEligible(activity)` — base implementation returns `true` (RandomAgent/ReplayAgent unchanged); `SataAgent` overrides it with the cap check over its discretionary pick counts. When the hook returns `false`, the pass SHALL NOT modify the MENU action's priority and SHALL NOT set its `menuBoost`.

#### Scenario: Boost applied when OPTIONSMENU has MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.A")==true` AND `MopScorer.scoreOpenMenu("com.x.A", data)` is called
- **THEN** the return value SHALL equal `Config.mopWeightOpenMenu`

#### Scenario: Zero when no OPTIONSMENU MOP widget
- **WHEN** `data.activityHasMopOptionsMenu("com.x.B")==false`
- **THEN** `MopScorer.scoreOpenMenu("com.x.B", data)` SHALL return `0`

#### Scenario: Action-priority pass boosts MODEL_MENU when activity has MOP options menu
- **WHEN** `StatefulAgent`'s action-priority pass runs on a state whose activity is in `activitiesWithMopOptionsMenu` AND `menuPickEligible(activity)` returns `true`
- **THEN** the `MODEL_MENU` action's priority SHALL be incremented by `Config.mopWeightOpenMenu`
- **AND** a `Logger.iformat` line SHALL summarize the boost

#### Scenario: Boost suppressed once the MENU key is capped
- **WHEN** the activity's MENU pick key has reached `ape.backMenuPickCap` (so `menuPickEligible(activity)` returns `false`) AND the action-priority pass runs on a state of that activity
- **THEN** the `MODEL_MENU` action's priority SHALL NOT be incremented and its `menuBoost` SHALL remain 0
