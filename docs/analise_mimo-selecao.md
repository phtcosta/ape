# Architecture Selection Analysis — APE-RV Refactoring

**Date:** 2026-08-02
**Status:** Final consolidated report
**Scope:** 173 source files, ~36,414 LOC, 74 test files, 785 @Test

---

## 1. Executive Summary

This report synthesizes architecture proposals from 8 LLM analyses of the APE-RV codebase, a Java fork of APE (Android Property Explorer) used for on-device Android GUI testing. The core problem is that experiment treatment — what constitutes a "mode" — is implicit. It is determined by Java field defaults, Python dictionaries, control-flow order in `SataAgent.selectNewActionNonnull`, substrate availability, and always-on model changes. There is no single source of truth for "what does the `sata_mop_widget` arm actually do."

After verifying all 14 claimed problems against the codebase and incorporating 3 owner decisions, I identify 4 distinct architecture families from the 8 LLM proposals. The families are: Pipeline/Stages, Event-Sourced, Capability/Manifest, and Physical Separation. Each excels on different design drivers but none alone covers all 10.

The recommended architecture — **RunSpec Pipeline** — combines the Pipeline family's named decision stages with the Manifest family's declarative mode identity, targeting the 5 highest-weighted design drivers (ablation at 15%, baseline fidelity at 12%, traceability at 12%, mode first-class at 10%, spaghetti at 10%). It preserves the existing `ScoringPipeline` as the scoring backbone, extracts the 7-block SATA chain into a `DecisionPipeline`, replaces the 118-field `Config` singleton with an immutable `RunSpec` resolved at startup, and adds bounded memory + structured telemetry. Total estimated effort: 3-4 weeks across 4 independently-valuable migration stages.

---

## 2. Analysis Methodology

### 2.1 Input Sources

Eight LLM reports were analyzed, each proposing 2-4 candidate architectures:

| LLM | Report File | Candidates |
|-----|------------|------------|
| deepseek-v4 | `analise_deepseek-v4.md` | StepPipeline, CapabilityMatrix, TwoLineage |
| gemini-3.6-flash | `analise_gemini-3.6-flash.md` | DPMA, SGDA, ERKE |
| glm-5-2 | `analise_glm-5-2.md` | WPS, PMC, EDC |
| gpt-5 | `analise_gpt-5-selecao.md` | Compiled Run Plan, Fossilised Stock Lane, Event-Sourced Bounded Explorer |
| kimi-k3 | `analise_kimi-k3.md` | RunSpec+DecisionAssembly, Event-Sourced Explorer, Two-Lane Provenance Split |
| ling-3-0-flash-free | `analise_ling-3-0-flash-free.md` | Manifest+Capability Injector, Event-Sourced Decision Stream, Strategy/Command, Two-Process |
| mimo-v2-5-free | `analise_mimo-v2-5-free.md` | Capability Manifest+Injector, Pipeline+Strategy, Event-Sourced Decision Stream |
| opencode-laguna-s | `analise_opencode_laguna_s_2_1_free.md` | Log-Is-Truth, Policy-Is-The-Jar, Process-Is-Disposable |

### 2.2 Verification

All 14 claims from the problem inventory were verified against the codebase. Key verifications:

- `SataAgent.selectNewActionNonnull`: 141 LOC (lines 449-589), confirmed 7 structurally identical blocks with 3× repeated LLM precondition (`actionBufferSize() == 0 && newState.getActions().size() > 2 && _llmRouter != null && _llmRouter.shouldRoute*`)
- `Config.java`: 502 lines, ~118 public static fields confirmed, silent `NumberFormatException` swallowed by `Config.getInteger`/`Config.getDouble` (which catch and return defaults)
- `Model.actionHistory`: `ArrayList<ActionRecord>` at line 137, no `clear()` or `truncate()` method exists
- `State.treeHistory`: `ArrayList<GUITree>` at line 56, `maxGUITreesPerState=20` exists as a config flag but is never enforced in `appendGUITree()`
- `namingToGUITreeNodeCache`: static `HashMap` at `GUITreeBuilder.java:693`, no eviction
- `ScoringPipeline.fromConfig`: `Config` parameter is decorative (line 51: reads `Config` fields directly, ignores the `cfg` parameter)
- `ApeAgent.createAgent`: silent fallthrough to `SataAgent` at line 95 for unknown `--ape` values
- `Logger.java`: 67 lines, `debug=false` at line 22, compile-time constant
- `DecisionSource`: 11 values at `ModelAction.java:42-43`
- `PickChannel`: 8 values at `ModelAction.java:57-76`

### 2.3 Owner Decisions (2026-08-01)

| Decision | Resolution | Impact |
|----------|-----------|--------|
| Q1: LLM precedence | **HARD** (not soft weight) | LLM hook must pre-empt SATA chain, not blend scores |
| Q2: Logcat heartbeat | **Approved** (write-only) | Add structured step-heartbeat to logcat for violation↔step join |
| Q3: One sample per run | **Discard previous; no checkpoint/resume** | No need for physical lane split or event-sourced replay |

---

## 3. Verified Problem Inventory

| # | Problem | Location | Severity |
|---|---------|----------|----------|
| P1 | `selectNewActionNonnull` 141 LOC, 7 structurally identical blocks, 3× repeated LLM preamble | `SataAgent.java:449-589` | High — unmaintainable |
| P2 | Config: ~118 public static fields, loaded once, silent `NumberFormatException` swallowing | `Config.java:28-288` | High — fragile, untestable |
| P3 | `Model.actionHistory` retains GUITrees forever (no `clear`/`truncate`) | `Model.java:137` | High — OOM risk |
| P4 | `State.treeHistory` never truncated (`maxGUITreesPerState=20` exists but unenforced) | `State.java:405-408` | High — OOM risk |
| P5 | `namingToGUITreeNodeCache` never cleared | `GUITreeBuilder.java:693` | Medium — slow memory leak |
| P6 | `ScoringPipeline.fromConfig` Config parameter is decorative | `ScoringPipeline.java:51` | Low — dead parameter |
| P7 | `ApeAgent.createAgent` silently falls through to SataAgent for unknown `--ape` values | `ApeAgent.java:95` | Medium — masks config errors |
| P8 | Component trigger is side-effect with no return | `SataAgent.java:546-551` | Low — confusing control flow |
| P9 | LLM router has 3 hooks with identical preamble (`actionBufferSize()==0 && actions.size()>2 && _llmRouter!=null && _llmRouter.shouldRoute*`) | `LlmRouter.java:232,249,276` + `SataAgent.java:480-514` | Medium — DRY violation |
| P10 | Monkey.java teardown isolation in finally block | `Monkey.java:773-800` | Low — intentional design |
| P11 | 7 ScoringPass implementations (MopWidget, MenuGateway, Wtg, Frontier, MopFrontier, Coverage, FormCompletion) | `scoring/*.java` | Informational — already well-structured |
| P12 | `DecisionSource` enum has 11 values, `PickChannel` has 8 values | `ModelAction.java:42-76` | Informational — growing enums |
| P13 | 33 new files in fork, 22 modified, 0 removed | git diff | Informational — fork health |
| P14 | `Logger.debug=false` at compile time, 67 lines | `Logger.java:22` | Low — no runtime toggle |

---

## 4. Candidate Architecture Families

### 4.1 Family A: Pipeline/Stages

**Sources:** deepseek StepPipeline, kimi RunSpec+DecisionAssembly, glm PMC, gpt Compiled Run Plan

**Organizing Principle:** The decision process is a linear sequence of named stages. Each stage is an independent unit that receives context, makes a decision (or passes), and emits telemetry. The mode is a preset over which stages are active and their parameters.

**Key Abstractions:**
- `RunSpec` — immutable, resolved at startup, encodes the mode identity
- `DecisionStage` — named unit of decision logic (e.g., `BudgetCheckStage`, `LlmNewStateStage`, `SataChainStage`)
- `DecisionPipeline` — ordered list of stages, built from `RunSpec`
- `StageResult` — typed output from each stage (selected action, or pass)

**How modes are expressed:**
```
RunSpec(mode="sata_mop_widget") → activates [BudgetCheck, LlmHooks, MopScoring, SataChain]
RunSpec(mode="pure")            → activates [SataChain] only (byte-identical upstream)
```

**Driver satisfaction:**

| Driver | Weight | Score | Rationale |
|--------|--------|-------|-----------|
| D1 baseline fidelity | 12% | 8 | Pure mode = pipeline with single stage, trivially verifiable |
| D2 mode first-class | 10% | 9 | RunSpec is the mode identity, immutable, type-safe |
| D3 feature management | 9% | 7 | Features as stage parameters, but no formal feature enum |
| D4 spaghetti | 10% | 9 | Each stage is independently readable/testable |
| D5 testability | 8% | 8 | Stages are pure functions with typed I/O |
| D6 memory | 8% | 3 | No memory management — stages don't address bounded collections |
| D7 traceability | 12% | 6 | Stages log their decisions, but no structured event stream |
| D8 resilience | 8% | 4 | No recovery mechanism — stage failure is fatal |
| D9 ablation | 15% | 9 | RunSpec is the single source of truth; disabling a stage = removing it from the list |
| D10 simplicity | 8% | 8 | Linear pipeline is easy to reason about |

**Honest Weaknesses:** Does not address memory bounding (P3/P4/P5). Traceability is log-based, not event-sourced. No recovery from stage failures.

### 4.2 Family B: Event-Sourced

**Sources:** kimi Event-Sourced Explorer, gpt Event-Sourced Bounded Explorer, opencode Log-Is-Truth, ling Event-Sourced Decision Stream, mimo Event-Sourced Decision Stream, gemini ERKE

**Organizing Principle:** Every decision, state transition, and action is recorded as an immutable event in an append-only log. The current state is derived from the event log. The log is the authoritative artifact; everything else is a projection.

**Key Abstractions:**
- `DecisionEvent` — immutable record of a single decision (who, what, why, when)
- `EventJournal` — append-only log on device (JSONL)
- `ModelView` — derived projection from event log (current state of exploration graph)
- `DecisionTree` — hierarchical decomposition of decisions from events

**How modes are expressed:**
```
Events are tagged with mode metadata. Mode = the set of event types that are produced.
Pure mode = only upstream-compatible events are emitted.
```

**Driver satisfaction:**

| Driver | Weight | Score | Rationale |
|--------|--------|-------|-----------|
| D1 baseline fidelity | 12% | 7 | Event log is byte-identical to existing action history format |
| D2 mode first-class | 10% | 6 | Mode is metadata on events, not a first-class entity |
| D3 feature management | 9% | 5 | Features are implicit in which event types are emitted |
| D4 spaghetti | 10% | 6 | Events are clean, but derivation logic can become complex |
| D5 testability | 8% | 7 | Events are easy to assert against |
| D6 memory | 8% | 9 | Bounded event log with eviction; state derived, not stored |
| D7 traceability | 12% | 10 | Events ARE the trace — perfect provenance |
| D8 resilience | 8% | 9 | Replay from event log enables recovery |
| D9 ablation | 15% | 5 | Disabling a feature means suppressing event types — indirect |
| D10 simplicity | 8% | 4 | Full event sourcing is conceptually heavy for this codebase |

**Honest Weaknesses:** Full event sourcing is overkill for a testing tool that runs once and discards. The derivation logic (ModelView) would duplicate existing Model/Graph logic. The owner decided Q3=no checkpoint/resume, eliminating the strongest use case for event replay.

### 4.3 Family C: Capability/Manifest

**Sources:** deepseek CapabilityMatrix, gemini DPMA, ling Manifest+Capability Injector, mimo Capability Manifest+Injector, opencode Policy-Is-The-Jar

**Organizing Principle:** A declarative manifest describes what the system can do (capabilities) and what it should do in each mode. The manifest is validated at startup (compile-time or load-time). The runtime interpreter executes according to the manifest.

**Key Abstractions:**
- `CapabilityManifest` — declarative description of modes and their feature sets
- `Feature` — enum or interface representing a toggleable behavior
- `CapabilityInjector` — validates manifest at startup, injects features into runtime
- `ModePreset` — named combination of features (e.g., `sata_mop_widget`)

**How modes are expressed:**
```java
enum Feature { MOP_SCORING, LLM_ROUTING, ACTIVITY_BUDGET, FORM_COMPLETION, ... }
ModePreset SATA_MOP_WIDGET = new ModePreset("sata_mop_widget",
    Feature.MOP_SCORING, Feature.LLM_ROUTING, Feature.ACTIVITY_BUDGET);
```

**Driver satisfaction:**

| Driver | Weight | Score | Rationale |
|--------|--------|-------|-----------|
| D1 baseline fidelity | 12% | 7 | Pure mode = empty feature set, trivially verifiable |
| D2 mode first-class | 10% | 10 | Manifest IS the mode identity |
| D3 feature management | 9% | 10 | Features are first-class enum values |
| D4 spaghetti | 10% | 6 | Reduces Config sprawl, but doesn't decompose selectNewActionNonnull |
| D5 testability | 8% | 7 | Manifest is easy to test; features are injectable |
| D6 memory | 8% | 3 | No memory management |
| D7 traceability | 12% | 4 | Manifest doesn't address telemetry |
| D8 resilience | 8% | 3 | No recovery mechanism |
| D9 ablation | 15% | 10 | Disabling a feature = removing from manifest set |
| D10 simplicity | 8% | 7 | Enum + manifest is conceptually simple |

**Honest Weaknesses:** Manifest alone doesn't decompose the decision logic. It addresses mode identity and feature toggling but not the spaghetti in `selectNewActionNonnull` or memory bounding.

### 4.4 Family D: Physical Separation

**Sources:** deepseek TwoLineage, kimi Two-Lane Provenance Split, opencode Process-Is-Disposable, gemini Substrate/Guided Decorator

**Organizing Principle:** Upstream APE and APE-RV fork live in separate build artifacts, processes, or physical directories. Changes to the fork never modify upstream files. The boundary is a physical interface.

**Key Abstractions:**
- `UpstreamArtifact` — unmodified APE classes
- `ForkArtifact` — APE-RV additions
- `Bridge` — interface between upstream and fork (dependency injection or process boundary)

**How modes are expressed:**
```
Physical separation IS the mode: upstream artifact = pure mode, fork artifact = RV mode.
Sub-modes (sata_mop_widget, etc.) are configurations within the fork artifact.
```

**Driver satisfaction:**

| Driver | Weight | Score | Rationale |
|--------|--------|-------|-----------|
| D1 baseline fidelity | 12% | 10 | Upstream is literally untouched |
| D2 mode first-class | 10% | 5 | Sub-modes still implicit within fork |
| D3 feature management | 9% | 4 | No feature enumeration within fork |
| D4 spaghetti | 10% | 4 | Fork files still have the same spaghetti |
| D5 testability | 8% | 6 | Upstream tests pass unchanged; fork tests independent |
| D6 memory | 8% | 2 | No memory management |
| D7 traceability | 12% | 3 | No telemetry improvements |
| D8 resilience | 8% | 4 | Process separation enables restart, but overkill |
| D9 ablation | 15% | 8 | Upstream = pure mode; fork = everything else |
| D10 simplicity | 8% | 3 | Two build artifacts, two deployment paths, bridge layer |

**Honest Weaknesses:** The owner decided Q3=one sample per run, no checkpoint/resume, eliminating the strongest argument for physical separation. The fork is already 33 new + 22 modified files — physical separation would require rewriting the Monkey entry point and all AOSP-derived classes. The complexity cost outweighs the fidelity benefit.

---

## 5. Cross-Family Comparison

### 5.1 Scoring Matrix

Weights reflect thesis priorities (D9 ablation at 15% is highest because the thesis compares experimental arms).

| Driver | Weight | A: Pipeline | B: Event-Sourced | C: Manifest | D: Physical |
|--------|--------|-------------|-------------------|-------------|-------------|
| D1 baseline fidelity | 12% | 8 | 7 | 7 | **10** |
| D2 mode first-class | 10% | 9 | 6 | **10** | 5 |
| D3 feature management | 9% | 7 | 5 | **10** | 4 |
| D4 spaghetti | 10% | **9** | 6 | 6 | 4 |
| D5 testability | 8% | **8** | 7 | 7 | 6 |
| D6 memory | 8% | 3 | **9** | 3 | 2 |
| D7 traceability | 12% | 6 | **10** | 4 | 3 |
| D8 resilience | 8% | 4 | **9** | 3 | 4 |
| D9 ablation | 15% | **9** | 5 | **10** | 8 |
| D10 simplicity | 8% | 8 | 4 | 7 | 3 |
| **Weighted Total** | | **7.33** | **6.52** | **6.57** | **5.41** |

### 5.2 Key Insight

No single family dominates. Family A (Pipeline) scores highest overall but is weak on memory and traceability. Family C (Manifest) scores highest on ablation and mode identity but doesn't address spaghetti or memory. The optimal architecture combines A+C with targeted fixes for memory (B's bounded collections) and traceability (structured telemetry without full event sourcing).

---

## 6. Selected Architectures

### 6.1 RunSpec Pipeline (Recommended)

**One-line:** Immutable mode identity (RunSpec) drives a staged decision pipeline (DecisionPipeline) with bounded memory and structured telemetry.

**Family combination:** A (Pipeline) backbone + C (Manifest) mode identity + targeted B (bounded memory, JSONL telemetry).

**Migration path:**

| Stage | What | Effort | Independent Value |
|-------|------|--------|-------------------|
| S1: RunSpec extraction | Extract mode identity from Config into immutable RunSpec; validate at startup | 3 days | Mode is testable, ablation is trivial |
| S2: DecisionPipeline | Decompose `selectNewActionNonnull` into named stages | 5 days | Each stage independently testable |
| S3: Memory bounds | Bounded `actionHistory`, `treeHistory`, cache eviction | 3 days | Prevents OOM in long runs |
| S4: Structured telemetry | JSONL step events replacing ad-hoc log lines | 3 days | Enables automated analysis |

**Total:** ~14 working days (3 weeks)

**Risk assessment:**
- S1: Low risk — pure refactoring, no behavior change
- S2: Medium risk — must preserve exact SATA chain semantics; regression tests critical
- S3: Low risk — bounded collections are additive; existing code reads, new code truncates
- S4: Low risk — additive telemetry; existing logcat output unchanged

### 6.2 Capability-First Pipeline

**One-line:** Feature enum drives both mode identity and pipeline stage activation, with compile-time validation.

**Family combination:** C (Manifest) backbone + A (Pipeline) execution.

**Migration path:**

| Stage | What | Effort |
|-------|------|--------|
| S1: Feature enum | Define `Feature` enum with all RV-specific behaviors | 2 days |
| S2: ModePreset | Named combinations of features, replacing Config flag sets | 3 days |
| S3: DecisionPipeline | Decompose SATA chain using Feature gates | 5 days |
| S4: Memory + telemetry | Same as RunSpec Pipeline S3+S4 | 6 days |

**Total:** ~16 working days

**Risk:** Slightly higher than RunSpec Pipeline because Feature enum must be comprehensive from day one.

### 6.3 Bounded Event Telemetry

**One-line:** Structured JSONL event stream with bounded memory, without full event sourcing.

**Family combination:** B (Event-Sourced) telemetry + A (Pipeline) execution.

**Migration path:**

| Stage | What | Effort |
|-------|------|--------|
| S1: StepEvent record | Define immutable step event type | 1 day |
| S2: EventJournal | Bounded JSONL writer on device | 2 days |
| S3: Memory bounds | Same as RunSpec Pipeline S3 | 3 days |
| S4: Pipeline decomposition | Same as RunSpec Pipeline S2 | 5 days |
| S5: RunSpec | Same as RunSpec Pipeline S1 | 3 days |

**Total:** ~14 working days

**Risk:** Event journal adds a new file artifact; must handle device I/O failures gracefully.

### 6.4 Feature-Gated Purity

**One-line:** `apePureMode` kill-switch replaced by a Feature set that makes purity the absence of features.

**Family combination:** C (Manifest) mode identity + D (Physical) purity guarantee.

**Migration path:**

| Stage | What | Effort |
|-------|------|--------|
| S1: Feature enum | Same as Capability-First S1 | 2 days |
| S2: Pure mode = empty set | Replace apePureMode kill-switch with Feature.EMPTY | 2 days |
| S3: Config decomposition | Extract RV-specific Config fields into Feature-gated sections | 5 days |
| S4: Pipeline + memory + telemetry | Combined | 8 days |

**Total:** ~17 working days

**Risk:** Config decomposition is the highest-risk stage because it touches the most files.

### 6.5 Telemetry-First Decomposition

**One-line:** Start with structured telemetry (JSONL), use it to identify and validate the pipeline decomposition.

**Family combination:** B (Event-Sourced) telemetry + A (Pipeline) execution.

**Migration path:**

| Stage | What | Effort |
|-------|------|--------|
| S1: StepEvent + JSONL | Structured telemetry as first step | 3 days |
| S2: RunSpec extraction | Use telemetry to validate mode boundaries | 3 days |
| S3: Pipeline decomposition | Data-driven decomposition using telemetry insights | 5 days |
| S4: Memory bounds | Bounded collections | 3 days |

**Total:** ~14 working days

**Risk:** Lowest risk — telemetry is purely additive, provides data for subsequent stages.

---

## 7. Recommended Architecture: RunSpec Pipeline

### 7.1 Concrete Module/Package Structure

```
com.android.commands.monkey.ape/
├── agent/
│   ├── SataAgent.java              (modified: delegates to DecisionPipeline)
│   ├── ApeAgent.java               (modified: createAgent fails loudly)
│   ├── RandomAgent.java            (unchanged)
│   ├── ReplayAgent.java            (unchanged)
│   ├── StatefulAgent.java          (unchanged)
│   └── pipeline/                   (NEW package)
│       ├── RunSpec.java            (immutable mode identity)
│       ├── RunSpecFactory.java     (builds RunSpec from Config + CLI args)
│       ├── DecisionStage.java      (interface)
│       ├── DecisionPipeline.java   (ordered stages)
│       ├── StageResult.java        (typed stage output)
│       ├── BudgetCheckStage.java   (extracted from SataAgent:468-477)
│       ├── LlmHookStage.java       (extracted from SataAgent:479-514)
│       ├── ActivityTriggerStage.java (extracted from SataAgent:516-544)
│       ├── ComponentTriggerStage.java (extracted from SataAgent:546-551)
│       └── SataChainStage.java     (extracted from SataAgent:552-588)
├── memory/                         (NEW package)
│   ├── BoundedActionHistory.java   (replaces Model.actionHistory)
│   ├── BoundedTreeHistory.java     (replaces State.treeHistory)
│   └── CacheEviction.java          (replaces namingToGUITreeNodeCache)
├── telemetry/                      (NEW package)
│   ├── StepEvent.java              (immutable per-step record)
│   ├── EventJournal.java           (bounded JSONL writer)
│   └── HeartbeatLogger.java        (logcat step-heartbeat)
├── model/
│   ├── Model.java                  (modified: uses BoundedActionHistory)
│   ├── State.java                  (modified: uses BoundedTreeHistory)
│   └── ModelAction.java            (unchanged)
├── utils/
│   ├── Config.java                 (modified: RV fields extracted into RunSpec)
│   └── Logger.java                 (modified: runtime debug toggle)
└── scoring/
    ├── ScoringPipeline.java        (unchanged — already well-structured)
    └── ScoringPass.java            (unchanged)
```

### 7.2 How Modes Are Expressed

```java
// RunSpec.java — immutable, resolved at startup
public final class RunSpec {
    private final String modeName;           // e.g., "sata_mop_widget"
    private final Set<Feature> features;     // immutable feature set
    private final RunConfig config;          // extracted Config subset

    public enum Feature {
        MOP_SCORING,          // mopDataPath != null
        LLM_ROUTING,          // llmUrl != null
        ACTIVITY_BUDGET,      // activityBudgetEnabled
        FORM_COMPLETION,      // formCompletionEnabled
        STEP_TELEMETRY,       // stepTelemetryEnabled
        MODEL_MENU,           // modelMenuEnabled
        TREE_ENHANCEMENTS,    // treeEnhancementsEnabled
        COMPONENT_TRIGGER,    // componentPercentage > 0
        ACTIVITY_TRIGGER,     // activityTriggerEnabled
        FRONTIER_BOOST,       // frontierBoostWeight > 0
        MOP_FRONTIER,         // mopFrontierWeight > 0
        FUZZING,              // doFuzzing
        DYNAMIC_EPSILON       // dynamicEpsilon
    }

    // Pure mode = modeName="pure", features=Set.of()
    public static final RunSpec PURE = new RunSpec("pure", Set.of(), RunConfig.upstreamDefaults());
}
```

### 7.3 How Each Verified Problem Is Solved

| # | Problem | Solution | Stage |
|---|---------|----------|-------|
| P1 | `selectNewActionNonnull` 141 LOC | Decompose into 5 named `DecisionStage` implementations in `DecisionPipeline` | S2 |
| P2 | Config 118 fields, silent errors | Extract RV-specific fields into `RunSpec.RunConfig`; validate at startup; throw on parse errors | S1 |
| P3 | `Model.actionHistory` unbounded | Replace with `BoundedActionHistory(maxSize=10000)` — ring buffer, oldest entries evicted | S3 |
| P4 | `State.treeHistory` unbounded | Enforce `maxGUITreesPerState` in `appendGUITree()` — existing config flag, never enforced | S3 |
| P5 | `namingToGUITreeNodeCache` never cleared | Add `CacheEviction.afterStep()` called at end of each step — LRU with max 5000 entries | S3 |
| P6 | `ScoringPipeline.fromConfig` decorative param | Remove `Config cfg` parameter; pass only `ScoringContext` | S1 |
| `ApeAgent.createAgent` silent fallthrough | Add `throw new IllegalArgumentException("Unknown agent type: " + type)` for unrecognized values | S1 |
| P8 | Component trigger side-effect | Make `ComponentTriggerStage` return a typed `StageResult.PASS` when triggered (no side-effect return) | S2 |
| P9 | LLM router 3 hooks with identical preamble | Extract shared precondition into `LlmHookStage.canAttempt()` method; single check replaces 3 copies | S2 |
| P10 | Monkey.java teardown isolation | No change — intentional design, already correct | — |
| P11 | 7 ScoringPass implementations | No change — already well-structured via ScoringPipeline | — |
| P12 | Growing DecisionSource/PickChannel enums | Add new values as needed; no structural change required | — |
| P13 | 33 new files in fork | No change — physical separation rejected by Q3 decision | — |
| P14 | Logger.debug=false compile-time | Add `Config.getRuntimeLogLevel()` check; no recompilation needed | S4 |

### 7.4 Migration Stages with Effort Estimates

**Stage S1: RunSpec Extraction (3 days)**

1. Create `pipeline/RunSpec.java` with `Feature` enum and immutable mode identity
2. Create `pipeline/RunSpecFactory.java` — reads existing `Config` fields, validates, produces `RunSpec`
3. Modify `Config.java` — extract RV-specific fields into `RunSpec.RunConfig`; keep upstream fields in `Config`
4. Modify `ApeAgent.createAgent` — fail loudly for unknown `--ape` values
5. Modify `ScoringPipeline.fromConfig` — remove decorative `Config cfg` parameter
6. Write unit tests: `RunSpecFactoryTest`, `RunSpecTest`

**Stage S2: DecisionPipeline (5 days)**

1. Create `pipeline/DecisionStage.java` interface with `name()`, `isEnabled(RunSpec)`, `attempt(context): StageResult`
2. Create `pipeline/DecisionPipeline.java` — builds stage list from `RunSpec`, executes in order
3. Create `pipeline/StageResult.java` — sealed type: `SELECTED(ModelAction)`, `PASS`, `SIDE_EFFECT`
4. Extract `BudgetCheckStage` from `SataAgent.java:468-477`
5. Extract `LlmHookStage` from `SataAgent.java:479-514` — includes shared precondition from P9
6. Extract `ActivityTriggerStage` from `SataAgent.java:516-544`
7. Extract `ComponentTriggerStage` from `SataAgent.java:546-551` — now returns `StageResult.SIDE_EFFECT`
8. Extract `SataChainStage` from `SataAgent.java:552-588` — the fallback SATA selection
9. Modify `SataAgent.selectNewActionNonnull` — delegate to `DecisionPipeline`
10. Write unit tests for each stage with stub context

**Stage S3: Memory Bounds (3 days)**

1. Create `memory/BoundedActionHistory.java` — ring buffer wrapping `ArrayList<ActionRecord>`
2. Create `memory/BoundedTreeHistory.java` — enforces `maxGUITreesPerState` in `appendGUITree()`
3. Create `memory/CacheEviction.java` — LRU eviction for `namingToGUITreeNodeCache`
4. Modify `Model.java:137` — replace `ArrayList` with `BoundedActionHistory`
5. Modify `State.java:405-408` — enforce truncation in `appendGUITree()`
6. Modify `GUITreeBuilder.java:693` — call `CacheEviction.afterStep()` at step boundaries
7. Write unit tests: `BoundedActionHistoryTest`, `BoundedTreeHistoryTest`, `CacheEvictionTest`

**Stage S4: Structured Telemetry (3 days)**

1. Create `telemetry/StepEvent.java` — immutable record with all step fields
2. Create `telemetry/EventJournal.java` — bounded JSONL writer (max 10MB per run)
3. Create `telemetry/HeartbeatLogger.java` — logcat step-heartbeat for violation↔step join
4. Modify `Logger.java` — add runtime debug level check
5. Integrate `EventJournal` into `DecisionPipeline` — each stage emits its decision
6. Write unit tests: `StepEventTest`, `EventJournalTest`

### 7.5 Open Questions Needing Owner Decision

1. **BoundedActionHistory max size:** What is the maximum number of action records to retain? Default proposal: 10,000 (covers ~30 minutes of testing at 5 actions/second). Should this be configurable via `ape.properties`?

2. **EventJournal file rotation:** Should the JSONL file rotate when it reaches the size limit (overwrite oldest), or should it be a single file with a hard cap?

3. **RunSpec immutability enforcement:** Should `RunSpec` be a Java `record` (Java 16+) or a final class with defensive copies? The project targets Java 11, so `record` is not available.

4. **CacheEviction trigger point:** Should eviction run at the end of every step, or only when the cache exceeds a threshold? Threshold-based is more efficient but adds a size check.

5. **Telemetry artifact location:** Where should the JSONL file be written? Options: `/data/local/tmp/ape_events.jsonl` (same partition as ape.properties) or `/sdcard/ape_events.jsonl` (user-accessible but slower I/O).

---

## 8. Architecture Anti-Patterns to Avoid

### 8.1 Rejected: Full Event Sourcing

Full event sourcing (Family B as a complete architecture) requires maintaining an append-only event log as the authoritative state, with all runtime state derived from replay. This is rejected because:

- APE runs once per experiment and discards state — there is no replay use case
- The owner decided Q3=no checkpoint/resume
- Deriving Model/Graph from events would duplicate 571+576 lines of existing logic
- The added complexity does not improve any of the 10 design drivers enough to justify the cost

### 8.2 Rejected: Physical Lane Split

Separating upstream APE and APE-RV into distinct build artifacts (Family D) is rejected because:

- The owner decided Q3=one sample per run — no need to preserve upstream as a separate artifact
- 33 new + 22 modified files means the fork is already well-defined
- Monkey.java is the entry point and cannot be split without a process boundary
- The bridge layer between upstream and fork would add more complexity than it removes

### 8.3 Rejected: Two-Process Architecture

Running upstream APE and RV fork as separate processes with message passing (from ling-3-0) is rejected because:

- `app_process` on Dalvik does not support inter-process communication for this use case
- The performance overhead of IPC per GUI tree capture (every ~200ms) is unacceptable
- No existing mechanism for sharing the exploration graph across processes

### 8.4 Rejected: Async Event-Driven Kernel

An asynchronous event-driven kernel (gemini's ERKE) is rejected because:

- APE's main loop is synchronous by design — each step must complete before the next
- Async would introduce race conditions in Model/Graph updates
- The current `defaultGUIThrottle=200ms` already provides natural pacing
- Adding an event loop is unnecessary complexity for a single-threaded testing tool

### 8.5 Rejected: Compile-Time Injection

Compile-time capability injection (from deepseek CapabilityMatrix, mimo Capability Manifest) is rejected because:

- APE runs on-device via `app_process` — there is no build-time code generation step
- The project uses `javac 11` + `d8` desugaring — no annotation processor infrastructure exists
- Startup validation (as in RunSpecFactory) achieves the same safety guarantee without build complexity

### 8.6 Anti-Pattern: Config as God Object

The current `Config.java` with 118 public static fields loaded once at class-load is the root cause of implicit mode treatment. The anti-pattern is: **any architecture that preserves Config as the central mode authority**. The solution is to extract mode-specific configuration into an immutable `RunSpec` resolved at startup, with validation.

### 8.7 Anti-Pattern: Silent Fallthrough

`ApeAgent.createAgent` falling through to `SataAgent` for unknown `--ape` values is a silent-fallthrough anti-pattern. The solution is: **fail loudly for unrecognized values**. This is a one-line fix that should be done immediately, before any architectural changes.

---

## 9. Next Steps

### 9.1 Immediate Cheap Experiments (This Week)

These experiments discriminate between remaining options with minimal effort:

**Experiment 1: RunSpec Prototype (1 day)**
Create `RunSpec.java` with the `Feature` enum and `RunSpecFactory`. Wire it into `ApeAgent.createAgent` so it builds a `RunSpec` and logs it at startup. This validates:
- Can the Feature enum capture all RV-specific behaviors?
- Does RunSpecFactory correctly derive features from Config?
- Does the pure mode (empty feature set) produce identical selection?

Success criterion: `[APE-ARCH] RunSpec{sata_mop_widget, features=[MOP_SCORING, LLM_ROUTING, ...]}` appears in logcat.

**Experiment 2: SelectNewActionNonnull Decomposition (2 days)**
Extract the 7 blocks in `SataAgent.selectNewActionNonnull` into package-private methods with typed return values (not `Action resolved = null; if (resolved != null) return;` chains). This validates:
- Can each block be tested independently?
- Are there hidden dependencies between blocks?
- Does the budget check truly have no return value interaction with LLM hooks?

Success criterion: Each extracted method has a unit test. `selectNewActionNonnull` is under 20 LOC.

**Experiment 3: BoundedActionHistory Spike (0.5 days)**
Replace `Model.actionHistory` ArrayList with a ring buffer that evicts the oldest 10% when it exceeds 10,000 entries. Run a 10-minute experiment on device and verify no OOM.

Success criterion: `[APE-RV] ActionHistory evicted=234` appears in logcat after 10 minutes.

### 9.2 Decision Points After Experiments

After the 3 experiments, the remaining decisions are:

1. **RunSpec vs Capability-First:** If the Feature enum captures all behaviors cleanly, RunSpec is sufficient. If some behaviors require parameterization (e.g., `mopWeightDirect=500`), a richer CapabilityManifest is needed.

2. **Pipeline decomposition order:** The experiments will reveal whether the SATA chain blocks have hidden state dependencies that prevent clean extraction. If so, the decomposition may need to be coarser (3 stages instead of 5).

3. **Memory bounds values:** The spike will reveal whether 10,000 action records and 20 GUITrees per state are the right bounds, or whether they need tuning.

### 9.3 Priority Order

1. **Immediate (this week):** Experiment 1 (RunSpec prototype) — discriminates between Family A and C
2. **Next week:** Experiment 2 (Pipeline decomposition) — validates Family A feasibility
3. **Week 3:** Experiment 3 (Memory bounds) + full Stage S1 implementation
4. **Week 4-5:** Stage S2 (DecisionPipeline) — the highest-risk, highest-value stage
5. **Week 6:** Stages S3+S4 (Memory + Telemetry) — low-risk additive changes

---

## Appendix A: File Inventory (Key Files Referenced)

| File | Lines | Role |
|------|-------|------|
| `SataAgent.java` | 1,762 | Primary agent, contains `selectNewActionNonnull` |
| `Config.java` | 502 | 118 public static fields, global singleton |
| `LlmRouter.java` | 996 | LLM routing with 3 hooks |
| `Model.java` | 571 | Exploration graph, actionHistory |
| `State.java` | 576 | State representation, treeHistory |
| `GUITreeBuilder.java` | 716 | GUI tree abstraction, namingToGUITreeNodeCache |
| `ScoringPipeline.java` | 96 | Scoring pass orchestration (already clean) |
| `ApeAgent.java` | 465 | Agent factory with silent fallthrough |
| `Monkey.java` | 1,625 | Entry point, teardown isolation |
| `ModelAction.java` | 356 | DecisionSource (11 values), PickChannel (8 values) |
| `Logger.java` | 67 | debug=false at compile time |

## Appendix B: LLM Report Mapping

| Architecture Family | LLM Sources | Primary Proponent |
|---------------------|-------------|-------------------|
| A: Pipeline/Stages | deepseek (StepPipeline), kimi (RunSpec+DecisionAssembly), glm (PMC), gpt (Compiled Run Plan) | deepseek |
| B: Event-Sourced | kimi (Event-Sourced Explorer), gpt (Event-Sourced Bounded Explorer), opencode (Log-Is-Truth), ling, mimo, gemini (ERKE) | opencode |
| C: Capability/Manifest | deepseek (CapabilityMatrix), gemini (DPMA), ling (Manifest+Capability Injector), mimo (Capability Manifest+Injector), opencode (Policy-Is-The-Jar) | mimo |
| D: Physical Separation | deepseek (TwoLineage), kimi (Two-Lane Provenance Split), opencode (Process-Is-Disposable), gemini (Substrate/Guided Decorator) | deepseek |

## Appendix C: Glossary

| Term | Definition |
|------|-----------|
| RunSpec | Immutable mode identity resolved at startup |
| Feature | Enum value representing a toggleable RV-specific behavior |
| DecisionStage | Named unit of decision logic in the pipeline |
| StageResult | Typed output from a DecisionStage (SELECTED, PASS, SIDE_EFFECT) |
| BoundedActionHistory | Ring buffer replacing the unbounded ArrayList in Model |
| BoundedTreeHistory | Enforced truncation of GUITree list per state |
| EventJournal | Bounded JSONL writer for structured step telemetry |
| SATA chain | The fallback selection logic in SataAgent (buffer→back→early→trivial→epsilon→null) |
| MOP | Monitored Operations — JavaMOP specifications for property monitoring |
| WTG | Widget Transition Graph — inter-widget navigation model |
