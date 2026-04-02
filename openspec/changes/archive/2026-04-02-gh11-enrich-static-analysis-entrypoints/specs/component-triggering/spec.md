# Specification: Component Triggering

## Purpose

APE-RV operates exclusively via GUI interactions (AccessibilityService + Monkey events). Services and BroadcastReceivers are non-GUI Android components whose code paths are invisible to UI-only exploration. These components frequently execute security-relevant operations (crypto, TLS) that MOP specifications monitor.

This specification defines how APE-RV SHALL trigger Services and BroadcastReceivers at runtime using `am broadcast` and `am startservice`, with intent data derived from the static analysis JSON's `components{}` section. Triggering is probabilistic — on each exploration step, there is a `Config.componentPercentage` chance of triggering one component (round-robin). The trigger is a side-effect that does not consume a SATA step.

Activities are excluded from triggering because they are already reachable via GUI exploration, and triggering them via `startActivity()` disrupts the SATA exploration flow.

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

- **INV-CT-01**: Component triggering SHALL only fire when `Config.componentPercentage > 0` AND `MopData.hasComponents()` is true. When `componentPercentage` is 0.0 (default when no mopDataPath), behavior SHALL be identical to APE-RV without component triggering.
- **INV-CT-02**: Component triggering SHALL be probabilistic — on each step in `SataAgent.selectNewActionNonnull()`, a random check against `componentPercentage` determines whether to trigger. The trigger is a side-effect; normal SATA action selection continues regardless.
- **INV-CT-03**: Only BroadcastReceivers and Services SHALL be triggered. Activities and ContentProviders are excluded.

---

## ADDED Requirements

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

`Config.componentPercentage` (double) SHALL control the probability of component triggering per step. Default: `0.05` when `Config.mopDataPath` is set, `0.0` otherwise.

#### Scenario: Default with mopDataPath
- **WHEN** `ape.properties` sets `ape.mopDataPath` but not `ape.componentPercentage`
- **THEN** `Config.componentPercentage` SHALL default to `0.05` (5%)

#### Scenario: Default without mopDataPath
- **WHEN** `ape.properties` does not set `ape.mopDataPath`
- **THEN** `Config.componentPercentage` SHALL default to `0.0` (disabled)

#### Scenario: Explicit override
- **WHEN** `ape.properties` sets `ape.componentPercentage=0.10`
- **THEN** `Config.componentPercentage` SHALL be `0.10` regardless of `mopDataPath`
