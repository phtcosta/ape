# exploration — delta: tree-package-guard

## Purpose

Keep the modeled screen consistent with the app under test. `generateEvents` fetches the top activity (`getTopActivityComponentName`, from ActivityManager) and the accessibility root (`getRootInActiveWindow`, from the accessibility bridge) on two consecutive lines from two independent subsystems, and the in-source comment at `MonkeySourceApe.java:792` already notes the two "may not be the same". The pair reaches `mAgent.updateState(topComp, info)` (`:798`) with no cross-check: during an app relaunch, `topComp` can report the app's `MainActivity` while the accessibility tree is still the HOME launcher, and APE abstracts the launcher tree as the app, then clicks launcher widgets. The cmpft2 audit measured 10,001 relaunch cycles over 657 runs (41.7% with ≥10 restarts; `org.fossify.messages` at 101–103 restarts for 4–5 modeled steps), with launcher widgets leaking into GUITrees in 113/657 runs. The mismatch is invisible after capture because `GUITree` stores `activityPackageName` from `topComp` (`GUITree.java:81`), not from the tree.

This delta closes the modeling boundary: before `updateState`, the event source compares the accessibility root's owning package (`info.getPackageName()`, already in hand — no extra IPC) against the top activity's package. A mismatch is a transient relaunch frame; the source re-fetches both within the **existing** `while (repeat-- > 0)` refetch loop (`refectchInfoCount = 4`) rather than modeling the pair. If the retries are exhausted and the mismatch persists, the guard **fails open** and models the pair as today — it must not deadlock capture on a genuinely mixed surface. A tree owned by a system-interaction package (the whitelist introduced by `foreign-activity-guard`) is exempt: a permission dialog legitimately owns the tree while `topComp` reports the app.

This is the complement of `foreign-activity-guard`, which covers the case where `topComp` itself is foreign; here `topComp` is in-package but the tree is foreign or stale. This delta composes with that change: it is applied **after** the foreign-`topComp` check, both before `updateState`, and reuses its `SYSTEM_INTERACTION_PACKAGES` set.

## Data Contracts

### Input
- `ape.treePackageGuard: boolean` — default true; false restores the pre-guard behavior (the `(topComp, tree)` pair modeled unchecked).

### Side-Effects
- **[Trace]**: `[APE-RV] Tree/package mismatch: top=<pkg> tree=<pkg> -> refetch` — once per distinct `top→tree` package pair per run (throttled), on the first mismatch.

## ADDED Requirements

### Requirement: Tree/Package Guard in Event Generation

When `ape.treePackageGuard` is true and the freshly fetched top activity component is non-null, `generateEvents` SHALL evaluate the pure decision `shouldRefetch(topPkg, treePkg, systemWhitelist)` before calling `mAgent.updateState`, where `topPkg` is `topComp.getPackageName()`, `treePkg` is `info.getPackageName()` (the accessibility root's owning package, converted null-safely to a String), and `systemWhitelist` is the `SYSTEM_INTERACTION_PACKAGES` set defined by `foreign-activity-guard` ({`com.android.packageinstaller`, `com.android.permissioncontroller`, `com.google.android.permissioncontroller`}). `shouldRefetch` SHALL return true (mismatch) only when `treePkg` is non-null, differs from `topPkg`, and is not in the whitelist.

When `shouldRefetch` is true and at least one refetch iteration remains (`repeat > 0` inside the existing `while (repeat-- > 0)` loop), the source SHALL `continue` — re-fetching both `topComp` and `info` on the next loop iteration — and SHALL NOT invoke `updateState` on the current pair: the mismatched screen SHALL NOT be abstracted into a `State`, SHALL NOT be registered in UI-coverage tracking, and SHALL NOT be built into a `GUITree`. When `shouldRefetch` is true but the refetch iterations are exhausted (`repeat == 0`), the source SHALL fall through and model the pair exactly as today (fail-open) — the guard SHALL NOT deadlock capture on a persistent mismatch.

The decision SHALL be based exclusively on package names (`topComp.getPackageName()` and `info.getPackageName()`), never on activity class-name prefixes. A null top component SHALL bypass the guard (existing null / START handling proceeds). A null tree package SHALL be treated as a match (uncheckable — defer to the existing paths, model normally). A tree owned by a whitelist package SHALL NOT be treated as a mismatch (a permission dialog legitimately owning the tree over the app is modeled, not re-fetched). With `ape.treePackageGuard` false, event generation SHALL be identical to the pre-guard specification.

The first mismatch of each distinct `top→tree` package pair SHALL log `[APE-RV] Tree/package mismatch: top=<pkg> tree=<pkg> -> refetch`; subsequent mismatches of the same pair SHALL NOT log (throttle — a persistent mismatch across the refetch iterations must not spam the trace). The guard SHALL be inserted after `foreign-activity-guard`'s top-component foreign check and before `updateState`, and SHALL reuse that change's whitelist set rather than redefining it.

- **INV-EXPL-26**: While at least one refetch iteration remains, no `State`, `GUITree`, or UI-coverage registration SHALL originate from a `(topComp, tree)` pair whose tree package fails the guard decision (mismatched and not whitelisted).
- **INV-EXPL-27**: On refetch-iteration exhaustion the guard SHALL fail open — a still-mismatched pair SHALL be modeled exactly as the pre-guard path; the guard SHALL NOT prevent capture indefinitely. (Reasoning invariant over the loop-exhaustion branch, not a pure-seam matrix case — `shouldRefetch` does not see `repeat`.)
- **INV-EXPL-28**: The `ape.treePackageGuard` flag SHALL gate the entire guard block; with the flag false, event generation SHALL follow the pre-guard path (no refetch `continue`, no guard log lines). (Reasoning invariant over the flag placement — `shouldRefetch` has no flag parameter.)

#### Scenario: launcher tree under an in-package MainActivity is re-fetched
- **WHEN** the app under test is `com.example.app`, `topComp` reports `com.example.app/.MainActivity` but the accessibility root's `getPackageName()` is `com.google.android.apps.nexuslauncher` (a relaunch frame not yet painted), the guard is enabled, and at least one refetch iteration remains
- **THEN** `updateState` SHALL NOT be called on this pair, the loop SHALL `continue` to re-fetch both `topComp` and `info`, and (on first occurrence) the trace SHALL contain `[APE-RV] Tree/package mismatch: top=com.example.app tree=com.google.android.apps.nexuslauncher -> refetch`

#### Scenario: whitelisted permission dialog tree is not a mismatch
- **WHEN** `topComp` reports the app under test but the accessibility root's `getPackageName()` is `com.google.android.permissioncontroller` or `com.android.permissioncontroller` (a runtime-permission dialog legitimately owning the tree over the app)
- **THEN** `shouldRefetch` SHALL return false, the guard SHALL NOT re-fetch, and `updateState` SHALL model the pair normally

#### Scenario: persistent mismatch fails open on exhaustion
- **WHEN** the tree package differs from `topComp`'s package on every refetch iteration and the last iteration is reached (`repeat == 0`)
- **THEN** the guard SHALL NOT `continue` past loop exhaustion, and the pair SHALL be modeled via `updateState` exactly as the pre-guard behavior (fail-open — capture is not deadlocked)

#### Scenario: guard disabled
- **WHEN** `ape.treePackageGuard=false` and a `(topComp, tree)` pair with a foreign tree reaches `generateEvents`
- **THEN** the pair SHALL be modeled exactly as before this change (unchecked pairing), with no refetch `continue` and no guard log line
