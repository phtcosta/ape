# Specification: Component Triggering

## Purpose

APE-RV operates exclusively via GUI interactions (AccessibilityService + Monkey events). Services, BroadcastReceivers and ContentProviders are non-GUI Android components whose code paths are invisible to UI-only exploration. These components frequently execute security-relevant operations (crypto, TLS) that MOP specifications monitor.

This specification defines how APE-RV SHALL trigger BroadcastReceivers, Services and ContentProviders at runtime — broadcasts and service starts through the reflective `IActivityManager` calls behind `AndroidDevice.sendBroadcast` and `AndroidDevice.startService`, provider operations through the `content` shell command run by `AndroidDevice.executeCommandAndWaitFor` — with intent data read from the compact artifact's component trigger surface (`permission`, `intentFilters.actions`/`categories`, the additive `intentFilters.data` member — `schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`, design D-B14.11 — and `authorities`) — derived host-side from the static analysis and read through the static provider (`StaticGuiModel`), not parsed on the device. Component triggering is the STATIC member `COMPONENT_TRIGGER` (design D-B13), activated by `ape.componentPercentage` (POSITIVE, neutral and jar default `0.0`; the STATIC block of the `as*` presets sets `0.03`). The trigger pool reads no MOP data. Triggering is probabilistic: on each step that reaches the `ComponentTrigger` stage, there is an `ape.componentPercentage` chance of triggering one component (round-robin), decided by the coin `u_C` drawn at step open from its own stream, `StepRng.componentCoin` (`decision-pipeline` INV-DP-13). The trigger is a side effect that does not consume a SATA step; a fired trigger is recorded on its step as `dec.trig`. The system-broadcast catalog is loaded iff `COMPONENT_TRIGGER` is mounted.

Activities are excluded from the *probabilistic* pool: they are already reachable via GUI exploration, and unfiltered per-step `startActivity()` jumps disrupt the SATA flow (the gh11 mechanism). They are instead reachable through a dedicated, model-visible **cadence launcher**, the MOP member `MOP_LAUNCHER` — a first-class `EVENT_TRIGGER_ACTIVITY` step (`dec.src="launcher"`) aimed only at the run's MOP census activities (permission-free, non-main, not yet visited, not framework-namespaced; exported status is not consulted, and the census is the only package filter: the launch intent is addressed to the artifact's package), each launched at most once per run (D-B15). Its cadence is global (D-A16, D-B11): the counter advances on every step, in the stage's `onStepOpen` hook, and the launch happens on the first step that reaches the MOP/SATA branch once the counter is due, so the launcher never comes before the LLM. `dec.comp` records the launcher's launch outcome. See the Cadence-Based MOP Activity Launch requirement.

## Data Contracts

### Input
- `StaticGuiModel.receivers()` / `services()` — receivers and services with their intent filters (actions, categories, optional `data`)
- `StaticGuiModel.providers()` — providers with their `authorities`
- `StaticGuiModel.packageName()` — the package of every trigger `ComponentName`
- `ape.componentPercentage` — probability per step, read through the plan (`COMPONENT_TRIGGER` activation key; jar default `0.0`, `0.03` in the STATIC block)
- `SystemBroadcastCatalog` — lookup table of typed extras for system broadcast actions (from VLM-Fuzz catalog, 187 entries)

### Output
- Broadcast intents sent to receivers via `AndroidDevice.sendBroadcast()`
- Service start requests via `AndroidDevice.startService()`
- `content query|insert|update` commands against providers
- `dec.trig` on the step record of a fired trigger

### Side-Effects
- **[Android runtime]**: Broadcasts may trigger receiver code, potentially opening new Activities or changing app state
- **[Android runtime]**: Services may start background operations

### Error
- Failed broadcast/service start SHALL be logged as WARNING and SHALL NOT crash the agent. Exploration continues normally.

---

## Invariants

- **INV-CT-01**: Component triggering SHALL fire only when the plan mounts `COMPONENT_TRIGGER`. That requires the `STATIC` group and `ape.componentPercentage > 0`, and the static provider must report `hasComponents()`. When `componentPercentage` is `0.0`, the jar default, behaviour SHALL be identical to APE-RV without component triggering, whatever the artifact holds.
- **INV-CT-02**: Component triggering SHALL be probabilistic. On each step that reaches the `ComponentTrigger` stage, the trigger fires when `u_C < componentPercentage`, where `u_C` was drawn at step open from `StepRng.componentCoin` on every step of a plan that mounts the trigger. The trigger is a side effect, and action selection continues regardless.
- **INV-CT-03**: The probabilistic pool SHALL hold the following and nothing else:
  - the BroadcastReceiver and Service tuples, one per (intent filter × action);
  - one component-name-only tuple per Service with no filter action;
  - the ContentProvider tuples, one per (provider with a non-empty `authorities` × operation `query`, `insert`, `update`).

  Activities SHALL NOT enter the pool under any configuration. They are launched only by the cadence launcher (`EVENT_TRIGGER_ACTIVITY`, `dec.src="launcher"`).
- **INV-CT-04**: The package component of every trigger `ComponentName` SHALL equal the artifact's `package`, read from the static provider. No trigger path SHALL derive it from the component class name.
- **INV-CT-05**: At most one launch attempt SHALL occur per cadence window: the counter is reset at the firing point and must again reach the cadence before the next firing. The `MopLauncher` stage SHALL NOT read or reset `graphStableCounter`. The counter SHALL advance exactly once per step, in `onStepOpen`, whichever stage takes the step (global cadence, D-A16; `decision-pipeline` INV-DP-08). Only the stage's `decide()` resets it.
- **INV-CT-06**: Every launched activity SHALL satisfy, at fire time (`MopLauncherStage.isEligible`): member of `MopData.getMopActivities()`, permission-free, not the main activity (by `isMain` or by name), unvisited, not launched before in this run (design D-B15), and not framework/tooling-namespaced (`FRAMEWORK_ACTIVITY_PREFIXES` prefix match). There is no package test: the census is the only membership filter, and the launch intent is addressed to `StaticGuiModel.packageName()` (INV-CT-04). Exported status SHALL NOT be consulted, and cannot be: the field is not on the wire.
- **INV-CT-07**: Every launch SHALL be model-visible as exactly one step record with `dec.src="launcher"` and `dec.ch="launcher"`, and no graph edge SHALL be labelled by the launch. Its deep-link URI is a wire field (`ComponentInfo.deepLinkUri`), assembled host-side (rv-android INV-DRV-07), not a jar-side computation.
- **INV-CT-08**: When the plan does not mount `MOP_LAUNCHER`, no activity SHALL ever be launched by APE-RV: no `MopLauncher` stage exists, and the probabilistic pool contains no activities.
- **INV-CT-10**: No `EVENT_TRIGGER_ACTIVITY` action SHALL ever carry a class name matching a `FRAMEWORK_ACTIVITY_PREFIXES` prefix; the denylist SHALL be consulted only inside the stage's candidate eligibility — no second exclusion mechanism.
- **INV-CT-12**: When `ape.activityTriggerMaxPerRun` is `N > 0`, the number of `EVENT_TRIGGER_ACTIVITY` actions emitted in a run SHALL never exceed `N`; when `0`, no cap applies. Budget accounting SHALL count only kept launches: an empty candidate scan consumes nothing, and neither does a launch that a post-selection restart replaces, which also leaves its activity outside the launched set and counts no `launches`.
- **INV-CT-14**: The launch result SHALL be recorded on the launch's own step record and SHALL NOT be acted upon. No control-flow decision — retry, re-dispatch, candidate re-selection, cursor or budget adjustment — may depend on it, in the jar or in the harness. It is an observation of a value the dispatch already produced, and the launcher's behaviour with the recording present SHALL be identical to its behaviour without it.
- **INV-CT-15**: The scoring mode SHALL change only which eligible activity is chosen, never which activities are eligible. Under `ape.mopScoring=flag` the stage's choice SHALL be the round-robin over the eligible set, in manifest order with the cursor persisted across firings; the eligible set excludes the activities launched before in this run (design D-B15). Under `distance` the choice SHALL be a function of the eligible set, the artifact's `activityDist`, the retirement state, `ape.mopLauncherDmax` and the position of the previous selection, with manifest order as the only tie-break, and never of a launch result.
- **INV-CT-16**: The pool and the dispatch SHALL read no MOP data. Neither the pool's membership nor any trigger intent SHALL depend on `reachesMop`/`reachesTarget`, `targetMethods`, `hasTargetMethods`, distances or handlers. Two artifacts that differ only in MOP data SHALL yield the same pool in the same order.
- **INV-CT-17**: A receiver or service tuple whose intent filter carries a `data` member SHALL dispatch an intent built from it:
  - `setDataAndType(uri, mimeType)` when the member has a scheme and a MIME type;
  - `setData(uri)` when it has a scheme only;
  - `setType(mimeType)` when it has a MIME type only.

  `uri` is `scheme://host + path`, built from the filter's first scheme, first host and first path, the path falling back to the first path prefix, with `host` and `path` empty when absent (design D-B14.11, D-B14.15); `mimeType` is its first MIME type. `ports` and `pathPatterns` are not projected. A filter without a `data` member SHALL dispatch with no data and no type.
- **INV-CT-18**: `SystemBroadcastCatalog` SHALL be loaded if and only if `COMPONENT_TRIGGER` is mounted.

---

## Requirements

### Requirement: Probabilistic Component Trigger Stage

Probabilistic component triggering SHALL be a `ComponentTrigger` decision stage (`ComponentTriggerStage`). It is assembled after the
`MopLauncher` stage and before the `MopCoin` and `SataChain` stages. The stage exists only when the plan mounts
`COMPONENT_TRIGGER`, a member of the STATIC group with `componentPercentage > 0` (design D-B13). When the feature
is absent, so is the stage (`decision-pipeline` INV-DP-03). The stage reads the trigger pool from the static
provider and never from `MopData` (INV-CT-16).

On each step that reaches it, when the step's `u_C` is below `componentPercentage` and the static provider reports
`hasComponents()`, the stage SHALL ask the pool size (`StatefulAgent.componentTargetCount()`). When it is positive,
the stage SHALL call `StatefulAgent.triggerComponent(target)` with `target = cursor % size`, advance its round-robin
cursor (episode state the stage owns, `decision-pipeline` INV-DP-07), and return `StageResult.SideEffect`. When the
pool is empty, the stage SHALL return `SideEffect` ("no triggerable component target") without calling
`triggerComponent` and without moving the cursor. Otherwise it SHALL return `Continue`. A `SideEffect` never decides
the step: the pipeline records it and continues, and the `MopCoin` stage (when it is assembled and takes the step)
or the `SataChain` stage selects the step's action. The trigger's side effect SHALL be recorded on the record of the
step on which it fired as `dec.trig:{"k":"broadcast"|"service"|"provider","c":"<class>"}`, `k` the kind of the fired
tuple and `c` the fully-qualified class of its component (design decision A7). `dec.trig` SHALL be absent on every
step on which no target was fired, and `dec.comp` SHALL remain the launcher's launch outcome only.

`u_C` SHALL be drawn at step open from `StepRng.componentCoin` on every step of a plan that mounts the
trigger, whether or not the step reaches the stage (`decision-pipeline` INV-DP-13). It SHALL NOT come from the
agent's generator. Mounting the trigger therefore never shifts SATA's draws.

#### Scenario: Component trigger fires
- **WHEN** the stage is assembled with `componentPercentage=0.03`, the static provider reports `hasComponents()`, the pool is non-empty, and `u_C < 0.03` on a step that reaches the stage
- **THEN** `triggerComponent(target)` SHALL be called with the cursor's target and the stage SHALL return `SideEffect`
- **AND** the `MopCoin` or `SataChain` stage SHALL still select the step's action (trigger does not replace the step)

#### Scenario: a fired trigger is recorded as dec.trig
- **WHEN** the stage fires on step 120 and the round-robin cursor selects the receiver tuple of `br.unb.app.receivers.SyncReceiver`
- **THEN** the record of step 120 SHALL carry `"trig":{"k":"broadcast","c":"br.unb.app.receivers.SyncReceiver"}` in `dec`, and no `comp` member
- **AND** the records of the steps on which the stage returned `Continue` or was not reached SHALL carry no `trig` member

#### Scenario: Component trigger does not fire
- **WHEN** `u_C >= componentPercentage`
- **THEN** no component trigger occurs and the stage SHALL return `Continue`

#### Scenario: No mopDataPath set
- **WHEN** the plan does not mount `COMPONENT_TRIGGER`, because `componentPercentage` is 0, the STATIC group is off, or `ape.staticDataPath` is absent
- **THEN** no `ComponentTrigger` stage SHALL exist in the pipeline
- **AND** no `u_C` SHALL ever be drawn, and no catalog SHALL be loaded (INV-CT-18)

#### Scenario: Preempted step draws no coin
- **WHEN** a guard, an LLM stage or the launcher decides a step on a plan that mounts the trigger
- **THEN** the `ComponentTrigger` stage SHALL NOT be evaluated on that step and SHALL consume no draw from the agent or helper stream
- **AND** `u_C` SHALL still have been drawn at that step's open, so the next step's `u_C` is the stream's next value

### Requirement: Broadcast triggering

`StatefulAgent.triggerComponent(target)` SHALL send targeted broadcasts to BroadcastReceivers (`StatefulAgent.dispatchTrigger`, then `AndroidDevice.sendBroadcast`). The intent SHALL be constructed with:
- the action string of the tuple, which comes from one of the receiver's intent-filter actions;
- the categories of the filter that supplied the action;
- a ComponentName targeting `(packageName, receiverClassName)` for explicit delivery;
- the data and type of the filter that supplied the action, when that filter carries a `data` member
  (INV-CT-17);
- typed extras from `SystemBroadcastCatalog`, when the action matches a known system broadcast.

The static analysis emits no intent extras, so the catalog is the only source of extras.

Receivers with no intent-filter actions (`intentFilters: []`) SHALL be skipped. The pool admits every receiver
with a filter action, whether or not the MOP data says it reaches a target (INV-CT-16). The trigger log line SHALL
carry the permission gate and SHALL NOT claim `reachesTarget=true`.

#### Scenario: Broadcast with catalog extras
- **WHEN** `triggerComponent(target)` fires a receiver tuple with action `android.intent.action.BOOT_COMPLETED`
- **AND** `SystemBroadcastCatalog` has an entry for this action
- **THEN** APE-RV SHALL send the broadcast with action, ComponentName, and catalog extras

#### Scenario: Broadcast with no catalog match
- **WHEN** the receiver has action `com.example.CUSTOM_ACTION` not in the catalog, and its filter carries no `data` member
- **THEN** APE-RV SHALL send the broadcast with action + ComponentName only (no extras, no data, no type)

#### Scenario: Protected broadcast
- **WHEN** the broadcast action is protected (e.g., `BOOT_COMPLETED`)
- **AND** Android throws `SecurityException`
- **THEN** the exception SHALL be caught and logged as WARNING
- **AND** exploration SHALL continue normally

#### Scenario: Broadcast carries the filter's data
- **WHEN** a receiver's filter has action `com.x.IMPORT` and `"data": {"schemes": ["content"], "hosts": ["com.x.files"], "paths": ["/in"], "mimeTypes": ["text/csv", "text/plain"]}`
- **THEN** the intent SHALL carry `setDataAndType(Uri.parse("content://com.x.files/in"), "text/csv")`
- **AND WHEN** another filter's `data` holds only `"mimeTypes": ["image/png"]`
- **THEN** its intent SHALL carry `setType("image/png")` and no data

### Requirement: Service triggering

`StatefulAgent.triggerComponent(target)` SHALL start Services via `AndroidDevice.startService()`. The intent SHALL use ComponentName
for explicit delivery, with the tuple's action when a filter supplied one. It SHALL also carry the data and type
of that filter, when the filter has a `data` member (INV-CT-17). Services without intent-filters can still be
started by ComponentName alone. Every such service yields one component-name-only tuple, without reading
`targetMethods` (INV-CT-16).

`AndroidDevice.startService()` SHALL use reflection to handle different `IActivityManager.startService()` signatures across Android versions (M through Q); when no signature matches, or the call throws, it SHALL log a warning and return `false`.

#### Scenario: Service started
- **WHEN** `triggerComponent(target)` fires a service tuple
- **THEN** APE-RV SHALL start it via `AndroidDevice.startService(intent)`
- **AND** when the service's filter has `"data": {"schemes": ["myapp"]}`, the intent SHALL carry `setData(Uri.parse("myapp://"))` and no type

### Requirement: SystemBroadcastCatalog

APE-RV SHALL load a catalog of system broadcast actions with typed extras from
`/data/local/tmp/system-broadcast.json` on the device. The catalog is loaded **if and only if** `COMPONENT_TRIGGER`
is mounted (INV-CT-18). The tool pushes it for the four `as*` cells and not for `base` or `mop`. The catalog
provides lookup by action string.

For each entry, extras are parsed from the `adb` command field: `--es` (String), `--ei` (int), `--ez` (boolean), `--el` (long), `--ef` (float).

If the catalog file is absent on a plan that mounts the trigger, an empty catalog SHALL be used: no extras for any
action, and no error.

#### Scenario: Catalog lookup for known action
- **WHEN** `COMPONENT_TRIGGER` is mounted and `SystemBroadcastCatalog.lookup("android.net.conn.CONNECTIVITY_CHANGE")` is called
- **THEN** it SHALL return typed extras for that action

#### Scenario: Catalog lookup for unknown action
- **WHEN** `SystemBroadcastCatalog.lookup("com.example.CUSTOM")` is called
- **THEN** it SHALL return an empty list
- **AND** on the `base` and `mop` cells, which do not mount the trigger, the catalog file SHALL NOT be read at all

---

### Requirement: Config — componentPercentage

`ape.componentPercentage` (double) SHALL control the probability of component triggering per step. The key is
owned by the STATIC member `COMPONENT_TRIGGER` (POSITIVE, neutral value 0) and read through the plan. Its jar
default SHALL be `0.0`. The STATIC group block sets it to `0.03` (design D-B13), so the cells `as`, `as_mop`,
`as_llm` and `as_mop_llm` trigger at 3 % per step unless a variant overrides it.

Component triggering is decoupled from MOP. Neither `ape.mop` nor any MOP key SHALL change `componentPercentage`,
or which components the pool holds. Setting `ape.componentPercentage > 0` explicitly without the STATIC group
aborts resolution as a missing dependency (`run-spec`). The value is clamped to `[0, 1]` at resolution.

#### Scenario: Default with mopDataPath set
- **WHEN** `ape.properties` sets `ape.staticDataPath` and `ape.mop=true` but neither `ape.static` nor `ape.componentPercentage` (the `mop` cell)
- **THEN** `COMPONENT_TRIGGER` SHALL be absent from the plan (triggering disabled)
- **AND** no component triggering SHALL occur

#### Scenario: Default without mopDataPath
- **WHEN** `ape.properties` sets neither `ape.staticDataPath` nor `ape.componentPercentage` (the `base` cell)
- **THEN** the plan's `componentPercentage` SHALL be `0.0` and no `ComponentTrigger` stage SHALL exist

#### Scenario: Explicit override enables triggering
- **WHEN** the `as` cell is resolved with an explicit `ape.componentPercentage=0.10`
- **THEN** the plan's `componentPercentage` SHALL be `0.10`, whatever the MOP group's state
- **AND** triggering SHALL fire with probability `0.10` per step that reaches the stage (subject to INV-CT-01)

---

### Requirement: ComponentName Package Derivation

Every trigger `ComponentName` SHALL pair the app package from the artifact's `package` field, read as
`StaticGuiModel.packageName()`, with the component's fully-qualified class name. Two sites build one:
`StatefulAgent.dispatchTrigger` for the broadcast and service tuples of the probabilistic pool, and the activity
launcher, whose `ActivityTriggerAction` carries `StaticGuiModel.packageName()` (`MopLauncherStage`) and which
`MonkeySourceApe.generateActivityTriggerEvent` turns into `ComponentName(action.getPackageName(),
action.getClassName())`, or into `setPackage(action.getPackageName())` for a deep link. Neither site SHALL read the
package from `MopData`, which is null in a cell without `MOP` such as `as` (design D-B14.21), and the probabilistic
pool reads no MOP data at all (INV-CT-16). Neither SHALL derive the package by truncating the class name at its
last dot. That heuristic yields the enclosing Java namespace, which differs from the app package for any component
declared in a subpackage (e.g. class `br.unb.app.receivers.MyReceiver` in app package `br.unb.app`). The result
would be an invalid `ComponentName` and a silently failed trigger.

#### Scenario: subpackaged receiver gets a valid ComponentName
- **WHEN** the JSON declares `package="br.unb.app"` and a receiver class `br.unb.app.receivers.MyReceiver` is triggered
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.receivers.MyReceiver")`

#### Scenario: top-level component unchanged
- **WHEN** the component class lives directly in the app package (`br.unb.app.MainReceiver`)
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.MainReceiver")`

### Requirement: Cadence-Based MOP Activity Launch

The cadence launcher SHALL be a `MopLauncher` decision stage (`MopLauncherStage`), assembled when the plan mounts `MOP_LAUNCHER` (a MOP member; key `ape.activityTriggerEnabled`), positioned after the guards and the LLM stages and before the `ComponentTrigger` and `MopCoin` stages — so a guard or an enabled LLM stage takes precedence at a shared step (`decision-pipeline` INV-DP-02, design D-A8). The stage SHALL own its episode state: the dedicated launcher step counter, the per-run launch budget counter, the round-robin cursor and the set of activities it launched (`decision-pipeline` INV-DP-07).

**Global cadence** (design D-A16, D-B11). The stage's `onStepOpen` hook SHALL increment the launcher step counter once per step, on every step, whichever stage then takes it (`decision-pipeline` INV-DP-08). A step decided by an earlier stage (a guard or an LLM stage) therefore advances the counter, so the LLM's dose does not starve the launcher. The stage's `decide()` SHALL fire on the first step on which the counter is at or past the configured cadence and the pipeline reaches the stage. Reaching the stage means that no guard and no LLM stage took the step. When the counter is at or past the configured cadence (`activityTriggerStagnationStep`, injected from the plan; the property name is kept for the rv-android `tool.py` mapping) and the per-run launch budget is not exhausted (`activityTriggerMaxPerRun == 0` OR launches emitted this run `< activityTriggerMaxPerRun`), the stage SHALL reset the step counter to 0 and attempt to select a launch candidate. The stage SHALL NOT read or reset `graphStableCounter` (INV-CT-05).

**Eligibility** (`MopLauncherStage.isEligible`, INV-CT-06). A manifest activity (the activities, the main activity and the package are read from `StaticGuiModel`; the MOP census and `activityDist` from `MopData`) is eligible when it satisfies ALL of — a member of the arm's MOP census (`className` in `MopData.getMopActivities()`, the selected set of `mop-guidance` INV-MOP-27; the component-level `ComponentInfo.reachesMop` field SHALL NOT be used — it false-negatives lambda-triggered activities), `permission == null`, not the main activity (neither `isMain` nor equal to the main activity's name), currently unvisited (the activity has no `ActivityNode` in the graph at fire time), **not launched before in this run** (the stage's set of launched activities, filled when the agent keeps the stage's launch; design D-B15), and **not framework/tooling-namespaced**: a candidate whose `className` starts with any prefix in the code constant `FRAMEWORK_ACTIVITY_PREFIXES` — `android.`, `androidx.`, `com.google.android.`, `kotlin.`, `kotlinx.`, `junit.`, `org.junit.`, `leakcanary.` — SHALL be ineligible (INV-CT-10). The match is a class-name **prefix** match (never substring). The denylist is a fixed code constant located with the candidate selection (a correctness filter, not a tunable — no configuration key). There is no package test: the census is the only membership filter, and the launch intent is addressed to the artifact's package. Eligibility SHALL NOT include an `exported` test: the dispatch path (`AndroidDevice.startActivity` → `IActivityManager.startActivity` from uid 2000) launches non-exported activities, and the compact artifact carries no `exported` field at all (`static-analysis-entrypoints` "Derived compact MOP artifact — projection contents" item 7), so there is nothing for the stage to consult. There SHALL be no fallback outside the MOP census: when the census yields no eligible candidate, no launch occurs. Eligibility is the same in both scoring modes.

**Choice among eligible activities, by scoring mode** (`ape.mopScoring`, `run-spec` "Feature Model with Declared Dependencies"; design D20; INV-CT-15):

- **`flag`** (default): the launch candidate is the next eligible manifest activity in round-robin order persisted across firings, over an eligible set that holds no activity launched before in this run (design D-B15).
- **`distance`**: the launch candidate is chosen by a rotation over the following order. An activity's key is the smallest `d` over the pairs `[i, d]` of its `activityDist` entry whose target `i` is not retired and whose `d ≤ ape.mopLauncherDmax` (default 6) — `MopRetirement.nearest(pairs, mopLauncherDmax)` of the `mop-guidance` design API. Activities are sorted by that key ascending, ties by manifest order. An activity with no such pair (no `activityDist` entry, only distances above the bound, or only retired targets) sorts after every activity that has one, in manifest order among themselves. The order is recomputed at each firing point, so a target that retired since the last firing no longer counts. The stage walks this order as a rotation: it remembers the sort position `(key, manifest index)` of the activity it selected last, and the candidate is the first eligible activity whose position comes after it, wrapping to the head of the order when none does. The remembered position is the stage's round-robin cursor (`decision-pipeline` INV-DP-07), which under `distance` holds the sort position of the last selection instead of a firing count; the field's content changes kind with the mode, and the stage's launched set (design D-B15) is a second field of its episode state (INV-DP-07). It moves at every selection, whatever the launch result. An activity whose launch is refused leaves the eligible set anyway (D-B15); the rotation decides the order among the activities not yet launched (the analysis's "rodízio dentro de faixas", `20261006_analise_rigorosa_plano_guia_mop.md:288`). The keys are recomputed at every firing, so a retirement between two firings can move the last selection to a later position, and the rotation can return to it before it has covered the others: the rotation is a full cycle only while the keys stay fixed (review NT-03).

A launched activity leaves the eligible set for the rest of the run, whether or not its launch opened it: one that opens becomes visited, and every launched one is in the stage's launched set (design D-B15). This keeps an activity that crashes on a cold launch from being selected again at most firings (`RecordAudioActivity` of spell4wiki in `e03iggsmk`: 3 of 4 launches in `mop`, 7 of 10 in `as_mop`, before D-B15). The launched set is filled from the stage's own kept selections, never from the launch result, so INV-CT-14 holds.

When a candidate exists, the stage SHALL return `StageResult.Select` carrying a first-class `EVENT_TRIGGER_ACTIVITY` action (`ActivityTriggerAction`) with `StaticGuiModel.packageName()`, the candidate's class name and, when the artifact supplies one, its deep-link URI — the action is the step (it produces exactly one step record with `dec.src="launcher"` and `dec.ch="launcher"` and is NOT a graph edge label, mirroring `EVENT_RESTART` semantics; the record is written by the non-model branch of `StatefulAgent.resolveNewAction`; INV-CT-07). The step's owner SHALL be `launcher` (`dec.src`, `dec.own.i`).

**A launch is committed when the agent keeps it** (option b of review WR-02). After `checkRestart`, the agent forwards a kept selection to the stages' `onSelectionKept` hook (`decision-pipeline` "Episode-State Ownership and Reset"). When the kept action is the stage's launch, the stage SHALL add the activity to its launched set, increment the per-run launch counter, count one `RUN_END.counters.launches`, and abandon a non-empty SATA buffer without weakening (`decision-pipeline` INV-DP-15, `dec.buf="launcher"`). A launch that a post-selection restart replaces (a plan without `APE_FIXES`, such as the `mop` cell; `decision-pipeline` "Post-Selection Restart Model Effect") is not executed and SHALL commit none of the four, so its activity stays eligible, the budget unspent, and the buffer is left to the restart's new episode, which weakens the planned edge as in APE. The cadence reset and the round-robin cursor move at the firing, as for a kept launch. With the `Restart` stage mounted no restart replaces a selection, so every launch is kept on the step that selects it. When no candidate exists (census exhausted, all visited, all launched before in this run, or all denylisted), the stage SHALL return `Continue` with no side effects beyond the already-performed step-counter reset — in particular the launch counter SHALL NOT be incremented (INV-CT-12).

Event generation (`MonkeySourceApe.generateActivityTriggerEvent`) SHALL dispatch the action as an explicit intent (`ComponentName(action.getPackageName(), action.getClassName())`) via `AndroidDevice.startActivity`, which adds `FLAG_ACTIVITY_NEW_TASK`; the package component SHALL be the action's package, `StaticGuiModel.packageName()`, and SHALL NOT be derived from the target class name (INV-CT-04, "ComponentName Package Derivation"). When the candidate carries a non-null `deepLinkUri`, the intent SHALL instead be `ACTION_VIEW` with `Uri.parse(deepLinkUri)`, targeted at the **package** (`Intent.setPackage(...)`) and carrying no component: the platform, not the launcher, resolves which activity of that package handles the URI. The URI SHALL be `deepLinkUri` verbatim: the launcher SHALL NOT read intent-filter `data` (the assembly rule itself is rv-android INV-DRV-07, applied host-side by the generator). The filter `data` that the wire carries since D-B13 is read only by the probabilistic pool's receiver and service dispatch (INV-CT-17), never by the launcher.

When two activities of one package assemble the same URI, the platform can open an activity other than the one the launcher selected. Measured over the pinned 345 apps, this happens in **7 apps** (`content://` ×3, `file://` ×2, `geo://`, `mailto://`), each a case where `scheme + "://" + host + path` discards the `mimeTypes`/`pathPrefixes`/`pathPatterns` that discriminate those filters. That is a defect in the assembly rule (rv-android INV-DRV-07), not in the dispatch, and is recorded as issue #17. Activities SHALL NOT participate in the `componentPercentage` probabilistic pool under any configuration.

**The dispatch SHALL record whether it was accepted.** `AndroidDevice.startActivity` SHALL return an `AndroidDevice.LaunchResult` carrying the `START_*` code that `IActivityManager.startActivity` returns (`code`), or, when the platform never answered, an error label (`no_method` or `exception`) and no code. `generateActivityTriggerEvent` SHALL pass it to the sink (`EventSink.componentLaunch`) as the launch step record's `dec.comp` (`event-sink` capability), and log a warning `[APE-RV] Triggering activity failed: <package>/<class>` when the intent was not dispatched. This is a recording and nothing more: no retry, no re-dispatch, no second probe of the device, and no effect on the round-robin cursor, the distance order or the launch budget, which continue to count *kept actions* exactly as INV-CT-12 requires (INV-CT-14). It matters because the launcher carries this study's strongest mechanism result — roughly 1,075 direct launches in the guided arm against none in the control, and an activity-coverage gain of about 14 percentage points that rests on them.

The cadence (`ape.activityTriggerStagnationStep`, default `50`; a configured value `<= 0` is clamped to `50` at plan resolution) and the per-run cap (`ape.activityTriggerMaxPerRun`, default `0` = unlimited; `< 0` is clamped to `0`) SHALL be injected into the stage from the resolved plan at assembly, and so SHALL the scoring mode and `ape.mopLauncherDmax` (both owned by the `MOP` feature, `run-spec`); the stage SHALL NOT read static `Config` (`decision-pipeline` INV-DP-12). The two clamps are silent (`RunSpec.clamp` writes no log line); the plan carries the clamped value. The retirement state the `distance` order reads is the run's single `MopRetirement` instance owned by `StatefulAgent` (`mop-guidance` "Target Retirement"); the stage reads it and never writes it. Both cadence keys are declared in the run-spec `Feature` model as sub-parameters owned by the `MOP_LAUNCHER` feature (which requires `MOP`); when the feature is absent from the resolved plan no launcher mechanism exists and the two keys are accepted only at their neutral values (`run-spec` INV-RUN-05). There is no `triggerMopFirst` key and no `Config.triggerMopFirst` field. Only kept `EVENT_TRIGGER_ACTIVITY` actions consume budget; a firing whose candidate scan comes up empty does not, and neither does a launch a post-selection restart replaces. Sustained exploration yields a firing point every `cadence` steps of the run, independent of graph growth. When a guard or an LLM stage takes the step on which the counter reaches the cadence, the firing is deferred to the next step that reaches the stage, and the next window starts from that firing. Expected launches per run ≈ `min(maxPerRun, steps/cadence, |unvisited eligible census|)`, minus the deferral.

The launcher's invariants are INV-CT-05, INV-CT-06, INV-CT-07, INV-CT-08, INV-CT-10, INV-CT-12, INV-CT-14 and INV-CT-15, stated in the Invariants section.

**On the scenario headers below.** `openspec archive` matches scenarios by name and cannot tell a rename from a deletion, so a renamed scenario must keep the main spec's header. Two here read in retired vocabulary as a result: "clamped at load" names a site that is plan resolution, and "launcher disabled" names what is feature absence. The bodies are current; the headers are the archive's cost. The scenarios that do not name a scoring mode hold in both.

#### Scenario: an activity whose launch did not open it is not launched again
- **WHEN** the census holds `com.x.RecordAudioActivity` and `com.x.SearchActivity`, both eligible, the stage launches `RecordAudioActivity` at a firing and the app crashes, so the activity never becomes visited
- **THEN** at every later firing of the run `RecordAudioActivity` SHALL NOT be eligible, under `flag` and under `distance` alike, and `SearchActivity` SHALL be selected while it is eligible
- **AND** when no other activity is eligible, the firing SHALL find no candidate (`dec.own.fb:"ineligible"`) and the launch counter SHALL NOT be incremented
- **AND** no input of the decision SHALL be the launch result (`dec.comp`, INV-CT-14)

#### Scenario: a launch replaced by a post-selection restart commits nothing
- **WHEN** in the `mop` cell the stage selects `com.x.Target` on a step on which a periodic restart is due, so `checkRestart` replaces the launch by a start action
- **THEN** `com.x.Target` SHALL NOT enter the launched set, the launch counter SHALL NOT be incremented, `RUN_END.counters.launches` SHALL NOT count the step, and a non-empty SATA buffer SHALL NOT be abandoned
- **AND** at the next firing `com.x.Target` SHALL be eligible while it stays unvisited

#### Scenario: deep link dispatched from the wire field
- **WHEN** the artifact's activity entry for `com.x.DetailActivity` carries `"deepLinkUri": "myapp://detail/x"` and the stage selects it
- **THEN** the injected intent SHALL be `ACTION_VIEW` with `Uri.parse("myapp://detail/x")` and `setPackage("com.x")`, with no component set
- **AND** the launcher SHALL NOT have read intent-filter `data`, a scheme list, a host or a path to produce it

#### Scenario: activity without a deep link falls back to the explicit component
- **WHEN** the artifact's activity entry omits `deepLinkUri`
- **THEN** the injected intent SHALL be the explicit component intent with `FLAG_ACTIVITY_NEW_TASK`
- **AND** the launch SHALL otherwise behave identically to one carrying a URI (same step accounting, same budget consumption, same step record)

#### Scenario: cadence fires independently of graph growth
- **WHEN** the cadence is 10, the exploration graph grows on every step (no stagnation ever), and no guard or LLM stage takes any step
- **THEN** the stage SHALL fire at steps 10, 20, 30 of the run

#### Scenario: periodic firing under the default cadence
- **WHEN** the cadence is unset (default 50) and eligible census candidates remain
- **THEN** the stage SHALL fire on the run's 50th step when that step reaches it, reset its step counter, and fire again after each further 50 steps

#### Scenario: LLM-preempted steps do not advance the cadence (finding 3.3-1)
- **WHEN** the stage's counter is at 49 and the next step is decided by an LLM stage
- **THEN** the counter SHALL be 50 after that step, because `onStepOpen` advanced it, and the stage SHALL NOT have fired
- **AND** the stage SHALL fire on the next step that falls through to it, with the counter at 51
- **AND** the header is kept because `openspec archive` matches scenarios by name: preempted steps do advance the counter

#### Scenario: non-exported census activity is launched
- **WHEN** the arm census contains `com.x.CryptoActivity`, `permission=null`, unvisited, declared `android:exported="false"` in the app's manifest
- **THEN** the stage SHALL select it and return an `EVENT_TRIGGER_ACTIVITY` action for it
- **AND** the manifest's export status SHALL NOT have reached the decision, the artifact carrying no `exported` field to convey it

#### Scenario: non-census activity is never launched
- **WHEN** `com.x.AboutActivity` is exported, permission-free, non-main and unvisited but not in `MopData.getMopActivities()`
- **THEN** the stage SHALL NOT select it, even when no census candidate is eligible (no fallback), in either scoring mode
- **AND** under `distance` an `activityDist` entry for it SHALL NOT make it eligible

#### Scenario: census exhausted falls through without side effects
- **WHEN** a firing point is reached but every census activity is visited or denylisted
- **THEN** no launch SHALL occur, the launch budget SHALL be unchanged, the step counter SHALL reset, and the stage SHALL return `Continue` (the `ComponentTrigger`, `MopCoin` and `SataChain` stages decide the step)

#### Scenario: permission-gated census activity skipped
- **WHEN** a census activity declares `permission="android.permission.MANAGE_DOCUMENTS"`
- **THEN** the candidate selection SHALL skip it, whatever its distance under `distance`

#### Scenario: denylisted census entry skipped
- **WHEN** the census contains `androidx.activity.ComponentActivity` (over-approximated reachability) and `com.x.HistoryActivity`, both otherwise eligible
- **THEN** the stage SHALL skip the `androidx.` entry and launch `com.x.HistoryActivity`, in either scoring mode

#### Scenario: cap exhausts the launch budget
- **WHEN** `activityTriggerMaxPerRun=2` and two `EVENT_TRIGGER_ACTIVITY` actions have been emitted this run
- **THEN** the firing predicate SHALL return false at every subsequent firing point and the stage SHALL return `Continue`

#### Scenario: cap zero means unlimited
- **WHEN** `activityTriggerMaxPerRun=0` (default) and 10 launches have already been emitted
- **THEN** the stage SHALL still fire at the next firing point (subject to the other gates)

#### Scenario: invalid values clamped at load
- **WHEN** the properties set `ape.activityTriggerStagnationStep=0` and `ape.activityTriggerMaxPerRun=-3`
- **THEN** plan resolution SHALL clamp them to `50` and `0` respectively, silently, and the plan SHALL carry the clamped values

#### Scenario: launcher disabled
- **WHEN** the plan does not enable activity triggering
- **THEN** no `MopLauncher` stage SHALL exist, no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced, and the probabilistic pool SHALL contain no activities

#### Scenario: arm contrast is the launched set
- **WHEN** the control arm's census (`ape.mopActivitySourceComponents=false`, so the artifact's `mopActivities`) is `{A}` and the treatment arm's (`=true`, so its `mopActivitiesAugmented`) is `{A, B, C}`
- **THEN** the control arm's `MopLauncher` SHALL only ever launch `A` while the treatment arm's can launch `A`, `B` and `C`
- **AND** the contrast SHALL come from the census the stage reads, never from the stage's own firing rule — both arms assemble the same stage with the same cadence, cap, scoring mode and choice rule
- **AND** the two sets arrive precomputed on the wire and the stage reads exactly one of them, selected at load (`mop-guidance` INV-MOP-27), so *where* the sets are computed changes neither which set an arm reads nor what the contrast measures

#### Scenario: distance mode launches the nearest eligible activity first
- **WHEN** `ape.mopScoring=distance`, the manifest lists eligible activities `P`, `Q`, `R` in that order, and their `activityDist` entries are `P: [[4, 5]]`, `Q: [[0, 2], [7, 3]]`, `R: [[2, 2]]`, no target retired
- **THEN** successive firing points SHALL launch `Q`, then `R` (same distance 2, later in manifest order), then `P`, each one leaving the eligible set once it is launched

#### Scenario: distances beyond the bound and retired targets do not count
- **WHEN** `ape.mopScoring=distance`, `ape.mopLauncherDmax=6`, eligible activities `P`, `Q`, `R` in manifest order have `P: [[1, 7]]`, `Q: [[3, 1], [5, 4]]` with target 3 retired, and `R` has no `activityDist` entry
- **THEN** the order SHALL be `Q` (key 4, from the unretired target 5), then `P` and `R` in manifest order, both without a key

#### Scenario: flag mode keeps the round-robin
- **WHEN** `ape.mopScoring=flag` and the artifact carries `activityDist` for every eligible activity
- **THEN** the stage SHALL select the next eligible activity of the manifest-order round-robin from its cursor (the eligible set excluding activities launched before in this run, design D-B15), and SHALL NOT read `activityDist` or the retirement state

#### Scenario: a refused launch is not retired under distance
- **WHEN** `ape.mopScoring=distance`, the first activity of the order is `Q`, and its launch returns a non-success `START_*` code, so `Q` stays unvisited
- **THEN** the next firing point SHALL select the eligible activity that follows `Q` in the order, and `Q` SHALL NOT be selected again in the run, because it is in the stage's launched set (design D-B15)
- **AND** no target of `Q` SHALL be counted toward retirement by the failed launch (INV-CT-14)

### Requirement: Launcher-Owned Null-Tree Recovery

When the accessibility bridge returns a null root more than ten times in a row, `MonkeySourceApe` recovers. The upstream recovery, `stopTopActivity()`, kills the first process of `getRunningAppProcesses()`. After a launch that crashed the app, that process is not the app: in `e03igg` it was the IME (`com.google.android.inputmethod.latin`) or the system launcher in 7 runs, all in the MOP arm, and the null trees went on (design D-B16).

When the plan contains `Feature.MOP_LAUNCHER` (`MonkeySourceApe.NullTreeRecovery.relaunches(spec)`), the recovery SHALL stop the package under test and start its main activity again, through the existing restart path with the launcher of `exploration` INV-EXPL-33 (`restartPackage(launchers().get(0), false, "null tree")`), and SHALL NOT call `stopTopActivity()`. Like `stopTopActivity()`, it SHALL first tell the agent that the activity stopped (`onActivityStopped()`), which clears the agent's current state, action and buffer. On the step of the recovery the branch SHALL NOT queue the `ACTIVATE` action: `ACTIVATE` is an app switch followed by HOME or BACK, which would send the app just relaunched to the background (author, 2026-10-18). The step's action is then `NOP`. The rest of the branch is unchanged: the counter reset, `onVoidGUITree` and the interactive check. Each such recovery SHALL add one to `RUN_END.counters.nullRelaunches` (`event-sink`).

When the plan does not contain `Feature.MOP_LAUNCHER`, the recovery SHALL be upstream's `stopTopActivity()`, unchanged, and `nullRelaunches` SHALL stay `0`: BASE stays upstream APE (author, 2026-10-18).

- **INV-CT-19**: A null-tree recovery SHALL relaunch the app under test if and only if `MOP_LAUNCHER` is in the plan; otherwise it SHALL be upstream's code path.

#### Scenario: with the launcher, the recovery relaunches the app
- **WHEN** in cell `mop` the eleventh consecutive null root arrives after the launcher opened an activity that crashed
- **THEN** the recovery SHALL call `onActivityStopped()`, stop the package under test and start its main activity, no process other than the app's SHALL be killed, no app switch, HOME or BACK SHALL be queued on that step, and `RUN_END.counters.nullRelaunches` SHALL be `1`

#### Scenario: without the launcher, the recovery is upstream's
- **WHEN** in cell `base` (or `as`, `as_llm`) the eleventh consecutive null root arrives
- **THEN** the recovery SHALL be `stopTopActivity()`, exactly as upstream, and `RUN_END.counters.nullRelaunches` SHALL be `0`
