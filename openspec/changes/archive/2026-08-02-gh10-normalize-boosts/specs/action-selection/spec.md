## Purpose

Delta spec for action selection stabilization. Two changes: (1) add a per-action boost cap after all guidance passes to prevent priority inflation regardless of weight configuration, and (2) disable dynamic epsilon by default to eliminate the path-dependent feedback loop that amplifies run-to-run variance.

## ADDED Requirements

### Requirement: Per-action boost cap

After all guidance passes (MOP, WTG, coverage) in `StatefulAgent.adjustActionsByGUITree()`, the system SHALL cap the total boost added to each action. The cap is defined by `Config.maxBoostCap` (default 80, configurable via `ape.maxBoostCap`).

For each target-requiring action, if `currentPriority - basePriority > maxBoostCap`, the priority SHALL be set to `basePriority + maxBoostCap`.

#### Scenario: Boost within cap

- **WHEN** an action has basePriority=32 and receives MOP boost=50 and WTG boost=20 (total boost=70)
- **THEN** the final priority SHALL be 102 (32+70, within cap of 80)
- **AND** no capping SHALL occur

#### Scenario: Boost exceeds cap

- **WHEN** an action has basePriority=32 and receives MOP boost=50, WTG boost=20, and coverage boost=20 (total boost=90)
- **THEN** the final priority SHALL be 112 (32+80, capped)
- **AND** the excess 10 points SHALL be discarded

#### Scenario: Cap disabled

- **WHEN** `ape.maxBoostCap=0` is set in ape.properties
- **THEN** no capping SHALL occur and boosts SHALL be applied without limit

## MODIFIED Requirements

### Requirement: Dynamic epsilon default

`Config.dynamicEpsilon` SHALL default to `false` (previously `true`). When disabled, `computeDynamicEpsilon()` SHALL return the static `epsilon` value (Config.defaultEpsilon, default 0.05). The dynamic epsilon implementation SHALL remain available for activation via `ape.dynamicEpsilon=true`.

#### Scenario: Static epsilon with dynamic disabled

- **WHEN** `Config.dynamicEpsilon` is false (default)
- **THEN** `computeDynamicEpsilon()` SHALL return `Config.defaultEpsilon` (0.05)
- **AND** the epsilon value SHALL NOT depend on coverage gap or any per-state metric

#### Scenario: Dynamic epsilon re-enabled

- **WHEN** `ape.dynamicEpsilon=true` is set in ape.properties
- **THEN** `computeDynamicEpsilon()` SHALL use the coverage gap formula as before

## Invariants

- **INV-SEL-04**: `maxBoostCap` MUST be non-negative. Value 0 disables capping.
- **INV-SEL-05**: The capping pass MUST run after all boost passes (MOP, WTG, coverage) and before action selection.
- **INV-SEL-06**: Non-targeted actions (BACK, MENU) SHALL NOT be subject to boost capping.
