## Purpose

This delta spec extends the MOP guidance capability with WTG-based scoring. The existing MOP scoring is reactive — it boosts widgets in the current state that have MOP-reachable listeners. The WTG scoring is proactive — it boosts widgets whose click, according to the static Window Transition Graph, navigates to an activity containing MOP-reachable methods. This addition uses the `transitions[]` data parsed by the new `wtg-navigation` capability.

The WTG scoring pass is added after the existing MOP pass in `StatefulAgent.adjustActionsByGUITree()`, following the same additive priority pattern. It is controlled by `Config.mopWeightWtg` (default 200, set to 0 to disable).

## ADDED Requirements

### Requirement: WTG Scoring Pass in adjustActionsByGUITree

`StatefulAgent.adjustActionsByGUITree()` SHALL include a WTG scoring pass after the existing MOP scoring pass (lines 1141-1163). For each valid, target-requiring, resolved action, the pass SHALL call `MopScorer.scoreWtg(activity, shortId, mopData)` and add the result to the action's priority. This pass SHALL only execute when `_mopData` is non-null and `_mopData` has WTG transitions loaded.

#### Scenario: WTG boost applied alongside MOP boost
- **WHEN** a widget "btn_settings" in "com.example.MainActivity" has a direct MOP listener (MOP boost = +500) AND WTG shows it leads to "com.example.SettingsActivity" which also has MOP methods (WTG boost = +200)
- **THEN** the total priority boost SHALL be +700 (500 + 200)

#### Scenario: WTG boost without MOP boost
- **WHEN** a widget "btn_about" has no MOP listener (MOP boost = 0) but WTG shows it leads to a MOP-reachable activity (WTG boost = +200)
- **THEN** the total priority boost SHALL be +200

#### Scenario: No WTG data available
- **WHEN** `_mopData` is null or has no transitions loaded
- **THEN** the WTG scoring pass SHALL be skipped entirely (no boost applied)

### Requirement: Config Flag for WTG Weight

`Config.java` SHALL declare `ape.mopWeightWtg` as a public static final int, loaded via `Config.getInteger("ape.mopWeightWtg", 200)`. Setting this to 0 disables WTG scoring.

#### Scenario: Custom WTG weight
- **WHEN** `ape.properties` contains `ape.mopWeightWtg=400`
- **THEN** `Config.mopWeightWtg` SHALL be 400

#### Scenario: WTG scoring disabled
- **WHEN** `ape.mopWeightWtg=0` is set
- **THEN** `MopScorer.scoreWtg()` SHALL return 0 for all widgets

## Invariants

- **INV-MOP-05**: The WTG scoring pass SHALL execute AFTER the existing MOP scoring pass in `adjustActionsByGUITree()`. The pass order is: base priority → unvisited bonus → state transition bonus → MOP boost → WTG boost → coverage boost.
- **INV-MOP-06**: `MopScorer.scoreWtg()` SHALL return 0 when `MopData` is null, when WTG data is absent, when the widget has no matching WTG transition, or when `Config.mopWeightWtg` is 0. (Complete zero-return conditions; see also INV-WTG-02.)
