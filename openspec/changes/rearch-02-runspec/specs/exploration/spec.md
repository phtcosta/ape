# Delta Specification: exploration (rearch-02-runspec)

## Invariants

- **INV-EXPL-03** is **REMOVED**. It documented that `sataModel.obj` contains a serialized `Model` (not a `Graph`) — the write half of a persistence protocol verified broken by construction (writes `Model`, reads with a `Graph` cast, swallows the `ClassCastException`, returns an empty graph). The protocol is deleted by this change (report Sec. 8: deliberately dissolved; R1/R3 — clean runs, no read-back). Nothing replaces it: there is no serialized model artifact to constrain.
- **INV-EXPL-29** step list updated: teardown steps are now LLM summary, `super.tearDown()`, coverage dump, action-history save, action counters, activity nodes, naming dump, model counters (the graph-save step no longer exists). The isolation semantics are unchanged.
- **INV-EXPL-16**, **INV-EXPL-14** unchanged (restated here only because their surrounding requirements are modified below).

## ADDED Requirements

### Requirement: Run Lifecycle Opens with RUN_START

The exploration run lifecycle SHALL begin with plan resolution and the `RUN_START` echo (specified by the `run-spec` capability): `RunSpec.resolve` runs in `Monkey.run` before `MonkeySourceApe` is constructed, an invalid plan aborts the process before step 1, and a valid plan is echoed as a single JSON object line before any exploration output. The exploration engine SHALL NOT begin capturing GUI trees, constructing agents, or injecting events until the plan is resolved and echoed.

#### Scenario: no exploration before a valid plan

- **WHEN** the process starts with an invalid `ape.properties` (e.g., an unknown key)
- **THEN** the process SHALL exit nonzero with the `[APE-RUNSPEC-ABORT]` diagnostic
- **AND** zero GUI trees SHALL have been captured and zero events injected

#### Scenario: echo precedes the first step

- **WHEN** the process starts with a valid plan
- **THEN** `RUN_START` SHALL appear in the trace before the first step's output

## MODIFIED Requirements

### Requirement: Strategy Selection

The exploration strategy MUST be selected at process startup via the `--ape <strategy>` command-line argument passed to `Monkey`, and the agent type is **validated plan data**: it is carried by the resolved `RunSpec` (`run-spec` capability), not by a mutable property. The argument value is a case-sensitive string. The three legal values are `sata` (creates `SataAgent`), `random` (creates `RandomAgent`), and `replay` (creates `ReplayAgent`; requires `--ape-replay <log>`). If the argument is absent, the strategy SHALL default to `sata` (a documented default; the aperv deployment always passes the flag). If the argument does not match a legal value — including `bfs`, `dfs`, and `ape`, which previously fell through silently to `SataAgent` — the process SHALL abort with a diagnostic naming the valid set, before step 1. `ApeAgent.createAgent` SHALL contain no fallback arm.

The agent type SHALL NOT be settable from a properties file: `ape.agentType` (and `ape.replayLog`) appearing in `/data/local/tmp/ape.properties` or `/sdcard/ape.properties` is a retired-key abort — a stray device file can no longer swap the agent of a run. The strategy object is constructed once and shared for the entire session; it MUST NOT be replaced or re-instantiated during a running session.

#### Scenario: Valid strategy argument provided

- **WHEN** the process is launched with `app_process ... com.android.commands.monkey.Monkey -p com.example.app --ape sata`
- **THEN** a `SataAgent` instance SHALL be created and used for all `Agent.updateState()` calls for the duration of the session
- **AND** no other agent type SHALL be instantiated

#### Scenario: Strategy argument is `random`

- **WHEN** the process is launched with `--ape random`
- **THEN** a `RandomAgent` instance SHALL be created
- **AND** `RandomAgent.selectNewActionNonnull()` SHALL be called at each exploration step instead of any `SataAgent` method

#### Scenario: Strategy argument is absent

- **WHEN** the process is launched without an `--ape` argument
- **THEN** the strategy SHALL default to `sata` and a `SataAgent` SHALL be created

#### Scenario: Unknown strategy aborts instead of falling back

- **WHEN** the process is launched with `--ape bfs`
- **THEN** the process SHALL exit nonzero with a diagnostic naming the valid set `{sata, random, replay}`
- **AND** no agent SHALL be constructed

#### Scenario: Properties file cannot swap the agent

- **WHEN** `/sdcard/ape.properties` contains `ape.agentType=random` and the process is launched with `--ape sata`
- **THEN** the process SHALL abort with a retired-key diagnostic (`ape.agentType` is CLI-only)

---

### Requirement: Output Persistence on Termination

On termination — normal (when `StopTestingException` is caught) or abnormal (any other `Throwable` escaping the exploration loop) — the exploration engine SHALL run its teardown chain inside a `finally` block in `Monkey`, so an uncaught `RuntimeException` from the event loop still produces the run's outputs before the process exits. **The legacy graph persistence no longer exists**: `sataModel.obj`, `sataGraph.dot`, and `sataGraph.vis.js` SHALL NOT be written, `StatefulAgent.saveGraph()` SHALL NOT exist, and the keys `ape.saveObjModel`/`ape.saveDotGraph`/`ape.saveVisGraph` are retired (their presence aborts resolution). Resilience is the Python supervisor's retry; no run state survives the process (R1/R3).

The teardown chain in `StatefulAgent.tearDown` SHALL be, in order: LLM summary, `super.tearDown()`, **coverage dump**, action-history save, action counters, activity nodes, naming dump, model counters — each step isolated via `safeStep` (INV-EXPL-29). The coverage dump SHALL run strictly before the first teardown artifact write (the action-history save, the only remaining `/sdcard`-writing step) — this restates INV-COV-10's boundary now that the model serialization it was originally ordered against no longer exists; the protected property (the fragile emission precedes the expensive write) is unchanged.

The `finally` block in `Monkey.run` SHALL guard each of its throw-capable statements individually: the rotation restore (`MonkeyRotationEvent.injectEvent`) and the `MonkeySourceApe.tearDown()` call each run inside their own catch that logs the failure with a full stack trace. A failure in the rotation restore SHALL NOT skip teardown, and a failure anywhere in teardown SHALL NOT replace the exception that terminated the loop (INV-EXPL-16): after the `finally` completes, the original loop exception SHALL propagate to `Monkey.main`'s outermost handler and be the stack trace reported for the run.

#### Scenario: normal termination writes no graph artifacts

- **WHEN** `StopTestingException` is caught after the time limit expires
- **THEN** no `sataModel.obj`, `sataGraph.dot`, or `sataGraph.vis.js` SHALL exist in the output directory
- **AND** the coverage dump and `action-history.log` SHALL be produced as before

#### Scenario: coverage dump precedes the first artifact write

- **WHEN** a run reaches teardown
- **THEN** the `[APE-RV] UICOV` / `UICOV-ACT` lines SHALL appear in the trace before any output of the action-history save

#### Scenario: abnormal termination still runs teardown

- **WHEN** a `RuntimeException` escapes the exploration loop
- **THEN** `tearDown()` SHALL still run (via `finally`) and the exception SHALL still propagate (the run is reported as failed)

#### Scenario: teardown failure does not mask the loop exception

- **WHEN** an `IllegalStateException` escapes the exploration loop
- **AND** a teardown step (e.g. action-history persistence) throws its own `RuntimeException`
- **THEN** the teardown failure SHALL be logged with its full stack trace
- **AND** the exception reported by `Monkey.main`'s outermost handler SHALL be the original loop `IllegalStateException`, not the teardown failure

#### Scenario: rotation-restore failure does not skip teardown

- **WHEN** the exploration loop terminates and `MonkeyRotationEvent.injectEvent` throws (e.g. a binder failure)
- **THEN** the failure SHALL be logged
- **AND** `MonkeySourceApe.tearDown()` SHALL still execute

#### Scenario: one failing agent-teardown step does not skip the rest

- **WHEN** `StatefulAgent.tearDown()` runs and `saveActionHistory()` throws a `RuntimeException`
- **THEN** the failure SHALL be logged with its stack trace
- **AND** the action counters, activity nodes, naming dump, and model counters SHALL still be printed

---

### Requirement: Configuration Loading

All tuning parameters MUST be loaded from `ape.properties` at process startup — `/data/local/tmp/ape.properties` first, then `/sdcard/ape.properties` (later wins) — and the loaded entries SHALL be **validated in full by `RunSpec.resolve`** (`run-spec` capability) before any component consumes them: unknown keys, foreign keys, retired keys, type-invalid values, missing feature dependencies, and invalid combinations abort the process before step 1. JVM system properties are not a configuration channel for `ape.*` behavior. All surviving `Config` fields are `public static final`, resolved once at class-loading time, and MUST NOT change for the lifetime of the process; the five formerly non-final test knobs no longer exist (tests construct `RunSpec` values instead). `Config` is a loading detail; the resolved `RunSpec` is the behavioral authority.

The table below lists the configuration keys relevant to the exploration engine with their types and defaults (unchanged values):

| Key | Type | Default | Description |
|---|---|---|---|
| `ape.graphStableRestartThreshold` | int | 100 | Steps without graph growth before forced restart |
| `ape.stateStableRestartThreshold` | int | 50 | Steps in same state before forced restart |
| `ape.activityStableRestartThreshold` | int | 200 | Steps in same activity before forced restart |
| `ape.evolveModel` | boolean | true | Enable CEGAR naming refinement |
| `ape.doFuzzing` | boolean | true | Enable random fuzzing injection |
| `ape.fuzzingRate` | double | 0.02 | Probability of fuzzing per step |
| `ape.fuzzingActivityVisitThreshold` | int | 10 | Minimum activity visits before fuzzing activates |
| `ape.defaultEpsilon` | double | 0.05 | Epsilon for SataAgent epsilon-greedy |
| `ape.takeScreenshot` | boolean | true | Save screenshots |
| `ape.saveGUITreeToXmlEveryStep` | boolean | false | Save GUITree XML per step (see "Per-Step Debug Artifact Defaults" / INV-EXPL-17) |
| `ape.defaultGUIThrottle` | long (ms) | 200 | Delay between injected events |
| `ape.trivialActivityRankThreshold` | int | 3 | Minimum activity count before trivial-activity logic activates |

The keys `ape.saveObjModel`, `ape.saveDotGraph`, and `ape.saveVisGraph` are retired (persistence removal) and abort resolution if present.

#### Scenario: Property file present on device

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.graphStableRestartThreshold=200`
- **THEN** `Config.graphStableRestartThreshold` SHALL equal `200` for the entire session
- **AND** the default value of `100` SHALL NOT be used

#### Scenario: No property file present

- **WHEN** neither `/data/local/tmp/ape.properties` nor `/sdcard/ape.properties` exists
- **THEN** all `Config` fields SHALL take their hardcoded default values
- **AND** resolution SHALL succeed (an absent file is valid; an invalid entry is not)

#### Scenario: Typo aborts instead of defaulting

- **WHEN** `/data/local/tmp/ape.properties` contains `ape.fuzzingRate=O.02` (letter O)
- **THEN** the process SHALL abort with an invalid-type diagnostic before step 1

### Requirement: Exploration Loop Termination

The exploration loop inside `MonkeySourceApe.nextEventImpl()` SHALL run continuously until a stop condition is reached. Two stop conditions are supported: (1) elapsed wall-clock time exceeds the value specified by `--running-minutes N`, and (2) the total Monkey event count reaches the value specified by `-v N`. When either condition is detected, the agent SHALL throw `StopTestingException`, which `MonkeySourceApe` catches to exit the loop. The loop MUST NOT exit silently on any other condition. Crashes and ANRs in the app under test MUST be logged and counted but MUST NOT terminate the loop unless a stop condition is also triggered.

#### Scenario: Time limit reached

- **WHEN** `--running-minutes 30` is specified and 30 minutes of wall-clock time have elapsed since the session started
- **THEN** the agent SHALL throw `StopTestingException`
- **AND** `MonkeySourceApe` SHALL catch the exception and proceed to the teardown phase (the teardown chain; **no** `sataModel.obj` or `sataGraph.vis.js` is written — the persistence protocol and the graph dumps are deleted by this change)

#### Scenario: App crash does not stop exploration

- **WHEN** the app under test crashes (process dies) during a step
- **AND** the time limit has not been reached
- **THEN** `Agent.appCrashed()` SHALL be called and the crash SHALL be logged
- **AND** the agent SHALL initiate an app restart via an `EVENT_RESTART` action
- **AND** the exploration loop SHALL continue from the restarted app state

### Requirement: Seeded Agent Decision Reproducibility

All agent-decision randomness routed through `RandomHelper` (priority roulettes such as `randomPickWithPriority`, uniform picks, `toss`, and `ApeFuzzer` gesture generation) SHALL be driven by a `java.util.Random` seeded from the Monkey `-s` seed. The seeded `java.util.Random` is owned by the `RunContext` (`run-spec` capability), constructed once at bootstrap from the plan's seed — the same value that initializes `Monkey.mRandom` — and reached through the context, never through static state. Input-string generation SHALL draw from this same stream: the `StringCache` `ThreadLocalRandom` path (V23) and the `/sdcard/ape.strings` reader are deleted by this change (owner decision D6), so no agent-decision or input randomness remains outside the seeded stream. Two runs launched with the same `-s`, the same APK, and the same configuration SHALL produce the same sequence of `RandomHelper` draws. (Historically `RandomHelper` used `ThreadLocalRandom.current()`, which cannot be seeded, and a residual `ThreadLocalRandom` survived in `StringCache.nextString()` — verified V23 — leaving input generation unseeded even after `RandomHelper` was fixed. Both are gone at this stage.)

#### Scenario: identical seeds produce identical draw sequences
- **WHEN** `RandomHelper.seed(42)` is called and a sequence of `randomPick`/`toss`/`nextInt` draws is recorded, then `RandomHelper.seed(42)` is called again
- **THEN** repeating the same sequence of calls SHALL yield the same values in the same order

#### Scenario: seed comes from the Monkey -s flag
- **WHEN** the Monkey is launched with `-s 12345`
- **THEN** `RandomHelper` SHALL be seeded with `12345` before the first agent decision

### Requirement: OptionsMenu Systematic Exploration (MODEL_MENU)

Every `State` object SHALL hold a `menuAction` field of type `ModelAction(this, ActionType.MODEL_MENU)`, initialised in the `State` constructor immediately after `backAction`. The field SHALL be exposed via `State.getMenuAction()` and SHALL be non-null for the life of the state. This mirrors the `backAction` / `getBackAction()` pattern exactly.

Inclusion of the `menuAction` in the state's **selectable** action set is gated by `Config.modelMenuEnabled` (declared by the `scoring-pipeline` capability; default `true`). When `modelMenuEnabled` is `true` (default), the `menuAction` SHALL be included in the array returned by `State.getActions()`, exactly as before this change. When `modelMenuEnabled` is `false` (the feature absent from the resolved plan — `run-spec` INV-RUN-05), the `menuAction` SHALL NOT be included in `State.getActions()` and the agent SHALL never select `MODEL_MENU`; the field SHALL still be constructed and returned by `State.getMenuAction()` (so `INV-EXPL-06` holds). This reproduces upstream APE, which has no model-level options-menu action.

`MonkeySourceApe.generateEventsForActionInternal()` SHALL handle `MODEL_MENU` in its switch statement by calling `generateKeyMenuEvent()`. No target widget node is required or inspected.

`MonkeySourceApe.validateResolvedAction()` SHALL return `true` for `MODEL_MENU` without calling any widget validator (same pattern as `MODEL_BACK`).

#### Scenario: State constructor initialises menuAction
- **WHEN** a new `State` is constructed for any `StateKey`
- **THEN** `state.getMenuAction()` MUST return a non-null `ModelAction` whose `getType()` returns `ActionType.MODEL_MENU`
- **AND** when `Config.modelMenuEnabled` is `true` (default) the `menuAction` MUST be included in the actions array returned by `state.getActions()`

#### Scenario: MODEL_MENU excluded from selection when modelMenuEnabled is false
- **WHEN** `Config.modelMenuEnabled` is `false` and a new `State` is constructed
- **THEN** `state.getMenuAction()` MUST still return a non-null `ModelAction` of type `MODEL_MENU`
- **AND** `state.getActions()` MUST NOT contain the `menuAction`
- **AND** the agent MUST never select `MODEL_MENU` for that state

#### Scenario: MODEL_MENU event generation
- **WHEN** `MonkeySourceApe.generateEventsForActionInternal()` is called with a `ModelAction` whose type is `MODEL_MENU`
- **THEN** `generateKeyMenuEvent()` SHALL be called
- **AND** no target `GUITreeNode` SHALL be required or consulted

#### Scenario: MODEL_MENU validation always passes
- **WHEN** `MonkeySourceApe.validateResolvedAction()` is called with a `ModelAction` of type `MODEL_MENU`
- **THEN** the method SHALL return `true`
- **AND** no widget validator (`validateClickAction`, `validateScrollAction`) SHALL be invoked
