# Specification: Component Triggering

## Purpose

APE-RV operates exclusively via GUI interactions (AccessibilityService + Monkey events). Services and BroadcastReceivers are non-GUI Android components whose code paths are invisible to UI-only exploration. These components frequently execute security-relevant operations (crypto, TLS) that MOP specifications monitor.

This specification defines how APE-RV SHALL trigger Services and BroadcastReceivers at runtime using `am broadcast` and `am startservice`, with intent data derived from the static analysis JSON's `components{}` section. Triggering is probabilistic — on each exploration step, there is a `Config.componentPercentage` chance of triggering one component (round-robin). The trigger is a side-effect that does not consume a SATA step.

Activities are excluded from the *probabilistic* pool: they are already reachable via GUI exploration, and unfiltered per-step `startActivity()` jumps disrupt the SATA flow (the gh11 mechanism). They are instead reachable through a dedicated, model-visible **stagnation launcher** — a first-class `EVENT_TRIGGER_ACTIVITY` step (`decision_source=Component`) fired only on exploration stagnation, only to manifest-eligible frontier activities (exported, permission-free, same-package, currently unvisited). See the Stagnation-Triggered Activity Launch requirement.

---

## Data Contracts

### Input
- `MopData.getReceivers()` — list of receivers with their intent-filter actions
- `MopData.getServices()` — list of services with their intent-filter actions
- `Config.componentPercentage` — probability per step (default: 0.05 when mopDataPath is set, 0.0 otherwise)
- `SystemBroadcastCatalog` — lookup table of typed extras for system broadcast actions (from VLM-Fuzz catalog, 187 entries)

### Output
- Broadcast intents sent to receivers via `AndroidDevice.sendBroadcast()`
- Service start requests via `AndroidDevice.startService()`

### Side-Effects
- **[Android runtime]**: Broadcasts may trigger receiver code, potentially opening new Activities or changing app state
- **[Android runtime]**: Services may start background operations

### Error
- Failed broadcast/service start SHALL be logged as WARNING and SHALL NOT crash the agent. Exploration continues normally.

---

## Invariants

- **INV-CT-01**: Component triggering SHALL only fire when `Config.componentPercentage > 0` AND `MopData.hasComponents()` is true. When `componentPercentage` is `0.0` (the default, regardless of whether `mopDataPath` is set), behavior SHALL be identical to APE-RV without component triggering.
- **INV-CT-02**: Component triggering SHALL be probabilistic — on each step in `SataAgent.selectNewActionNonnull()`, a random check against `componentPercentage` determines whether to trigger. The trigger is a side-effect; normal SATA action selection continues regardless.
- **INV-CT-03**: Only BroadcastReceivers and Services SHALL enter the probabilistic `buildTriggerTuples` pool; ContentProviders and activities SHALL NOT enter it under any configuration. ContentProviders remain excluded from triggering entirely. Activities are launched exclusively via the stagnation launcher (`EVENT_TRIGGER_ACTIVITY`, `decision_source=Component`; see Stagnation-Triggered Activity Launch), never probabilistically.
- **INV-CT-04**: The package component of every trigger `ComponentName` SHALL equal `MopData.getPackageName()`; no trigger path SHALL derive it from the component class name.

---
## Requirements
### Requirement: Probabilistic component triggering in SataAgent

In `SataAgent.selectNewActionNonnull()`, after LLM hooks and before the SATA chain, a probabilistic check SHALL fire with probability `Config.componentPercentage`. When fired, `triggerMopComponent()` SHALL be called as a side-effect — the method triggers one component (round-robin) and returns. The normal SATA action selection continues immediately after.

#### Scenario: Component trigger fires
- **WHEN** `Config.componentPercentage` is `0.05` and `MopData.hasComponents()` is true
- **AND** `random.nextDouble() < 0.05` on this step
- **THEN** `triggerMopComponent()` SHALL be called
- **AND** normal SATA action selection SHALL continue (trigger does not replace the step)

#### Scenario: Component trigger does not fire
- **WHEN** `random.nextDouble() >= Config.componentPercentage`
- **THEN** no component trigger occurs
- **AND** SATA proceeds normally

#### Scenario: No mopDataPath set
- **WHEN** `Config.mopDataPath` is null
- **THEN** `Config.componentPercentage` defaults to `0.0`
- **AND** no component triggering occurs

---

### Requirement: Broadcast triggering

`triggerMopComponent()` SHALL send targeted broadcasts to BroadcastReceivers. The intent SHALL be constructed with:
- Action string from the receiver's first intent-filter action
- ComponentName targeting `(packageName, receiverClassName)` for explicit delivery
- Typed extras from `SystemBroadcastCatalog` if the action matches a known system broadcast

Receivers with no intent-filter actions (`intentFilters: []`) SHALL be skipped.

#### Scenario: Broadcast with catalog extras
- **WHEN** `triggerMopComponent()` selects a receiver with action `android.intent.action.BOOT_COMPLETED`
- **AND** `SystemBroadcastCatalog` has an entry for this action
- **THEN** APE-RV SHALL send the broadcast with action, ComponentName, and catalog extras

#### Scenario: Broadcast with no catalog match
- **WHEN** the receiver has action `com.example.CUSTOM_ACTION` not in the catalog
- **THEN** APE-RV SHALL send the broadcast with action + ComponentName only (no extras)

#### Scenario: Protected broadcast
- **WHEN** the broadcast action is protected (e.g., `BOOT_COMPLETED`)
- **AND** Android throws `SecurityException`
- **THEN** the exception SHALL be caught and logged as WARNING
- **AND** exploration SHALL continue normally

---

### Requirement: Service triggering

`triggerMopComponent()` SHALL start Services via `AndroidDevice.startService()`. The intent SHALL use ComponentName for explicit delivery, with action from intent-filter if available. Services without intent-filters can still be started by ComponentName alone.

`AndroidDevice.startService()` SHALL use reflection to handle different `IActivityManager.startService()` signatures across Android versions (M through Q).

#### Scenario: Service started
- **WHEN** `triggerMopComponent()` selects a service
- **THEN** APE-RV SHALL start it via `AndroidDevice.startService(intent)`

---

### Requirement: SystemBroadcastCatalog

APE-RV SHALL load a catalog of system broadcast actions with typed extras from `/data/local/tmp/system-broadcast.json` on the device (pushed alongside `ape-rv.jar`). The catalog provides lookup by action string.

For each entry, extras are parsed from the `adb` command field: `--es` (String), `--ei` (int), `--ez` (boolean), `--el` (long), `--ef` (float).

If the catalog file is absent, an empty catalog SHALL be used (no extras for any action, no error).

#### Scenario: Catalog lookup for known action
- **WHEN** `SystemBroadcastCatalog.lookup("android.net.conn.CONNECTIVITY_CHANGE")` is called
- **THEN** it SHALL return typed extras for that action

#### Scenario: Catalog lookup for unknown action
- **WHEN** `SystemBroadcastCatalog.lookup("com.example.CUSTOM")` is called
- **THEN** it SHALL return an empty list

---

### Requirement: Config — componentPercentage

`Config.componentPercentage` (double) SHALL control the probability of component triggering per step. The default SHALL be `0.0` regardless of `Config.mopDataPath`. Component triggering is enabled only by an explicit `ape.componentPercentage` setting in `ape.properties`.

This decouples component triggering from MOP scoring: setting `ape.mopDataPath` (which enables the MOP scorer) SHALL NOT change `componentPercentage`. An experiment arm that wants both MOP scoring and triggering SHALL set `ape.componentPercentage` explicitly.

Anchor: `Config.java:169-170`. Sole consumer: `SataAgent.java:351-354`.

#### Scenario: Default with mopDataPath set
- **WHEN** `ape.properties` sets `ape.mopDataPath` but not `ape.componentPercentage`
- **THEN** `Config.componentPercentage` SHALL default to `0.0` (triggering disabled)
- **AND** no component triggering SHALL occur

#### Scenario: Default without mopDataPath
- **WHEN** `ape.properties` does not set `ape.mopDataPath` and does not set `ape.componentPercentage`
- **THEN** `Config.componentPercentage` SHALL default to `0.0` (disabled)

#### Scenario: Explicit override enables triggering
- **WHEN** `ape.properties` sets `ape.componentPercentage=0.10`
- **THEN** `Config.componentPercentage` SHALL be `0.10` regardless of `mopDataPath`
- **AND** triggering SHALL fire with probability `0.10` per step (subject to INV-CT-01)

---

### Requirement: ComponentName Package Derivation

`StatefulAgent.dispatchTrigger` SHALL build the `ComponentName` for every trigger kind (broadcast, service, activity) using the app package parsed from the static-analysis JSON (`MopData.getPackageName()`, sourced from the JSON `package` field), paired with the component's fully-qualified class name. It SHALL NOT derive the package by truncating the class name at its last dot: that heuristic yields the enclosing Java namespace, which differs from the app package for any component declared in a subpackage (e.g. class `br.unb.app.receivers.MyReceiver` in app package `br.unb.app`), producing an invalid `ComponentName` and a silently failed trigger.

#### Scenario: subpackaged receiver gets a valid ComponentName
- **WHEN** the JSON declares `package="br.unb.app"` and a reachable receiver class `br.unb.app.receivers.MyReceiver` is triggered
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.receivers.MyReceiver")`

#### Scenario: top-level component unchanged
- **WHEN** the component class lives directly in the app package (`br.unb.app.MainReceiver`)
- **THEN** the `ComponentName` SHALL be `("br.unb.app", "br.unb.app.MainReceiver")` (same result as before)

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

### Requirement: MOP-First Ordering of Stagnation-Launch Candidates

`Config.triggerMopFirst` (declared in `Config.java`, loaded via `ape.triggerMopFirst`, default `false`, registered in the `apePureMode` RV-flag registry — INV-ARCH-06 of `scoring-pipeline` — and forced to `false` when `apePureMode=true`) SHALL control the order in which the stagnation activity launcher's candidate selection (`selectTriggerCandidate`, from `activity-frontier`'s `Stagnation-Triggered Activity Launch` requirement) considers eligible candidates. Eligibility is unchanged (exported ∧ `permission == null` ∧ not main ∧ unvisited at fire time — INV-CT-06); this requirement changes only ordering, never the eligible set.

A candidate is **MOP-reaching** iff `MopData.activityHasMop(candidate.className) == true` (the reachability-augmented set, INV-MOP-27). The component-level `ComponentInfo.reachesTarget` field SHALL NOT be used for this decision (it false-negatives lambda-triggered activities).

- When `Config.triggerMopFirst == false` (default), candidate selection SHALL be exactly `activity-frontier`'s single-pass round-robin over the manifest activity list — behaviour byte-identical to that change (MOP membership is not consulted).
- When `Config.triggerMopFirst == true`, candidate selection SHALL consider eligible MOP-reaching candidates **before** eligible non-MOP-reaching candidates. The ordering SHALL be a stable two-pass over the eligible set (MOP-reaching group first, then the rest), each group walked in the existing round-robin order, so selection is deterministic and reproducible under a fixed seed with no dependence on set/iteration order.

The ordering SHALL NOT launch receivers, services, or providers (E-ext is out of scope) and SHALL NOT alter the `EVENT_TRIGGER_ACTIVITY` step semantics, the `decision_source=Component` attribution, the once-per-episode gate, or the `ComponentName` package derivation (all owned by `activity-frontier`).

- **INV-CT-09**: With `Config.triggerMopFirst == true`, when at least one eligible candidate is MOP-reaching (`activityHasMop(className)`), the launched activity SHALL be a MOP-reaching candidate; a non-MOP-reaching eligible candidate SHALL be launched only when no eligible MOP-reaching candidate exists. With `Config.triggerMopFirst == false`, selection order SHALL be identical to `activity-frontier`'s round-robin, and `activityHasMop` SHALL NOT be consulted.

#### Scenario: MOP-reachable candidate preferred
- **WHEN** `ape.triggerMopFirst=true` and the eligible set contains `com.x.Plain` (`activityHasMop=false`) and `com.x.Crypto` (`activityHasMop=true`, e.g. reachable only via a lambda handler so `components.reachesTarget=false`)
- **THEN** the launcher SHALL select `com.x.Crypto`

#### Scenario: falls back to non-MOP when no MOP candidate eligible
- **WHEN** `ape.triggerMopFirst=true` and no eligible candidate is MOP-reaching (`activityHasMop=false` for all)
- **THEN** the launcher SHALL select an eligible candidate in round-robin order (no candidate is skipped for lacking MOP)

#### Scenario: flag off preserves round-robin
- **WHEN** `ape.triggerMopFirst=false`
- **THEN** candidate selection SHALL be identical to `activity-frontier`'s round-robin, ignoring MOP membership

#### Scenario: eligibility unchanged
- **WHEN** `ape.triggerMopFirst=true` and a MOP-reaching activity (`activityHasMop=true`) is non-exported (ineligible)
- **THEN** it SHALL NOT be launched (ordering never widens eligibility)

