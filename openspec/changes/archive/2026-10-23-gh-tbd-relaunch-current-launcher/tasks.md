<!-- Subagent dispatch hints:
     - Groups 1, 2, 3 and 4 are independent and run in parallel. Each touches only the files named in its heading.
       The contracts they share are fixed in design.md "API Design": `LauncherQuery.query(IPackageManager,
       List<String>) -> List<ComponentName>`, the `MonkeySourceApe` constructor parameter
       `Supplier<List<ComponentName>> launcherQuery` right after `List<ComponentName> MainApps`, and
       `MonkeySourceApe.RelaunchTargets.of(RunSpec, List<T>, Supplier<List<T>>) -> List<T>`.
     - The tree compiles only when Groups 1 and 2 are both done (the constructor call in Monkey.java is Group 1's; the
       constructor itself is Group 2's). No group runs `mvn`; Group 5 compiles and tests.
     - Group 5 runs after all of them.
     - No group runs git commands. Commits are made by the orchestrator, by path, only when Pedro asks, with no
       co-author trailer. No push, no smoke, no emulator.
     - Critical path: {1, 2, 3, 4} -> 5. Paths are relative to src/main/java/com/android/commands/monkey/ (main) and
       src/test/java/com/android/commands/monkey/ (test). -->

## 1. Shared launcher query: `LauncherQuery.java` (new), `Monkey.java`

- [x] 1.1 New `LauncherQuery.java`: final class with a private constructor and `static List<ComponentName> query(IPackageManager pm, List<String> categories)`, which holds the loop now in `Monkey.getMainApps`: one `ACTION_MAIN` intent per category (category added when non-empty), `ApeAPIAdapter.queryIntentActivities(pm, intent, UserHandle.myUserId())`, filtered by `MonkeyUtils.getPackageFilter().checkEnteringPackage`. A `null` adapter result counts as no match; the declared `RemoteException` is caught and yields an empty list. It never returns null and prints nothing (design D2, API Design). Class javadoc: what a launcher component is and that startup and relaunch both call it
- [x] 1.2 `Monkey.getMainApps`: `mMainApps.addAll(LauncherQuery.query(mPm, mMainCategories))`, then the existing `** No activities found to run, monkey aborted.` check. Delete the old loop, its per-category warning, its `-v` lines and the `** Failed talking with package manager!` branch, and remove the imports left unused (`ResolveInfo`; `RemoteException` stays, used elsewhere) (design D2)
- [x] 1.3 `Monkey`: delete the `mUids` field and its write (design D3). Check with a search that no other reference exists in `src/main`
- [x] 1.4 `Monkey.run`: the `new MonkeySourceApe(...)` call passes `() -> LauncherQuery.query(mPm, mMainCategories)` right after `mMainApps` (design D6). `MonkeySourceRandom` keeps its arguments
- [x] 1.5 Run `/sdd-doc-code src/main/java/com/android/commands/monkey/LauncherQuery.java`

## 2. Relaunch decision and call sites: `MonkeySourceApe.java`

- [x] 2.1 Constructor: new parameter `Supplier<List<ComponentName>> launcherQuery` after `MainApps`, stored in a `private final` field `mLauncherQuery`
- [x] 2.2 Nested `static final class RelaunchTargets`, next to `NullTreeRecovery` and in the same style: `static <T> List<T> of(RunSpec spec, List<T> startup, Supplier<List<T>> query)`. Without `Feature.APE_FIXES` it returns `startup` and does not call `query`. Otherwise it returns `query.get()` when that list is non-null and non-empty, else `startup`. Javadoc cites INV-EXPL-33 (design D5, D6)
- [x] 2.3 `private List<ComponentName> launchers()`: `RelaunchTargets.of(RunContext.current().spec(), mMainApps, mLauncherQuery)`. When the result is not `equals` to `mMainApps`, it writes `Logger.iformat("[APE-RV] relaunch launcher %s", list)` (design D10)
- [x] 2.4 `randomlyPickMainApp()`: read `launchers()` once into a local, then one `mRandom.nextInt(list.size())` and `list.get(index)` (design D7)
- [x] 2.5 Null-tree branch (`restartPackage(getMainApp(), false, "null tree")`): `restartPackage(launchers().get(0), false, "null tree")` (design D8)
- [x] 2.6 `getMainApp()` stays `mMainApps.get(0)`. Update its javadoc: the startup launcher, used by the static-artifact check; relaunches read `launchers()`
- [x] 2.7 Check that `mMainApps` is never reassigned or mutated after the constructor (design D4)

## 3. Tests: `RelaunchTargetsTest.java` (new), `MonkeySourceApeRelaunchWiringTest.java` (new), `MonkeySourceApeNullTreeRecoveryTest.java`

- [x] 3.1 `RelaunchTargetsTest`, resolving cells as `MonkeySourceApeNullTreeRecoveryTest.cell` does, with lists of `String`:
  - `apeFixesTakesTheCurrentLauncher` (`as`, `as_mop`, `as_llm`, `as_mop_llm`: startup `[Green]`, query `[Red]` → `[Red]`);
  - `emptyQueryFallsBackToStartup` (empty list and `null` → startup);
  - `withoutApeFixesTheQueryIsNotCalled` (`base`, `mop`, and `as` with `ape.apeFixes=false`: a supplier that fails the test when called);
  - `equalListGivesTheSamePick` (two `new Random(42)`, one `nextInt` over the startup list and one over the equal query result, same element)
- [x] 3.2 `MonkeySourceApeRelaunchWiringTest`, source assertions in the style of `MonkeySourceApeStepWiringTest`:
  - `randomlyPickMainApp` and the null-tree `restartPackage` read `launchers()`;
  - `getMainApp` reads `mMainApps.get(0)`;
  - `launchers()` calls `RelaunchTargets.of` and logs `[APE-RV] relaunch launcher`;
  - `Monkey.java` calls `LauncherQuery.query`, contains neither `queryIntentActivities` nor `mUids`, and passes the query to `MonkeySourceApe`
- [x] 3.3 Test javadocs state what is asserted and why the call sites are asserted over the source (`MonkeySourceApe` does not load on the JVM)
- [x] 3.4 `MonkeySourceApeNullTreeRecoveryTest.theRelaunchBranchStopsTheAgentRestartsTheMainAppAndCounts`: the restart literal becomes `restartPackage(launchers().get(0), false, "null tree");` (design D8, D9). The `randomlyPickMainApp` absence assertion stays

## 4. Documentation: `CLAUDE.md`

- [x] 4.1 `CLAUDE.md` "Behavior groups and cells": add "the launcher resolved again at each relaunch" to the `APE_FIXES` list ("the bounded foreground wait, …")
- [x] 4.2 `CLAUDE.md` "Notes": update the test count after 5.2
- [x] 4.3 `CLAUDE.md` "Behavior groups and cells", null-tree recovery (D-B16): `restartPackage(getMainApp(), …)` becomes the relaunch launcher (`launchers().get(0)`, INV-EXPL-33) (design D9)

## 5. Integration and verification

- [x] 5.1 `mvn clean compile` in the worktree
- [x] 5.2 `mvn test` in the worktree: every test passes (baseline 1487 tests, 0 failures, 28 skipped, plus the new ones). Report the counts
- [x] 5.3 `mvn package`: `target/ape-rv.jar` builds (the jar is not deployed; the rebuild for rv-android is Pedro's call)
- [x] 5.4 `openspec validate gh-tbd-relaunch-current-launcher` passes
- [x] 5.5 Run `/sdd-qa-lint-fix` on the touched files
- [x] 5.6 Run `/sdd-verify`
- [x] 5.7 Invoke `/sdd-code-reviewer` via Skill tool
- [x] 5.8 At archive: hand-sync the `exploration` `## Invariants` section with INV-EXPL-33, after INV-EXPL-15 (archive syncs requirements only)
