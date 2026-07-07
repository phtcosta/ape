# Design — tree-package-guard

## Context

`generateEvents` (`MonkeySourceApe.java:788-798`) captures the current screen inside a bounded refetch loop:

```
int repeat = refectchInfoCount;          // = 4
while (repeat-- > 0) {
    topComp = this.getTopActivityComponentName();   // :790  ActivityManager
    info = getRootInActiveWindow();                 // :791  accessibility bridge
    // this two operations may not be the same       // :792  (in-source comment)
    if (info == null) { sleep(...); continue; }
    if (info != null) {
        nullInfoCounter = 0;
        action = mAgent.updateState(topComp, info);  // :798  models the pair
        ...
        break;
    }
}
```

`topComp` and `info` come from two independent subsystems fetched on two consecutive lines. During an app relaunch the ActivityManager can already report the app's `MainActivity` while the accessibility tree is still the HOME launcher (`com.google.android.apps.nexuslauncher`) that has not yet been repainted. The pair reaches `updateState` unchecked and the launcher tree is abstracted as the app. The mismatch is unrecoverable downstream: `GUITree` records `activityPackageName` from `topComp` (`GUITree.java:81`), so the tree's true owner is discarded at build time.

`AccessibilityNodeInfo.getPackageName()` returns the package that owns the node — already in memory, no extra IPC. That is the missing cross-check.

## Architecture

One guard, one seam, one flag — mirroring `foreign-activity-guard`. The guard sits in the **existing** refetch loop, between the `info != null` check and `updateState`; the decision is a pure static; the whitelist is `foreign-activity-guard`'s existing `SYSTEM_INTERACTION_PACKAGES` set (shared, not duplicated).

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `Config.treePackageGuard` | flag `ape.treePackageGuard` (default true) | properties | boolean |
| `SYSTEM_INTERACTION_PACKAGES` | reused from `foreign-activity-guard` (not redefined) | — | permission/installer packages that may own a tree over the app |
| `static boolean shouldRefetch(String topPkg, String treePkg, Set<String> systemWhitelist)` | pure guard decision | top package + tree package + whitelist | boolean |
| guard block in `generateEvents` | on mismatch with retries left → `continue`; on exhaustion → fall through (model) | `topComp`, `info`, remaining `repeat` | re-fetch or normal flow |

## Composition with `foreign-activity-guard`

Both changes edit the same `generateEvents` loop and both run before `updateState`. Required order and layering:

1. **Implement `foreign-activity-guard` first.** It introduces `SYSTEM_INTERACTION_PACKAGES` and the foreign-`topComp` check. This change **reuses** that set — do not redefine it.
2. **This guard sits after the foreign guard's `topComp` check**, still before `updateState`. Rationale: the foreign guard already handles "`topComp` is foreign" (it BACKs out and returns). Once control reaches this guard, `topComp` is known in-package (or whitelisted/null); the only remaining question is whether the **tree** matches it. Ordering the tree check second means it never fires for a screen the foreign guard already deflected.
3. Both guards read `Config` independently; either can be toggled off without the other.

If `foreign-activity-guard` is not yet merged when this is implemented, the whitelist set must be introduced by whichever change lands first; the design assumes `foreign-activity-guard` lands first (its own "Archive ordering" is standalone against the main spec).

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| INV-EXPL-26 (mismatched pair not modeled while retries remain) | `shouldRefetch` true + `repeat > 0` → `continue` | `shouldRefetch` seam matrix + device smoke |
| INV-EXPL-27 (fail-open after exhaustion) | `shouldRefetch` true + `repeat == 0` → fall through to `updateState` | reasoning invariant (loop-exit case) + exhaustion task |
| INV-EXPL-28 (flag off = pre-guard behavior) | flag check wraps the whole guard block | reasoning invariant + flag-off task |
| whitelist exemption | `systemWhitelist.contains(treePkg)` inside `shouldRefetch` | whitelist matrix test |
| throttled log | once-per-`top→tree`-pair `Set<String>` | log-throttle test |

## Goals / Non-Goals

**Goals:** a `(topComp, tree)` pair whose tree is foreign is re-fetched within the existing loop, not modeled; transient relaunch frames are absorbed; a persistent mismatch still models (fail-open, no deadlock); permission-dialog trees over the app are exempt; first JVM coverage for this decision.

**Non-Goals:** touching `foreign-activity-guard`'s own topComp check (this change layers after it); adding a new retry loop or restart (the existing loop is reused); filtering inside `GUITree`/`UICoverageTracker` (the guard prevents the call); resolving genuinely mixed surfaces beyond the fail-open fall-through.

## Decisions

1. **Guard in the existing refetch loop, not a new one.** `generateEvents` already re-fetches `topComp`+`info` up to `refectchInfoCount` (4) times when `info == null`. A tree/package mismatch is the same class of transient — the relaunch has not settled — so it belongs on the same loop. `continue` re-runs both fetches; no new machinery.
2. **Fail-open after exhaustion.** On the last iteration (`repeat == 0`) a persisting mismatch falls through to `updateState` and is modeled as today. Refetching forever would deadlock capture on any legitimately mixed surface (e.g. an overlay from another package). The guard is an opportunistic absorber of transient frames, not a hard gate. This is the deliberate difference from `foreign-activity-guard`, which BACKs out (its foreign screen is never legitimate); a tree mismatch **can** be legitimate, so it must fail open.
3. **Tree identity = `info.getPackageName()`.** The accessibility root carries its owning package. Compare against `topComp.getPackageName()` (the task applicationId, same source `foreign-activity-guard` uses). A null `treePkg` is uncheckable → treat as match (do not refetch), deferring to existing paths. A null `topComp` bypasses the guard (mirrors `checkAppActivity:1186-1190` and the foreign guard).
4. **Whitelist exemption shares `foreign-activity-guard`'s set.** A runtime-permission dialog (`com.android.permissioncontroller` / `com.google.android.permissioncontroller`, or the legacy `com.android.packageinstaller`) legitimately owns the tree while `topComp` still reports the app. Treating that as a mismatch would refetch away a real interaction surface. Reusing the same set keeps the two guards coherent: a package the foreign guard would not BACK out of is also not a tree mismatch here.
5. **Pure seam `shouldRefetch(topPkg, treePkg, whitelist)`.** `AccessibilityNodeInfo` and ActivityManager are runtime-only; passing the two package strings keeps the decision JVM-testable (zero tests today). Mirrors `foreign-activity-guard`'s `shouldModel` seam so the two are reviewed the same way.
6. **Log throttled once per `top→tree` pair per run.** A persistent mismatch would otherwise spam the trace on every one of the (up to 4) refetch iterations, every step.

## API Design

### `static boolean shouldRefetch(String topPkg, String treePkg, Set<String> systemWhitelist)`
Returns `true` (the pair is a foreign-tree mismatch, re-fetch) when **all** hold: `treePkg != null`, `!treePkg.equals(topPkg)`, and `!systemWhitelist.contains(treePkg)`. Returns `false` (proceed to model) otherwise — matching packages, a null `treePkg` (uncheckable), or a whitelisted tree owner. Pure, no I/O. (`topPkg` null is a caller concern: the guard block only runs when `topComp != null`.)

### Guard block (inside the existing `while (repeat-- > 0)` loop)
Sits immediately after `if (info != null) { nullInfoCounter = 0; ... }` and before `mAgent.updateState(topComp, info)` (`:798`), after `foreign-activity-guard`'s `topComp` check. `repeat` has already been decremented by the `while` test, so `repeat > 0` means "at least one more iteration is available".
```
if (Config.treePackageGuard && topComp != null) {
    String topPkg = topComp.getPackageName();
    CharSequence tp = info.getPackageName();
    String treePkg = tp == null ? null : tp.toString();
    if (shouldRefetch(topPkg, treePkg, SYSTEM_INTERACTION_PACKAGES)) {
        if (mismatchPairs.add(topPkg + "->" + treePkg)) {
            Logger.iformat("[APE-RV] Tree/package mismatch: top=%s tree=%s -> refetch", topPkg, treePkg);
        }
        if (repeat > 0) {
            continue;          // re-fetch topComp+info on the next loop iteration
        }
        // retries exhausted -> fail-open: fall through and model the pair as today
    }
}
action = mAgent.updateState(topComp, info);
```

## Data Flow

`topComp` (fresh at `:790`) + `info.getPackageName()` (fresh at `:791`) → `shouldRefetch`. Match / whitelisted / uncheckable → `updateState` as today. Mismatch with retries left → `continue` (re-fetch). Mismatch with retries spent → fall through to `updateState` (fail-open). A deflected transient frame therefore never reaches `buildAndValidateNewState`, `registerScreenElements`, or `GUITree` construction.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `treePkg == null` | accessibility root without a package | uncheckable → treat as match, do not refetch | existing paths model normally |
| `topComp == null` | transient/no foreground task | guard skipped | existing null-info / START handling |
| persistent mismatch (mixed surface) | non-transient overlay | refetch up to `refectchInfoCount`, then fail-open | pair modeled as today |

## Risks / Trade-offs

- [Over-refetching a legitimate mixed surface] → bounded by `refectchInfoCount = 4` and fail-open; worst case is up to 3 extra `getRootInActiveWindow` calls then the pair models anyway. Rollback knob restores old behavior.
- [Whitelist incomplete for some Android build] → a permission dialog on an OEM build with a different package would be refetched, then modeled on exhaustion (fail-open) — degraded, not broken; extend the shared set if a validation run shows it.
- [Interaction with `foreign-activity-guard`] → the two guards must be layered (foreign first, tree second, both before `updateState`); mis-ordering could double-check `topComp`. Encoded in "Composition" and the tasks.

## Testing Strategy

| Layer | What to test | How |
|-------|-------------|-----|
| Unit | `shouldRefetch` matrix: matching pkgs → false; foreign tree → true; each whitelisted tree owner → false; null `treePkg` → false; log-throttle set semantics | plain JUnit on the pure seam |
| Reasoning | fail-open on `repeat == 0`; flag-off bypass | invariants over the loop/flag placement (not seam-matrix cases) |
| Device smoke | on a cmpft2 launcher-leak app (e.g. `org.fossify.messages`): trace shows `Tree/package mismatch: ... -> refetch`, no launcher package in the GUITrees, fewer relaunch cycles | deferred to cmpft3 (`rvsec/rv-android/docs/20260707_cmpft3.md`) |

## Open Questions

- None. The whitelist is inherited from `foreign-activity-guard`; the flag default and fail-open policy are settled above.
