# Specification: Component Triggering

## Purpose

APE-RV operates exclusively via GUI interactions (AccessibilityService + Monkey events). Services and BroadcastReceivers are non-GUI Android components whose code paths are invisible to UI-only exploration. These components frequently execute security-relevant operations (crypto, TLS) that MOP specifications monitor.

This specification defines how APE-RV SHALL trigger Services and BroadcastReceivers at runtime using `am broadcast` and `am startservice`, with intent data derived from the static analysis JSON's `components{}` section. Triggering is probabilistic — on each exploration step, there is a `Config.componentPercentage` chance of triggering one component (round-robin). The trigger is a side-effect that does not consume a SATA step.

Activities are excluded from the *probabilistic* pool: they are already reachable via GUI exploration, and unfiltered per-step `startActivity()` jumps disrupt the SATA flow (the gh11 mechanism). They are instead reachable through a dedicated, model-visible **cadence launcher** — a first-class `EVENT_TRIGGER_ACTIVITY` step (`decision_source=Component`) fired on a fixed step cadence, only to the arm's MOP census activities (permission-free, same-package, currently unvisited; exported status is not consulted). See the Cadence-Based MOP Activity Launch requirement.

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
- **INV-CT-03**: Only BroadcastReceivers and Services SHALL enter the probabilistic `buildTriggerTuples` pool; ContentProviders and activities SHALL NOT enter it under any configuration. ContentProviders remain excluded from triggering entirely. Activities are launched exclusively via the cadence launcher (`EVENT_TRIGGER_ACTIVITY`, `decision_source=Component`; see Cadence-Based MOP Activity Launch), never probabilistically.
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

### Requirement: Cadence-Based MOP Activity Launch

When `Config.activityTriggerEnabled` is true and `MopData` is loaded, the agent SHALL maintain a dedicated launcher step counter, incremented once per action-selection pass through the launcher block (evaluated in `SataAgent.selectNewActionNonnull` after the LLM hooks, so an enabled LLM hook takes precedence at a shared step). When the counter reaches exactly `Config.activityTriggerStagnationStep` (the launcher **cadence**; the property name is kept for the rv-android `tool.py` mapping and documented at its `Config` declaration) and the per-run launch budget is not exhausted (`Config.activityTriggerMaxPerRun == 0` OR launches emitted this run `< Config.activityTriggerMaxPerRun`), the launcher SHALL reset the step counter to 0 and attempt to select a launch candidate. The launcher SHALL NOT read or reset `graphStableCounter`.

A launch candidate is the next manifest activity, in round-robin order persisted across firings, satisfying ALL of — a member of the arm's MOP census (`className` in `MopData.getMopActivities()`, the `activityHasMop` reachability-augmented set of INV-MOP-27; the component-level `ComponentInfo.reachesTarget` field SHALL NOT be used — it false-negatives lambda-triggered activities), `permission == null`, not the main activity, currently unvisited (`Graph.getActivityNode(className) == null` at fire time), and **not framework/tooling-namespaced**: a candidate whose `className` starts with any prefix in the code constant `FRAMEWORK_ACTIVITY_PREFIXES` — `android.`, `androidx.`, `com.google.android.`, `kotlin.`, `kotlinx.`, `junit.`, `org.junit.`, `leakcanary.` — SHALL be ineligible. The match is a class-name **prefix** match (never substring). The denylist is a fixed code constant located with `firstEligible` (a correctness filter, not a tunable — no Config flag). Eligibility SHALL NOT include an `exported` test: the dispatch path (`AndroidDevice.startActivity` → `IActivityManager.startActivity` from uid 2000) launches non-exported activities. There SHALL be no fallback outside the MOP census: when the census yields no eligible candidate, no launch occurs.

When a candidate exists, the agent SHALL increment the per-run launch counter and return a first-class `EVENT_TRIGGER_ACTIVITY` action carrying the candidate's class name and, when available, its deep-link URI — the action is the step (it produces exactly one `[APE-STEP]` line with `decision_source=Component` and is NOT a graph edge label, mirroring `EVENT_RESTART` semantics; the `[APE-STEP]` line is emitted by the else-branch of `StatefulAgent.resolveNewAction`, which derives the decision source from the action). When no candidate exists (census exhausted, all visited, or all denylisted), selection SHALL fall through to the normal SATA chain with no side effects beyond the already-performed step-counter reset — in particular the launch counter SHALL NOT be incremented.

`Config.activityTriggerStagnationStep` (int, loaded via `ape.activityTriggerStagnationStep`, default `50`) SHALL be the launcher cadence in selection steps. A configured value `<= 0` SHALL be clamped to the default at load time (logged). Sustained exploration yields periodic firing points every cadence steps, independent of graph growth; expected launches per run = `min(maxPerRun, steps/cadence, |unvisited eligible census|)`.

`Config.activityTriggerMaxPerRun` (int, loaded via `ape.activityTriggerMaxPerRun`, default `0` = unlimited) SHALL cap the number of `EVENT_TRIGGER_ACTIVITY` actions emitted in a run. A configured value `< 0` SHALL be clamped to `0` at load time (logged). Only actually returned `EVENT_TRIGGER_ACTIVITY` actions consume budget; a firing whose candidate scan comes up empty does not.

Both keys SHALL be declared in the run-spec `Feature` model as sub-parameters owned by the `ACTIVITY_TRIGGER` feature (which requires `MOP`): when the feature is absent from the resolved plan, no launcher mechanism exists and the two keys are accepted only at their neutral values (INV-RUN-05 of `run-spec`). `Config.triggerMopFirst` SHALL NOT exist (deleted).

Event generation SHALL dispatch the action as an explicit intent (`ComponentName(MopData.getPackageName(), className)`, `FLAG_ACTIVITY_NEW_TASK`) via `AndroidDevice.startActivity`; the package component SHALL be `MopData.getPackageName()` and SHALL NOT be derived from the target class name (main-spec INV-CT-04, ComponentName Package Derivation). When the candidate's intent-filters contain an `ACTION_VIEW` filter with non-empty `data.schemes`, the intent SHALL instead be `ACTION_VIEW` with a best-effort URI assembled from the filter's first scheme, host and path, still targeted at the component. Activities SHALL NOT participate in the `componentPercentage` probabilistic pool under any configuration.

- **INV-CT-05 (amended)**: At most one launch attempt SHALL occur per cadence window (the exact-equality gate on the dedicated step counter plus its reset at the firing point make re-fire impossible within a window). The launcher SHALL NOT read or reset `graphStableCounter`.
- **INV-CT-06 (amended)**: Every launched activity SHALL satisfy, at fire time: member of `MopData.getMopActivities()`, permission-free, non-main, same-package, unvisited, and not framework/tooling-namespaced (`FRAMEWORK_ACTIVITY_PREFIXES` prefix match). Exported status SHALL NOT be consulted.
- **INV-CT-07**: unchanged — every launch model-visible as exactly one `[APE-STEP]` line with `decision_source=Component`; no graph edge labeled by the launch. This requires the non-model `[APE-STEP]` branch in `StatefulAgent.resolveNewAction` to derive the source from the action instead of hardcoding `SATA`.
- **INV-CT-08**: unchanged — with `ape.activityTriggerEnabled=false`, no activity SHALL ever be launched by APE-RV (neither by the launcher nor by the probabilistic pool, which no longer contains activities).
- **INV-CT-10**: unchanged — no `EVENT_TRIGGER_ACTIVITY` action SHALL ever carry a class name matching a `FRAMEWORK_ACTIVITY_PREFIXES` prefix; the denylist SHALL be consulted only inside the launcher eligibility (`firstEligible`) — no second exclusion mechanism.
- **INV-CT-12**: unchanged — when `ape.activityTriggerMaxPerRun` is `N > 0`, the number of `EVENT_TRIGGER_ACTIVITY` actions emitted in a run SHALL never exceed `N`; when `0`, no cap applies. Budget accounting SHALL count only returned actions (an empty candidate scan consumes nothing).

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
- **THEN** the load SHALL clamp them to `50` and `0` respectively and log each clamp (documented value semantics — clamps, not aborts)

#### Scenario: launcher disabled
- **WHEN** `ape.activityTriggerEnabled=false`
- **THEN** no `EVENT_TRIGGER_ACTIVITY` step SHALL ever be produced regardless of cadence/cap values, and the probabilistic pool SHALL contain no activities

