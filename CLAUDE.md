# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

APE-RV is a fork of APE (Android Property Explorer), a model-based Android GUI testing tool from ETH Zurich's AST Lab (ICSE 2019). This fork is developed at the University of Brasília as part of the RVSEC research infrastructure. It extends the original APE with: a modernized build system (Phase 1), AndroidX UI coverage improvements and MODEL_MENU exploration (Phase 2), MOP-guided action scoring (Phase 3), and an aperv plugin for rv-android (Phase 4).

**Current repository state**: Ant+dx+Java 1.7 build → `ape.jar`. Phases 1–4 are not yet implemented.

## Build Commands

Requires: Java 11+, Apache Maven, Android SDK with `d8` in PATH (build-tools 28+).

```bash
mvn compile    # Compile Java source to bytecode (target/classes/)
mvn package    # Compile + convert to Dalvik bytecode → produces target/ape-rv.jar
mvn clean      # Remove build artifacts (target/)
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
| `test-apks/cryptoapp.apk.json` | Static analysis JSON for Phase 3 MOP-guided scoring (`ape.mopDataPath`) |

Source:
```
rv-android/results/cli_experiment_20260305_155802_9bd8c909/instrumented_apks/cryptoapp.apk
rv-android/results/cli_experiment_20260305_155802_9bd8c909/instrumented_apks/cryptoapp.apk.json
```

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
              ├── ReplayAgent    — replay recorded scripts (active via ape.replayLog config)
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
| `ape.utils` | Config (100+ flags), Logger, RandomHelper, Utils |
| `reducer/` | Crash test-case minimization (delta debugging) |

### Central Configuration

`ape/utils/Config.java` defines all configuration flags loaded from `ape.properties`. Key flags:
- `doFuzzing` / `fuzzingRate` — enables random fuzzing at 2% rate per step (enabled by default)
- `evolveModel` / `actionRefinementFirst` — controls model refinement behavior
- `maxStatesPerActivity` / `maxGUITreesPerState` — state space limits
- `takeScreenshot` / `saveGUITreeToXmlEveryStep` — debug output
- `defaultGUIThrottle` — delay between actions

## Notes

- No automated test suite — validation is done by running on real Android devices
- Supports Android Marshmallow through Q; uses reflection (`ApeAPIAdapter`) for version compatibility
- Known issue: `OutOfMemoryError` possible due to keeping all GUITrees in memory
- Pre-compiled `ape.jar` is included in repo for convenience
