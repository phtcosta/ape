# Proposal: activity-trigger-dose

## Why

cmpft5's Gate 0 (2026-07-11, `rvsec/rv-android/docs/20260711_relatorio_gate0_cmpft5.md`) proved the
launcher-OFF design cannot test H1: with `activity_trigger_enabled=false`, A′ has one consumer that
never fired (nav tiebreak: 0/6 arm-runs; cmpft4 mining: the multi-path precondition occurs 0.39×/trace
in 44/219 apps) and one that did not diverge (`scoreWtg`: identical boosted sets in 3/3 smoke apps).
The strong A′ consumer is the stagnation activity launcher (`triggerMopFirst` ordering over
`activityHasMop`), but its dose is homeopathic: it fires only when `graphStableCounter` equals exactly
`graphStableRestartThreshold / 2` (= 50 consecutive stagnation steps) — in cmpft4's frontier arm that
produced 114 launches across 657 traces (0.17/trace, 89 traces with ≥1). No 15-hour experiment can
detect a treatment engaging in ~0.05% of decisions. This change makes the launcher's firing cadence
configurable and adds a per-run launch cap, so the redesigned cmpft5 (2 arms, launcher ON in both,
single differing flag `mop_activity_source_components`) has a measurable treatment dose with a
pre-registrable dose gate.

## What Changes

- `SataAgent.shouldTriggerAtStagnation` fires when `graphStableCounter == Config.activityTriggerStagnationStep`
  instead of the hardcoded `graphStableRestartThreshold / 2`. New flag `ape.activityTriggerStagnationStep`
  (int, default `50` — byte-identical behavior to today under the default `graphStableRestartThreshold=100`,
  preserving the frozen gh43 arms, INV-APV-17). Since `graphStableCounter` resets to 0 on every launch,
  a low step yields periodic launches under sustained stagnation and natural backoff when a launch
  grows the graph (graph growth also resets the counter).
- New per-run launch cap `ape.activityTriggerMaxPerRun` (int, default `0` = unlimited): once the agent
  has emitted that many `EVENT_TRIGGER_ACTIVITY` steps, the launcher never fires again in the run —
  guards against launch-storms in apps whose graph never grows (with step=10 an uncapped dead-end app
  would spend ~10% of its steps on launches).
- Both new flags registered in the `apePureMode` kill-switch registry as exempt sub-params
  (`rvExemptReasons()`: inert when `activityTriggerEnabled` is forced false) — INV-ARCH-06 completeness
  guard stays green.
- No new telemetry: the existing `[APE-RV] Triggering activity: %s` line is the dose counter; the
  cmpft5 Gate 0 dose gate (median ≥3 launches/run in the smoke) counts those lines.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `component-triggering`: the Stagnation-Triggered Activity Launch requirement's firing condition
  changes from the fixed `graphStableRestartThreshold / 2` equality to the configurable
  `activityTriggerStagnationStep` equality, and gains the `activityTriggerMaxPerRun` cap. Eligibility,
  ordering (`triggerMopFirst`), denylist, `EVENT_TRIGGER_ACTIVITY` semantics, and `decision_source=Component`
  attribution are all unchanged. NOTE: the open change `mop-activity-consumers` modifies the same
  requirement (denylist); this delta is written against that change's amended text and MUST be archived
  after it (archive ordering, INV-CT-06 amended + INV-CT-10 already folded in).

## Impact

- **Code**: `Config.java` (2 new flags + `rvExemptReasons()` entries), `SataAgent.java`
  (`shouldTriggerAtStagnation` signature/logic + launch counter in the trigger block). ~30 lines.
- **Tests**: extend the existing JVM tests for `shouldTriggerAtStagnation` (pure static seam) with
  step/cap cases; extend the Config registry guard test's expected exempt set.
- **Interactions**:
  - `mop-activity-consumers` (OPEN, same worktree): same requirement, orthogonal mechanism
    (eligibility denylist vs firing cadence). Archive order: mop-activity-consumers first.
  - LLM stagnation hook (`SataAgent.java:396`) keeps its own `== graphStableRestartThreshold / 2`
    point; with step ≠ 50 the two decouple (documented; LLM is OFF in cmpft5, no interaction there).
  - Frozen gh43 arms (INV-APV-17) and every current caller: defaults preserve today's behavior exactly.
- **Out of scope (experiment-side dependency, rv-android repo)**: map
  `activity_trigger_stagnation_step` / `activity_trigger_max_per_run` in `APERV_PROPERTY_MAPPING`
  (`tool.py`) and define the two cmpft5 arms — `sata_mop_launcher` (control: widget reach + launcher ON
  + `trigger_mop_first` + low step, e.g. 10) and `sata_mop_launcher_act` (treatment: same +
  `mop_activity_source_components=true`), WITHOUT `frontier_boost_weight`/`mop_frontier_weight` so the
  contrast isolates A′-launch. Tracked in the cmpft5 experiment session, not this change.
