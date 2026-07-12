# Proposal: mop-census-launcher

## Why

Three consecutive null results (cmpft4 forensic, cmpft5 Gate 0 v2/v3) showed the stagnation
activity launcher structurally cannot deliver a treatment dose: (1) its `exported == true`
eligibility filter excludes every MOP-reachable activity in modern apps (API 31+ apps export only
the launcher activity — Gate 0 v3: dose=0 in 12/12 arm-runs, `launchableRT=0` in all 3 smoke apps),
while the actual launch path (`IActivityManager.startActivity` from uid 2000) needs no export
(probe-verified); (2) stagnation-gated firing yields near-zero episodes in exactly the large apps
where the A′ census contrast lives (48% of cmpft4 traces have zero 10-stable windows; speakthat
never exceeded counter 6 in 300s); (3) the non-MOP fallback in candidate selection makes both
experiment arms launch the same eligible set, diluting the contrast to ordering only.

This change is the last attempt at H1 ("the A′ activity census converts into MOP coverage") and it
is a **simplification, not a fourth patch**: the launcher is reduced to one behavior — every N
steps, launch the next unvisited activity from the arm's MOP census — deleting the exported
filter, the non-MOP fallback, the MOP-first ordering flag, and the coupling to the stagnation
counter. No new Config flags; no rv-android changes (all property names already mapped in
`tool.py` are kept).

## What Changes

- **Firing**: cadence-based — the launcher fires every `Config.activityTriggerStagnationStep`
  selection steps (dedicated step counter), decoupled from `graphStableCounter`. **BREAKING**: the
  stagnation-equality gate is deleted; the property name `ape.activityTriggerStagnationStep` is
  kept (rv-android `tool.py` maps it) but now means cadence, documented at the declaration.
- **Eligibility**: candidates come **only** from the arm's MOP census
  (`MopData.getMopActivities()`, the `activityHasMop` set — augmented in the treatment arm via
  `ape.mopActivitySourceComponents`). **BREAKING**: the `exported == true` term is deleted;
  `permission == null`, not-main, unvisited, and the `FRAMEWORK_ACTIVITY_PREFIXES` denylist remain.
- **No fallback**: **BREAKING** — `selectTriggerCandidate` no longer falls back to non-MOP
  activities; `Config.triggerMopFirst` and the "MOP-First Ordering" requirement are deleted (P3).
  The arm contrast becomes the launched set itself (control: widget-join census; treatment:
  augmented census).
- **Unchanged**: `EVENT_TRIGGER_ACTIVITY` step semantics (`decision_source=Component`, no graph
  edge), `ape.activityTriggerMaxPerRun` budget (INV-CT-12), explicit-intent/deep-link dispatch,
  `ComponentName` package derivation (INV-CT-04), kill-switch registry structure, LLM stagnation
  hook (keeps its own `graphStableRestartThreshold / 2` point).

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `component-triggering`: requirement "Stagnation-Triggered Activity Launch" RENAMED to
  "Cadence-Based MOP Activity Launch" and MODIFIED (cadence gate, census-only eligibility, no
  exported term); requirement "MOP-First Ordering of Stagnation-Launch Candidates" REMOVED
  (INV-CT-09 deleted with it). INV-CT-11 (byte-identical pre-change default behavior) is deleted —
  the semantics change is the point of this change and a compatibility invariant would be false.

## Impact

- `Config.java`: delete `triggerMopFirst` (field, load, kill-switch forced list); re-document
  `activityTriggerStagnationStep` as cadence. No new flags.
- `SataAgent.java`: firing predicate reworked to a dedicated per-step counter; `selectTriggerCandidate`
  collapses to a single census-restricted walk; `firstEligible` drops `exported`, requires census
  membership; launcher block stops reading/resetting `graphStableCounter`.
- Tests: `ConfigTest`, `ApePureModeKillSwitchTest`, `ActivityFrontierTest` updated (fallback/ordering
  tests deleted, cadence + census-only + non-exported cases added).
- rv-android: **zero changes** (existing `tool.py` mappings suffice; arms stay in
  `experiment_config.json` via the `@k=v` DSL; `trigger_mop_first` simply stops being passed).
- Experiment (cmpft5 re-run): arms `activity_trigger_enabled=true, activity_trigger_stagnation_step=<N>,
  activity_trigger_max_per_run=<cap>`, contrast flag `mop_activity_source_components`; device gate =
  dose > 0 in both arms AND launched sets differ between arms.
