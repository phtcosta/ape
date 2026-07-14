# component-triggering — delta: activity-frontier

## Purpose

Give activities a dedicated, model-visible launch path and remove them from the probabilistic pool for good. The main spec's rationale for excluding activities ("triggering them via `startActivity()` disrupts the SATA exploration flow", INV-CT-03) described the gh11 mechanism: unfiltered, per-step-probabilistic jumps invisible to the model. That mechanism is deleted by this change. What replaces it is shaped by the failure analysis: launches happen only on exploration stagnation (the `graphStableCounter` escalation point the LLM hook already uses), only to manifest-eligible frontier activities (exported, not permission-gated, same package, currently unvisited), and as a first-class `EVENT_TRIGGER_ACTIVITY` step — one `[APE-STEP]` line with `decision_source=Component`, no graph edge (EVENT_* semantics), deep-link URI when the manifest provides one. Receivers, services and providers keep the existing probabilistic side-effect path unchanged.

This delta supersedes the activity clause of INV-CT-03: activities are excluded from the *probabilistic pool* (stronger than before — the `activityTriggerEnabled` branch in `buildTriggerTuples` is deleted), and handled exclusively by the stagnation launcher specified here.

## Data Contracts

### Input
- `Config.activityTriggerEnabled` (repurposed) — gates the stagnation launcher (default **true**; false disables activity launches entirely).
- `MopData.getActivities()` — manifest activities with `className`, `isMain`, `exported`, `permission`, `intentFilters[].{actions, categories, data{schemes, hosts, paths}}`.
- `StatefulAgent.graphStableCounter` / `Config.graphStableRestartThreshold` — the existing stagnation signal.

### Side-Effects
- **[Trace]**: one `[APE-STEP] ... decision_source=Component` line per launch (via the standard step ledger, INV-SEL-04) plus the existing `[APE-RV] Triggering activity: ...` dispatch line.
- **[Android runtime]**: `AndroidDevice.startActivity` with an explicit component intent, or an `ACTION_VIEW` deep-link intent targeted at the component.

### Error
- Failed launches (reflection gap, SecurityException, malformed URI) SHALL be logged as WARNING and SHALL NOT crash the agent.

## ADDED Requirements

### Requirement: Stagnation-Triggered Activity Launch

When `Config.activityTriggerEnabled` is true, `MopData` is loaded, and `graphStableCounter` reaches exactly `graphStableRestartThreshold / 2` (evaluated in `SataAgent.selectNewActionNonnull` after the LLM hooks, so an enabled LLM stagnation hook takes precedence), the agent SHALL attempt to select a launch candidate: the next manifest activity, in round-robin order persisted across episodes, satisfying ALL of — `exported == true`, `permission == null`, not the main activity, and currently unvisited (`Graph.getActivityNode(className) == null` at fire time). When a candidate exists, the agent SHALL reset `graphStableCounter` to 0 and return a first-class `EVENT_TRIGGER_ACTIVITY` action carrying the candidate's class name and, when available, its deep-link URI — the action is the step (it produces exactly one `[APE-STEP]` line with `decision_source=Component` and is NOT a graph edge label, mirroring `EVENT_RESTART` semantics). Because `EVENT_TRIGGER_ACTIVITY` is a non-model action, its `[APE-STEP]` line is emitted by the else-branch of `StatefulAgent.resolveNewAction` — which currently hardcodes `decision_source=SATA` for every non-model action. Satisfying `decision_source=Component` therefore REQUIRES teaching that else-branch to read the decision source from the action (attributing `EVENT_TRIGGER_ACTIVITY` as `Component`) rather than emitting a literal `SATA`; the launcher cannot set it via `ModelAction.setDecisionSource`, which does not exist on the non-model `Action` base type. When no candidate exists, selection SHALL fall through to the normal SATA chain with no side effects.

Event generation SHALL dispatch the action as an explicit intent (`ComponentName(MopData.getPackageName(), className)`, `FLAG_ACTIVITY_NEW_TASK`) via `AndroidDevice.startActivity`; the package component SHALL be `MopData.getPackageName()` and SHALL NOT be derived from the target class name (main-spec INV-CT-04, ComponentName Package Derivation). When the candidate's intent-filters contain an `ACTION_VIEW` filter with non-empty `data.schemes`, the intent SHALL instead be `ACTION_VIEW` with a best-effort URI assembled from the filter's first scheme, host and path, still targeted at the component. Activities SHALL NOT participate in the `componentPercentage` probabilistic pool under any configuration — the former `activityTriggerEnabled` branch of `buildTriggerTuples` is deleted (this supersedes the activity clause of INV-CT-03; receivers/services/providers are unaffected).

- **INV-CT-05**: An activity launch SHALL occur at most once per stagnation episode (the counter-equality gate plus the reset make re-fire impossible within an episode).
- **INV-CT-06**: Every launched activity SHALL satisfy, at fire time: exported, permission-free, non-main, same-package, unvisited.
- **INV-CT-07**: Every launch SHALL be model-visible as exactly one `[APE-STEP]` line with `decision_source=Component`; no graph edge SHALL be labeled by the launch. This requires the non-model `[APE-STEP]` branch in `StatefulAgent.resolveNewAction` to derive the source from the action instead of hardcoding `SATA`.
- **INV-CT-08**: With `ape.activityTriggerEnabled=false`, no activity SHALL ever be launched by APE-RV (neither by the launcher nor by the probabilistic pool, which no longer contains activities).

#### Scenario: stagnation launches an unvisited exported activity
- **WHEN** `graphStableCounter` reaches `graphStableRestartThreshold / 2`, the LLM is disabled, and the manifest has an exported, permission-free, unvisited `com.x.SettingsActivity`
- **THEN** the step SHALL be an `EVENT_TRIGGER_ACTIVITY` action for `com.x.SettingsActivity`, the `[APE-STEP]` line SHALL carry `decision_source=Component`, and `graphStableCounter` SHALL be reset to 0

#### Scenario: deep-link candidate launched with VIEW intent
- **WHEN** the selected candidate has an intent-filter with `android.intent.action.VIEW` and `data.schemes=["myscheme"]`, `hosts=[]`
- **THEN** dispatch SHALL use `ACTION_VIEW` with URI `myscheme://` targeted at the component

#### Scenario: all frontier activities visited — fall through
- **WHEN** the stagnation point is reached but every exported, permission-free, non-main activity already has an `ActivityNode`
- **THEN** no launch SHALL occur, `graphStableCounter` SHALL NOT be reset by the launcher, and the normal SATA chain SHALL select the step

#### Scenario: launcher disabled
- **WHEN** `ape.activityTriggerEnabled=false`
- **THEN** no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced and the probabilistic pool SHALL contain no activities

#### Scenario: permission-gated activity is never launched
- **WHEN** an unvisited exported activity declares `permission="android.permission.MANAGE_DOCUMENTS"`
- **THEN** the candidate selection SHALL skip it
