## Why

APE resolves the app's launcher components once, at startup (`Monkey.getMainApps`, `queryIntentActivities` for
`MAIN` + `LAUNCHER`/`MONKEY`), and every relaunch picks from that list. An app can change its own launcher at run
time: the Fossify apps ship 19 `MAIN`/`LAUNCHER` `activity-alias`es, one per icon colour, with one enabled, and
`CustomizationActivity.saveChanges` → `toggleAppIconColor` enables another alias and disables the current one
(`setComponentEnabledSetting(…, DONT_KILL_APP)`). The app leaves the foreground, every relaunch targets the disabled
alias, and `checkAppActivity` waits for the rest of the budget. With `APE_FIXES` the 100-cycle bound (INV-EXPL-15)
relaunches through the same stale component and does not recover either.

Measured on e03igg5 (jar `b6b3d62`, the same code as master `be587667`; rv-android
`docs/20261023_espera_activity_loading_e03igg5.md`, `data/e03igg_analysis/out/e03igg5/waits/waits.csv`): the 10
tasks with ≥ 100 wait lines are exactly the 10 whose logcat records `toggleAppIconColor`. In those tasks the arms
lost 504 s (ape, 4 tasks), 58 s (base, 1 task), 8 s (mop, 1 task) and 606 s (as_mop, 4 tasks, 81 relaunches, none of
which brought the app back). The apps were keyboard, paint, math, calendar and voicerecorder. Pedro approved the
change on 2026-10-23, scoped to the cells that mount ADDONS. No GitHub issue.

## What Changes

- **With `APE_FIXES`, every relaunch of the app under test resolves the launcher again**, at the moment of the
  relaunch, using the same query and package filter as startup. This covers `startRandomMainApp` (null top
  activity, the 100-cycle bound, the blocked-package path), the agent's `EVENT_START`/`EVENT_RESTART`/
  `EVENT_CLEAN_RESTART` actions (`ApeAgent.getStartAction`) and the null-tree relaunch (`restartPackage`, D-B16). If
  the query returns nothing, the relaunch uses the startup list, as it does today.
- **Without `APE_FIXES`** (`base`, `mop`), the relaunch uses the startup list and no query is made, as in upstream
  APE (the base-equals-APE decision).
- The startup list itself is never overwritten. `getMainApp()` keeps the startup value for the static-artifact
  sanity check in the `StatefulAgent` constructor.
- **One launcher query.** The query loop moves out of `Monkey.getMainApps` into a single function, which startup and
  relaunch both call. The loop's per-category stderr warning and its `-v`-level `//   + Using main activity` lines
  are removed. The `** No activities found to run, monkey aborted.` abort stays.
- **Dead code removed:** `Monkey.mUids` is written by the loop and read nowhere.
- No telemetry member is added. When a relaunch resolves a list different from the startup list, one `Logger` line
  `[APE-RV] relaunch launcher <components>` names the components. The trace names the launched component only for the
  agent's restarts (`dec.a`); `startRandomMainApp` (which includes the 100-cycle bound) and the null-tree relaunch
  write no record that names it, so without this line a switch on those paths would not be visible.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `exploration`: a new requirement, *Launcher Resolved at Relaunch*, with invariant INV-EXPL-33, placed next to
  *Bounded Foreground Wait* (INV-EXPL-15, unchanged).
- `guidance-groups`: *Declared Consequences of the Cell Composition* adds row 16 to the `APE_FIXES` table: without
  the member, relaunches use the launcher resolved at startup; with it, the launcher is resolved at each relaunch.
- `component-triggering`: *Launcher-Owned Null-Tree Recovery* names the launcher of INV-EXPL-33 as the component the
  null-tree recovery restarts, instead of `getMainApp()`. INV-CT-19 is unchanged.

## Impact

- `src/main/java/com/android/commands/monkey/Monkey.java`: `getMainApps` calls the shared query; `mUids` is removed;
  the `MonkeySourceApe` constructor call passes the query.
- New `src/main/java/com/android/commands/monkey/LauncherQuery.java`: the query, as a static function of the
  package manager and the categories.
- `src/main/java/com/android/commands/monkey/MonkeySourceApe.java`: the constructor takes the query; a pure decision
  `RelaunchTargets` (nested, like `NullTreeRecovery`); `randomlyPickMainApp` and the null-tree relaunch read the
  decision.
- Tests: a JVM test of the pure decision and a source-wiring test, following `MonkeySourceApeNullTreeRecoveryTest`,
  whose null-tree call-site literal is updated.
- RNG: `randomlyPickMainApp` still makes one `mRandom.nextInt(n)` draw per call. Until the app changes its launcher,
  the resolved list equals the startup list, so a seeded run picks the same components as before.
- Cells: only those that mount ADDONS change behaviour (`as`, `as_mop`, `as_llm`, `as_mop_llm`). In `base` and
  `mop` no query is made.
- rv-android: none. The jar rebuild and the aperv-tool image are a later step, decided by Pedro.
- `CLAUDE.md`: the `APE_FIXES` list gains this point, and the null-tree recovery text names the relaunch launcher.
