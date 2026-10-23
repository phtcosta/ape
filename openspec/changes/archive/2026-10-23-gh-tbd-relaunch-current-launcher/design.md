## Context

`Monkey.getMainApps()` (`src/main/java/com/android/commands/monkey/Monkey.java:1312-1360`) queries the package
manager once, at startup. It sends `ACTION_MAIN` with each entry of `mMainCategories` (default `LAUNCHER`, `MONKEY`;
`-c` adds others), keeps the activities whose package the package filter admits, and stores them in `mMainApps`. The
same loop also fills `mUids`, which nothing reads. `MonkeySourceApe` (`src/main/java/com/android/commands/monkey/MonkeySourceApe.java`)
receives `mMainApps` in its constructor and relaunches the app only from it:

| Call site | Line | Reached from |
|---|---|---|
| `randomlyPickMainApp()` | 1282 | `startRandomMainApp()` (:950) ← `checkAppActivity` null top (:1330), the 100-cycle bound (:1357), blocked package (:1375); `ApeAgent.getStartAction` (`ape/agent/ApeAgent.java:321`) ← `EVENT_START`/`EVENT_RESTART`/`EVENT_CLEAN_RESTART` |
| `getMainApp()` | 1278 | null-tree relaunch `restartPackage(getMainApp(), false, "null tree")` (:888); the `StatefulAgent` constructor (`ape/agent/StatefulAgent.java:208`) |

The defect, the e03igg5 measurements and the scope are given in `proposal.md`. Pedro decided the scope (only cells
with `APE_FIXES`) and the shape (one shared query, fall back to the startup list, no change to the stored list, no
telemetry member, a new invariant, a pure-decision test) on 2026-10-23.

`MonkeySourceApe` cannot be class-loaded on the JVM, and the test classpath has no mocking library. The existing
pattern for a testable decision in this class is a nested pure class asserted directly, with the call site asserted
over the source (`NullTreeRecovery`, `MonkeySourceApeNullTreeRecoveryTest`).

## Architecture

```
Monkey.run
  ├─ getMainApps(): mMainApps = LauncherQuery.query(mPm, mMainCategories)       (startup, every cell)
  └─ new MonkeySourceApe(…, mMainApps, () -> LauncherQuery.query(mPm, mMainCategories), …)

MonkeySourceApe
  launchers()            = RelaunchTargets.of(spec, mMainApps, mLauncherQuery)
                           + one Logger line when the result differs from mMainApps
  randomlyPickMainApp()  = launchers().get(mRandom.nextInt(launchers.size()))
  null-tree relaunch     = restartPackage(launchers().get(0), false, "null tree")
  getMainApp()           = mMainApps.get(0)                                      (startup value, unchanged)

RelaunchTargets.of(spec, startup, query):
  !spec.has(APE_FIXES)        -> startup            (query not called)
  query.get() non-empty       -> that list
  otherwise                   -> startup
```

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `LauncherQuery.query` (new, `com.android.commands.monkey`) | Resolve the app's launcher activities | `IPackageManager`, `List<String>` categories | `List<ComponentName>`, empty when nothing matches |
| `MonkeySourceApe.RelaunchTargets.of` (new nested, pure) | Decide which list a relaunch picks from | `RunSpec`, startup `List<T>`, `Supplier<List<T>>` | `List<T>` |
| `MonkeySourceApe.launchers()` (new, private) | Apply the decision to the run's spec and lists | — | `List<ComponentName>` |
| `Monkey.getMainApps` | Startup list and the abort on empty | — | `boolean` |

## Mapping: Spec -> Implementation -> Test

| Requirement | Implementation | Test |
|-------------|---------------|------|
| *Launcher Resolved at Relaunch*, scenario "the app switched its launcher alias" | `RelaunchTargets.of` returns the query result | `RelaunchTargetsTest.apeFixesTakesTheCurrentLauncher` |
| scenario "the query returns nothing" | `RelaunchTargets.of` falls back to `startup` | `RelaunchTargetsTest.emptyQueryFallsBackToStartup` |
| scenario "base keeps the startup launcher" | `RelaunchTargets.of` without `APE_FIXES` | `RelaunchTargetsTest.withoutApeFixesTheQueryIsNotCalled` (`base`, `mop`) |
| scenario "an unchanged launcher leaves the seeded picks unchanged" | one `nextInt(n)` over a list equal to the startup list | `RelaunchTargetsTest.equalListGivesTheSamePick` |
| D10 log line | `launchers()` | `MonkeySourceApeRelaunchWiringTest.launchersLogsADifferentList` (source) |
| INV-EXPL-33, call sites | `randomlyPickMainApp` and the null-tree branch read `launchers()`; `getMainApp` reads `mMainApps` | `MonkeySourceApeRelaunchWiringTest` (source assertions) |
| `component-triggering` *Launcher-Owned Null-Tree Recovery* | the null-tree branch restarts `launchers().get(0)` | `MonkeySourceApeNullTreeRecoveryTest.theRelaunchBranchStopsTheAgentRestartsTheMainAppAndCounts` (literal updated) |
| one launcher query | `Monkey.getMainApps` calls `LauncherQuery.query`; no `queryIntentActivities` call left in `Monkey` | `MonkeySourceApeRelaunchWiringTest.monkeyUsesTheSharedQuery` |
| `guidance-groups` row 16 | as above | `RelaunchTargetsTest` covers every cell's presence/absence of `APE_FIXES` |

## Goals / Non-Goals

**Goals:**
- A relaunch in a cell with `APE_FIXES` reaches the app after it has switched its enabled launcher alias.
- One definition of "the app's launcher activities", shared by startup and relaunch.
- No change to `base` or `mop`: same components, same RNG draws, no package-manager call after startup.

**Non-Goals:**
- Why the app leaves the foreground when its alias is switched (not needed for the fix; open in the rv-android
  report).
- Bounding the wait in `base`/`mop` (decided: APE's behaviour).
- Any app- or screen-specific rule (for example, avoiding the Customization screen).
- A telemetry member for the resolution.
- Rebuilding the jar or the aperv-tool image.

## Decisions

**D1. Re-query at the relaunch, not on a timer or on an event.** A launcher can only matter when the app is
launched, so the query runs exactly then. *Alternatives:* (a) launching by package with an implicit
`MAIN`/`LAUNCHER` intent and letting `ActivityManager` resolve it. That changes `MonkeyActivityEvent` and
`StartAction`, which carry an explicit component in the model and the trace, in every cell. Rejected. (b) Refreshing
`mMainApps` on `onActivityBlocked`. That adds state and misses the agent's restart actions. Rejected.

**D2. One shared query, `LauncherQuery.query(IPackageManager, List<String>)`.** The loop leaves `Monkey.getMainApps`.
Startup and relaunch call the same static function, and `Monkey` keeps only the empty-list abort. The function
returns `List<ComponentName>`. It treats a `null` result of `ApeAPIAdapter.queryIntentActivities` as no match, and it
catches the `RemoteException` that method declares by returning an empty list. In practice that exception does not
reach it: `ApeAPIAdapter.invoke` (`ApeAPIAdapter.java:169-176`) turns a binder failure (`InvocationTargetException`)
into `System.exit(1)`, as it does for every reflective call of the run (`getTasks` each step, `AndroidDevice.java:150`).
At startup an empty list aborts with the existing `** No activities found to run, monkey aborted.` line. The
`** Failed talking with package manager!` line is removed. The per-category warning and the `-v ≥ 2/3` lines of the
loop are removed: aperv never passes `-v`, and printing them at each relaunch would be noise. *Alternative:* keep a
copy of the loop in `MonkeySourceApe`. Rejected: two definitions of the same rule.

**D3. `mUids` is removed.** It is written by the loop and read nowhere in `src/main`. Removing it lets the query
return components only.

**D4. The startup list is never overwritten.** `launchers()` returns a list for one relaunch, and `mMainApps` keeps
the startup value. As a result:
- the path without `APE_FIXES` is APE's by construction;
- `getMainApp()` keeps its startup meaning for the static-artifact check;
- `stopPackages`, `grantRuntimePermissions`, `packagePermissions` and the `mApeCleanApp` loop of `Monkey.run`
  (`Monkey.java:769-776`) are unaffected, because they read only the package name, and a component returned by the
  query belongs to an admitted package.

**D5. Empty query → startup list.** A transient empty answer (between the disable of one alias and the enable of
another) gives the behaviour of today, never a worse one. No abort is added. A binder failure exits the process
inside the adapter (D2), as any other binder call of the run would; this change adds no new kind of failure.

**D6. The decision is a pure, generic function.** `RelaunchTargets.of(RunSpec, List<T>, Supplier<List<T>>)` holds
no Android type, so the JVM test passes strings and lambdas. The `Supplier` is not called without `APE_FIXES`, so the
test can assert that `base` makes no query (a supplier that fails the test when called).
`MonkeySourceApe` receives the query as a `Supplier<List<ComponentName>>` constructor argument, and `Monkey` passes
`() -> LauncherQuery.query(mPm, mMainCategories)`. `java.util.function` is already used in `src/main`.

**D7. RNG.** `randomlyPickMainApp` keeps exactly one `mRandom.nextInt(n)` per call. While the query returns a list
equal to the startup list (same order: the same package-manager query), `n` and the pick are the same as before.
After the app switches its alias, `n` may differ. That happens only in cells with `APE_FIXES`, and the pick is then
the intended fix.

**D8. The null-tree relaunch takes `launchers().get(0)`.** It used `getMainApp()` = `mMainApps.get(0)`. The fresh
list's first element is the analogue, and with an unchanged launcher it is the same component.

**D9. Spec.** INV-EXPL-15 stays as it is. INV-EXPL-33 states the new rule. `guidance-groups` row 16 lists the point
among the `APE_FIXES` corrections. `component-triggering` *Launcher-Owned Null-Tree Recovery* quotes the null-tree
call site, so its text names the launcher of INV-EXPL-33 instead of `getMainApp()`; INV-CT-19 is unchanged.

**D10. One diagnostic line, no telemetry member.** When `launchers()` returns a list not equal to `mMainApps`,
it logs `[APE-RV] relaunch launcher <list>` through `Logger.iformat`. The line is a `[APE] ` Logger line, not a sink
record, and it can only appear with `APE_FIXES`. The trace names the launched component only for the agent's
restarts: `dec.a` is `action.toString()` (`StatefulAgent.java:1050`, `:1890`, `:1915`), which for a `StartAction` is
`TYPE@className` (`StartAction.java:86-88`). `startRandomMainApp` writes no step record (it only appends to the
action history, `MonkeySourceApe.java:1066-1072`), and the null-tree relaunch logs only the package name
(`Try to restart package …`, `MonkeySourceApe.java:1100`). `ACT` entries are activities
(`NdjsonSink.internActivity`). Without the line, a switch on those two paths, which include the 100-cycle bound, would
not be visible. *Alternative:* a `RUN_END` counter. Rejected: Pedro chose no telemetry member.

## API Design

### `LauncherQuery.query(IPackageManager pm, List<String> categories) -> List<ComponentName>`

- For each category: build `new Intent(Intent.ACTION_MAIN)`, add the category when it is non-empty, and call
  `ApeAPIAdapter.queryIntentActivities(pm, intent, UserHandle.myUserId())`.
- Keep `new ComponentName(r.activityInfo.applicationInfo.packageName, r.activityInfo.name)` for each result whose
  package `MonkeyUtils.getPackageFilter().checkEnteringPackage(packageName)` admits, in result order.
- A `null` result from the adapter counts as no match. The declared `RemoteException` is caught and yields an empty
  list (D2).
- Postcondition: never null. The list is empty when nothing matches.
- Final class with a private constructor, as other static helpers in the package.

### `MonkeySourceApe.RelaunchTargets.of(RunSpec spec, List<T> startup, Supplier<List<T>> query) -> List<T>`

- `!spec.has(Feature.APE_FIXES)` → `startup`, with no call to `query`.
- Otherwise `List<T> current = query.get()`. Return `current` when it is non-null and non-empty, else `startup`.
- Pure: no RNG, no logging, no state.

### `MonkeySourceApe(…, List<ComponentName> mainApps, Supplier<List<ComponentName>> launcherQuery, …)`

- The new parameter follows `MainApps` and is stored in `mLauncherQuery`.
- `private List<ComponentName> launchers()` returns `RelaunchTargets.of(RunContext.current().spec(), mMainApps, mLauncherQuery)`
  and logs the D10 line when that list is not equal to `mMainApps`.

## Data Flow

Startup: `getMainApps` → `LauncherQuery.query` → `mMainApps` → `MonkeySourceApe` (startup list + query).
Relaunch: call site → `launchers()` → `RelaunchTargets.of` → `query.get()` (only with `APE_FIXES`) → the chosen
`ComponentName` → `StartAction` / `restartPackage` → `MonkeyActivityEvent`.

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| empty result | `queryIntentActivities` at startup | `LauncherQuery` returns empty | existing abort `** No activities found…`, exit code -4 |
| empty result | the app has, for an instant, no enabled launcher alias at a relaunch | `LauncherQuery` returns empty | `RelaunchTargets` returns the startup list; the next relaunch queries again |
| binder failure | `ApeAPIAdapter.invoke` | `System.exit(1)` in the adapter, unchanged | none, as for every reflective binder call of the run |

## Risks / Trade-offs

- [Each relaunch with `APE_FIXES` makes one or two package-manager calls] → relaunches happen at most once per
  episode or per 100-cycle wait. A binder call costs milliseconds against the 2 s throttle that follows a launch.
- [An app with several launchers changes the list size, and with it the pick] → only after the app changes its
  launcher, only with `APE_FIXES`.
- [Removing the `-v` diagnostics of the loop] → aperv never passes `-v`. The abort line stays.
- [The relaunch still lands on the same screen that switches the alias] → the next selection is the agent's, as for
  any other screen. No screen-specific rule (Non-Goals).

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM) | `RelaunchTargets.of` over the six cells and the three outcomes | resolved `RunSpec` per cell (as `MonkeySourceApeNullTreeRecoveryTest.cell`), string lists, a counting/failing `Supplier` | ~5 tests |
| Source wiring | `randomlyPickMainApp` and the null-tree branch read `launchers()`; `getMainApp` reads `mMainApps`; `Monkey` calls `LauncherQuery.query` and has no `queryIntentActivities` loop or `mUids` | read the source, as `MonkeySourceApeStepWiringTest` | ~3 tests |
| Regression | full suite | `mvn test` | 1487 + new |
| Device | the stuck keyboard task recovers | a smoke run, only if Pedro asks | 0 by default |

## Open Questions

None.
