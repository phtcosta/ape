## Purpose

Delta spec for MOP guidance weight normalization. The MOP priority boosts (direct 500, transitive 300, activity 100) dwarf the base priority space (1–60), causing single actions to absorb 88%+ of selection probability. This change reduces defaults by 10× to make MOP guidance an influence rather than a dominator.

## MODIFIED Requirements

### Requirement: MOP weight defaults

MopScorer SHALL use reduced default weights that fit within the base priority range (1–60). The weights remain configurable via `ape.properties`.

| Parameter | Old Default | New Default |
|-----------|-------------|-------------|
| `ape.mopWeightDirect` | 500 | 50 |
| `ape.mopWeightTransitive` | 300 | 30 |
| `ape.mopWeightActivity` | 100 | 10 |

The scoring logic in `MopScorer.score()` SHALL NOT change — only the `Config` defaults change.

#### Scenario: MOP direct boost with new default

- **WHEN** a widget has `directMop=true` and no explicit property override
- **THEN** `MopScorer.score()` SHALL return 50
- **AND** the boosted action's total priority SHALL be approximately 80–110 (base ~30 + MOP 50)

#### Scenario: Custom weight override via ape.properties

- **WHEN** `ape.mopWeightDirect=200` is set in `ape.properties`
- **THEN** `MopScorer.score()` SHALL return 200 for direct MOP widgets
- **AND** the default value of 50 SHALL be ignored

## Invariants

- **INV-MOP-03** (unchanged): MOP boosts SHALL be additive to existing priority, not replacements.
- **INV-MOP-05** (unchanged): Pass order: base → unvisited → transition → MOP → WTG → coverage.
