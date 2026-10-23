## ADDED Requirements

### Requirement: Launcher Resolved at Relaunch

The app's launcher components are the activities that `LauncherQuery.query(pm, categories)` returns: the activities
the package manager resolves for `Intent.ACTION_MAIN` with each of the run's main categories (`LAUNCHER` and `MONKEY`
unless `-c` names others), restricted to the packages the package filter admits. Startup (`Monkey.getMainApps`) calls
this function once and keeps the result as the startup list, which is never overwritten. The run aborts with
`** No activities found to run, monkey aborted.` when the startup list is empty.

An app can change its own launcher during the run. `PackageManager.setComponentEnabledSetting` can enable one
`activity-alias` and disable another, and a component disabled this way no longer starts. A relaunch is any launch
of the app under test through `MonkeySourceApe.randomlyPickMainApp()` or the null-tree relaunch. The first covers
`startRandomMainApp` (null top activity, the bound of INV-EXPL-15, the blocked-package path) and the agent's
`EVENT_START`, `EVENT_RESTART` and `EVENT_CLEAN_RESTART` actions (`ApeAgent.getStartAction`). The second is
`restartPackage(…, "null tree")` (D-B16).

When the plan carries the ADDONS member `APE_FIXES`, each relaunch SHALL call `LauncherQuery.query` again and SHALL
pick its component from that result. The random pick draws `mRandom.nextInt(n)` once over the result, and the
null-tree relaunch takes its first element. If the query returns an empty list, the relaunch SHALL use the startup
list. Without `APE_FIXES` (the `base` and `mop` cells), a relaunch SHALL pick from the startup list and SHALL NOT
query the package manager, as in upstream APE. When the list a relaunch picks from differs from the startup list,
the engine SHALL log one line `[APE-RV] relaunch launcher <components>`. `MonkeySourceApe.getMainApp()` returns the
first element of the startup list in every cell. The `StatefulAgent` constructor uses it for the static-artifact
package and main-activity check.

One pure function makes the choice, `MonkeySourceApe.RelaunchTargets.of(spec, startup, query)`. With `APE_FIXES` it
returns `query.get()` when that list is non-empty and `startup` otherwise. Without `APE_FIXES` it returns `startup`
and does not call `query`. INV-EXPL-33: when the plan carries `APE_FIXES`, the component of every relaunch of the app
under test SHALL be taken from a launcher query made at that relaunch, unless that query returns nothing, in which
case it SHALL be taken from the startup list. Without `APE_FIXES`, every relaunch SHALL take its component from the
startup list and no relaunch SHALL query the package manager.

#### Scenario: the app switched its launcher alias

- **WHEN** the plan carries `APE_FIXES`
- **AND** the startup list is `[org.fossify.keyboard/.SplashActivity.Green]`
- **AND** at the relaunch the query returns `[org.fossify.keyboard/.SplashActivity.Red]`
- **THEN** the relaunch SHALL start `org.fossify.keyboard/.SplashActivity.Red`
- **AND** the startup list SHALL still be `[org.fossify.keyboard/.SplashActivity.Green]`
- **AND** one `[APE-RV] relaunch launcher` line naming `SplashActivity.Red` SHALL be emitted

#### Scenario: the query returns nothing

- **WHEN** the plan carries `APE_FIXES`
- **AND** the query at the relaunch returns an empty list
- **THEN** the relaunch SHALL pick from the startup list

#### Scenario: base keeps the startup launcher

- **WHEN** the `base` cell runs and the app has disabled the component of its startup list
- **THEN** every relaunch SHALL target that startup component
- **AND** the launcher query SHALL NOT be called after startup

#### Scenario: an unchanged launcher leaves the seeded picks unchanged

- **WHEN** the plan carries `APE_FIXES` and the query at each relaunch returns a list equal to the startup list
- **THEN** each `randomlyPickMainApp()` call SHALL draw `mRandom.nextInt(n)` once with the same `n`
- **AND** it SHALL return the same component as a pick from the startup list with the same seed
