# component-triggering — delta: mop-census-launcher

## Purpose

Replace the stagnation activity launcher's mechanism with a strictly smaller one after three
structural nulls (cmpft4 forensic, cmpft5 Gate 0 v2/v3): cadence-based firing on a dedicated step
counter (episode scarcity fix), eligibility restricted to the arm's MOP census with the
`exported` term deleted (Gate 0 v3 structural-null fix; launch path is uid-2000
`IActivityManager`, probe-verified), and no non-MOP fallback (contrast-dilution fix —
`triggerMopFirst` deleted). No new Config flags; property names kept (rv-android `tool.py`
mapping untouched). At sync time the capability overview's launcher summary (Purpose section and
INV-CT-03 prose: "fired only on exploration stagnation, only to manifest-eligible frontier
activities (exported, ...)") SHALL be updated to match the renamed requirement.

## RENAMED Requirements

- FROM: `### Requirement: Stagnation-Triggered Activity Launch`
- TO: `### Requirement: Cadence-Based MOP Activity Launch`

## MODIFIED Requirements

### Requirement: Cadence-Based MOP Activity Launch

When `Config.activityTriggerEnabled` is true and `MopData` is loaded, the agent SHALL maintain a
dedicated launcher step counter, incremented once per action-selection pass through the launcher
block (evaluated in `SataAgent.selectNewActionNonnull` after the LLM hooks, so an enabled LLM hook
takes precedence at a shared step). When the counter reaches exactly
`Config.activityTriggerStagnationStep` (the launcher **cadence**; the property name is kept for the
rv-android `tool.py` mapping and documented at its `Config` declaration) and the per-run launch
budget is not exhausted (`Config.activityTriggerMaxPerRun == 0` OR launches emitted this run
`< Config.activityTriggerMaxPerRun`), the launcher SHALL reset the step counter to 0 and attempt to
select a launch candidate. The launcher SHALL NOT read or reset `graphStableCounter`.

A launch candidate is the next manifest activity, in round-robin order persisted across firings,
satisfying ALL of — a member of the arm's MOP census (`className` in
`MopData.getMopActivities()`, the `activityHasMop` reachability-augmented set of INV-MOP-27;
the component-level `ComponentInfo.reachesTarget` field SHALL NOT be used — it false-negatives
lambda-triggered activities), `permission == null`, not the main activity, currently unvisited
(`Graph.getActivityNode(className) == null` at fire time), and **not framework/tooling-namespaced**:
a candidate whose `className` starts with any prefix in the code constant
`FRAMEWORK_ACTIVITY_PREFIXES` — `android.`, `androidx.`, `com.google.android.`, `kotlin.`,
`kotlinx.`, `junit.`, `org.junit.`, `leakcanary.` — SHALL be ineligible. The match is a class-name
**prefix** match (never substring). The denylist is a fixed code constant located with
`firstEligible` (a correctness filter, not a tunable — no Config flag). Eligibility SHALL NOT
include an `exported` test: the dispatch path (`AndroidDevice.startActivity` →
`IActivityManager.startActivity` from uid 2000) launches non-exported activities. There SHALL be
no fallback outside the MOP census: when the census yields no eligible candidate, no launch occurs.

When a candidate exists, the agent SHALL increment the per-run launch counter and return a
first-class `EVENT_TRIGGER_ACTIVITY` action carrying the candidate's class name and, when
available, its deep-link URI — the action is the step (it produces exactly one `[APE-STEP]` line
with `decision_source=Component` and is NOT a graph edge label, mirroring `EVENT_RESTART`
semantics; the `[APE-STEP]` line is emitted by the else-branch of `StatefulAgent.resolveNewAction`,
which derives the decision source from the action). When no candidate exists (census exhausted,
all visited, or all denylisted), selection SHALL fall through to the normal SATA chain with no
side effects beyond the already-performed step-counter reset — in particular the launch counter
SHALL NOT be incremented.

`Config.activityTriggerStagnationStep` (int, loaded via `ape.activityTriggerStagnationStep`,
default `50`) SHALL be the launcher cadence in selection steps. A configured value `<= 0` SHALL be
clamped to the default at load time (logged). Sustained exploration yields periodic firing points
every cadence steps, independent of graph growth; expected launches per run =
`min(maxPerRun, steps/cadence, |unvisited eligible census|)`.

`Config.activityTriggerMaxPerRun` (int, loaded via `ape.activityTriggerMaxPerRun`, default `0` =
unlimited) SHALL cap the number of `EVENT_TRIGGER_ACTIVITY` actions emitted in a run. A configured
value `< 0` SHALL be clamped to `0` at load time (logged). Only actually returned
`EVENT_TRIGGER_ACTIVITY` actions consume budget; a firing whose candidate scan comes up empty does
not.

Both flags SHALL be registered in the `apePureMode` kill-switch registry (INV-ARCH-06 of
`scoring-pipeline`) as exempt sub-params (`rvExemptReasons()`): they are inert when
`activityTriggerEnabled` is forced false by the kill-switch. `Config.triggerMopFirst` SHALL NOT
exist (deleted; see REMOVED below) and SHALL NOT appear in the kill-switch forced list.

Event generation SHALL dispatch the action as an explicit intent
(`ComponentName(MopData.getPackageName(), className)`, `FLAG_ACTIVITY_NEW_TASK`) via
`AndroidDevice.startActivity`; the package component SHALL be `MopData.getPackageName()` and SHALL
NOT be derived from the target class name (main-spec INV-CT-04). When the candidate's
intent-filters contain an `ACTION_VIEW` filter with non-empty `data.schemes`, the intent SHALL
instead be `ACTION_VIEW` with a best-effort URI assembled from the filter's first scheme, host and
path, still targeted at the component. Activities SHALL NOT participate in the
`componentPercentage` probabilistic pool under any configuration.

- **INV-CT-05 (amended)**: At most one launch attempt SHALL occur per cadence window (the
  exact-equality gate on the dedicated step counter plus its reset at the firing point make
  re-fire impossible within a window). The launcher SHALL NOT read or reset `graphStableCounter`.
- **INV-CT-06 (amended)**: Every launched activity SHALL satisfy, at fire time: member of
  `MopData.getMopActivities()`, permission-free, non-main, same-package, unvisited, and not
  framework/tooling-namespaced (`FRAMEWORK_ACTIVITY_PREFIXES` prefix match). Exported status SHALL
  NOT be consulted.
- **INV-CT-07**: unchanged — every launch model-visible as exactly one `[APE-STEP]` line with
  `decision_source=Component`; no graph edge labeled by the launch.
- **INV-CT-08**: unchanged — with `ape.activityTriggerEnabled=false`, no activity is ever launched.
- **INV-CT-10**: unchanged — denylist consulted only inside `firstEligible`.
- **INV-CT-11**: REMOVED (it pinned launcher firing to the pre-`activity-trigger-dose` stagnation
  gate; this change replaces that mechanism, so a compatibility invariant would be false).
- **INV-CT-12**: unchanged — cap `N > 0` never exceeded; `0` = no cap; only returned actions
  consume budget.

#### Scenario: cadence fires independently of graph growth
- **WHEN** `ape.activityTriggerStagnationStep=10` and the exploration graph grows on every step (no stagnation ever)
- **THEN** the launcher SHALL still reach a firing point at every 10th selection step

#### Scenario: periodic firing under the default cadence
- **WHEN** `ape.activityTriggerStagnationStep` is unset (default 50) and eligible census candidates remain
- **THEN** the launcher SHALL fire at step 50, reset its step counter, and fire again after each further 50 selection steps

#### Scenario: non-exported census activity is launched
- **WHEN** the arm census contains `com.x.CryptoActivity` with `exported=false`, `permission=null`, unvisited
- **THEN** the launcher SHALL select it and return an `EVENT_TRIGGER_ACTIVITY` action for it

#### Scenario: non-census activity is never launched
- **WHEN** `com.x.AboutActivity` is exported, permission-free, non-main and unvisited but not in `MopData.getMopActivities()`
- **THEN** the launcher SHALL NOT select it, even when no census candidate is eligible (no fallback)

#### Scenario: census exhausted falls through without side effects
- **WHEN** a firing point is reached but every census activity is visited or denylisted
- **THEN** no launch SHALL occur, the launch budget SHALL be unchanged, the step counter SHALL reset, and the normal SATA chain SHALL select the step

#### Scenario: arm contrast is the launched set
- **WHEN** the control arm census (`ape.mopActivitySourceComponents=false`) is `{A}` and the treatment census (`=true`) is `{A, B, C}`
- **THEN** the control launcher SHALL only ever launch `A` while the treatment launcher can launch `A`, `B`, and `C`

#### Scenario: permission-gated census activity skipped
- **WHEN** a census activity declares `permission="android.permission.MANAGE_DOCUMENTS"`
- **THEN** the candidate selection SHALL skip it

#### Scenario: denylisted census entry skipped
- **WHEN** the census contains `androidx.activity.ComponentActivity` (over-approximated reachability) and `com.x.HistoryActivity`, both otherwise eligible
- **THEN** the launcher SHALL skip the `androidx.` entry and launch `com.x.HistoryActivity`

#### Scenario: cap exhausts the launch budget
- **WHEN** `ape.activityTriggerMaxPerRun=2` and two `EVENT_TRIGGER_ACTIVITY` actions have been emitted this run
- **THEN** the firing predicate SHALL return false at every subsequent firing point and the normal SATA chain SHALL select the step

#### Scenario: cap zero means unlimited
- **WHEN** `ape.activityTriggerMaxPerRun=0` (default) and 10 launches have already been emitted
- **THEN** the launcher SHALL still fire at the next firing point (subject to the other gates)

#### Scenario: invalid values clamped at load
- **WHEN** `ape.properties` sets `ape.activityTriggerStagnationStep=0` and `ape.activityTriggerMaxPerRun=-3`
- **THEN** `Config.load` SHALL clamp them to `50` and `0` respectively and log each clamp

#### Scenario: launcher disabled
- **WHEN** `ape.activityTriggerEnabled=false`
- **THEN** no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced regardless of cadence/cap values, and the probabilistic pool SHALL contain no activities

## REMOVED Requirements

### Requirement: MOP-First Ordering of Stagnation-Launch Candidates

Deleted with `Config.triggerMopFirst` (P3). The requirement ordered a two-group walk (MOP census
first, then a non-MOP fallback) over an unchanged eligible set; under census-only eligibility
there is no second group to order. INV-CT-09 is removed with it. `activityHasMop` as the census
truth (never `ComponentInfo.reachesTarget`) is preserved inside the Cadence-Based MOP Activity
Launch requirement above.
