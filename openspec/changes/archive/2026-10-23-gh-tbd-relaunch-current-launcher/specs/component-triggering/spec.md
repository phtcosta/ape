## MODIFIED Requirements

### Requirement: Launcher-Owned Null-Tree Recovery

When the accessibility bridge returns a null root more than ten times in a row, `MonkeySourceApe` recovers. The upstream recovery, `stopTopActivity()`, kills the first process of `getRunningAppProcesses()`. After a launch that crashed the app, that process is not the app: in `e03igg` it was the IME (`com.google.android.inputmethod.latin`) or the system launcher in 7 runs, all in the MOP arm, and the null trees went on (design D-B16).

When the plan contains `Feature.MOP_LAUNCHER` (`MonkeySourceApe.NullTreeRecovery.relaunches(spec)`), the recovery SHALL stop the package under test and start its main activity again, through the existing restart path with the launcher of `exploration` INV-EXPL-33 (`restartPackage(launchers().get(0), false, "null tree")`), and SHALL NOT call `stopTopActivity()`. Like `stopTopActivity()`, it SHALL first tell the agent that the activity stopped (`onActivityStopped()`), which clears the agent's current state, action and buffer. On the step of the recovery the branch SHALL NOT queue the `ACTIVATE` action: `ACTIVATE` is an app switch followed by HOME or BACK, which would send the app just relaunched to the background (author, 2026-10-18). The step's action is then `NOP`. The rest of the branch is unchanged: the counter reset, `onVoidGUITree` and the interactive check. Each such recovery SHALL add one to `RUN_END.counters.nullRelaunches` (`event-sink`).

When the plan does not contain `Feature.MOP_LAUNCHER`, the recovery SHALL be upstream's `stopTopActivity()`, unchanged, and `nullRelaunches` SHALL stay `0`: BASE stays upstream APE (author, 2026-10-18).

- **INV-CT-19**: A null-tree recovery SHALL relaunch the app under test if and only if `MOP_LAUNCHER` is in the plan; otherwise it SHALL be upstream's code path.

#### Scenario: with the launcher, the recovery relaunches the app
- **WHEN** in cell `mop` the eleventh consecutive null root arrives after the launcher opened an activity that crashed
- **THEN** the recovery SHALL call `onActivityStopped()`, stop the package under test and start its main activity, no process other than the app's SHALL be killed, no app switch, HOME or BACK SHALL be queued on that step, and `RUN_END.counters.nullRelaunches` SHALL be `1`

#### Scenario: without the launcher, the recovery is upstream's
- **WHEN** in cell `base` (or `as`, `as_llm`) the eleventh consecutive null root arrives
- **THEN** the recovery SHALL be `stopTopActivity()`, exactly as upstream, and `RUN_END.counters.nullRelaunches` SHALL be `0`
