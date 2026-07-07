# Tasks — tree-package-guard

> Implement **after** `foreign-activity-guard` (shares its `SYSTEM_INTERACTION_PACKAGES` set and edits the same `generateEvents` loop). See design "Composition".

## 1. Config and pure seam

- [x] 1.1 Add `Config.treePackageGuard` (`ape.treePackageGuard`, default true) with a current-state comment (P4)
- [x] 1.2 Add the pure static `shouldRefetch(String topPkg, String treePkg, Set<String> systemWhitelist)` to a new **dependency-free `com.android.commands.monkey.ape.TreePackageGuard`** class (mirroring `foreign-activity-guard`'s `ForeignActivityGuard` — the seam cannot live in `MonkeySourceApe`, which is not JVM-class-loadable off-device; `generateEvents` delegates to it): returns true (refetch) only when `treePkg != null && !treePkg.equals(topPkg) && !systemWhitelist.contains(treePkg)`; false (proceed) for matching packages, null `treePkg`, or a whitelisted tree owner. Reuse `foreign-activity-guard`'s `ForeignActivityGuard.SYSTEM_INTERACTION_PACKAGES` — do not redefine it.

## 2. Guard wiring (MonkeySourceApe)

- [x] 2.1 Insert the guard block in `generateEvents` **inside the existing `while (repeat-- > 0)` loop**, after the `if (info != null)` block and after `foreign-activity-guard`'s `topComp` check, immediately before `mAgent.updateState(topComp, info)` (`:798`): compute `topPkg = topComp.getPackageName()` and `treePkg = info.getPackageName()` (CharSequence → String, null-safe); when `shouldRefetch` is true and retries remain (`repeat > 0`) `continue` (re-fetch on the next iteration); when `shouldRefetch` is true and `repeat == 0` fall through and model the pair (fail-open). `topComp == null` bypasses the guard.
- [x] 2.2 Add the once-per-pair log throttle (`Set<String> mismatchPairs`, keyed `topPkg + "->" + treePkg`) emitting `[APE-RV] Tree/package mismatch: top=<pkg> tree=<pkg> -> refetch` on the first deflection of each distinct pair only
- [x] 2.3 Update the `generateEvents` comment at `:792` to note the guard now cross-checks the two independent fetches (P4)

## 3. Unit tests (JVM, pure seam)

- [x] 3.1 `shouldRefetch` matrix: matching packages → false; foreign tree (`com.google.android.apps.nexuslauncher` under an in-package `topComp`) → true; each of the three whitelist packages as tree owner → false; null `treePkg` → false (INV-EXPL-26)
- [x] 3.2 Log-throttle semantics: first mismatch of a `top→tree` pair signals log, repeat does not; a different pair signals again (plain `Set` contract test alongside the seam)
- [x] 3.3 Run the new test class via `mvn test -Dtest=MonkeySourceApeTreeGuardTest`
- [x] 3.4 Flag-off assertion (INV-EXPL-28): with `Config.treePackageGuard=false` the guard block is bypassed — no refetch `continue`, no guard log line; event generation is identical to the pre-guard path (the flag gates the whole block, `shouldRefetch` itself has no flag)
- [x] 3.5 Fail-open assertion (INV-EXPL-27): a persistent mismatch reaching the last loop iteration (`repeat == 0`) falls through to `updateState` — the pair is modeled, the guard does not `continue` past loop exhaustion (reasoning over the `repeat > 0` guard, not a seam-matrix case)

## 4. Verification

- [x] 4.1 Full suite: `mvn test` (0 failures/errors)
- [x] 4.2 `openspec validate tree-package-guard --strict`
- [ ] 4.3 Device smoke — **deferred to cmpft3** (`rvsec/rv-android/docs/20260707_cmpft3.md` closes this): on a cmpft2 launcher-leak app (e.g. `org.fossify.messages`, 101–103 restarts in cmpft2), the trace shows `Tree/package mismatch: ... -> refetch`, no launcher package appears in the captured GUITrees, and the relaunch-cycle count drops versus the cmpft2 trace of the same APK. No interactive emulator run required in this change.
