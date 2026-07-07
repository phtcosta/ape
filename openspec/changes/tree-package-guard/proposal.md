# tree-package-guard

## Why

The cmpft2 trace audit (`rvsec/rv-android/docs/20260707_verificacao_mecanismos_cmpft2.md`) found APE modeling accessibility trees that do not belong to the app under test. `generateEvents` (`MonkeySourceApe.java:788-798`) fetches the top activity and the accessibility root from **two different subsystems** in two consecutive lines — `topComp = getTopActivityComponentName()` from ActivityManager (`:790`) and `info = getRootInActiveWindow()` from the accessibility bridge (`:791`) — and the in-source comment at `:792` already acknowledges "this two operations may not be the same". The possibly-mismatched pair reaches `mAgent.updateState(topComp, info)` (`:798`) with no cross-check: when `topComp` reports the app's `MainActivity` but the accessibility tree is actually the HOME launcher (the app's relaunch has not yet painted), APE abstracts the launcher tree as if it were the app, then clicks launcher widgets.

The damage is measured over cmpft2 (657 runs, 900 s each): 10,001 app-relaunch cycles total, 41.7% of runs with ≥10 restarts; the extreme case `org.fossify.messages` shows 101–103 restarts with only 4–5 modeled steps per run — APE keeps capturing `MainActivity` as `topComp` while the tree is `com.google.android.apps.nexuslauncher`, clicks a launcher widget, and bounces straight back out. Launcher widgets leak into GUITrees in 113/657 runs.

The mismatch is invisible after the tree is built: `GUITree` stores `activityPackageName` from `topComp` at construction (`GUITree.java:81`), not from the tree's own `getPackageName()`, so nothing downstream can tell the tree was foreign. It must be caught **before** `updateState`.

This is the complement of the open `foreign-activity-guard` change, which covers the case where `topComp` **itself** is foreign. This change covers the case where `topComp` is in-package but the **tree** is foreign or stale.

## What Changes

- Add a tree/package guard inside `generateEvents` (`MonkeySourceApe.java`), after the freshly fetched `topComp`/`info` pass the `info != null` check and **before** `mAgent.updateState(topComp, info)` (`:798`): when the accessibility root's owning package (`info.getPackageName()`) differs from the top activity's package (`topComp.getPackageName()`), the pair SHALL NOT be modeled on that iteration.
- Reuse the **existing** `while (repeat-- > 0)` refetch loop (`refectchInfoCount = 4`) already present at `:788`: on a mismatch with retries remaining, the source SHALL `continue` — re-fetch both `topComp` and `info` on the next loop iteration. No new retry machinery, no restart, no `startRandomMainApp`.
- **Fail-open** on exhaustion: if the retries are spent and the mismatch persists, the source SHALL fall through and model the pair exactly as today. The guard targets transient mismatch frames (the relaunch not-yet-painted window); it MUST NOT deadlock capture on a genuinely mixed surface.
- **Whitelist exemption** (keep the two guards coherent): a tree owned by one of `foreign-activity-guard`'s `SYSTEM_INTERACTION_PACKAGES` (`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`) SHALL NOT be treated as a mismatch — a runtime-permission dialog legitimately owns the tree while `topComp` still reports the app, and backing that out would defeat the permission flow. `info.getPackageName()` costs zero extra IPC (the node is already in hand).
- Extract the decision into a pure static (`shouldRefetch(topPkg, treePkg, systemWhitelist)`), unit-testable on the JVM (this path has zero test coverage), mirroring `foreign-activity-guard`'s `shouldModel` seam.
- Rollback knob: `Config.treePackageGuard` (`ape.treePackageGuard`, default true; false restores the current unchecked pairing) for arm-to-arm fidelity comparisons.
- Telemetry: `[APE-RV] Tree/package mismatch: top=<pkg> tree=<pkg> -> refetch` (throttled to once per `top→tree` package pair per run).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `exploration`: add the tree/package guard requirement to the event-generation/state-capture contract — a `(topComp, tree)` pair whose tree is owned by a foreign package is re-fetched within the existing loop rather than modeled; on retry exhaustion the guard fails open; system-interaction packages that own the tree are exempt.

## Impact

- **Code**: `MonkeySourceApe.java` (guard + pure decision seam, inside the existing refetch loop), `Config.java` (one flag). Agents, model, naming, `GUITree`, `UICoverageTracker` untouched — the guard prevents the mismatched `updateState` call, it does not filter anything downstream.
- **Behavior**: transient relaunch frames where the tree is still the launcher get re-fetched (up to `refectchInfoCount` times) instead of modeled; a persistent mismatch is still modeled (fail-open). Launcher widgets stop leaking into GUITrees.
- **Telemetry**: one new throttled trace line per `top→tree` pair.
- **Tests**: pure-seam unit tests (first coverage for this decision); device smoke deferred to cmpft3.
- **Composition / merge order**: this change edits the **same** `generateEvents` region as the open `foreign-activity-guard` change, and **depends on** its `SYSTEM_INTERACTION_PACKAGES` set. It MUST be implemented **after** `foreign-activity-guard`; the tree guard sits **after** the foreign guard's `topComp` package check, both before `updateState` (`:798`). See design "Composition" for the exact ordering.
- **Arm neutrality**: identical code in all experiment arms, but it alters the state graph (foreign trees no longer enter the model), so it MUST be introduced uniformly and validated by the cmpft3 run before any comparative conclusion.
- **Archive ordering**: depends on `foreign-activity-guard` (shares the whitelist set and the guard region); do not archive ahead of it.
- **Risk**: over-refetching a genuinely mixed surface (e.g. an in-app overlay rendered by another package) — bounded by `refectchInfoCount = 4` and the fail-open fall-through, plus the whitelist and the rollback knob.
