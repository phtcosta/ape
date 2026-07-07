# exploration — delta: foreign-activity-guard

## Purpose

Keep the exploration budget inside the app under test. Today a screen that turns foreign between `checkAppActivity`'s validation and `generateEvents`' own top-activity fetch reaches `mAgent.updateState` unchecked: the foreign screen (launcher, installer session dialog) is abstracted into a `State`, registered in UICOV, and interacted with — the cmpft2 audit measured up to 12.4% of discovered widget-actions sitting in out-of-package activities. The only existing APE-side reaction (`checkAppActivity`) is a heavyweight app restart, and the system-level `IActivityController` veto is best-effort on modern Android.

This delta closes the modeling boundary: before `updateState`, the event source checks the top task's real applicationId (`ComponentName.getPackageName()` — robust to apps whose activity classes live in a different namespace than the applicationId) against the Monkey package filter plus a fixed system-interaction whitelist; a foreign screen gets one BACK and is never modeled. Permission dialogs and other system-interaction surfaces are not deflected **by the guard** — the guard is a no-op for them — but this is guard-local and best-effort only: the whitelist does not grant permissions and does not change `checkAppActivity`, which (untouched here) may still restart over those packages on the next cycle. The existing `checkAppActivity` wait/restart ladder remains the fallback when BACK does not recover.

## Data Contracts

### Input
- `ape.foreignActivityGuard: boolean` — default true; false restores the pre-guard behavior (foreign screens modeled, restart-only recovery).

### Side-Effects
- **[Trace]**: `[APE-RV] Foreign activity: pkg=<pkg> -> BACK` — once per foreign package per run (throttled), on the first deflection.

## ADDED Requirements

### Requirement: Foreign-Activity Guard in Event Generation

When `ape.foreignActivityGuard` is true and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldModel(pkg, filterAccepts, systemWhitelist)` before calling `mAgent.updateState`, where `pkg` is `topComp.getPackageName()`, `filterAccepts` is `MonkeyUtils.getPackageFilter().isPackageValid(pkg)` (the same predicate the active backstop `checkAppActivity` gates on), and `systemWhitelist` is the fixed set {`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`} (the Google-image package is required because the RVSec AVD runs a Google emulator image whose runtime-permission UI is `com.google.android.permissioncontroller`; the AOSP entry alone would never match). `com.android.systemui` is NOT whitelisted — `checkAppActivity` already treats it as invalid and restarts on it, so the guard SHALL BACK out of it. When the decision is negative, the source SHALL enqueue exactly one BACK key event plus the standard throttle and return without invoking `updateState` — the foreign screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT contribute actions.

The decision SHALL be based exclusively on the component's package name (the task's applicationId), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing START/ACTIVATE handling proceeds). A null package SHALL be treated as modelable (uncheckable — defer to the existing paths). With `ape.foreignActivityGuard` false, event generation SHALL be identical to the pre-guard specification.

The first deflection of each distinct foreign package SHALL log `[APE-RV] Foreign activity: pkg=<pkg> -> BACK`; subsequent deflections of the same package SHALL NOT log (throttle — a persistent foreign screen must not spam the trace). The dead `checkPackage(ComponentName, AccessibilityNodeInfo)` helper SHALL be deleted (P3 — it has no callers and the guard supersedes its intent).

- **INV-EXPL-20**: No `State` or UI-coverage registration SHALL ever originate from a screen whose package fails the guard decision.
- **INV-EXPL-21**: Screens of the system-interaction whitelist packages SHALL NOT be backed out by the guard (the guard SHALL be a no-op for them). This is a guard-local guarantee only: it does not assert durable in-package modeling — `checkAppActivity`, which this change does not touch, may still restart over these packages on a later cycle.
- **INV-EXPL-22**: The `ape.foreignActivityGuard` flag SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no BACK deflections, no guard log lines). This is a reasoning invariant over the flag placement, not a pure-seam matrix case — `shouldModel` has no flag parameter.

#### Scenario: launcher screen deflected
- **WHEN** the app under test is `com.example.app`, a click lands the device on `com.google.android.apps.nexuslauncher/.NexusLauncherActivity`, and `generateEvents` runs with the guard enabled
- **THEN** one BACK key event SHALL be enqueued, `updateState` SHALL NOT be called, and (on first occurrence) the trace SHALL contain `[APE-RV] Foreign activity: pkg=com.google.android.apps.nexuslauncher -> BACK`

#### Scenario: permission dialog not deflected by the guard
- **WHEN** the top component's package is `com.google.android.permissioncontroller` or `com.android.permissioncontroller` (runtime permission dialog; the Google package is the one seen on the RVSec Google-image AVD)
- **THEN** the guard SHALL be a no-op for that cycle and `updateState` SHALL proceed normally (a guard-local no-op — this does not guarantee the screen survives `checkAppActivity` on a later cycle)

#### Scenario: divergent class namespace is not misflagged
- **WHEN** the app under test has applicationId `info.metadude.android.fosdem.schedule` and the top component is `info.metadude.android.fosdem.schedule/nerd.tuxmobil.fahrplan.congress.MainActivity`
- **THEN** the caller SHALL derive `pkg` from `topComp.getPackageName()` (the applicationId `info.metadude.android.fosdem.schedule`), never from the activity class prefix `nerd.tuxmobil.*`, so `filterAccepts` is true and the screen is modeled normally (a caller-level `getPackageName()` assertion, not a `shouldModel`-seam matrix case)

#### Scenario: guard disabled
- **WHEN** `ape.foreignActivityGuard=false` and a foreign screen reaches `generateEvents`
- **THEN** the screen SHALL be modeled exactly as before this change (leak-and-restart behavior), with no guard log line
