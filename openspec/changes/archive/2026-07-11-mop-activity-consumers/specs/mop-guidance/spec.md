# mop-guidance — delta: mop-activity-consumers

## Purpose

Give the A′-widened `mopActivities` set a live navigation consumer and make it observable. The
cmpft4 forensic (2026-07-10) showed `stateMopDensity` is self-defeating on exactly the
activities A′ rescues: it gates on `activityHasMop` (87.7% of apps with A′) but counts only
widget-level flags (17.9% of apps), so the tiebreak compares 0-vs-0 wherever A′ made the
difference — the `sata_mop_widget` and `sata_mop_activity` arms ran statistically identically
(Friedman cov_method p=0.967). This delta adds a +1 activity-substrate floor to the density and
two load-line counters plus a tiebreak decision log so the arm contrast is auditable per trace.

## MODIFIED Requirements

### Requirement: MopScorer — MOP-Flagged State Density

`MopScorer.stateMopDensity(State, MopData, int timestamp)` SHALL return `0` when the state's
activity does not satisfy `activityHasMop(activity)` (cheap early-out, no widget resolution).
Otherwise it SHALL return `1 + count`, where `count` counts only actions whose resolved widget
is MOP-flagged: for each valid, target-requiring action, resolve the node's short id, look up
the widget (same resolution as `score`), and count it when `isDirectMop(eventType)` or
`isTransitiveMop(eventType)` holds.

The `+1` term is the **activity-substrate floor**: a state on a MOP-bearing activity that
carries no MOP-flagged widget (the A′-contributed case — activities whose MOP reach flows
through lambdas or component-level evidence, invisible at widget granularity) SHALL rank above
a state on a non-MOP activity (`1 > 0`) in the SATA navigation tiebreaks, while any
widget-flagged state still ranks strictly above an activity-floor-only state
(`1 + count > 1` for `count ≥ 1`). Comparisons between two widget-flagged states are unchanged
(both shift by +1). The floor is a cross-state ranking signal only; it SHALL NOT feed any
per-candidate boost (`MopScorer.score` remains widget-discriminative — the removed uniform
`+mopWeightActivity` fallback stays removed).

The method keeps the `int timestamp` parameter contract and its call sites as previously
specified (all call sites pass the current GUITree timestamp via `getTimestamp()`).

- **INV-MOP-24 (amended)**: above the `+1` activity-substrate floor, `stateMopDensity` SHALL
  count only MOP-flagged resolved widgets; it SHALL never reduce to a total action count, and
  the floor SHALL be exactly `1` (never proportional to action count or widget count). The
  Invariants-block entry for INV-MOP-24 SHALL be updated to this text at archive time.

#### Scenario: MOP-flagged widgets counted above the floor, unflagged ignored
- **WHEN** a state in a MOP activity has 10 valid targeted actions of which 2 resolve to MOP-flagged widgets
- **THEN** `stateMopDensity` SHALL return 3 (floor 1 + count 2)

#### Scenario: A′-only activity ranks above non-MOP activity
- **WHEN** state A is on an activity with `activityHasMop==true` contributed solely by the A′ component/reachability sources (zero MOP-flagged widgets resolve), and state B is on an activity with `activityHasMop==false`
- **THEN** `stateMopDensity(A) == 1` and `stateMopDensity(B) == 0` (navigation tiebreak prefers A)

#### Scenario: widget evidence outranks activity-floor-only evidence
- **WHEN** state A has exactly 1 MOP-flagged resolved action and state B is on a MOP activity with none
- **THEN** `stateMopDensity(A) == 2` and `stateMopDensity(B) == 1` (widget-flagged state preferred)

#### Scenario: dense non-MOP screen scores below sparse MOP screen
- **WHEN** state A has 12 valid actions, none MOP-flagged, on a non-MOP activity, and state B has 3 valid actions, one MOP-flagged, in a MOP activity
- **THEN** `stateMopDensity(A) == 0` and `stateMopDensity(B) == 2` (navigation tiebreak prefers B)

#### Scenario: non-MOP activity unchanged
- **WHEN** the state's activity has no MOP-reachable methods
- **THEN** `stateMopDensity` SHALL return 0 without resolving any widget

## ADDED Requirements

### Requirement: MopData — Activity-Substrate Counters on the Load Line

The `[APE-MOP-DATA] status=loaded …` line emitted by `MopData.load` SHALL additionally report
the activity-level MOP substrate: `mopActivities=<n>` — the final size of the `mopActivities`
set (after Pass-2 widget derivation, DIALOG re-keying, and A′ augmentation) — and
`mopActsAugmented=<m>` — the number of entries contributed by `augmentActivitiesFromSources`
beyond the set as it stood before augmentation. With `Config.mopActivitySourceComponents=false`,
`m` SHALL be `0`. These fields are diagnostic only and SHALL NOT alter any scoring, routing, or
load outcome.

Rationale: cmpft4 ran 657 `sata_mop_activity` traces in which the A′ augmentation was active
but invisible — no log line distinguished the arm from `sata_mop_widget`; the census had to
diff 109-line config echoes. The counters make the arm contrast (and the per-app A′ substrate)
readable from line 1 of every trace.

- **INV-MOP-32**: `mopActivities`/`mopActsAugmented` SHALL be pure counters over the load;
  their presence or values SHALL NOT change widget flags, the `mopActivities` set itself, or
  the loaded/rejected decision.

#### Scenario: flag off reports zero augmentation
- **WHEN** `Config.mopActivitySourceComponents=false` and the widget-derived set has 3 activities
- **THEN** the `status=loaded` line SHALL include `mopActivities=3 mopActsAugmented=0`

#### Scenario: flag on reports the A′ contribution
- **WHEN** `Config.mopActivitySourceComponents=true` and the A′ sources add 2 activities beyond the 3 widget-derived ones
- **THEN** the `status=loaded` line SHALL include `mopActivities=5 mopActsAugmented=2`

### Requirement: Navigation MOP-Tiebreak Decision Log

When the SATA trivial-activity path selection chooses among multiple shortest paths and the
`stateMopDensity` comparison is decisive (densities not all equal — the non-random branch), the
agent SHALL log exactly one line: `[APE-RV] Nav MOP tiebreak: density=<d> paths=<n>`, where
`<d>` is the winning path's target-state density and `<n>` the number of candidate paths. When
all densities are equal (random fallback) or `MopData` is null, no line SHALL be logged. The
per-step cold-state/cold-activity density comparisons SHALL NOT log (they run inside a
candidate loop; logging there would flood the trace).

- **INV-MOP-33**: the tiebreak log SHALL be emitted only from the path-selection site and only
  on the decisive branch; it SHALL NOT alter which path is selected.

#### Scenario: decisive density logs once
- **WHEN** 3 candidate paths have target densities 0, 0, 2
- **THEN** the path with density 2 SHALL be chosen and one `[APE-RV] Nav MOP tiebreak: density=2 paths=3` line SHALL be logged

#### Scenario: all-equal densities stay silent
- **WHEN** all candidate paths have equal target density
- **THEN** the path SHALL be chosen by the existing random fallback and no tiebreak line SHALL be logged
