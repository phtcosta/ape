# component-triggering — delta: activity-trigger-dose

## Purpose

Make the stagnation activity launcher's firing cadence configurable and bound its per-run launch
count. The fixed `graphStableRestartThreshold / 2` gate (= 50 consecutive stagnation steps) produced
a homeopathic treatment dose in cmpft4 (114 launches / 657 frontier-arm traces, 0.17/trace); the
redesigned cmpft5 needs per-arm dose control with a pre-registered dose gate. This delta is written
against the requirement text as amended by the open change `mop-activity-consumers` (framework
denylist, INV-CT-06 amended, INV-CT-10) and MUST be archived after it.

## MODIFIED Requirements

### Requirement: Stagnation-Triggered Activity Launch

When `Config.activityTriggerEnabled` is true, `MopData` is loaded, `graphStableCounter` reaches
exactly `Config.activityTriggerStagnationStep` (evaluated in `SataAgent.selectNewActionNonnull`
after the LLM hooks, so an enabled LLM stagnation hook takes precedence when the two points
coincide), and the per-run launch budget is not exhausted (`Config.activityTriggerMaxPerRun == 0`
OR launches emitted this run `< Config.activityTriggerMaxPerRun`), the agent SHALL attempt to
select a launch candidate: the next manifest activity, in round-robin order persisted across
episodes, satisfying ALL of — `exported == true`, `permission == null`, not the main activity,
currently unvisited (`Graph.getActivityNode(className) == null` at fire time), and not
framework/tooling-namespaced (`FRAMEWORK_ACTIVITY_PREFIXES` prefix match; the denylist is a fixed
code constant located with `firstEligible`, applied identically in all `selectTriggerCandidate`
ordering modes, before ordering). When a candidate exists, the agent SHALL reset
`graphStableCounter` to 0, increment the per-run launch counter, and return a first-class
`EVENT_TRIGGER_ACTIVITY` action carrying the candidate's class name and, when available, its
deep-link URI — the action is the step (it produces exactly one `[APE-STEP]` line with
`decision_source=Component` and is NOT a graph edge label, mirroring `EVENT_RESTART` semantics;
the `[APE-STEP]` line is emitted by the else-branch of `StatefulAgent.resolveNewAction`, which
derives the decision source from the action). When no candidate exists (including when every
remaining candidate is denylisted), selection SHALL fall through to the normal SATA chain with no
side effects — in particular the launch counter SHALL NOT be incremented and `graphStableCounter`
SHALL NOT be reset by the launcher.

`Config.activityTriggerStagnationStep` (int, loaded via `ape.activityTriggerStagnationStep`,
default `50`) SHALL be the number of consecutive stagnation steps between launcher firings. A
configured value `<= 0` SHALL be clamped to the default at load time (logged). Because
`graphStableCounter` resets to 0 on graph growth and on every launch, the exact-equality gate
yields at most one launch per reset interval (episode), and sustained stagnation yields periodic
launches every `activityTriggerStagnationStep` steps.

`Config.activityTriggerMaxPerRun` (int, loaded via `ape.activityTriggerMaxPerRun`, default `0` =
unlimited) SHALL cap the number of `EVENT_TRIGGER_ACTIVITY` actions emitted in a run. A configured
value `< 0` SHALL be clamped to `0` at load time (logged). Only actually returned
`EVENT_TRIGGER_ACTIVITY` actions consume budget; a firing whose candidate scan comes up empty does
not.

Both flags SHALL be registered in the `apePureMode` kill-switch registry (INV-ARCH-06 of
`scoring-pipeline`) as exempt sub-params (`rvExemptReasons()`): they are inert when
`activityTriggerEnabled` is forced false by the kill-switch.

Event generation SHALL dispatch the action as an explicit intent
(`ComponentName(MopData.getPackageName(), className)`, `FLAG_ACTIVITY_NEW_TASK`) via
`AndroidDevice.startActivity`; the package component SHALL be `MopData.getPackageName()` and
SHALL NOT be derived from the target class name (main-spec INV-CT-04). When the candidate's
intent-filters contain an `ACTION_VIEW` filter with non-empty `data.schemes`, the intent SHALL
instead be `ACTION_VIEW` with a best-effort URI assembled from the filter's first scheme, host and
path, still targeted at the component. Activities SHALL NOT participate in the
`componentPercentage` probabilistic pool under any configuration.

- **INV-CT-05 (amended)**: An activity launch SHALL occur at most once per stagnation episode,
  where an episode is the interval between two consecutive resets of `graphStableCounter` (reset on
  graph growth or on a launch). The exact-equality gate on `activityTriggerStagnationStep` plus the
  reset make re-fire impossible within an episode. The Invariants-block entry SHALL be updated to
  this text at archive time.
- **INV-CT-06**: unchanged (as amended by `mop-activity-consumers`): exported, permission-free,
  non-main, same-package, unvisited, not framework/tooling-namespaced.
- **INV-CT-07**: unchanged: every launch model-visible as exactly one `[APE-STEP]` line with
  `decision_source=Component`; no graph edge labeled by the launch.
- **INV-CT-08**: unchanged: with `ape.activityTriggerEnabled=false`, no activity is ever launched.
- **INV-CT-10**: unchanged: denylist consulted only inside `firstEligible`.
- **INV-CT-11**: With `ape.activityTriggerStagnationStep` unset, launcher firing behavior SHALL be
  byte-identical to the pre-change gate (`graphStableRestartThreshold / 2` with the default
  threshold 100): the default step SHALL be `50` and SHALL NOT be derived from
  `graphStableRestartThreshold` at runtime.
- **INV-CT-12**: When `ape.activityTriggerMaxPerRun` is `N > 0`, the number of
  `EVENT_TRIGGER_ACTIVITY` actions emitted in a run SHALL never exceed `N`; when `0`, no cap
  applies. Budget accounting SHALL count only returned actions (an empty candidate scan consumes
  nothing).

#### Scenario: default step preserves current behavior
- **WHEN** `ape.activityTriggerStagnationStep` is not set and `graphStableCounter` reaches 50
- **THEN** the launcher SHALL fire exactly as the pre-change `graphStableRestartThreshold / 2` gate did

#### Scenario: low step yields periodic launches under sustained stagnation
- **WHEN** `ape.activityTriggerStagnationStep=10` and the graph never grows while eligible candidates remain
- **THEN** the launcher SHALL fire at counter 10, reset the counter, and fire again after each further 10 consecutive stagnation steps

#### Scenario: cap exhausts the launch budget
- **WHEN** `ape.activityTriggerMaxPerRun=2` and two `EVENT_TRIGGER_ACTIVITY` actions have been emitted this run
- **THEN** `shouldTriggerAtStagnation` SHALL return false at every subsequent firing point and the normal SATA chain SHALL select the step

#### Scenario: cap zero means unlimited
- **WHEN** `ape.activityTriggerMaxPerRun=0` (default) and 10 launches have already been emitted
- **THEN** the launcher SHALL still fire at the next firing point (subject to the other gates)

#### Scenario: empty candidate scan does not consume budget
- **WHEN** the launcher fires with `ape.activityTriggerMaxPerRun=1` but every remaining candidate is visited or denylisted
- **THEN** no launch SHALL occur, the launch counter SHALL remain unchanged, and a later firing with a fresh eligible candidate SHALL still be allowed to launch

#### Scenario: invalid values clamped at load
- **WHEN** `ape.properties` sets `ape.activityTriggerStagnationStep=0` and `ape.activityTriggerMaxPerRun=-3`
- **THEN** `Config.load` SHALL clamp them to `50` and `0` respectively and log each clamp

#### Scenario: stagnation launches an unvisited exported activity
- **WHEN** `graphStableCounter` reaches `activityTriggerStagnationStep`, the LLM is disabled, and the manifest has an exported, permission-free, unvisited `com.x.SettingsActivity`
- **THEN** the step SHALL be an `EVENT_TRIGGER_ACTIVITY` action for `com.x.SettingsActivity`, the `[APE-STEP]` line SHALL carry `decision_source=Component`, and `graphStableCounter` SHALL be reset to 0

#### Scenario: launcher disabled
- **WHEN** `ape.activityTriggerEnabled=false`
- **THEN** no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced regardless of step/cap values
