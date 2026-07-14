# foreign-activity-guard

## Why

The cmpft2 trace audit (`rvsec/rv-android/docs/20260707_verificacao_mecanismos_cmpft2.md` §8) found exploration leaking out of the app under test: foreign activities such as the Pixel launcher (`NexusLauncherActivity`, 14 discovered widgets) and installer session dialogs (`SessionBasedInstallConfirmationActivity`) end up modeled, tracked by UICOV, and interacted with — burning steps outside the target package (up to 12.4% of discovered widget-actions sit in out-of-package activities, prefix-heuristic upper bound).

Two gaps let this happen. (1) The system-level `IActivityController` hook (`Monkey.java:274-417`) that should veto foreign launches is best-effort and partially ignored on modern Android. (2) The APE-side backstop `checkAppActivity` (`MonkeySourceApe.java:1184-1233`) and `generateEvents` (`:778-827`) fetch the top activity **independently**: a screen that turns foreign between the two fetches (the click's delayed effect) reaches `mAgent.updateState(topComp, info)` (`:798`) with no package check at all, and the foreign screen enters the model. When `checkAppActivity` does catch it, the reaction is a heavyweight `startRandomMainApp()` restart — not a light BACK.

## What Changes

- Add a foreign-activity guard inside `generateEvents` (`MonkeySourceApe.java`, between the `info != null` check and `updateState`): when the top activity's package (`topComp.getPackageName()` — the task's real applicationId, robust to apps whose activity classes live in a different namespace) is not accepted by `MonkeyUtils.getPackageFilter()` and is not a system-interaction package, the source SHALL enqueue a single BACK (`generateKeyBackEvent`) and skip `updateState` entirely — no `State`, no `GUITree`, no UICOV registration for the foreign screen.
- System-interaction whitelist (guard is a no-op for them — not backed out during the guard's window; `checkAppActivity`, untouched by this change, may still restart over them on the next cycle): `com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`. The Google entry is required because the RVSec AVD runs a Google emulator image, whose runtime-permission UI is the `com.google.android.permissioncontroller` package — the AOSP entry alone would never match it. These entries affect **only** the guard's modeling decision in `generateEvents`; they do NOT grant permissions and do NOT change `checkAppActivity` or Monkey's auto-grant (`Monkey.java:404-414`), which still special-cases only the legacy `com.android.packageinstaller`/`GrantPermissionsActivity` dialog. `com.android.systemui` is deliberately excluded: `checkAppActivity` already treats it as invalid and restarts on it, so whitelisting it here would only re-admit the shade/recents surface into the model — the exact leak this change targets.
- Extract the decision into a pure static method (`shouldModel(topComp, packageFilterAccepts, systemWhitelist)`), unit-testable on the JVM (the current package-check paths have zero test coverage).
- Delete the dead `checkPackage` method (`MonkeySourceApe.java:910-922`, no callers — P3).
- Rollback knob: `Config.foreignActivityGuard` (`ape.foreignActivityGuard`, default true; false restores the current leak-and-restart behavior) for arm-to-arm fidelity comparisons.
- Telemetry: `[APE-RV] Foreign activity: pkg=<pkg> -> BACK` (throttled to once per package per run) so validation runs can count deflections.
- Out of scope (deferred): extending whitelist semantics into `checkAppActivity` so whitelisted packages are durably modeled instead of restarted over on the next cycle — that belongs to a separate change that owns `checkAppActivity`. This change's whitelist is guard-local only.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `exploration`: add the foreign-activity guard requirement to the event-generation/state-capture contract — foreign screens are deflected with BACK and never modeled; system-interaction packages are exempt.

## Impact

- **Code**: `MonkeySourceApe.java` (guard + pure decision seam + dead-code deletion), `Config.java` (one flag). Agents, model, naming, UICoverageTracker untouched — the guard prevents the call, it does not filter inside the tracker.
- **Behavior**: foreign screens get BACK instead of being modeled; the existing `checkAppActivity` restart path remains as the fallback when BACK does not recover. Launcher/installer states disappear from models and UICOV rollups.
- **Telemetry**: one new throttled trace line; UICOV-ACT rollups stop listing foreign activities (expected diff vs cmpft2 in validation).
- **Tests**: pure seam unit tests (first coverage for the package-check decision); device smoke on an app that leaks (e.g. one of the cmpft2 launcher-leak traces).
- **Archive ordering**: standalone against the main `exploration` spec; no dependency on unarchived deltas.
- **Risk**: over-deflection of legitimate cross-package flows (share sheets, file pickers) — mitigated by the whitelist, the once-per-package BACK (the next cycle falls back to `checkAppActivity`'s existing handling if the screen persists), and the rollback knob.
