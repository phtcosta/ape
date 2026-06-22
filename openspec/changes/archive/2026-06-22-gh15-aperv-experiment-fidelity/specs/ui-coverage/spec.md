## Purpose

The UI-coverage metric is used as an internal steering signal, not as the headline result (method coverage comes from rv-android, independently). Three properties make the current "coverage gap" unfaithful: (1) the coverage key uses `action.getTarget().toXPath()` and folds the action *type* into the key only for non-target actions, so distinct action types on the same target (e.g. SCROLL_DOWN vs SCROLL_UP, or CLICK vs LONG_CLICK) collapse into one key; (2) `StateKey` includes the `naming` level in identity, so one Activity fragments into many coverage maps (mean ~22, max ~84), inflating the gap; (3) `stateData` is never pruned and grows monotonically, a latent OOM. This delta redefines the metric to be faithful. The previous "gap" semantics is **replaced**, not kept in parallel — historical comparability of the gap is intentionally abandoned (the gap is an internal signal, not the reported outcome).

## Invariants

- **INV-COV-05**: `stateData` SHALL be bounded. When the bound is reached, the tracker SHALL evict entries but SHALL preserve the per-Activity rollup so that already-counted coverage is not zeroed mid-run.
- **INV-COV-06**: The coverage key for a target action SHALL incorporate the action type, so two distinct action types on the same target widget are tracked as distinct coverage elements.

## MODIFIED Requirements

### Requirement: UICoverageTracker — Widget Registration

`UICoverageTracker.registerScreenElements(State state, List<ModelAction> actions)` SHALL register all actions of the state as trackable widgets. The widget ID for each action SHALL incorporate the action type for ALL actions:
- For actions with a target (`requireTarget() == true`): `action.getTarget().toXPath() + "|" + action.getType().name()`
- For actions without a target (BACK, MENU): `action.getType().name()`

Including the action type for target actions ensures distinct action types on the same target widget are tracked separately (INV-COV-06). Registration is idempotent — re-registering the same state replaces the widget set while preserving prior interaction counts for widgets still present.

#### Scenario: Register distinct action types on the same target
- **WHEN** `registerScreenElements(stateA, actions)` is called for a state where the same `RecyclerView` target has both `MODEL_SCROLL_DOWN` and `MODEL_SCROLL_UP` actions
- **THEN** the state SHALL have two distinct registered elements
- **AND** their IDs SHALL be `"<xpath>|MODEL_SCROLL_DOWN"` and `"<xpath>|MODEL_SCROLL_UP"`

#### Scenario: Non-target action keyed by type
- **WHEN** the state has a `MODEL_BACK` action
- **THEN** its element ID SHALL be `"MODEL_BACK"`

#### Scenario: Re-register preserves counts for present widgets
- **WHEN** `registerScreenElements(stateA, newActions)` is called after a previous registration
- **THEN** widgets still present SHALL retain their prior interaction counts
- **AND** widgets no longer present SHALL be dropped

## ADDED Requirements

### Requirement: Per-Activity coverage aggregation for reporting

Coverage reporting SHALL aggregate per Activity, collapsing the `naming`-level fragments that `StateKey` (`StateKey.java:47,57`) produces for one Activity. The reported coverage for an Activity SHALL be computed over the union of its fragments' registered widgets and interactions, so that finer NamingFactory refinement does not inflate the reported gap. The per-`State` tracking used for steering MAY remain fragment-level; only the reported metric aggregates.

#### Scenario: Fragments of one Activity report as one
- **WHEN** `MainActivity` has fragmented into 4 `StateKey`s via naming refinement
- **THEN** the reported coverage for `MainActivity` SHALL aggregate all 4 fragments' widgets and interactions
- **AND** the reported gap SHALL NOT count the same Activity four times

### Requirement: Bounded stateData

`UICoverageTracker.stateData` SHALL be bounded to a configurable maximum number of entries. When the bound is exceeded, the tracker SHALL evict the least-recently-updated entries, after folding their coverage into the per-Activity rollup so that eviction does not zero already-counted coverage mid-run (INV-COV-05). This replaces the current unbounded growth (`StatefulAgent.java:163`, no prune).

#### Scenario: Eviction preserves the rollup
- **WHEN** `stateData` reaches its bound and an entry is evicted
- **THEN** the evicted entry's interaction counts SHALL already be folded into the Activity rollup
- **AND** the reported per-Activity coverage SHALL NOT decrease due to the eviction

#### Scenario: Bound configurable
- **WHEN** the configured bound is `N`
- **THEN** `stateData` SHALL hold at most `N` live entries at any time
