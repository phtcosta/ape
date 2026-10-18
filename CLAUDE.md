# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

APE-RV is a fork of APE (Android Property Explorer), a model-based Android GUI testing tool from ETH Zurich's AST Lab (ICSE 2019). This fork is developed at the University of Brasília as part of the RVSEC research infrastructure. It extends the original APE with: a modernized build system (Phase 1), AndroidX UI coverage improvements and MODEL_MENU exploration (Phase 2), MOP-guided action scoring (Phase 3), and an aperv plugin for rv-android (Phase 4).

**Current repository state**: Maven+d8+Java 11 build → `target/ape-rv.jar`. All phases complete: Phase 1 (build), Phase 2 (AndroidX/MODEL_MENU), Phase 3 (MOP-guided action scoring with configurable weights + navigation-level MOP density tiebreaker), Phase 4 (aperv-tool in rv-android), Phase 5 (LLM integration via SGLang/Qwen3-VL for new-state and stagnation-breaking exploration). Behavior is organised in four independently switchable groups over BASE (≈ upstream APE) — see *Behavior groups and cells* below.

## Build Commands

Requires: Java 11+, Apache Maven, Android SDK with `d8` in PATH (build-tools 28+).

```bash
mvn compile    # Compile Java source to bytecode (target/classes/)
mvn package    # Compile + convert to Dalvik bytecode → produces target/ape-rv.jar
mvn clean      # Remove build artifacts (target/)
mvn install -Drvsec_home=<path>   # package + copy ape-rv.jar to aperv-tool module in rv-android
                                  # <path> = root of rvsec workspace (e.g. /pedro/.../workspace-rv/rvsec)
                                  # copies to: <path>/rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/ape-rv.jar
```

The build uses `framework/classes-full-debug.jar` and `dalvik_stub/classes.jar` as compile-time-only dependencies (system scope). They must not appear in `target/ape-rv.jar`. Source layout: `src/main/java/`. Java release compatibility: 11.

## Running APE

### Normal workflow (via rv-platform)

In the RVSEC research infrastructure, rv-platform manages the emulator lifecycle (start, wait-for-boot, install APK, run tool, collect results). Use `aperv` as a registered tool plugin — do not start the emulator manually in this path.

### Standalone testing (device validation only)

For validating a new build outside rv-platform, start the RVSec AVD manually:

```bash
scripts/run_emulator.sh          # starts emulator @RVSec and blocks
```

Then in another terminal:

```bash
adb root                         # API 30 (the RVSec AVD): as the shell uid the first launch is denied
adb push target/ape-rv.jar /data/local/tmp/
adb install -r -g cryptoapp-aligned.apk   # see below: test-apks/cryptoapp.apk needs re-aligning first
adb shell CLASSPATH=/data/local/tmp/ape-rv.jar app_process /system/bin \
  com.android.commands.monkey.Monkey -p br.unb.cic.cryptoapp \
  --running-minutes 1 --ape sata
```

Two things the AVD imposes (found in the llm-coordinate-single-base smoke, 2026-10-08):
- **`adb root`.** `MonkeyActivityEvent` launches the app through `startActivityAsUser` with a null
  calling package. On API 30 the `shell` uid gets `Permission Denial: package=null does not belong
  to uid=2000`, and Monkey stops at event 1 with `SecurityException while injecting event`.
- **Re-aligned APK.** `test-apks/cryptoapp.apk` targets API 33 with a compressed `resources.arsc`,
  which API 30 refuses (`Failure [-124 …]`). Work on a copy outside the repository: store
  `resources.arsc` uncompressed (`zip -0`), drop `META-INF/` signatures, `zipalign -p -f 4`, and
  re-sign with `apksigner` and a throwaway key.

**Test APKs** (`test-apks/` — `.apk` files are gitignored, copy from rv-android results):

| File | Description |
|------|-------------|
| `test-apks/cryptoapp.apk` | Instrumented APK from rv-android experiment `cli_experiment_20260305_155802_9bd8c909` |
| `test-apks/cryptoapp.apk.json` | Full static-analysis JSON — the generator's **input**, never pushed to a device. Since llm-coordinate-single-base it is the gh120 document (`rv-android/modules/rv-static-analysis/tests/resources/cryptoapp.apk.json`, with `distanceTargets`) |
| `test-apks/cryptoapp.apk.mop.json` | Compact MOP artifact (format 2) derived from it — what `ape.staticDataPath` points at |

Source:
```
rv-android/results/cli_experiment_20260305_155802_9bd8c909/instrumented_apks/cryptoapp.apk
rv-android/results/cli_experiment_20260305_155802_9bd8c909/instrumented_apks/cryptoapp.apk.json
```

The `.mop.json` is not copied — it is **derived** from the `.json` beside it by rv-android's
`aperv_tool/tools/aperv/derive_mop_artifact.py`, which is where every derivation rule now lives.
`openspec/changes/archive/2026-10-09-llm-coordinate-single-base/evidence/derive_fixtures.py <rv-android root>` (run with
`python3 -I -B`) re-derives this pair and the two test fixtures (`src/test/resources/cryptoapp.apk.mop.json`,
`src/test/resources/synthetic-activity-selection.mop.json`, the latter from the synthetic
`.apk.json` beside it) through the same library calls `tool.py` uses.
Regenerate it from that repository rather than editing it: it is canonical JSON (sorted keys, no
spaces) and a hand edit breaks the `source.digest` chain that joins a run to its analysis input.

Configuration can be overridden via `/data/local/tmp/ape.properties` or `/sdcard/ape.properties` on the device.

## Architecture

### Testing Flow
1. App launches → initial `GUITree` captured from AccessibilityNodeInfo
2. `NamingFactory` abstracts GUITree into an abstract `State` via `Naming` strategies
3. `Agent` selects an action from available `ModelAction`s
4. Action executed via Monkey event system (`ApeEvent` subclasses)
5. Result GUITree captured → `Model` updated with new/existing state transition
6. `NamingFactory` checks if refinement is needed (detects non-determinism)
7. Loop until stop condition

### Key Component Relationships

```
Monkey (entry: com.android.commands.monkey.Monkey)
  └── MonkeySourceApe (bridges Agent → Monkey event queue)
        ├── AndroidDevice (singleton wrapping Android system APIs)
        ├── Model (exploration graph: State, StateTransition, Action)
        └── Agent (testing strategy)
              ├── SataAgent      — primary: SATA heuristic, epsilon-greedy (active via --ape sata)
              ├── RandomAgent    — priority-weighted random baseline (active via --ape random)
              ├── ReplayAgent    — replay recorded scripts (active via --ape replay --ape-replay <log>)
              ├── ApeAgent       — full CEGAR with refinement (class exists; Phase 2: wire into CLI)
              └── StatefulAgent  — base class for SataAgent/RandomAgent/ApeAgent
```

### Core Innovation: Naming/Abstraction (ape/naming/)

The central research contribution. `NamingFactory` manages a lattice of abstraction levels:
- `Naming` — one abstraction level; maps `GUITree`s to abstract `State`s
- `Name` — attribute path identifying a widget (e.g., `text='OK'`, `index=0`)
- `Namer` — strategy for grouping widgets (TextNamer, TypeNamer, IndexNamer, etc.)
- `NamingFactory` — implements refinement algorithm; detects when abstraction is too coarse and refines it

### Package Map

| Package | Purpose |
|---|---|
| `com.android.commands.monkey` | Base Monkey framework + event types (AOSP-derived) |
| `ape` | Agent interface, AndroidDevice singleton, ActionFilter |
| `ape.agent` | 5 Agent classes: SataAgent + RandomAgent + ReplayAgent (active); ApeAgent + StatefulAgent (base/Phase 2) |
| `ape.model` | Exploration graph: Model, State, StateTransition, Action, ActivityNode |
| `ape.tree` | Current-screen representation: GUITree, GUITreeNode, GUITreeBuilder |
| `ape.naming` | **Core innovation**: abstraction/refinement via Naming, Namer, NamingFactory |
| `ape.events` | Event generation: ApeClickEvent, ApeDragEvent, ApeKeyEvent, etc. |
| `ape.llm` | LLM integration: SglangClient, LlmEngine, CoordinateMapper, ApePromptBuilder, ToolCallParser, ImageProcessor, ScreenshotCapture, CoordinateNormalizer, LlmCircuitBreaker |
| `ape.telemetry` | The event sink: `EventSink`, `NdjsonSink`, `NoopSink`, `StepRecord`, `RunCounters` |
| `ape.utils` | Config (100+ flags), Logger, RandomHelper, Utils |
| `reducer/` | Crash test-case minimization (delta debugging) |

### The run plan (`ape/runtime/`)

A run's behavior is decided once, at startup, by a `RunSpec` — an immutable, validated plan resolved from `/data/local/tmp/ape.properties`, `/sdcard/ape.properties` and the command line. `Monkey.run` resolves it before any device interaction, and `RunContext.initialize` then holds it, together with the run id and the seeded RNG, for the whole run. Read it through `RunContext.current().spec()`.

Resolution is **total and fail-fast**: an unknown key, a key outside the `ape.` namespace, a value of the wrong type, a retired key, or an impossible feature combination aborts the run before the first event. The abort prints `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<msg>` and exits nonzero, so a supervisor sees a failed task rather than a run that quietly explored under a configuration nobody chose. `KeyOwnership` is the registry of what is known: every key belongs to the base exploration params, to one `Feature`, or to the resolver, and retired keys carry a reason naming what replaced them.

Presets are the six cells of *Behavior groups and cells*; `ape.preset` selects one and explicit keys override it.
The first line of a run's output is a level-0 `RUN_START` JSON record (`RunSpecEcho`) carrying the run id, seed, agent, preset, the harness `variant` (when `ape.variant` is set), active features, resolved params, inert keys, plan digest and the build stamp — so a trace says which jar produced it and under which plan. Its format `v` is 2. `BuildInfo.GIT_SHA` / `JAR_BUILT` are filled at package time from git.

### Behavior groups and cells (`runtime/Feature`, `runtime/Presets`)

Behavior is organised in five groups. **BASE** is the APE algorithm plus the declared keyless bug fixes, and has no
key. The other four switch independently, each by one boolean plan key, and every member `Feature` declares its group
as a dependency, so a member whose group is off is simply absent from the plan (stating its activation key explicitly
aborts with `missing_dependency`):

| Group | Key | Members (activation key) | Data |
|---|---|---|---|
| ADDONS | `ape.addons` | `MODEL_MENU`, `FORM_COMPLETION`, `TREE_ENHANCEMENTS`, `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`, `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD`, `COVERAGE_BOOST`, `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART` | none |
| STATIC | `ape.static` | `NAV_FRONTIER` (`ape.frontierBoostWeight`), `TYPED_FUZZ` (`ape.fuzzInputTyped`), `COMPONENT_TRIGGER` (`ape.componentPercentage`) | `StaticGuiModel` |
| MOP | `ape.mop` | `WTG` (`ape.mopWeightWtg`), `MENU_GATEWAY` (`ape.mopWeightOpenMenu`, also needs `MODEL_MENU`), `MOP_FRONTIER`, `MOP_LAUNCHER` (`ape.activityTriggerEnabled`), `MOP_ACTIVITY_SOURCE`, `MOP_COIN` (`ape.mopCoinPercentage`) | `MopData` + `StaticGuiModel` |
| LLM | `ape.llm` | `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_COIN` (`ape.llmPercentage`); needs `ape.llmUrl` | — |

STATIC and MOP also require `STATIC_DATA`, the artifact path `ape.staticDataPath` (`ape.mopDataPath` is a
retired key). The artifact is optional: without it every STATIC and MOP feature is off by dependency, and
switching either group on without it is a plan error. `StaticArtifactLoader.load` reads format 2 once and splits it
into two read-only providers — `StaticGuiModel` (package, main activity, WTG `wtgTransitions`, widget metadata
`widget(activity, shortId)`, components) and `MopData` (targets, distances, handlers, MOP activities, `reachesMop`,
`lookup`). STATIC reads only the first; MOP reads both. A use site asks for its member, never for the group.

The **cells** are the six presets, unions of group blocks: `base` (no block, the jar defaults), `mop`, `as` (ADDONS +
STATIC: the full tool without specifications), `as_mop`, `as_llm`, `as_mop_llm`. The blocks reproduce Study 03 E6's
configuration (ADDONS = E6's addon keys; STATIC = frontier 200, typed input, component trigger 0.03; MOP = arm 3's
`distance` guidance plus `p_M = 1.0`; LLM = arm 4's sampling with `p_L = 0.9`, prompt `v13`). A preset never states
`ape.staticDataPath`, `ape.llmUrl` or `ape.variant`: the harness supplies them. A dose or an ablation is a named override
on a cell, never a seventh preset. Any other preset name aborts with `unknown_preset`.

**The step is a fixed sequence of owners** (`agent/pipeline/DecisionPipeline`, stage names as `PIPELINE` writes
them): the foreign/tree guards and the model update, then `Budget`, `Restart`, `LlmNewState`, `LlmStagnation`,
`LlmCoin`, `MopLauncher`, `ComponentTrigger` (a side effect that does not decide), `MopCoin`, `SataChain` (terminal).
At most one LLM call is made per step. The LLM coin draws `u_L` and admits the call through the declared gates
`MinActionsGate` (`ape.llmGateMinActions`, 3; 0 unmounts it) and `BreakerGate` (`ape.llmBreakerFailures`,
`ape.llmBreakerOpenMs`); it wins over a non-empty SATA buffer, which is abandoned without `weakenStateTransition`
(`dec.buf`). The MOP coin takes an interaction (per activity, `xpath|ActionType`) executed fewer than
`ape.mopCoinLedgerMaxInteractions` times (k, default 1; `< 1` clamped to 1) by any owner, with an action of positive
unretired `mopWeight`, at `u_M < p_M`, ranked by weight, then (distance) unretired target count, then action order —
priority is not a key. `MopInteractionLedger` counts those executions and is built with k. The MOP launcher's cadence
counter advances every step and launches on the first step that reaches the MOP/SATA branch; it launches each
activity at most once per run (D-B15: a launched activity reads as visited, whether or not it opened). With
`MOP_LAUNCHER` in the plan, the null-tree recovery (more than 10 null roots) relaunches the app under test —
`onActivityStopped`, `restartPackage(getMainApp(), …)`, and `NOP` instead of `ACTIVATE` on that step (D-B16);
without it, upstream's `stopTopActivity()` path. The LLM coin, MOP coin, component coin and fuzz toss each draw from their own stream
(`agent/StepRng`, `new Random(seed ^ K)`), so mounting or re-dosing one never shifts SATA's draws.

**Scorers are additive and independent** (rule R1): each `agent/scoring/*Pass` writes its own field and never reads
the current priority — `CoveragePass`, `FormCompletionPass` (ADDONS), `NavFrontierPass` (STATIC, `navf`), `WtgPass`,
`MopWidgetPass`, `MopFrontierPass`, `MenuGatewayPass` (MOP). SATA reads priority only where APE does. Its extension
points (`agent/sata/`) carry the APE default: X1 least-visited tie (`ScorerSumLeastVisitedTie`: largest scorer sum,
APE's first-of-array with zero scorers), X3 ABA target tie (`ApeAbaTargetTie` / `MopAbaTargetTie`), X4 path to a
trivial activity (`ApeTrivialPathPick`, always `randomPick` / `MopTrivialPathPick`, largest MOP density); the MOP
variants are mounted only with MOP.

**The LLM prompt is built from the mounted groups' contributions** (`llm/StaticPromptContribution`,
`llm/MopPromptContribution`): MOP adds the markers, the count, `MOP NAVIGATION` and the MOP system-prompt sentences;
STATIC adds the widget metadata (`prompt=`, `spinnerMode=`, `contentDescription=`, `tooltipText=`, `entries=`,
`inputType=`, `hint=`). Without the group the section does not exist. Visits (`UICoverageTracker`, `SCREEN … visits:`)
are not MOP and appear in every cell.

**Rotation.** `ScreenshotCapture` captures the LLM screenshot at the display rotation
(`AndroidDevice.getRotation()`, read through `Display`, API 30-safe; `-1` when unreadable), and every step writes
`dec.rot`. Coordinates and taps were already in the current-orientation frame. `ape.llmBoundaryTopPct` is a fraction
of the display height, so in landscape the band is smaller than the status bar.

### Telemetry (the NDJSON trace)

Everything the jar records about its own behavior goes through one component, `EventSink`, as
NDJSON on `System.out` — the same stdout the harness captures into the `.trace`. A trace line is a
sink record **iff it begins with `{`**; every `Logger` line begins with `[APE] `, so the two streams
are mechanically separable and no free-text diagnostic can be mistaken for data.

The unit is one **`StepRecord` per exploration step**: envelope once (`s`, `t`, `act`, `st`), the
decision in `dec`, every LLM routing attempt of that step as an ordered `llm[]`, and the outcome in
`out`. The record is opened at selection and closed when its outcome resolves during step N+1's
graph update — the same timing the decision join buffer always encoded — so a decision, the calls
that produced it and its result share one line with no join key. A step whose outcome legitimately
never resolves closes without an `out` member; teardown's `flushPendingStep` writes a still-open one
with `out:{"resolved":false}`, which bounds loss on sudden death at one record.

Alongside the step records the same stream carries `RUN_START` (first, from `RunSpecEcho`), the
`ACT`/`STATE` dictionary entries that give the trace's longest repeated strings run-local integer
ids, `MOP_DATA`, `PIPELINE`, `LLM_ACK`, and `RUN_END` (reason + counters) last. Volume is managed by
omitting defaults — an absent boost field means `0` — with two deliberate exemptions, `dec.patched`
(`0` = resolved but not patch-promoted, absent = no resolved target) and `dec.own`, written whenever
`dec` is, as an empty object when nothing claimed the step.

`dec.src` names the **executed owner** (`DecisionSource`: `sata`, `mop`, `llm`, `budget`, `restart`,
`launcher`) and `dec.ch` the code path (`PickChannel`: `roulette_greedy`, `roulette_early`, `launcher`,
`llm`, `buffer`, `sata_other`, `mop_coin`, `budget`, `restart`). The conditional members, each absent
when its condition fails: `own` (`i` intended owner, `trg` LLM trigger, `uL`/`uM` coin draws, `g`
refusing gate, `fb` LLM fallback `error`/`screenshot`/`no_match`/`decline`; `screenshot` = the LLM
screenshot could not be captured, which still spends the step's call but is not a breaker failure, D-B18), `cfc` (per scorer: did it change the
least-visited pick, written only with ≥ 2 tied), `draw` (`[index, total]` of a roulette), `nav` (`x`
extension point, `c` variant, `alt`, `drew`), `inp` (input origin), `restart` (kind), `bmc` (BACK/MENU
picks removed by the cap), `strat` (the SATA path), `rot` (display rotation, every step), `buf` (owner
that abandoned the buffer), `trig` (`k` kind, `c` class of a component trigger; `comp` stays the
launcher's result), plus the boosts `mop`, `mopf`, `wtg`, `navf`, `cov`, `menu`, `form` and the MOP
members `mopx` (weighted actions, tried or not, and the total), `mopd`, `mops`, `hnd` (the stamped handler
class behind the weight, written iff `mops` is `s`), `ret`. `RUN_END.counters` carries the routing counters
`launches`, `gateMinActions`, `gateBreaker`, `guardBacks`, `bufAbandoned`, `restarts`, `coinLlm`, `coinMop`,
`nullRelaunches` (written in every cell, `0` without the launcher), and the LLM block's `screenshot_failed`. `PIPELINE` lists the mounted stages by the names above. Every new
computation is pure: no RNG draw, no `resolveAction`, no cache effect. Two neutrality gates guard it:
`SinkNeutralityTest` (one case per cell) and `RngNeutralityTest`; `CleanControlTest` asserts that a cell
without MOP has every MOP field absent or `0`.

**Telemetry is always on and identical for every arm.** There is no plan key selecting the sink and
no flag disabling it: `RunContext` constructs `NdjsonSink` on every production path, and `NoopSink`
is reachable only through `RunContext.installForTest`. `ape.stepTelemetryEnabled` no longer exists
and aborts resolution as an unknown key. What replaces the old arm-level switch is a property that
can be checked rather than trusted — with the sink on or replaced by a no-op, the same seed produces
the same action sequence, and `SinkNeutralityTest` is a permanent gate on it.

Two keys remain, and neither gates a mechanism: `ape.telemetryHeartbeat` (default `true`) writes one
`Log.i` line per step under the tag **`ApeRvHb`**, so the analysis side can join violations to steps
on one clock — the literal is fixed by `LogcatManager.default_tags` in rv-android and a mismatch is
silent on both sides; and `ape.llmPromptDump` (default `true`) carries the prompt and response text
as `sys`/`user`/`resp` fields on the LLM sub-event.

Serialization is `ape.runtime.Json`, hand-written and dependency-free, and it escapes by
construction: quotes, backslash, everything below U+0020 including NUL, and U+2028/U+2029. A record
never contains a raw newline. `org.json` appears only on the test classpath, as the round-trip parser.

A sink failure never reaches the exploration loop — the first internal `Throwable` latches the sink
off for the run with one warning, and the run continues untelemetered rather than dying of its own
instrumentation.

**Not written any more**: `sataGraph.dot`, `sataGraph.vis.js`, per-state `step-*.txt`,
`action-history.log`, `sataTimeline.vis.js`, `produce.log`, `consume.log`, and the `[APE-STEP]` /
`[APE-OUTCOME]` / `[APE-LLM-TEL]` `key=value` line family the record replaces. The keys that gated
them (`ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates`) are retired and abort resolution
with a reason. `ApeRRFormatter` survives as a **reader** only: `ReplayAgent` still consumes an
externally supplied replay log through it.

### Central Configuration

`ape/utils/Config.java` defines the configuration flags loaded from `ape.properties` that the plan does not own. Key flags:
- `doFuzzing` / `fuzzingRate` — enables random fuzzing at 2% rate per step (enabled by default)
- `evolveModel` / `actionRefinementFirst` — controls model refinement behavior
- `maxStatesPerActivity` / `maxGUITreesPerState` — state space limits
- `takeScreenshot` / `saveGUITreeToXmlEveryStep` — debug output
- `takeScreenshotForEveryStep` / `saveGUITreeToXmlEveryStep` — per-step PNG/XML artifacts; **both default `false`** for throughput (INV-EXPL-17). The aperv-tool deployment pulls neither artifact and the LLM path uses its own on-demand `ScreenshotCapture`; re-enable for local debugging with `ape.takeScreenshotForEveryStep=true` / `ape.saveGUITreeToXmlEveryStep=true` in `ape.properties`
- `defaultGUIThrottle` — delay between actions
- `ape.staticDataPath` (plan-owned, `STATIC_DATA`) — path to the compact artifact on device, `/data/local/tmp/mop-artifact.json` as aperv-tool pushes it (absent = STATIC and MOP off). The jar reads only **format 2** (`formatVersion: 2`, generator `aperv-derive/2`, rv-android gh122): format 1 and the full static-analysis JSON are rejected (`version-mismatch`). Format 2 adds `targets` (the producer's target count), per-widget `dist` pairs `[i, d]`, per-activity `activityDist` and a `handlers` table keyed by binary class name. The derive's cut (D15 as amended 2026-10-09, owned by gh122's `DIST_K`): a widget's and a handler's lists keep every target at `d ≤ 3`, `activityDist` the K = 3 nearest; the loader reads any length; `MOP_DATA` reports `targets`, `handlers` and `droppedPairs`. An artifact with `targets: 0` loads and runs without distance data
- `mopWeightDirect` / `mopWeightTransitive` — `flag`-mode MOP weights (defaults: 500/300), configurable via `ape.properties` (the former `mopWeightActivity` fallback was removed by mop-discriminative-boost). They reach scoring through the run's `MopContext` (`utils/MopContext`, built from the loaded `MopData`, the `StaticGuiModel` and `spec().mop()`), not through `ScoringParams`
- **Handler stamp and lookup** (llm-coordinate-single-base Part B, D16–D17): `GUITreeBuilder.fillNode` copies the gh121 extras `rvsec.click`/`rvsec.longClick` (an APK instrumented with `--instrumentation-variant dexlib2 --stamp-handlers`) into `GUITreeNode.getHandlerClick()/getHandlerLongClick()`. Every MOP reader goes through `MopData.lookup(activity, node, event)`: a stamp naming a class listed in `handlers` wins (source `STAMP`, even when it reaches nothing), else the id key (source `ID`), else `NONE`. Through the id key a **click** on a widget whose `mop` map has no `click` key reads the widget's *click aggregate* (`MopData.Widget.clickAggregate()`, built at load by `mergeByMinimum` over every event but `longclick`, `itemlongclick`, `implicitcreatecontextmenu`, `scroll`); an explicit `click` key, `none` included, blocks it, every other event reads its own pairs, and a stamp never reads it (D17 as amended 2026-10-09). Flags keep the INV-MOP-14 aggregate fallback; containment walks the node, two ancestors and two levels of descendants (`MopScorer.containmentNodes`). Widget metadata for the prompt and typed input comes from `StaticGuiModel.widget(activity, shortId)`, not from `MopData`
- **Scoring modes** (`ape.mopScoring`, D18): `flag` (default) is the E6 mark; `distance` weighs an action by its nearest unretired target (`mopWeightD1..3` = 500/400/300 for d ≤ 1 / 2 / 3, 0 beyond), splits ties at the state's best level as `max(W/n, W_next)` into `mopBoost`, and keeps the undivided `ModelAction.mopWeight` for the MOP coin, the prompt marker and retirement. In both modes only untried keys are boosted (D-B19): an action whose key the ledger holds keeps its `mopWeight` but gets `mopBoost` 0 and leaves the split
- **Retirement** (D19, `distance` only): the first execution (per activity, per `xpath|ActionType`) of an action with a positive weight counts the targets of its weight's lookup that lie at `d*`, its smallest unretired distance (D19 as amended 2026-10-09), taken at selection (`StatefulAgent.moveForward`) and applied once the action survives `checkRestart`; a target retires at `ape.mopRetireAfter` (3) and stops weighting, marking and ordering the launcher. `ape.mopRetireAfter <= 0` is accepted: every target reads as retired and the run explores unguided
- **Launcher** (D20, D-B15): under `distance` `MopLauncherStage` orders eligible activities by their nearest unretired `activityDist` (≤ `ape.mopLauncherDmax`, 6), then manifest order; in both modes an activity it launched is never eligible again in the run. SATA has no MOP shortcut: the MOP group decides through the `MopCoin` stage (see *Behavior groups and cells*); `ape.mopTargetPickCap` and `ape.leastVisitedPriorityTiebreak` are retired keys
- **Telemetry** (D23, D-B17): `dec.mopd` (distance behind a `distance` weight; `0` is written), `dec.mops` (`s` stamp / `i` id key), `dec.hnd` (the stamped handler, `MopData.Lookup.handler`), `dec.ret` (targets retired by the step); `RUN_END.counters` gains `stampNodes`, `stampHits`, `retired`

The group keys and every member's keys are owned by the plan rather than by `Config`, and are read through `RunContext.current().spec()` (`spec().mop()`, `spec().llm()`, `spec().exploration()`, `spec().has(Feature)`). Besides the activation keys in the groups table:
- `ape.mopWeightOpenMenu` — boost on the MODEL_MENU action when the activity's OPTIONSMENU is a MOP gateway (default 250)
- `ape.fuzzInputTyped` — type-aware EditText fuzzing from the static `inputType`/`hint` (STATIC; default true when STATIC is mounted)
- `ape.mopStrictPackageMatch` — reject the artifact when its package/mainActivity diverges from the runtime values (owned by `STATIC_DATA`; default false = warn-only)
- `ape.activityTriggerEnabled` — mounts the MOP launcher (default **true**, effective only with MOP)
- `ape.mopFrontierWeight` — boost for unvisited actions on MOP-reachable widgets (default 0 = pass disabled)
- `ape.mopScoring` — `flag` (default) or `distance` (enum `runtime/MopScoring`; any other value aborts resolution with `invalid_type`)
- `ape.mopWeightD1` / `D2` / `D3` — `distance` weights (500/400/300); `ape.mopRetireAfter` (3); `ape.mopLauncherDmax` (6)
- `ape.mopCoinPercentage` — `p_M` (default 1.0); `ape.mopCoinLedgerMaxInteractions` — k, the coin's executions per (activity, key) (default 1, `< 1` clamped to 1; `ape.mopTargetPickCap` is retired in its favour)
- `ape.llmGateMinActions` (3), `ape.llmBreakerFailures` (3), `ape.llmBreakerOpenMs` (60000) — the LLM coin's gates

The keys the selected mode does not read (`mopWeightDirect`/`Transitive` under `distance`; the five distance keys under `flag`) are accepted at any well-typed value, keep their value in the plan digest, and are listed in `RUN_START.inert` (INV-RUN-09).

`MopParams` is absent on a plan without the MOP group; the sites reachable on such a plan read that absence as "off".

- Naming: the static analyser generalized its output to any target method set, so its JSON speaks `Target` (`reachesTarget`/`directlyReachesTarget`/`targetMethods`) while aperv, an exclusively JavaMOP consumer, speaks `MOP`. That boundary (gh13 D7) does not sit in the jar: the host-side generator is the only component that reads the analyser's document, so it performs the rename and the wire arrives speaking MOP (`mop`, `mopActivities`, `reachesMop`). One `Target` name survives on the wire, `hasTargetMethods`, which the loader does not decode: the component trigger reads no MOP data. No `reachability[]`, no `directlyReachesTarget`, no listener join reaches the device
- `llmUrl` — the LLM server's base URL (vLLM; "sglang" is a legacy name), required by the LLM group and never stated by a preset; e.g., `http://10.0.2.2:30000/v1`
- `llmOnNewState` / `llmOnStagnation` — toggle LLM modes (default: true)
- `llmModel` / `llmTemperature` / `llmTopP` / `llmTopK` — LLM sampling params
- `llmTimeoutMs` — HTTP timeout (default: 15000ms)
- `llmPercentage` — `p_L`, the LLM coin's dose (jar default 0.02; 0.9 in the LLM block; 0.0 unmounts `LLM_COIN`)
- `llmMaxTokens` — `max_tokens` for the chat completion request (default: 1024; J1c expose-only, defaults reproduce the prior hard-coded value; not causal for truncation — `tokens_out` ≈ 25). Sent in the request body and echoed with the resolved params in `RUN_START`
- `llmSnapTolerancePx` — floor of the euclidean snap tolerance `max(floor, min(w,h)/2)` in `CoordinateMapper.map` (default: 50; J1b expose-only, lever analyzed and discarded — default not swept)
- `llmBoundaryTopPct` — the status-bar boundary reject band as a fraction of the display height (default: 0.035, the campaign AVD's 63 px status bar over its 1794 px display; set it for another density). A coordinate with `pixelY < h*top` is rejected with no off-tree tap synthesized, which also catches a degenerate `(0,0)`. There is no bottom band: the answer is mapped on the display, which excludes the navigation bar, so `llmBoundaryBottomPct` is retired and a properties file that sets it aborts resolution (`retired_key`). An answer outside `[0,999]` is refused before it is mapped (`CoordinateMapper.inAnswerSpace`, outcome `boundary`) (llm-coordinate-single-base, INV-RTR-22). All three J1b/J1c keys are owned by the LLM feature, so on a plan without it they are inert at their neutral values and are reported in `RUN_START.inert`. Native `tool_calls` malformations now run the same coordinate-repair pipeline as the XML `<tool_call>` path (`SglangClient.ToolCall.rawArguments` → `ToolCallParser` Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` telemetry field (INV-LLM-10, INV-RTR-14)

## Notes

- Unit + integration test suite: `mvn test` (1462 tests, 24 skipped — 18 `@Ignore` needing an Android runtime, 6 `Assume` in `SglangLiveTest`). Live LLM tests: `SGLANG_URL=http://localhost:30000/v1 mvn test -Dtest=SglangLiveTest` runs those six.
- Supports Android Marshmallow through Q; uses reflection (`ApeAPIAdapter`) for version compatibility
- Known issue: `OutOfMemoryError` is possible on long runs, because every `State` keeps its `GUITree`s in `treeHistory` and the `Graph` keeps every `State` — the heap grows with the number of distinct states a run reaches. That is the only retention by design. The `GUITreeBuilder` naming caches and a `ModelAction`'s resolved references are cleared when their tree is released, and the diagnostic action history holds primitive snapshots plus a single depth-1 recovery point, whose tree a live state owns anyway. Nothing bounds or evicts `Graph`, `treeHistory` or the naming structures: such a bound changes exploration behavior, so it waits on a heap profile by retention root. `OutOfMemoryError` is not caught — the process dies and the supervisor marks the task FAILED and retries it
- Pre-compiled `ape.jar` is included in repo for convenience
