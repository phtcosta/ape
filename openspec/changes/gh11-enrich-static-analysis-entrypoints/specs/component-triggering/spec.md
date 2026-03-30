# Specification: Component Triggering

## Purpose

APE-RV operates exclusively via GUI interactions (AccessibilityService + Monkey events). Services and BroadcastReceivers are non-GUI Android components whose code paths are invisible to UI-only exploration. These components frequently execute security-relevant operations (crypto, TLS) that MOP specifications monitor.

This specification defines how APE-RV SHALL trigger Services and BroadcastReceivers at runtime using `am broadcast` and `am startservice`, with intent data derived from the static analysis JSON's `components{}` section. Triggering is integrated as a stagnation escape hatch — tried before restart when the exploration graph stagnates.

---

## Data Contracts

### Input
- `MopData.getMopReceivers()` — list of receivers with MOP-reachable lifecycle methods and their intent-filter actions
- `MopData.getMopServices()` — list of services with MOP-reachable lifecycle methods and their intent-filter actions
- `Config.testBroadcasts` / `Config.testServices` — boolean flags enabling triggering (default: false)
- `graphStableCounter` — existing stagnation detector in SataAgent

### Output
- `EVENT_BROADCAST` action — sends a targeted broadcast to a receiver
- `EVENT_START_SERVICE` action — starts a service via `am startservice`

### Side-Effects
- **[Android runtime]**: Broadcasts may trigger receiver code, potentially opening new Activities or changing app state
- **[Android runtime]**: Services may start background operations, potentially changing app state

### Error
- Failed broadcast/service start SHALL be logged as WARNING and SHALL NOT crash the agent. Exploration continues normally.

---

## Invariants

- **INV-CT-01**: Component triggering SHALL only occur when `Config.testBroadcasts` or `Config.testServices` is `true` AND `MopData` has component data. Default behavior (flags false) SHALL be identical to current APE-RV.
- **INV-CT-02**: Component triggering SHALL be attempted only during stagnation (graphStableCounter exceeds threshold), before falling through to restart.
- **INV-CT-03**: After a broadcast/service trigger, `graphStableCounter` SHALL be reset to give the triggered component time to produce observable state changes.

---

## ADDED Requirements

### Requirement: EVENT_BROADCAST action type

APE-RV SHALL support a new `EVENT_BROADCAST` action type that sends a targeted broadcast intent to a specific BroadcastReceiver.

The intent SHALL be constructed with:
- Action string from the receiver's first intent-filter action
- ComponentName targeting `(appPackage, receiverClassName)` for explicit delivery
- Typed extras from `SystemBroadcastCatalog` if the action matches a known system broadcast

`AndroidDevice.broadcastIntent(Intent)` already exists (used for IME communication) and SHALL be reused.

#### Scenario: Broadcast sent to MOP receiver with catalog extras
- **WHEN** stagnation is detected and `Config.testBroadcasts` is true
- **AND** `MopData` contains a receiver `com.example.BootReceiver` with action `android.intent.action.BOOT_COMPLETED` and `reachesMop=true`
- **AND** `SystemBroadcastCatalog` has an entry for `android.intent.action.BOOT_COMPLETED`
- **THEN** APE-RV SHALL send the broadcast with action, ComponentName, and catalog extras
- **AND** `graphStableCounter` SHALL be reset

#### Scenario: Broadcast with no catalog match
- **WHEN** a MOP receiver has action `com.example.CUSTOM_ACTION` not in the catalog
- **THEN** APE-RV SHALL send the broadcast with action + ComponentName only (no extras)

#### Scenario: Receiver with no intent-filter actions
- **WHEN** a MOP receiver has `intentFilters: []` (no declared actions)
- **THEN** the receiver SHALL be skipped for triggering (no broadcast sent)

---

### Requirement: EVENT_START_SERVICE action type

APE-RV SHALL support a new `EVENT_START_SERVICE` action type that starts a specific Service.

The intent SHALL be constructed with:
- ComponentName targeting `(appPackage, serviceClassName)` for explicit delivery
- Action string from the service's first intent-filter action (if available)

A new `AndroidDevice.startService(Intent)` method SHALL be added, using `IActivityManager.startService()` via reflection, symmetric to the existing `broadcastIntent()`.

#### Scenario: Service started for MOP service
- **WHEN** stagnation is detected and `Config.testServices` is true
- **AND** `MopData` contains a service `com.example.CryptoService` with `reachesMop=true`
- **THEN** APE-RV SHALL start the service via `am startservice -n com.example/.CryptoService`
- **AND** `graphStableCounter` SHALL be reset

---

### Requirement: Stagnation escape hatch in SataAgent

When `graphStableCounter` exceeds the stagnation threshold, SataAgent SHALL attempt component triggering BEFORE falling through to the existing restart mechanism. The selection SHALL use round-robin across MOP receivers and services.

#### Scenario: Stagnation with MOP components available
- **WHEN** `graphStableCounter > Config.graphStableRestartThreshold`
- **AND** `Config.testBroadcasts` is true and `MopData.getMopReceivers()` is non-empty
- **THEN** SataAgent SHALL select a broadcast action (round-robin) instead of restart
- **AND** `graphStableCounter` SHALL be reset to 0

#### Scenario: Stagnation without MOP components
- **WHEN** `graphStableCounter > Config.graphStableRestartThreshold`
- **AND** no MOP receivers or services are available (or flags are false)
- **THEN** SataAgent SHALL fall through to the existing restart mechanism (unchanged behavior)

---

### Requirement: Config flags for component triggering

Two new boolean Config flags SHALL control component triggering:
- `ape.testBroadcasts` (default: `false`) — enables broadcast triggering
- `ape.testServices` (default: `false`) — enables service triggering

#### Scenario: Default configuration
- **WHEN** `ape.properties` does not set `ape.testBroadcasts` or `ape.testServices`
- **THEN** both SHALL default to `false`
- **AND** APE-RV behavior SHALL be identical to current (no component triggering)

---

### Requirement: SystemBroadcastCatalog — typed extras for system broadcasts

APE-RV SHALL embed a catalog of 187 system broadcast actions with typed extras (sourced from VLM-Fuzz's `system-broadcast.json`). The catalog SHALL be loaded as a JSON resource and provide lookup by action string.

For each catalog entry, the `adb` command field SHALL be parsed to extract extras with their types:
- `--es key value` → String extra
- `--ei key value` → int extra
- `--ez key value` → boolean extra
- `--el key value` → long extra
- `--ef key value` → float extra

When constructing an `EVENT_BROADCAST` intent, if the action matches a catalog entry, the typed extras SHALL be added to the Intent via `intent.putExtra()`.

#### Scenario: Catalog lookup for known system broadcast
- **WHEN** `SystemBroadcastCatalog.lookup("android.net.conn.CONNECTIVITY_CHANGE")` is called
- **THEN** it SHALL return a list containing at least one extra: `{key: "android.net.conn.CONNECTIVITY_CHANGE_SAMEEN", type: "boolean", value: "true"}`

#### Scenario: Catalog lookup for unknown action
- **WHEN** `SystemBroadcastCatalog.lookup("com.example.CUSTOM_ACTION")` is called
- **THEN** it SHALL return an empty list

#### Scenario: Catalog lookup for action with no extras
- **WHEN** `SystemBroadcastCatalog.lookup("android.intent.action.BOOT_COMPLETED")` is called
- **AND** the catalog entry has no extras in its adb command
- **THEN** it SHALL return an empty list
