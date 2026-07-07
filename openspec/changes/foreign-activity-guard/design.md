# Design — foreign-activity-guard

## Context

Foreign screens (launcher, installer sessions) enter the APE model because `generateEvents` (`MonkeySourceApe.java:778-827`) calls `mAgent.updateState(topComp, info)` (`:798`) without any package check, and its `topComp` fetch (`:789`) is independent of the one `checkAppActivity` (`:1184-1233`) validated at the top of `getNextEvent` — a TOCTOU window that a click's delayed transition exploits. The system-level `IActivityController` veto (`Monkey.java:274-417`) is best-effort on modern Android. When `checkAppActivity` does catch a foreign package, its reaction is `startRandomMainApp()` (`:1229-1231`) — a full app restart that destroys in-app navigation state; `onActivityBlocked` is a no-op in every agent.

Existing pieces to reuse or remove: `MonkeyUtils.getPackageFilter()` (`MonkeyUtils.java:51-112`, exact-match valid-package set from `-p`), `generateKeyBackEvent()` (`MonkeySourceApe.java:429`), and the dead `checkPackage` (`:910-922`, no callers).

## Architecture

One guard, one seam, one flag. The guard sits in `generateEvents` between the `info != null` check and `updateState`; the decision logic is a pure static; the whitelist is a static set.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.foreignActivityGuard` | flag `ape.foreignActivityGuard` (default true) | properties | boolean |
| `SYSTEM_INTERACTION_PACKAGES` (static set) | packages modeled despite being foreign | — | `com.android.packageinstaller`, `com.android.permissioncontroller` |
| `static boolean shouldModel(String pkg, boolean filterAccepts, Set<String> systemWhitelist)` | pure guard decision | package name + filter verdict | boolean |
| guard block in `generateEvents` | deflect with BACK, skip modeling | `topComp` | BACK event or normal flow |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-EXPL-20 (foreign screen never modeled) | guard before `updateState` | `shouldModel` seam tests + device smoke |
| INV-EXPL-21 (whitelist exempt) | set membership check in `shouldModel` | whitelist matrix test |
| INV-EXPL-22 (guard disabled = current behavior) | flag check wraps the whole guard block | reasoning invariant (the flag gates the block; `shouldModel` has no flag, so this is not a seam-matrix case) + flag-off task 3.4 |
| throttled log | once-per-package `Set<String>` | log-throttle test |

## Goals / Non-Goals

**Goals:** foreign screens deflected with one light BACK and excluded from model/UICOV; permission dialogs still reachable; first JVM test coverage for the package decision; dead `checkPackage` deleted.

**Non-Goals:** replacing `checkAppActivity` (it remains the restart-level fallback); filtering inside `UICoverageTracker` (the guard prevents the call — filtering the tracker would hide the symptom and leave the model polluted); handling IME (not a top activity); credential/login flows (explicitly out of scope for this cycle).

## Decisions

1. **Guard placement in `generateEvents`, not `checkAppActivity`.** The leak is the unchecked `updateState` call; `checkAppActivity` already handles its own snapshot. Guarding at the modeling boundary closes the TOCTOU window regardless of how the foreign screen appeared.
2. **Package identity = `topComp.getPackageName()`.** `RunningTaskInfo.topActivity` carries the task's applicationId — correct for apps whose activity classes live in another namespace (e.g. `info.metadude.*` APKs with `nerd.tuxmobil.*` classes, which a class-prefix heuristic would misflag). Never use `getTopActivityPackageName()` (falls back to `Monkey.currentPackage` when the component is null). `topComp == null` → proceed (existing START/ACTIVATE handling), mirroring `checkAppActivity:1186-1190`.
3. **BACK + skip, not restart.** One `generateKeyBackEvent()` and return without modeling; if the foreign screen survives the BACK, the next `getNextEvent` iteration's `checkAppActivity` applies the existing wait/restart ladder. The guard adds a cheap first rung, it does not replace the ladder.
4. **Whitelist is hardcoded, not a flag.** The two system-interaction packages are Android-version facts, not experiment parameters (P1 — no gratuitous flags). Adding `com.android.permissioncontroller` also fixes the pre-existing gap where only the legacy installer package was special-cased for permission dialogs. `com.android.systemui` is deliberately **not** whitelisted: `checkAppActivity` (`MonkeySourceApe.java:1229-1231`) already classifies it as invalid and restarts on it (unlike `packageinstaller`/`permissioncontroller`, which carry an interactive grant rationale), so whitelisting it in the guard would only re-admit the shade/recents surface into the model — the guard should BACK out of it to keep the budget in-app.
5. **Pure seam `shouldModel(pkg, filterAccepts, whitelist)`.** `PackageFilter` and the ActivityManager are runtime-only; passing the filter verdict as a boolean keeps the seam JVM-testable (this area currently has zero tests).
6. **Delete `checkPackage` (:910-922).** Dead since its callers were removed; the guard supersedes its intent (P3 — no dead code).
7. **Log throttled once per package per run.** A persistent foreign screen would otherwise spam the trace on every deflection attempt.

## API Design

### `static boolean shouldModel(String pkg, boolean filterAccepts, Set<String> systemWhitelist)`
- Returns `true` when `filterAccepts` is true OR `pkg` is in `systemWhitelist`; `false` otherwise. Null `pkg` → `true` (uncheckable, defer to existing paths). Pure, no I/O.

### Guard block (inside `generateEvents`)
The block sits **inside** the `while (repeat-- > 0)` refetch loop (`MonkeySourceApe.java:788`), immediately after the `if (info != null)` check (`:794`) — where `topComp` (fetched at `:789`) is fresh — and before the `mAgent.updateState(topComp, info)` call (`:797`). The predicate mirrors the active backstop: `checkAppActivity` (`:1192`) gates on `MonkeyUtils.getPackageFilter().isPackageValid(pkg)`, so the guard uses the same call (identical to `checkEnteringPackage` under standard `-p <pkg>` config; the two diverge only when `validPackages` is empty).
```
if (Config.foreignActivityGuard && topComp != null) {
    String pkg = topComp.getPackageName();
    boolean accepts = MonkeyUtils.getPackageFilter().isPackageValid(pkg);
    if (!shouldModel(pkg, accepts, SYSTEM_INTERACTION_PACKAGES)) {
        if (deflectedPackages.add(pkg)) {
            Logger.iformat("[APE-RV] Foreign activity: pkg=%s -> BACK", pkg);
        }
        generateKeyBackEvent();
        generateThrottleEvent(mThrottle);
        return;   // no updateState, no modeling
    }
}
```

## Data Flow

`topComp` (fresh fetch at `:789`) → `shouldModel` → either normal `updateState` flow (in-package or whitelisted) or BACK-and-skip (foreign). Foreign screens therefore never reach `buildAndValidateNewState`, `registerScreenElements`, or the budget tracker.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `topComp == null` | transient/no foreground task | guard skipped, existing null handling proceeds | `checkAppActivity` next cycle |
| BACK does not leave the foreign screen | locked task / installer flow | guard fires once (log throttle), `checkAppActivity` wait/restart ladder takes over | existing restart path |

## Risks / Trade-offs

- [Legitimate cross-package flow interrupted (share sheet, document picker)] → those flows were already unusable (the agent cannot complete them meaningfully) and today they end in the heavier `startRandomMainApp`; the BACK is strictly cheaper. Rollback knob restores old behavior.
- [Whitelist incomplete for some Android build (OEM permission UI)] → symptom is a BACK on a permission dialog; the auto-grant at launch (`Monkey.java:404-414`) already covers the standard flow; extend the set if a validation run shows it.
- [BACK loop if system screen re-appears instantly] → one BACK per cycle plus the existing wait/restart ladder bounds it; no tight loop is possible inside a single `generateEvents` call.

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit | `shouldModel` matrix: in-package, foreign, each whitelist entry, null pkg; log-throttle set semantics | plain JUnit on the pure seam |
| Device smoke | on a cmpft2 launcher-leak app: `Foreign activity: ... -> BACK` line present, NO foreign activity in the UICOV-ACT rollup, no restart storm | future validation run |

## Open Questions

- None. The whitelist contents and the flag default are settled above; extension happens only on validation evidence.
