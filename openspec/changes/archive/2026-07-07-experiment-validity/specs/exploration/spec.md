## ADDED Requirements

### Requirement: Seeded Agent Decision Reproducibility

All agent-decision randomness routed through `RandomHelper` (priority roulettes such as `randomPickWithPriority`, uniform picks, `toss`, and `ApeFuzzer` gesture generation) SHALL be driven by a `java.util.Random` seeded from the Monkey `-s` seed. `MonkeySourceApe` SHALL call `RandomHelper.seed(seed)` exactly once during construction, with the same seed value that initializes `Monkey.mRandom`. Two runs launched with the same `-s`, the same APK, and the same configuration SHALL produce the same sequence of `RandomHelper` draws. (Previously `RandomHelper` used `ThreadLocalRandom.current()`, which cannot be seeded; the `-s` flag governed only the small subset of decisions using `mRandom`, so no run was reproducible.)

#### Scenario: identical seeds produce identical draw sequences
- **WHEN** `RandomHelper.seed(42)` is called and a sequence of `randomPick`/`toss`/`nextInt` draws is recorded, then `RandomHelper.seed(42)` is called again
- **THEN** repeating the same sequence of calls SHALL yield the same values in the same order

#### Scenario: seed comes from the Monkey -s flag
- **WHEN** the Monkey is launched with `-s 12345`
- **THEN** `RandomHelper` SHALL be seeded with `12345` before the first agent decision

### Requirement: Bounded Foreground Wait

When `waitForActivity` is active and the foreground package is not in the allowed set, `MonkeySourceApe.checkAppActivity()` throttles 100 ms and re-checks on the next `getNextEvent()`. This wait SHALL be bounded: a consecutive-iteration counter SHALL be maintained, and when it exceeds 100 iterations (~10 s) the engine SHALL log `[APE-RV] waitForActivity exceeded 100 cycles, relaunching`, clear the wait state, and relaunch the app under test via the existing restart path (`startRandomMainApp`). The counter SHALL reset whenever the allowed package reaches the foreground. (Previously there was no counter, timeout, or relaunch: an app that never returned to the foreground consumed the entire `--running-minutes` budget in 100 ms throttles, producing a run with zero actions.)

#### Scenario: wedged app is relaunched
- **WHEN** the foreground package remains disallowed for 101 consecutive `checkAppActivity` iterations with `waitForActivity` active
- **THEN** the wait state SHALL be cleared
- **AND** the app under test SHALL be relaunched
- **AND** one `[APE-RV] waitForActivity exceeded 100 cycles, relaunching` line SHALL be emitted

#### Scenario: normal wait resets the counter
- **WHEN** the allowed package reaches the foreground after 5 wait iterations
- **THEN** the counter SHALL reset to 0
- **AND** no relaunch SHALL occur

## MODIFIED Requirements

### Requirement: Output Persistence on Termination

On termination — normal (when `StopTestingException` is caught) or abnormal (any other `Throwable` escaping the exploration loop) — the exploration engine SHALL save graph artefacts to the output directory. The `tearDown()` call chain (agent teardown, model serialisation, coverage dump, timeline) SHALL execute inside a `finally` block in `Monkey`, so an uncaught `RuntimeException` from the event loop still produces the run's outputs before the process exits. The serialised graph (`sataModel.obj`) is written by `ObjectOutputStream` and contains the full in-memory `Graph` object. The Graphviz file (`sataGraph.dot`) is a DOT representation of every state and transition. The visualisation file (`sataGraph.vis.js`) is a vis.js JSON representation. All writes MUST complete before the process exits. If `ape.saveObjModel=false`, the `sataModel.obj` file SHALL NOT be written (default `true`). If `ape.saveDotGraph=false`, the `sataGraph.dot` file SHALL NOT be written (default `false`). If `ape.saveVisGraph=false`, the `sataGraph.vis.js` file SHALL NOT be written (default `true`).

#### Scenario: Normal termination with defaults

- **WHEN** `StopTestingException` is caught after the time limit expires
- **AND** `ape.saveObjModel` and `ape.saveVisGraph` are both at their defaults (`true`), and `ape.saveDotGraph` is at its default (`false`)
- **THEN** `sataModel.obj` SHALL be written to the output directory via Java object serialisation
- **AND** `sataGraph.vis.js` SHALL be written to the output directory as a vis.js JSON file
- **AND** `sataGraph.dot` SHALL NOT be written (disabled by default)
- **AND** all writes SHALL complete before the process returns

#### Scenario: saveObjModel disabled

- **WHEN** `ape.saveObjModel=false` is set in `ape.properties`
- **AND** the session terminates normally
- **THEN** `sataModel.obj` SHALL NOT be created or overwritten
- **AND** `sataGraph.vis.js` SHALL still be written (independent flag)

#### Scenario: abnormal termination still persists outputs

- **WHEN** a `RuntimeException` escapes the exploration loop
- **THEN** `tearDown()` SHALL still run (via `finally`) and write the enabled artefacts before the process exits
- **AND** the exception SHALL still propagate (the run is reported as failed)

## Invariants

- **INV-EXPL-14**: Given identical `-s` seed, APK, and configuration, the sequence of `RandomHelper` draws SHALL be identical across runs.
- **INV-EXPL-15**: No run SHALL spend more than 101 consecutive `checkAppActivity` iterations waiting for a foreground package without triggering a relaunch.
- **INV-EXPL-16**: `tearDown()` SHALL run on every termination path of the exploration loop, normal or abnormal.
