## Purpose

Delta spec for UI coverage boost weight normalization. The coverage boost (100) dwarfs the unvisited bonus (+20) in the base priority system, creating excessive priority inflation. Reduced to 20 to align with the unvisited bonus magnitude.

## MODIFIED Requirements

### Requirement: Coverage boost weight default

`Config.coverageBoostWeight` SHALL default to 20 (previously 100). The decay formula and UICoverageTracker logic SHALL NOT change.

#### Scenario: Coverage boost with new default at first visit

- **WHEN** a state has `stateVisits=0` and a widget has `interactionCount=0`
- **THEN** the coverage boost SHALL be 20 (full weight, no decay)
- **AND** the boosted action's priority SHALL be approximately 50–70 (base ~30 + coverage 20)

#### Scenario: Coverage boost decay

- **WHEN** a state has `stateVisits=10`
- **THEN** `decayedWeight` SHALL be `20 / (1 + 10/5) = 6`
- **AND** widgets with `interactionCount=0` SHALL receive +6 priority

## Invariants

- **INV-COV-01** (unchanged): Coverage gap SHALL be in [0.0, 1.0].
