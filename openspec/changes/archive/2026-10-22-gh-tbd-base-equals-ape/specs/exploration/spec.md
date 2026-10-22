## Purpose

This delta makes the `base` and `mop` cells run upstream APE (`8f51b99c`) at the points of the exploration engine
where the fork had corrected APE without a key, and keeps the corrections in every cell that mounts ADDONS. The
corrections become one ADDONS member, `APE_FIXES` (activation key `ape.apeFixes`, boolean, jar default `true`,
`false` when ADDONS is off; `guidance-groups`). Each point reads the member through
`RunContext.current().spec().has(Feature.APE_FIXES)`, and each has exactly one decision point:

- **Restart timing (INV-EXPL-32).** With the member and `ape.checkRestart`, the `Restart` guard stage selects the
  restart before any other stage, as before this change. Without the member the pipeline does not mount `Restart`,
  and `ApeAgent.checkRestart` is applied after selection, as APE applies it: SATA marks the selected action visited,
  the restart may then replace it, and the restart's new episode weakens the planned edge. The fork's own bookkeeping
  treats a replaced action as not executed, and the step is recorded as a restart.
- **Foreground wait (INV-EXPL-15).** Bounded at 100 cycles with a relaunch only with the member; unbounded without it.
- **Off-screen node target (INV-EXPL-19).** Dropped with the member; without it the display bounds substitute for the
  missing visible bounds and the click lands at the display centre, with APE's `Error to fetch bounds.` warning.
- **Fuzzing.** With the member the pinch/zoom event is appended and the fuzz toss comes from `StepRng`'s `fuzz`
  stream on every step; without it the pinch/zoom points are built with the same draws but the event is not
  appended, and the toss is `RandomHelper.toss(ape.fuzzingRate)`, drawn only when fuzzing is on.

Two further points change in every cell, with no member: `RandomHelper` is seeded with `seed ^ RANDOM_HELPER` (a
fixed odd 64-bit constant distinct from `StepRng`'s stream constants), so it no longer produces the sequence of
`Monkey.mRandom` at another offset; and the jar default of `ape.takeScreenshotForNewState` becomes `true`, so one PNG
is saved per new abstract state while the per-step PNG/XML artifacts stay off.

## MODIFIED Requirements

### Requirement: SataAgent — Forced App Restart on Graph Stability

`SataAgent` (via its superclass `StatefulAgent`) monitors whether the exploration graph has stopped growing. The counter `graphStableCounter` is incremented each step in which no new `State` or `StateTransition` is added to the `Graph`. When `graphStableCounter` exceeds `ape.graphStableRestartThreshold` (default: `100`) in step 2's `checkStable`, `onGraphStable` SHALL raise the restart flag with kind `graph` (`requestRestart()`) and the counter MUST be reset to zero immediately. This prevents the exploration from getting stuck in a saturated region of the state space. The state and activity stability checks raise the same flag with kinds `state` and `activity`. The activity threshold is `ape.activityStableRestartThreshold` when the plan carries the ADDONS member `ACTIVITY_STABLE_RESTART`, and otherwise the upstream `Integer.MAX_VALUE` (design D-B5).

Where the restart is applied depends on whether the pipeline mounted the `Restart` stage, which it does if and only if `ape.checkRestart` is `true` (BASE, jar default `true`; design D-B14.12) and the plan carries the ADDONS member `APE_FIXES` (design D5). Both are read from the plan (`spec`), never from `Config.checkRestart` (design D-B6, extended by author decision A10). `ApeAgent.restartStageMounted()` returns `false`, and `SataAgent` overrides it with whether its assembled pipeline contains `RESTART`, computed once when the pipeline is assembled; that is the single decision point.

**Stage mounted (the ADDONS cells).** The restart is selected by the `Restart` guard stage of the decision pipeline (`decision-pipeline` "Restart Guard Stage"). That stage runs after `Budget` and before any LLM, MOP or SATA stage, and it selects an `EVENT_RESTART` or `EVENT_CLEAN_RESTART` start action through `StatefulAgent.restartAction()`, which drops the action buffer without weakening and then calls `ApeAgent.startRestart()`. The step's record carries `dec.src="restart"` and `dec.restart` set to the kind, and `dec.a` names the start action that ran. A restart preempts the step. It never replaces a selected action, so no selected model action is lost to it, and `ApeAgent.checkRestart` returns its argument unchanged.

**Stage not mounted (`APE_FIXES` absent, as in the `base` and `mop` cells, with `ape.checkRestart=true`).** The restart is applied after selection, as upstream APE applies it (design D6). The latch that blocks a second threshold restart is cleared at step start. Inside `StatefulAgent.updateStateInternal`, after `resolveNewAction()` and after `getGraph().markVisited(action)` has marked a selected model action visited, `ApeAgent.checkRestart(action)` is called once. On the step's `BadStateException` retry, the second `updateStateInternal` that `ApeAgent.updateStateWrapper` runs after retrieving the window root again, `checkRestart` SHALL return the action unchanged, as APE's retry runs without `checkRestart` (`decision-pipeline` "Post-Selection Restart"). Otherwise it SHALL return the action unchanged when no restart is due (`restartDue()`: the restart flag is raised, or a threshold has passed and the latch is not set), and otherwise SHALL return the start action of `ApeAgent.startRestart()`, which records the kind and `lastRestartStep`, redraws the threshold for `periodic`, starts a new episode, writes `dec.restart`, increments the `restarts` counter and tosses the `EVENT_CLEAN_RESTART` kind. When the selected action is kept, `recordActionHistory` and `moveForward` run as on every step, and the action is forwarded to the pipeline's stages (`onSelectionKept`), where the `MopLauncher` stage commits a launch. When it is replaced, neither runs: `resetTrace` runs on the action buffer and nulls its pointers, and the new episode's buffer clear weakens the planned edge, so the graph, the visit marks and the weakening equal APE's. The fork-only bookkeeping reached through `moveForward` and `recordActionHistory` — the MOP interaction ledger and target retirement (`dec.ret`), the UI-coverage interaction count, the budget iteration and the LLM action history — SHALL NOT count the replaced action, and a replaced launch SHALL NOT enter the `MopLauncher` stage's launched set, consume its budget or count `launches`. `StatefulAgent` then rewrites the step's decision as a restart decision (`decisionReplaced` with owner `restart` and channel `restart`, replacing the earlier decision of the same step) and clears `lastDecisionAction`, so the record carries `dec.src="restart"`, `dec.ch="restart"`, `dec.restart` and `dec.a` naming the start action, as a stage-selected restart step does. A post-selection restart replaces the action of any owner (SATA, MOP coin, launcher, LLM); LLM attempts already made in the step stay in its `llm[]`.

With `ape.checkRestart=false` no restart is applied on either path. `RandomAgent` and `ReplayAgent` have no pipeline and always restart after selection, unchanged.

INV-EXPL-32: for an agent with a decision pipeline (`SataAgent`), the `Restart` stage is mounted if and only if `ape.checkRestart` is `true` and the plan carries `APE_FIXES`. With the stage mounted, no restart SHALL replace a selected action after selection, and every restart SHALL be a step whose owner is `restart` (`decision-pipeline` INV-DP-14). Without the stage and with `ape.checkRestart` `true`, `ApeAgent.checkRestart` SHALL be applied once per step after selection, never on the step's `BadStateException` retry, and MAY replace the selected action of any owner; a replaced action SHALL be marked visited and SHALL NOT be counted by the MOP interaction ledger, target retirement or the LLM action history, and the step SHALL be recorded with owner `restart`. `RandomAgent` and `ReplayAgent` have no pipeline and keep the post-selection `checkRestart` wrapping unchanged (design D-B14.12).

#### Scenario: Graph stable for threshold steps

- **WHEN** `graphStableCounter` reaches `101` consecutive steps without any change to the `Graph`, exceeding `ape.graphStableRestartThreshold=100`
- **AND** the plan carries `APE_FIXES` and `ape.checkRestart=true`, so the `Restart` stage is mounted
- **THEN** `StatefulAgent.onGraphStable(101)` SHALL be called on the active agent in step 2's `checkStable`
- **AND** `SataAgent.onGraphStable()` SHALL return `true` and raise the restart flag with kind `graph`, indicating a restart should be performed
- **AND** at `graphStableCounter=100` it SHALL have returned `false`, because the threshold must be exceeded, not reached
- **AND** the `Restart` stage of the same step SHALL select a start action (`EVENT_RESTART`, or `EVENT_CLEAN_RESTART` with probability 0.2) before any LLM or SATA stage is evaluated, with `dec.src="restart"`, `dec.ch="restart"` and `dec.restart="graph"`
- **AND** `graphStableCounter` SHALL be reset to `0`

#### Scenario: Graph grows; counter resets

- **WHEN** a new `State` is discovered on step N (making `graphStableCounter = 0` at step N-1 become irrelevant)
- **THEN** `graphStableCounter` SHALL be reset to `0`
- **AND** the restart SHALL NOT be triggered regardless of the previous counter value

#### Scenario: RandomAgent ignores graph stability

- **WHEN** `RandomAgent` is the active strategy
- **AND** `graphStableCounter` reaches `100`
- **THEN** `RandomAgent.onGraphStable(100)` SHALL return `false`
- **AND** no forced restart SHALL be initiated by the graph-stability path

#### Scenario: post-selection restart without APE_FIXES replaces the selected action

- **WHEN** the `base` cell runs `SataAgent` with `ape.checkRestart=true`, so `APE_FIXES` is absent and the `Restart` stage is not mounted
- **AND** `onGraphStable` raises the restart flag with kind `graph` in step 2's `checkStable` and SATA then selects click A on state S
- **THEN** A SHALL be marked visited in the graph before `ApeAgent.checkRestart` runs
- **AND** `ApeAgent.checkRestart` SHALL replace A with the start action (`EVENT_RESTART`, or `EVENT_CLEAN_RESTART` with probability 0.2)
- **AND** `moveForward` and `recordActionHistory` SHALL NOT run for A, so no MOP interaction, target retirement, UI-coverage interaction, budget iteration or action-history entry SHALL be recorded for A
- **AND** the new episode SHALL weaken the planned edge of the action buffer
- **AND** the step's record SHALL carry `dec.src="restart"`, `dec.ch="restart"`, `dec.restart="graph"` and `dec.a` naming the start action, and the `restarts` counter SHALL increase by `1`

#### Scenario: post-selection restart keeps an action when none is due

- **WHEN** the `Restart` stage is not mounted, `ape.checkRestart=true`, the restart flag is not raised and no threshold has passed
- **AND** SATA selects click A
- **THEN** `ApeAgent.checkRestart` SHALL return A unchanged
- **AND** `recordActionHistory` and `moveForward` SHALL run for A, and the step's record SHALL keep the decision that selected A

---

### Requirement: Fuzzing Integration

`ape.doFuzzing` and `ape.fuzzingRate` are BASE keys of the feature `FUZZING`, and `MonkeySourceApe` SHALL read them from the plan (`spec`), never from `Config` (design D-B6, extended by author decision A10). When `ape.doFuzzing=false`, `FUZZING` is absent from the plan and the agent never injects random `FUZZ` actions outside the structured exploration loop. When `ape.doFuzzing=true` (the jar default), at each step the agent MAY inject a random `FuzzAction` with probability `ape.fuzzingRate` (default: `0.02`, i.e., 2% of steps). The fuzz toss is evaluated in `MonkeySourceApe.generateEvents`, after the step's action events are generated, as one expression whose source is decided by the ADDONS member `APE_FIXES` (design D9):

- **With `APE_FIXES` in the plan** (the ADDONS cells), the toss SHALL come from `StepRng`'s own `fuzz` stream (`StepRng.fuzzToss`, `decision-pipeline` INV-DP-13), not from `RandomHelper`. It is drawn once per step, whether or not fuzzing is enabled and whatever the step's owner, so that enabling fuzzing or changing its rate never shifts a helper-stream draw.
- **Without `APE_FIXES`** (the `base` and `mop` cells), the toss SHALL be `RandomHelper.toss(ape.fuzzingRate)`, drawn only when `FUZZING` is in the plan, as upstream APE. With fuzzing off no draw is made for fuzzing from either stream.

The injection happens when `FUZZING` is in the plan, the toss holds, and `Agent.canFuzzing()` admits. `canFuzzing()` is `false` on a step whose selected action's activity has been visited fewer than `ape.fuzzingActivityVisitThreshold` (default: `10`) times (`StatefulAgent.checkFuzzing` sets `disableFuzzing`), preventing fuzzing from disrupting early exploration, and always `false` under `ReplayAgent`.

A step that injects a `FuzzAction` SHALL record the kinds of the gesture events it queued as `dec.fz` (`event-sink` "StepRecord Schema", design D14), with no RNG draw and no change to the events.

#### Scenario: Fuzzing disabled

- **WHEN** `ape.doFuzzing=false`
- **THEN** `FUZZING` SHALL be absent from the plan, so `Agent.canFuzzing()` is never consulted and no `FuzzAction` is generated at any step
- **AND** no `FuzzAction` SHALL be injected into the event queue
- **AND** with `APE_FIXES` in the plan, the fuzz toss SHALL still be drawn from the `fuzz` stream once on every step, and no `RandomHelper` draw SHALL be made for fuzzing
- **AND** without `APE_FIXES`, no draw SHALL be made for fuzzing from either the `fuzz` stream or `RandomHelper`

#### Scenario: Fuzzing fires at expected rate

- **WHEN** `ape.doFuzzing=true` and `ape.fuzzingRate=0.02`
- **AND** the current activity's visit count reaches `ape.fuzzingActivityVisitThreshold` (default: `10`)
- **THEN** across a large number of steps, approximately 2% of steps SHALL inject a `FuzzAction`
- **AND** the remaining 98% of steps SHALL proceed through the normal action-selection path

#### Scenario: an injected FuzzAction is recorded

- **WHEN** a step's fuzz toss fires, `canFuzzing()` holds, and the `FuzzAction` queues a trackball event and a pinch, with `APE_FIXES` in the plan
- **THEN** the step's record SHALL carry `dec.fz:["trackball","pinch"]`
- **AND WHEN** the same events are generated without `APE_FIXES`, so the pinch is not queued
- **THEN** the record SHALL carry `dec.fz:["trackball"]`

#### Scenario: fuzz toss without APE_FIXES draws from RandomHelper

- **WHEN** the `base` cell runs with `ape.doFuzzing=true` and `ape.fuzzingRate=0.02`
- **THEN** each step SHALL draw the fuzz toss as `RandomHelper.toss(0.02)` after the step's action events are generated
- **AND** no draw SHALL be made from `StepRng`'s `fuzz` stream

---

### Requirement: Configuration Loading

All tuning parameters MUST be loaded from `ape.properties` at process startup — `/data/local/tmp/ape.properties` first, then `/sdcard/ape.properties` (later wins) — and the loaded entries SHALL be **validated in full by `RunSpec.resolve`** (`run-spec` capability) before any component consumes them: unknown keys, foreign keys, retired keys, type-invalid values, missing feature dependencies, and invalid combinations abort the process before step 1. JVM system properties are not a supported configuration channel; note that `Config` builds its `Properties` with `System.getProperties()` as defaults, so a system property could reach a key that `Config` reads without plan validation or echo. All surviving `Config` fields are `public static final`, resolved once at class-loading time, and MUST NOT change for the lifetime of the process; tests construct `RunSpec` values instead of mutating `Config`. `Config` is a loading detail; the resolved `RunSpec` is the behavioral authority. A key that the plan owns SHALL be read from the plan (`RunContext.current().spec()`) and has no `Config` field: in particular `ape.doFuzzing`, `ape.fuzzingRate` and `ape.checkRestart` are BASE keys read from the plan (design D-B6, extended by author decision A10), and `ape.apeFixes` is the activation key of the ADDONS member `APE_FIXES`, read through `spec().has(Feature.APE_FIXES)`.

The table below lists the configuration keys relevant to the exploration engine with their types and defaults:

| Key | Type | Default | Description |
|---|---|---|---|
| `ape.graphStableRestartThreshold` | int | 100 | Steps without graph growth before forced restart (BASE) |
| `ape.stateStableRestartThreshold` | int | 50 | Steps in same state before forced restart (BASE) |
| `ape.activityStableRestartThreshold` | int | 200 in the ADDONS block | Steps in same activity before forced restart. Activation key of the ADDONS member `ACTIVITY_STABLE_RESTART` (POSITIVE, neutral 0), no longer a BASE key; when the feature is absent (ADDONS off, or the key `0`) the threshold is the upstream `Integer.MAX_VALUE` (design D-B5) |
| `ape.apeFixes` | boolean | true (requires `ape.addons`; `false` when ADDONS is off) | Activation key of the ADDONS member `APE_FIXES`: with it the restart guard stage, the bounded foreground wait, the off-screen drop, the pinch/zoom emission and the `fuzz`-stream toss; without it APE's code at each of those points |
| `ape.evolveModel` | boolean | true | Enable CEGAR naming refinement |
| `ape.doFuzzing` | boolean | true | Enable random fuzzing injection (BASE, read from the plan) |
| `ape.fuzzingRate` | double | 0.02 | Probability of fuzzing per step (BASE, read from the plan) |
| `ape.checkRestart` | boolean | true | Enable restarts (BASE, read from the plan): the `Restart` guard stage when `APE_FIXES` is in the plan, the post-selection `checkRestart` otherwise |
| `ape.fuzzingActivityVisitThreshold` | int | 10 | Minimum activity visits before fuzzing activates |
| `ape.defaultEpsilon` | double | 0.05 | Epsilon for SataAgent epsilon-greedy |
| `ape.takeScreenshot` | boolean | true | Save screenshots |
| `ape.takeScreenshotForNewState` | boolean | true | Save one PNG per new abstract state (with `ape.takeScreenshot`) |
| `ape.saveGUITreeToXmlEveryStep` | boolean | false | Save GUITree XML per step (see "Per-Step Debug Artifact Defaults" / INV-EXPL-17) |
| `ape.defaultGUIThrottle` | long (ms) | 200 | Delay between injected events |
| `ape.trivialActivityRankThreshold` | int | 3 | Minimum activity count before trivial-activity logic activates |

The keys `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, and `ape.saveStates` are retired (persistence removal) and abort resolution if present. The keys `ape.mopDataPath` (renamed `ape.staticDataPath`), `ape.leastVisitedPriorityTiebreak` (replaced by the X1 scorer-sum tie) and `ape.mopTargetPickCap` (replaced by `ape.mopCoinLedgerMaxInteractions`, k, the MOP coin's limit on executions by any owner per activity and `xpath|ActionType` key (default 1)) are retired and abort resolution with `retired_key` (`run-spec`).

#### Scenario: Property file present on device

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.graphStableRestartThreshold=200`
- **THEN** the plan's `spec().exploration().graphStableRestartThreshold()` SHALL equal `200` for the entire session
- **AND** the default value of `100` SHALL NOT be used

#### Scenario: No property file present

- **WHEN** neither `/data/local/tmp/ape.properties` nor `/sdcard/ape.properties` exists
- **THEN** all `Config` fields and all BASE plan keys SHALL take their hardcoded default values, and no group member SHALL be in the plan, so `ACTIVITY_STABLE_RESTART` and `APE_FIXES` are absent and the activity threshold is `Integer.MAX_VALUE`
- **AND** resolution SHALL succeed (an absent file is valid; an invalid entry is not)

#### Scenario: Typo aborts instead of defaulting

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.fuzzingRate=O.02` (letter O)
- **THEN** the process SHALL abort with an invalid-type diagnostic before step 1

#### Scenario: the activity-stable threshold follows ADDONS
- **WHEN** the `as` cell resolves, whose ADDONS block sets `ape.activityStableRestartThreshold=200`
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be in the plan and the activity-stability check SHALL raise the restart flag once the counter exceeds `200`
- **AND WHEN** the `base` cell resolves
- **THEN** `ACTIVITY_STABLE_RESTART` SHALL be absent and the threshold SHALL be `Integer.MAX_VALUE`, so the activity check never raises the flag

#### Scenario: APE_FIXES follows ADDONS
- **WHEN** the `as`, `as_mop`, `as_llm` or `as_mop_llm` cell resolves, whose ADDONS block states `ape.apeFixes=true`
- **THEN** `APE_FIXES` SHALL be in the plan
- **AND WHEN** the `base` or `mop` cell resolves
- **THEN** `APE_FIXES` SHALL be absent, so every point it decides runs upstream APE's code

---

### Requirement: StatefulAgent — Action History Ring Buffer

`StatefulAgent` SHALL maintain a ring buffer of the last 5 selected model actions with their results. When a model action is selected, before it is dispatched, an `ActionHistoryEntry` SHALL be appended to the buffer with an empty result. The entry's result SHALL be the outcome of that entry's own action, filled by the next state update before any LLM stage of that step runs, so that no prompt pairs an action with another action's outcome.

**`ActionHistoryEntry`** is a simple data class:

| Field | Type | Description |
|-------|------|-------------|
| `actionType` | `String` | "click", "long_click", "type_text", "back", "menu" |
| `widgetClass` | `String` | Widget class simple name (e.g., "Button", "EditText"), null for back, menu and an off-tree LLM tap |
| `widgetText` | `String` | Widget text or content-description, truncated to 50 chars |
| `bounds` | `Rect` | The executed widget's bounds in screen **pixels**: a copy of the resolved node's `getBoundsInScreen()`; for an off-tree `MODEL_LLM_TAP`, the one-pixel rectangle `[x,y][x+1,y+1]` at `LlmTapAction.getPixelX()`/`getPixelY()`; null for back and menu |
| `typedText` | `String` | For type_text: the text that was typed; null otherwise |
| `result` | `String` | "same", "new screen", "previous screen"; empty until the next state update fills it |

`StatefulAgent` SHALL NOT normalize and SHALL NOT read `AndroidDevice.getDisplayBounds()` for the history. `ApePromptBuilder` derives the displayed coordinate at build time (the centre of the bounds' part inside the display) with the dimensions the decision was captured at (`llm-prompt` "Action History"), so the list and the history of one prompt share one base.

An action with no resolved node SHALL record what ran: a `MODEL_LLM_TAP` records `click`, or `long_click` when `LlmTapAction.isLongClick()` is true, with the one-pixel rectangle at the tap's own pixel, and no class or text; a `MODEL_MENU` press records `menu` with no bounds. A node action whose bounds cannot be read records null bounds, never a `(0,0)` placeholder.

For a node action, `widgetClass` SHALL be produced by the helper the element list uses for the class simple name, and `widgetText` by the text-then-content-description steps of the list's identifier fallback (`llm-prompt` "Widget List Generation", INV-PRM-05), not by reading `getClassName()` or `getText()` directly. The list's `id=<shortId>` step is not applied (`pending.md` P2).

**Result determination**: In `updateStateInternal()`, after the new state is resolved and `_lastState`/`_stateBeforeLast` have moved, and before `resolveNewAction()` runs, the last entry of the buffer, if its result is still empty, receives the outcome of its action. At that point `newState` is the screen that action produced, `_lastState` the state it was taken from, and `_stateBeforeLast` the state before that:
- If `newState == _lastState` → `"same"`
- If `newState == _stateBeforeLast` → `"previous screen"`
- Else → `"new screen"`

The entry is immutable: the agent replaces it with a copy carrying the result. A filled result is never overwritten. When the step after an entry's action selects no model action, nothing is appended, and the next update finds the last entry already filled, so an outcome is never assigned to an action it does not belong to.

**Restart**: A restart step appends no entry and removes none, on either restart path ("SataAgent — Forced App Restart on Graph Stability"). When the `Restart` guard stage is mounted, it selects the restart before any model action is selected (`decision-pipeline` INV-DP-14), so no model action is recorded for that step. When the stage is not mounted, the post-selection `ApeAgent.checkRestart` runs before `recordActionHistory`, and a selected model action it replaces never reaches `recordActionHistory`, so no entry is appended for it. The start action is not a model action. No path removes an entry after it was appended, and `startNewEpisode` removes nothing on any path.

The buffer is passed to `ApePromptBuilder.build()` via `LlmEngine.selectAction()` as the `recentActions` parameter.

#### Scenario: Action history populated after action

- **WHEN** a click action on Button "Login" is selected
- **THEN** an `ActionHistoryEntry` SHALL be added with `actionType="click"`, `widgetClass="Button"`, `widgetText="Login"` and an empty result
- **AND WHEN** the next state update resolves a state different from the state the click was taken from and from the state before that
- **THEN** that entry's result SHALL be `"new screen"` before any LLM stage of that step runs

#### Scenario: Ring buffer evicts oldest entry

- **WHEN** the action history buffer has 5 entries
- **AND** a new model action is selected
- **THEN** the oldest entry SHALL be removed
- **AND** the new entry SHALL be appended
- **AND** the buffer size SHALL remain 5

#### Scenario: Action returns to previous screen

- **WHEN** a back action is selected from state S2, which was reached from state S1
- **AND** the next state update resolves S1
- **THEN** the back action's `ActionHistoryEntry.result` SHALL be `"previous screen"`

#### Scenario: each entry carries its own action's outcome
- **WHEN** click A1 is selected from state S1 and the next update resolves a new state S2
- **AND** click A2 is selected from S2 and the next update resolves S2 again
- **THEN** the prompt built at that update SHALL show `A1 → new screen` and `A2 → same`
- **AND** A2's entry SHALL NOT read `new screen`, the outcome of A1

#### Scenario: a step without a model action does not shift an outcome
- **WHEN** click A1 is selected and the next update fills its result
- **AND** that step selects an action that is not a model action, so no entry is appended
- **AND** the update after it resolves a different state
- **THEN** A1's result SHALL keep the value filled first

#### Scenario: a model action replaced by a restart leaves no entry
- **WHEN** click A1 is selected and the next update fills its result
- **AND** on the next step either the `Restart` guard selects a start action before any model action is selected, or, with the stage not mounted, the post-selection `checkRestart` replaces the selected click A2 with a start action
- **THEN** no entry SHALL be appended for that step, so no entry for a replaced action can exist
- **AND** A1's entry SHALL remain the last entry, with the result filled first

#### Scenario: a restart that replaces no model action removes nothing
- **WHEN** click A1 is selected and the next update fills its result
- **AND** a restart takes the following step with a start action, through the `Restart` guard or the post-selection `checkRestart`
- **THEN** A1's entry SHALL remain, with its result, and the buffer SHALL be unchanged

#### Scenario: entry carries the pixel bounds
- **WHEN** a click on a widget with bounds `[0,105][1080,252]` is selected
- **THEN** the entry SHALL carry the bounds `[0,105][1080,252]`, a copy rather than the node's own `Rect`
- **AND** no display dimension SHALL have been read to produce it

#### Scenario: off-tree LLM tap recorded at its pixel
- **WHEN** a `MODEL_LLM_TAP` at pixel `(600, 900)` with `isLongClick()` false is selected
- **THEN** the entry SHALL carry `actionType="click"`, the bounds `[600,900][601,901]`, and no widget class

#### Scenario: menu press recorded as menu
- **WHEN** a `MODEL_MENU` action is selected
- **THEN** the entry SHALL carry `actionType="menu"`, no bounds and no widget class

#### Scenario: icon with only a content-description
- **WHEN** a click is selected on a node of class `android.widget.ImageButton` with empty text and content-description `"Settings"`
- **THEN** the entry SHALL carry `widgetClass="ImageButton"` and `widgetText="Settings"`

---

### Requirement: SataAgent — LLM Stagnation Hook

LLM stagnation routing SHALL be the `LlmStagnation` decision stage, assembled after `LlmNewState` and before `LlmCoin` when the plan carries `LLM_STAGNATION`. The trigger is at-or-past the midpoint with the episode's single shot unspent: `graphStableCounter >= graphStableRestartThreshold / 2` AND the stage's per-episode fired flag is armed. The trigger consumes no coin (D-A17). When it holds, the same shared gate chain applies (`MinActionsGate`, then `BreakerGate`; `llm-routing` "Declared LLM Gates"). The action buffer is not a gate, and an accepted answer abandons a non-empty buffer without weakening (`decision-pipeline` INV-DP-15). A gate refusal leaves the flag armed, because no call was made.

**Episode-state ownership (moved out of `StatefulAgent`):** the fired flag SHALL be a private field of the `LlmStagnation` stage. It is burned inside `decide()` whenever the trigger fires and the gates admit the call — whatever the LLM answers (a null result is a failed attempt, not an unused one; the restart at the full threshold is what follows if the stagnation persists) — and re-armed by the stage's `onStateTransition` hook on `NEW_ACTION`/`NEW_ACTION_TARGET` edges (a new edge ends the episode). The stage's own reset of `graphStableCounter` after a successful escape does not re-arm the flag: no new edge was observed, so it is the same episode.

**One scenario below is a replacement, not a carry-forward.** `"Stagnation hook fires only once per phase"` asserted in the main spec that the hook fires *only* at `graphStableCounter == threshold/2` exactly — 49 and 51 both declined by the equality check. This change replaces that trigger with at-or-past the midpoint guarded by the episode flag, so the old body is contradicted rather than forgotten: 51 with the flag armed now fires. What the scenario's name asserts — one firing per episode — is what survives, and it survives structurally (the flag is burned in `decide()`, not re-derived from the counter), so the scenario is restated under its own header with the new mechanism as its body. The counter-jump case the old equality check would have missed is covered by `llm-routing :: Stagnation LLM Mode :: "midpoint skipped by a counter jump still fires"`.

`graphStableCounter` remains a `StatefulAgent`/`RunContext` field — it is shared exploration state consumed by the stability restart (`onGraphStable` raises the flag at `counter > threshold`, and the restart is applied by the `Restart` guard when the pipeline mounted it, or by the post-selection `checkRestart` otherwise; "SataAgent — Forced App Restart on Graph Stability"), which this stage does not modify. On an accepted escape the stage SHALL reset the counter to 0 through the `StepContext`'s single declared write method (unblocking exploration, unchanged semantics).

#### Scenario: LLM breaks stagnation (single-shot at midpoint)

- **WHEN** `graphStableCounter` reaches `graphStableRestartThreshold / 2` with the episode flag armed, the shared precondition holds, and the breaker allows
- **AND** the engine returns a non-null `ModelAction`
- **THEN** the action SHALL decide the step, the flag SHALL be burned, `graphStableCounter` SHALL be reset to `0`
- **AND** `requestRestart()` SHALL NOT be called

#### Scenario: Stagnation hook fires only once per phase

- **WHEN** `graphStableCounter` is `49` with `graphStableRestartThreshold=100` (below the midpoint) and the episode flag is armed
- **THEN** the stage SHALL NOT fire
- **WHEN** the counter then reaches `50` and the stage fires, burning the flag
- **THEN** every later step of the same episode — `51`, `52`, and every counter value up to the restart threshold — SHALL NOT fire again, whatever the counter reads
- **AND** the single shot SHALL be spent by the firing itself, not by the counter leaving the midpoint (the pre-change equality check declined `51` because `51 != 50`; the flag declines it because the episode is over)

#### Scenario: LLM fails at midpoint, stagnation continues to restart

- **WHEN** the trigger fires and the engine returns `null`
- **THEN** the flag SHALL be burned and the stage SHALL return `Continue`
- **AND** the counter SHALL continue incrementing and the stage SHALL NOT fire again this episode
- **AND** if `graphStableCounter` eventually exceeds `graphStableRestartThreshold`, `requestRestart()` SHALL be called in step 2's `checkStable`; with the `Restart` stage mounted, the `Restart` guard SHALL select the restart in that same step, before any LLM stage is evaluated (INV-EXPL-09, `decision-pipeline` "Restart Guard Stage"), and without it the post-selection `checkRestart` SHALL replace that step's selected action with the start action (INV-EXPL-32)

#### Scenario: new edge re-arms the episode through the stage hook

- **WHEN** the flag is burned and a later transition records a `NEW_ACTION` edge
- **THEN** `onStateTransition` SHALL re-arm the flag inside the stage
- **AND** a later stagnation reaching the midpoint SHALL fire again

#### Scenario: no stagnation state remains in StatefulAgent

- **WHEN** the change is complete
- **THEN** `StatefulAgent` SHALL declare no LLM-stagnation fired flag
- **AND** the only stagnation-episode state SHALL live in the `LlmStagnation` stage

---

### Requirement: Seeded Agent Decision Reproducibility

All agent-decision randomness routed through `RandomHelper` (priority roulettes such as `randomPickWithPriority`, uniform picks, `toss`, and `ApeFuzzer` gesture generation) SHALL be driven by a `java.util.Random` seeded from the Monkey `-s` seed. The seeded `java.util.Random` is owned by the `RunContext` (`run-spec` capability), constructed once at bootstrap as `RandomHelper.seed(seed ^ RANDOM_HELPER)`, where `seed` is the plan's seed — the same value that initializes `Monkey.mRandom` — and `RANDOM_HELPER` is a fixed odd 64-bit constant distinct from `StepRng`'s four stream constants (design D11). It is reached through the context, never through static state. `Monkey.mRandom` keeps `new Random(seed)`, so the two generators do not produce one sequence at two offsets, and `RUN_START.seed` keeps the run seed. INV-EXPL-14: given identical `-s` seed, APK, and configuration, the sequence of `RandomHelper` draws SHALL be identical across runs. Input-string generation SHALL draw from this same stream: the `StringCache` `ThreadLocalRandom` path (V23) and the `/sdcard/ape.strings` reader are deleted (owner decision D6), so no agent-decision or input randomness remains outside the seeded stream. Two runs launched with the same `-s`, the same APK, and the same configuration SHALL produce the same sequence of `RandomHelper` draws. (Historically `RandomHelper` used `ThreadLocalRandom.current()`, which cannot be seeded, and a residual `ThreadLocalRandom` survived in `StringCache.nextString()` — verified V23 — leaving input generation unseeded even after `RandomHelper` was fixed. Both are gone.)

#### Scenario: identical seeds produce identical draw sequences
- **WHEN** `RandomHelper.seed(42)` is called and a sequence of `randomPick`/`toss`/`nextInt` draws is recorded, then `RandomHelper.seed(42)` is called again
- **THEN** repeating the same sequence of calls SHALL yield the same values in the same order

#### Scenario: seed comes from the Monkey -s flag
- **WHEN** the Monkey is launched with `-s 12345`
- **THEN** `RandomHelper` SHALL be seeded with `12345 ^ RANDOM_HELPER` before the first agent decision
- **AND** `Monkey.mRandom` SHALL be `new Random(12345)`
- **AND** `RUN_START.seed` SHALL be `12345`

#### Scenario: helper seed differs from the Monkey seed
- **WHEN** the run seed is any value `s`
- **THEN** the seed handed to `RandomHelper` SHALL be `s ^ RANDOM_HELPER`, which differs from `s` because `RANDOM_HELPER` is non-zero
- **AND** `RANDOM_HELPER` SHALL differ from each of `StepRng`'s stream constants

---

### Requirement: Bounded Foreground Wait

When `waitForActivity` is active and the foreground package is not in the allowed set, `MonkeySourceApe.checkAppActivity()` throttles 100 ms and re-checks on the next `getNextEvent()`. When the plan carries the ADDONS member `APE_FIXES`, this wait SHALL be bounded: a consecutive-iteration counter SHALL be maintained, and when it exceeds 100 iterations (~10 s) the engine SHALL log `[APE-RV] waitForActivity exceeded 100 cycles, relaunching`, clear the wait state, and relaunch the app under test via the existing restart path (`startRandomMainApp`). The counter SHALL reset whenever the allowed package reaches the foreground. The bound is one condition, `has(APE_FIXES) && ++waitForActivityCycles > 100` (design D8). Without `APE_FIXES` (the `base` and `mop` cells) the wait SHALL be unbounded, as upstream APE: no relaunch is triggered by the wait and the log line is not emitted, so an app that never returns to the foreground consumes the rest of the `--running-minutes` budget in 100 ms throttles. INV-EXPL-15: when the plan carries `APE_FIXES`, no run SHALL spend more than 101 consecutive `checkAppActivity` iterations waiting for a foreground package without triggering a relaunch; without `APE_FIXES` the wait SHALL be unbounded.

#### Scenario: wedged app is relaunched
- **WHEN** the plan carries `APE_FIXES`
- **AND** the foreground package remains disallowed for 101 consecutive `checkAppActivity` iterations with `waitForActivity` active
- **THEN** the wait state SHALL be cleared
- **AND** the app under test SHALL be relaunched
- **AND** one `[APE-RV] waitForActivity exceeded 100 cycles, relaunching` line SHALL be emitted

#### Scenario: normal wait resets the counter
- **WHEN** the allowed package reaches the foreground after 5 wait iterations
- **THEN** the counter SHALL reset to 0
- **AND** no relaunch SHALL occur

#### Scenario: wait is unbounded without APE_FIXES
- **WHEN** the `base` cell runs and the foreground package remains disallowed for 101 consecutive `checkAppActivity` iterations with `waitForActivity` active
- **THEN** the wait state SHALL NOT be cleared and the app under test SHALL NOT be relaunched by the wait
- **AND** no `[APE-RV] waitForActivity exceeded 100 cycles, relaunching` line SHALL be emitted

---

### Requirement: Per-Step Debug Artifact Defaults

`ape.takeScreenshotForEveryStep` and `ape.saveGUITreeToXmlEveryStep` SHALL default to `false`. Both are plan keys read through `RunContext.current().spec().exploration()` (`takeScreenshotForEveryStep()`, `saveGUITreeToXmlEveryStep()`), with their defaults in `KeyOwnership`; `Config` has no field for either (design D-B24). Both are debug aids: the experiment pipeline consumes neither (coverage comes from logcat, the trace from stdout; the aperv-tool pulls no per-step PNG/XML), and the LLM path captures its own screenshots on demand via `ScreenshotCapture`, independent of these flags. Per-step PNG + XML writes are pure I/O overhead on the exploration loop (estimated 20-40% of step throughput on an emulator). Debug runs re-enable either flag via `ape.properties`.

`ape.takeScreenshotForNewState` is not a per-step artifact and SHALL default to `true` (a jar default in `KeyOwnership`, not a cell key; design D12). `StatefulAgent.onAddNode` saves one PNG per new abstract state when `spec().exploration().takeScreenshot()` and `takeScreenshotForNewState()` are both true. The capture's `MonkeySourceApe.nextImageWriter()` draws `Monkey.mRandom` once per new state, as upstream APE does with the same setting. The output contract is therefore: per-step PNG screenshots when `ape.takeScreenshot` (default `true`) and `ape.takeScreenshotForEveryStep` (default `false`) are both true, per-new-state PNG screenshots when `ape.takeScreenshot` and `ape.takeScreenshotForNewState` (default `true`) are both true, and per-step GUITree XML when `ape.saveGUITreeToXmlEveryStep` (default `false`) is true; by default one PNG per new abstract state is written and no per-step artifact (INV-EXPL-17).

#### Scenario: defaults are off
- **WHEN** `ape.properties` contains neither key
- **THEN** the resolved plan's `takeScreenshotForEveryStep()` SHALL be `false`
- **AND** its `saveGUITreeToXmlEveryStep()` SHALL be `false`
- **AND** no per-step PNG or XML SHALL be written

#### Scenario: debug re-enable
- **WHEN** `ape.properties` contains `ape.saveGUITreeToXmlEveryStep=true`
- **THEN** a `step-N.xml` SHALL be written each step, as before

#### Scenario: LLM screenshots unaffected
- **WHEN** `ape.takeScreenshotForEveryStep=false` and the LLM routes a step
- **THEN** `ScreenshotCapture` SHALL still capture its on-demand screenshot for the prompt

#### Scenario: new-state screenshot on by default
- **WHEN** `ape.properties` sets neither `ape.takeScreenshot` nor `ape.takeScreenshotForNewState`
- **THEN** the resolved plan's `takeScreenshotForNewState()` SHALL be `true`
- **AND** a new abstract state added to the model SHALL produce one PNG named after the state in the output directory
- **AND** a revisited state SHALL produce no PNG

---

### Requirement: Fuzz Gesture Emission

Every branch of the fuzzer's default gesture switch (drag, pinch/zoom, click) SHALL build exactly one gesture per invocation. Whether `generatePinchOrZoomEvent` appends its event is decided by the ADDONS member `APE_FIXES` (design D9): with `APE_FIXES` in the plan it SHALL end with `events.add(new ApePinchOrZoomEvent(points))`, so every branch appends exactly one event; without `APE_FIXES` (the `base` and `mop` cells) it SHALL build the points with the same draws and SHALL NOT append the event, as upstream APE, whose pinch/zoom gesture was built and discarded. The drag and click branches append their event in every cell. INV-EXPL-18: every fuzz gesture branch SHALL build exactly one gesture per invocation; with `APE_FIXES` every branch SHALL append that gesture's event, and without it only the pinch/zoom branch SHALL NOT append its event.

Before appending or discarding, `generatePinchOrZoomEvent` SHALL size the `points` array to exactly the number of entries it writes, `6 + (count << 1)`, and emit no `null` entry, in every cell: the size consumes no draw. The former allocation `new PointF[4 + count << 1]` evaluated by Java precedence to `(4 + count) << 1` = `8 + 2·count`, while only `6 + 2·count` entries are written — leaving two trailing `null` slots that the constructor would dereference. The `ApePinchOrZoomEvent` constructor SHALL validate the array length (reject arrays shorter than 6 entries — 1 count slot + 1 duration slot + 2 coordinates × (count+1) pointers with count ≥ 0 gives the minimum valid payload of 6) BEFORE dereferencing any element, so a malformed array is rejected rather than throwing `NullPointerException`.

#### Scenario: pinch/zoom branch emits
- **WHEN** the plan carries `APE_FIXES`
- **AND** the fuzzer's gesture switch selects the pinch/zoom branch
- **THEN** exactly one `ApePinchOrZoomEvent` SHALL be appended to the event list
- **AND** its points array SHALL have at least 6 entries

#### Scenario: emitted points array has no null entries
- **WHEN** the pinch/zoom branch builds its `points` array for any `count ≥ 0`, with or without `APE_FIXES`
- **THEN** the array SHALL have length equal to the number of written entries, `6 + (count << 1)`
- **AND** it SHALL contain no `null` element and, when passed to `ApePinchOrZoomEvent`, construction SHALL NOT throw `NullPointerException`

#### Scenario: short payload rejected
- **WHEN** an `ApePinchOrZoomEvent` is constructed with 5 points
- **THEN** the constructor SHALL reject it before dereferencing any element

#### Scenario: pinch/zoom branch without APE_FIXES appends nothing
- **WHEN** the `base` cell's fuzzer selects the pinch/zoom branch with a given `RandomHelper` state
- **THEN** the branch SHALL make the same `RandomHelper` draws it makes with `APE_FIXES`
- **AND** no event SHALL be appended to the event list

---

### Requirement: Off-Screen Action Handling

When the node overload `generateClickEventAt(Rect nodeRect, long waitTime, ClickPoint clickPoint)` obtains null visible bounds for a node action — the target node's bounds do not intersect the visible screen (`getVisibleBounds` returns null) — the outcome is decided by the ADDONS member `APE_FIXES` (design D7), at that overload only:

- **With `APE_FIXES` in the plan** (the ADDONS cells), it SHALL NOT emit any touch event and SHALL log one `[APE-RV] off-screen action dropped: <rect>` line, where `<rect>` is the node rect handed to `generateClickEventAt`. It SHALL NOT substitute the display bounds and click the screen center: that behavior executed an unrelated click while the model credited the original action, creating false edges (260 occurrences measured across 17/1513 baseline runs).
- **Without `APE_FIXES`** (the `base` and `mop` cells), it SHALL log APE's `Error to fetch bounds.` warning, substitute `AndroidDevice.getDisplayBounds()` for the missing bounds, and compute the click point on the display bounds, so the default `ClickPoint.CENTER` click lands at the display centre, as upstream APE. No `[APE-RV] off-screen action dropped` line is emitted for that substitution.

In every cell the invalid-bounds branch (`!bounds.contains(p)`) keeps its no-event behavior and logs `Invalid bounds: <bounds>` followed by the same `[APE-RV] off-screen action dropped: <rect>` line. Visit/coverage crediting of the action is unchanged on both paths (the markVisited-before-event-generation reorder is a separate, deferred item); the log line makes the wasted-step frequency measurable.

For coordinate-carrying actions without a resolved node (`MODEL_LLM_TAP`), the rect handed to
`generateClickEventAt` MUST be the minimal non-degenerate rect anchored at the decided pixel —
`(x, y, x+1, y+1)` — never the zero-area `(x, y, x, y)`: an empty rect is unconditionally dropped by
both guard branches, which silently disables the action (cmpm finding A1: 16,625/16,625 taps
dropped). The validity domain of a coordinate tap is the **physical display bounds**
(`AndroidDevice.getDisplayBounds()`), not the app root-node visible bounds: the coordinate is
decided from a full-display screenshot, and its legitimate targets include cross-window elements
(IME keyboards, dialogs) that lie outside the app root node (measured: 6/7 thumbkey gate taps and
~50% of cmpm v2 taps dropped on the root-node domain). A coordinate outside the display produces
the log line and no event in every cell (INV-EXPL-30): the display-bounds substitution of the path
without `APE_FIXES` lives in the 3-arg node overload, and the explicit-bounds overload
`generateClickEventAt(Rect, long, ClickPoint, Rect bounds)`, through which `MODEL_LLM_TAP` passes
`clipToDisplay(...)`, drops null bounds in every cell. Node actions keep the root-node visible-bounds
domain unchanged.

INV-EXPL-19: when the plan carries `APE_FIXES`, no touch event SHALL ever be delivered to coordinates derived from bounds other than the resolved node's own bounds — or, for `MODEL_LLM_TAP`, other than the action's own decided coordinate. Without `APE_FIXES`, a node action whose bounds miss the visible screen SHALL be clicked on the display bounds, as upstream APE; a `MODEL_LLM_TAP` keeps INV-EXPL-30 in every cell.

#### Scenario: off-screen node produces no event
- **WHEN** the plan carries `APE_FIXES`
- **AND** a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped: <rect>` line SHALL be emitted, naming the node rect
- **AND** no click SHALL be delivered to the screen center

#### Scenario: on-screen node unchanged
- **WHEN** the node's bounds intersect the visible screen
- **THEN** click generation SHALL be identical to the previous implementation

#### Scenario: in-bounds coordinate tap is not dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(540, 1158)` is dispatched and the display bounds are
  `(0, 0, 1080, 1920)`
- **THEN** the rect handed to the guard SHALL be `(540, 1158, 541, 1159)`
- **AND** a touch down/up pair SHALL be enqueued at that point
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: coordinate tap below the app window but inside the display injects
- **WHEN** the app root-node bounds are `(0, 0, 1080, 1400)`, the display bounds are
  `(0, 0, 1080, 1920)`, and a `MODEL_LLM_TAP` carrying pixel `(424, 1618)` is dispatched (the
  IME band that the llm-tap-injection gate measured as dropped)
- **THEN** a touch down/up pair SHALL be enqueued at `(424, 1618)`
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: out-of-display coordinate tap is dropped
- **WHEN** a `MODEL_LLM_TAP` carrying pixel `(1080, 500)` is dispatched and the display bounds are
  `(0, 0, 1080, 1920)` (x equals the exclusive right edge), with or without `APE_FIXES`
- **THEN** no touch event SHALL be enqueued
- **AND** one `[APE-RV] off-screen action dropped` line SHALL be emitted

#### Scenario: off-screen node without APE_FIXES clicks the display centre
- **WHEN** the `base` cell runs, the display bounds are `(0, 0, 1080, 1920)`, and a MODEL_CLICK resolves to a node whose bounds do not intersect the visible screen
- **THEN** one `Error to fetch bounds.` warning SHALL be logged
- **AND** a touch down/up pair SHALL be enqueued at the display centre `(540, 960)`
- **AND** no `[APE-RV] off-screen action dropped` line SHALL be emitted
