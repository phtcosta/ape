# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

APE-RV is a fork of APE (Android Property Explorer), a model-based Android GUI testing tool from ETH Zurich's AST Lab (ICSE 2019). This fork is developed at the University of Brasília as part of the RVSEC research infrastructure. It extends the original APE with: a modernized build system (Phase 1), AndroidX UI coverage improvements and MODEL_MENU exploration (Phase 2), MOP-guided action scoring (Phase 3), and an aperv plugin for rv-android (Phase 4).

**Current repository state**: Maven+d8+Java 11 build → `target/ape-rv.jar`. All phases complete: Phase 1 (build), Phase 2 (AndroidX/MODEL_MENU), Phase 3 (MOP-guided action scoring with configurable weights + navigation-level MOP density tiebreaker), Phase 4 (aperv-tool in rv-android), Phase 5 (LLM integration via SGLang/Qwen3-VL for new-state and stagnation-breaking exploration).

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
adb push target/ape-rv.jar /data/local/tmp/
adb install test-apks/cryptoapp.apk
adb shell CLASSPATH=/data/local/tmp/ape-rv.jar app_process /system/bin \
  com.android.commands.monkey.Monkey -p com.example.cryptoapp \
  --running-minutes 1 --ape sata
```

**Test APKs** (`test-apks/` — `.apk` files are gitignored, copy from rv-android results):

| File | Description |
|------|-------------|
| `test-apks/cryptoapp.apk` | Instrumented APK from rv-android experiment `cli_experiment_20260305_155802_9bd8c909` |
| `test-apks/cryptoapp.apk.json` | Full static-analysis JSON — the generator's **input**, never pushed to a device |
| `test-apks/cryptoapp.apk.mop.json` | Compact MOP artifact derived from it — what `ape.mopDataPath` points at |

Source:
```
rv-android/results/cli_experiment_20260305_155802_9bd8c909/instrumented_apks/cryptoapp.apk
rv-android/results/cli_experiment_20260305_155802_9bd8c909/instrumented_apks/cryptoapp.apk.json
```

The `.mop.json` is not copied — it is **derived** from the `.json` beside it by rv-android's
`aperv_tool/tools/aperv/derive_mop_artifact.py`, which is where every derivation rule now lives.
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
| `ape.llm` | LLM integration: SglangClient, LlmRouter, ApePromptBuilder, ToolCallParser, ImageProcessor, ScreenshotCapture, CoordinateNormalizer, LlmCircuitBreaker |
| `ape.telemetry` | The event sink: `EventSink`, `NdjsonSink`, `NoopSink`, `StepRecord`, `RunCounters` |
| `ape.utils` | Config (100+ flags), Logger, RandomHelper, Utils |
| `reducer/` | Crash test-case minimization (delta debugging) |

### The run plan (`ape/runtime/`)

A run's behavior is decided once, at startup, by a `RunSpec` — an immutable, validated plan resolved from `/data/local/tmp/ape.properties`, `/sdcard/ape.properties` and the command line. `Monkey.run` resolves it before any device interaction, and `RunContext.initialize` then holds it, together with the run id and the seeded RNG, for the whole run. Read it through `RunContext.current().spec()`.

Resolution is **total and fail-fast**: an unknown key, a key outside the `ape.` namespace, a value of the wrong type, a retired key, or an impossible feature combination aborts the run before the first event. The abort prints `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<msg>` and exits nonzero, so a supervisor sees a failed task rather than a run that quietly explored under a configuration nobody chose. `KeyOwnership` is the registry of what is known: every key belongs to the base exploration params, to one `Feature`, or to the resolver, and retired keys carry a reason naming what replaced them.

Presets (`aperv`, `mop`, `llm`, `llm_mop`) name the campaign arms; `ape.preset` selects one and explicit keys override it.

The first line of a run's output is a level-0 `RUN_START` JSON record (`RunSpecEcho`) carrying the run id, seed, agent, preset, active features, resolved params, inert keys, plan digest and the build stamp — so a trace says which jar produced it and under which plan. `BuildInfo.GIT_SHA` / `JAR_BUILT` are filled at package time from git.

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
and `dec.cf`, whose absence is itself information.

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
- `mopDataPath` — path to the compact MOP artifact on device, `/data/local/tmp/mop-artifact.json` as aperv-tool pushes it (null = MOP scoring disabled). The full static-analysis JSON is never pushed and is rejected if it is (`version-mismatch`)
- `mopWeightDirect` / `mopWeightTransitive` — MOP scoring weights (defaults: 500/300), configurable via `ape.properties` (the former `mopWeightActivity` fallback was removed by mop-discriminative-boost)

Five keys are owned by the plan rather than by `Config`, and are read through `RunContext.current().spec()`:
- `ape.mopWeightOpenMenu` → `spec().mop().weightOpenMenu()` — boost on the MODEL_MENU action when the activity's OPTIONSMENU is a MOP gateway (default 250; gh13 T1.2)
- `ape.fuzzInputTyped` → `spec().exploration().fuzzInputTyped()` — type-aware EditText fuzzing from static `inputType`/`hint` (default true; set false for the random-string generator — gh13 T1.3 rollback knob)
- `ape.mopStrictPackageMatch` → `spec().mop().strictPackageMatch()` — reject `MopData.load` when the artifact's package/mainActivity diverges from the runtime values (default false = warn-only; gh13 T1.7)
- `ape.activityTriggerEnabled` → `spec().mop().activityTriggerEnabled()` — include activities in component triggering (default **true**)
- `ape.mopFrontierWeight` → `spec().mop().frontierWeight()` — boost for unvisited actions on MOP-reachable widgets (default 0 = pass disabled)

`MopParams` is absent on an arm whose plan carries no MOP feature; the two sites reachable on such an arm read that absence as "off".

- Naming: the static analyser generalized its output to any target method set, so its JSON speaks `Target` (`reachesTarget`/`directlyReachesTarget`/`targetMethods`) while aperv, an exclusively JavaMOP consumer, speaks `MOP`. That boundary (gh13 D7) **no longer sits in the jar**: the host-side generator is the only component that reads the analyser's document, so it performs the rename and the wire arrives speaking MOP (`mop`, `mopActivities`, `reachesMop`). Two `Target` names survive on this side and are worth knowing about rather than being surprised by — the wire key `hasTargetMethods` (the generator's own compaction of the signature list, kept as-is) and the Java field names `ComponentInfo.reachesTarget`/`targetMethods`, which the `reachesMop` decode at `MopData:470` feeds. What is genuinely gone is the *cross-reference*: no `reachability[]`, no `directlyReachesTarget`, no listener join reaches the device
- `llmUrl` — SGLang base URL (null = LLM disabled); e.g., `http://10.0.2.2:30000/v1`
- `llmOnNewState` / `llmOnStagnation` — toggle LLM modes (default: true)
- `llmModel` / `llmTemperature` / `llmTopP` / `llmTopK` — LLM sampling params
- `llmTimeoutMs` — HTTP timeout (default: 15000ms)
- `llmPercentage` — probability of random LLM routing per step (default: 0.02 = 2%; 0.0 disables; 0.7 = rv-agent-like 70%)
- `llmMaxTokens` — `max_tokens` for the chat completion request (default: 1024; J1c expose-only, defaults reproduce the prior hard-coded value; not causal for truncation — `tokens_out` ≈ 25). Shared by the `[APE-LLM-CONFIG]` manifest and the request body
- `llmSnapTolerancePx` — floor of the euclidean snap tolerance `max(floor, min(w,h)/2)` in `LlmRouter.mapToModelAction` (default: 50; J1b expose-only, lever analyzed and discarded — default not swept)
- `llmBoundaryTopPct` / `llmBoundaryBottomPct` — top/bottom boundary reject bands as a fraction of screen height (defaults: 0.05 / 0.94; J1b expose-only, policy levers, defaults reproduce the prior hard-coded bands). A coordinate with `pixelY < h*top` or `pixelY > h*bottom` is rejected (status/nav bar) with no off-tree tap synthesized. All four J1b/J1c keys are owned by the LLM feature, so on a plan without it they are inert at their neutral values and are reported in `RUN_START.inert`. Native `tool_calls` malformations now run the same coordinate-repair pipeline as the XML `<tool_call>` path (`SglangClient.ToolCall.rawArguments` → `ToolCallParser` Level 1 → shared `parseJsonString`), surfacing through the existing `repair=` telemetry field (INV-LLM-10, INV-RTR-14)

## Notes

- Unit + integration test suite: `mvn test` (1131 tests, 19 skipped — 13 `@Ignore` needing an Android runtime, 6 `Assume` in `SglangLiveTest`). Live LLM tests: `SGLANG_URL=http://localhost:30000/v1 mvn test -Dtest=SglangLiveTest` runs those six.
- Supports Android Marshmallow through Q; uses reflection (`ApeAPIAdapter`) for version compatibility
- Known issue: `OutOfMemoryError` is possible on long runs, because every `State` keeps its `GUITree`s in `treeHistory` and the `Graph` keeps every `State` — the heap grows with the number of distinct states a run reaches. That is the only retention by design. The `GUITreeBuilder` naming caches and a `ModelAction`'s resolved references are cleared when their tree is released, and the diagnostic action history holds primitive snapshots plus a single depth-1 recovery point, whose tree a live state owns anyway. Nothing bounds or evicts `Graph`, `treeHistory` or the naming structures: such a bound changes exploration behavior, so it waits on a heap profile by retention root. `OutOfMemoryError` is not caught — the process dies and the supervisor marks the task FAILED and retries it
- Pre-compiled `ape.jar` is included in repo for convenience
