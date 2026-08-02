## Purpose

Delta spec for WTG navigation weight normalization. The WTG boost (200) is 5–10× larger than base priorities, over-prioritizing WTG-matching widgets. Reduced to 20 to maintain influence without dominance.

## MODIFIED Requirements

### Requirement: WTG weight default

`Config.mopWeightWtg` SHALL default to 20 (previously 200). The scoring logic in `MopScorer.scoreWtg()` SHALL NOT change.

#### Scenario: WTG boost with new default

- **WHEN** a widget matches a WTG transition leading to a MOP-reachable activity and no explicit property override
- **THEN** `MopScorer.scoreWtg()` SHALL return 20
- **AND** the boosted action's priority increase SHALL be proportional to (not dominating) the base priority

## Invariants

- **INV-WTG-02** (unchanged): `scoreWtg()` SHALL return 0 when MopData is null or `mopWeightWtg=0`.
