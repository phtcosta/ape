---
model: gemini-3.6-flash
date: 2026-08-01
commit: 5dcf225976b26ce78d8b31dd88d7f858dad29d43
methodology: Direct systematic codebase inspection of ape repository (commit 5dcf225), static-analysis dataset verification, cross-repository alignment with rvsec/rv-android, inspection of thesis context (doutorado-tese) and journal publication scripts (ase-journal), and architectural evidence synthesis.
---

# Preliminary Architecture Study: Re-Architecting APE-RV

## Executive Overview & Methodology

This study presents a preliminary architectural analysis and redesign strategy for **APE-RV** (Android Property Explorer — Runtime Verification Fork). It addresses the structural debt, mode/feature entanglement, memory ownership ambiguities, observability gaps, and experimental fragility identified in commit `5dcf225976b26ce78d8b31dd88d7f858dad29d43`.

### Methodology & Scope Verification
- **Codebase Audited:** `src/main/java` (173 files, ~36,414 LOC), `src/test/java` (74 test files, 785 `@Test` annotations), `openspec/specs/` (20 capability specifications), and `openspec/changes/telemetry-proof-llm-efficacy/`.
- **Cross-Repository Alignment:** Audited `rvsec/rv-android` (`modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py`, 1,146 LOC + 1,181 LOC tests), `doutorado-tese` (`tex/1_Introducao.tex`, `tex/4_EstudoDeCaso.tex`, `tex/5_Cronograma.tex`), and `ase-journal` (`constants.tex`, `data-analysis/` scripts).
- **Scope Invariants:** No production code (`src/`, `pom.xml`, `openspec/`) or sibling repositories were modified. All proposals respect Dalvik/Android runtime constraints (`app_process` execution, javac release 11, `d8` desugaring, zero third-party framework dependencies at runtime).

---

## Part A — Architectural Diagnosis

### A1. Structural Reality of APE-RV Today
APE-RV is nominally structured around the upstream AOSP `Monkey` entry point and ETH Zurich's AST Lab `APE` state-refinement engine (`ape/naming/`). In reality, the fork has evolved into a monolithic, tightly coupled execution block where perception, scoring, action routing, process resilience, and experiment telemetry intersect across five hotspot files.

```
      [ rv-android (Python Pipeline) ]
                     │  (pushes ape.properties / SA JSON; launches via adb)
                     ▼
          [ Monkey.java (Entry point) ]
                     │
    ┌────────────────┴────────────────┐
    ▼                                 ▼
[ MonkeySourceApe.java ]     [ ApeAgent.java / StatefulAgent.java ]
(Event loop, guards, GT)     (State graph, telemetry, scoring pass)
                                      │
                                      ▼
                            [ SataAgent.java ]
                            (141 LOC selectNewActionNonnull)
                            ┌─────────┼─────────┐
                            ▼         ▼         ▼
                        [MopData] [LlmRouter] [SataChain]
```

Rather than a layered architecture (Driver → Model → Strategy → Telemetry), responsibilities are tangled across class boundaries:
1. **Perception & Tree Construction:** `GUITreeBuilder.java:515-520` mixes DOM parsing with AndroidX heuristics and WebView node prunings conditionally gated by static flags (`Config.treeEnhancementsEnabled`).
2. **Action Selection & Precedence:** `SataAgent.java:449-589` hardcodes the precedence of four independent subsystems (LLM hooks, MOP launcher, Component trigger, SATA fallback chain) into a 141 LOC statement sequence.
3. **Telemetry & Metric Harvesting:** Telemetry is scattered across `StatefulAgent.java` (`[APE-STEP]`, `[APE-OUTCOME]`), `LlmRouter.java` (`[APE-LLM-TEL]`), and `Logger.java` (`[APE-RV]`), writing directly to unbuffered stdout without structured serialization or escaping.

### A2. Mode & Feature Control Surface Archaeology
The mode and feature control surface lives split across two repositories:
- **Java Layer (`ape/utils/Config.java`):** 502 LOC containing 112 `public static [final]` fields + 5 ad-hoc property keys (117 total). Flags are evaluated at static class-load time from system properties or `/data/local/tmp/ape.properties`. `Config.set()` (`Config.java:414`) is ineffective after any field is first accessed. Numeric parsing swallows `NumberFormatException` silently (`Config.java:453-454, 465-466, 477-478`), falling back to defaults without warnings. `apePureMode` (`Config.java:41-43, 343-412`) acts as a brute-force property overwrite pre-initialiser rather than a structured mode context.
- **Python Layer (`rv-android/.../tool.py`):** The *de facto* mode system lives in Python as `APERV_PROPERTY_MAPPING` (`tool.py:75`), `ARM_DEFINING_KEYS` (`tool.py:171`, 18 keys), and `get_variants()` (`tool.py:427`), managing 26 experimental arms.

**Historical Context:** The system grew by accretion under experimental deadlines during DSR Cycle 2. When new features (e.g., MOP guidance, LLM vision-language routing) were introduced, they were added as inline `if (Config.featureEnabled)` blocks inside existing execution loops to minimize regression risks on existing code paths, leading to structural spaghetti.

### A3. Seven Core Architectural Tensions

#### Tension 1: Baseline Parity vs. Observability (Drivers D1, D7)
- **Evidence:** `Config.java:346` explicitly disables `stepTelemetryEnabled` under `apePureMode`.
- **Impact:** Running `apePureMode=true` restores near-baseline APE behavior but strips all `[APE-STEP]` and `[APE-OUTCOME]` telemetry. Consequently, the control arm (`ape_pure`) is the least observable arm in the experimental campaign, preventing deep comparative diagnostic analysis across arms.

#### Tension 2: Un-gated Substrate Modifications vs. Scientific Validity (Drivers D1, D9)
- **Evidence:** 11 core sites retain fork modifications regardless of `apePureMode` state. For example:
  - `RandomHelper.java:25-35` + `Monkey.java:731`: `ThreadLocalRandom` replaced with seedable `Random`, altering global explorer randomness.
  - `Naming.java:421-432`: `binarySearch` modification alters namelet containment and state abstraction.
  - `NamingFactory.java:280, 1180`: `an.getStates()` changed to `state.getGUITrees()`, modifying refinement triggers.
  - `ActionType.java:38,42,48`: New enum ordinals (`EVENT_TRIGGER_ACTIVITY`, `MODEL_MENU`, `MODEL_LLM_TAP`) break binary model serialization compatibility with upstream APE.
- **Impact:** `apePureMode=true` does not produce byte-for-byte upstream APE (`8f51b99`), but an "APE + bugfixes" variant. This frontier is currently unmanaged in code.

#### Tension 3: Hardcoded Ordering vs. Feature Independence (Drivers D2, D3, D4)
- **Evidence:** `SataAgent.java:449-589` executes subsystem logic in exact textual order: Budget Gate → LLM New-State → LLM Stagnation → LLM Random → MOP Launcher → Component Trigger → SATA Chain.
- **Impact:** Subsystems cannot be reordered or combined dynamically. Evaluating an LLM hook before a MOP launcher is an arbitrary architectural choice baked into code statement order.

#### Tension 4: Synchronous Inference vs. Exploration Throughput (Drivers D6, D8, D11)
- **Evidence:** `LlmRouter.java:327-612` blocks the main event loop while executing HTTP requests (`llmTimeoutMs=15000`).
- **Impact:** As measured in `docs/20260724_relatorio_calibracao_aperv.md`, each LLM call costs ~1.0s wall-clock and **~0.94-0.97 exploration steps**. In a fixed ~285s time budget, heavy LLM arms lose up to 33% of total exploration steps (falling from 257.9 steps to 161.2 steps), directly causing a coverage deficit.

#### Tension 5: Monolithic Retainers vs. Bounded Execution (Driver D6)
- **Evidence:** Three independent memory retention roots exist without eviction:
  1. `Model.actionHistory` (`Model.java:137`): `List<ActionRecord>` retains strong references to `Action` -> `GUITreeAction` -> `GUITree` -> `GUITreeNode` for every step in the run.
  2. `Graph.stateTransitionHistory` & `entryGUITrees` (`Graph.java:96-132`): Retains full `GUITree` graphs across restarts.
  3. `GUITreeBuilder.namingToGUITreeNodeCache` (`GUITreeBuilder.java:679`): Monotonic per-node cache with no release mechanism.
- **Impact:** Long-running executions accumulate heap pressure, leading to `OutOfMemoryError` during teardown serialization.

#### Tension 6: Fragile Positional Telemetry vs. Analysis Pipeline (Drivers D7, D9)
- **Evidence:** `StatefulAgent.java:1492-1506` formats `[APE-STEP]` as space-separated unquoted `key=value` pairs. Values containing spaces (e.g., widget labels or component names) corrupt downstream python parsing (`rv-android`). Traces also contain NUL bytes and stdout buffer truncations (`tool.py:1121-1125`).
- **Impact:** 45% of task truncations in certain arms were misclassified as successful runs because process exit codes were lost over stdout.

#### Tension 7: Untestable Static Singletons vs. JVM Testing (Driver D5)
- **Evidence:** `AndroidDevice.java:61-77` exposes 7 `public static` system handles; `pom.xml:84-104` excludes Android stub jars from unit testing classpath to avoid `RuntimeException("Stub!")`.
- **Impact:** Any component referencing `AndroidDevice` or `android.*` classes cannot be tested on a plain JVM, forcing logic into package-private workaround seams (`Config.clampLlmPercentageNoSubstrate`, `Config.java:315`).

### A4. Preserved Invariants & Sound Architectural Design Elements
The redesign MUST preserve the following well-engineered aspects of the existing codebase:
1. **Teardown Isolation Invariants (`INV-EXPL-16`, `INV-EXPL-29`):** `Monkey.java:773-800` and `safeStep` wrappers in `MonkeySourceApe.java:225-247` ensure that exceptions during step execution or teardown never crash the parent shell process ungracefully.
2. **Fail-Fast on MOP Load (`INV-MOP-22`):** `MopData.java` aborts execution via `StopTestingException` if `mopDataPath` is set but loading fails, preventing silent fallback to unguided exploration during benchmark arms.
3. **Temporal Step/Clock Join Design (`INV-ARCH-01`):** `[APE-STEP].clock` timestamping (epoch millis) and `step=` correlation IDs allow offline cross-joining with logcat RVSEC events without coupling APE to logcat at runtime.
4. **Arm Explicitness Invariants (`INV-APV-14`):** Pytest guards in `rv-android` verifying that experimental arms explicitly define all arm-defining keys.
5. **No-Op Pipeline Contract (`INV-ARCH-03`):** The `ScoringPass` interface design where a disabled pass is a strict, zero-overhead no-op.

---

## Part B — Design Space Exploration

Before formulating concrete candidate architectures, we map the design space across seven key architectural axes and inspect prior art from Android GUI testing literature.

### B1. Prior Art Survey
- **Monkey (AOSP):** Pure pseudorandom event stream injection. Fast, zero-state overhead, but low deep-code coverage.
- **APE (ETH Zurich, ICSE 2019):** Dynamic GUI state abstraction refinement (CEGAR-style). High coverage efficiency, but state graph retained in memory indefinitely.
- **DroidBot / Humanoid (Static/Model-Based & ML):** DroidBot separates state abstraction from policy; Humanoid uses deep learning models to bias action priority. Both run host-side via ADB, incurring network/IPC latency per step.
- **Fastbot2 (ByteDance):** High-throughput on-device C++/Java explorer using probabilistic model checking and reinforcement learning. Low per-step overhead.
- **Stoat / ARES:** Dynamic stochastic model-based testing with MCMC optimization. Employs dual-stage static/dynamic feedback loops.

### B2. Architectural Trade-off Space Matrix

| Axis | Option 1: Monolithic Config & Pipeline | Option 2: Strategy / Agent Hierarchy | Option 3: Event-Driven Micro-Kernel |
|---|---|---|---|
| **1. Mode Definition** | Implemented as named `Properties` presets in Jar. | Expressed as discrete `AgentStrategy` classes. | Expressed as dynamic Event Bus subscriptions. |
| **2. Feature Representation** | `ScoringPass` & `ActionRouter` passes. | Modular `ExplorerDecorator` capabilities. | Decoupled `CapabilityModule` components. |
| **3. Decision Dispatch** | Sequential evaluation pipeline with priority weights. | Hierarchical strategy overriding. | Asynchronous `ActionArbiter` with priority queue. |
| **4. Memory & State** | In-memory graph + periodic LRU pruning. | In-memory active window + disk log buffer. | Compact indexed graph + off-heap append-only log. |
| **5. Observability** | Formatted JSON-lines to stdout/file sink. | Structured binary/JSON telemetry stream. | Event-driven async log appender to disk file. |
| **6. Resilience** | External supervisor + process restart. | In-process state snapshotting + exception recovery. | Micro-kernel restart with state-reconstitution log. |
| **7. Matrix Definition** | Feature Manifest (JSON/YAML) compiled into jar. | Python-side explicit factory instantiations. | Declarative Capability Prescription File. |

---

## Part C — Candidate Architectures

We present **three genuinely distinct candidate architectures**, each founded on a unique organizing principle, complete with package skelling, code sketches, trade-offs, and driver evaluations.

---

### Candidate 1: Data-Driven Pipeline & Preset Manifest Architecture (DPMA)

#### Organizing Principle
*“Mode is not code; Mode is a validated, immutable configuration preset over an explicit Data-Driven Decision Pipeline.”*

DPMA eliminates all static conditional flags and branch-based mode switching. The explorer core becomes an immutable engine that executes a pipeline of **Perceive → Score/Filter → Route** steps based on a **Preset Feature Manifest**.

```
                       [ Preset Feature Manifest (JSON/YAML) ]
                                         │
                                         ▼
                             [ FeatureConfig (Immutable) ]
                                         │
[ GUITree ] ──► [ PerceptionPipeline ] ──┼──► [ GuidancePipeline ] ──► [ Priority Action Matrix ]
                      (F1, F2, F7)       │    (F3, F9-F14, F6b)                 │
                                         ▼                                      ▼
                                [ ActionRouter Pipeline ] ◄─────────────────────┘
                                  (Precedence Resolver)
                                         │
                                         ▼
                                  [ Target Action ]
```

#### Module & Package Structure
```
com.android.commands.monkey.ape
├── engine
│   ├── ExplorationEngine.java
│   ├── FeatureConfig.java
│   └── PresetRegistry.java
├── perception
│   ├── PerceptionPipeline.java
│   ├── AndroidXTreeEnhancer.java
│   └── PackageGuardFilter.java
├── guidance
│   ├── GuidancePipeline.java
│   ├── ScoringPass.java
│   ├── MopGuidancePass.java
│   └── FormCompletionPass.java
├── routing
│   ├── ActionRouter.java
│   ├── LlmRoutingPass.java
│   └── SataRoutingPass.java
└── telemetry
    ├── StructuredLogger.java
    └── JsonStepRecord.java
```

#### Concrete Java Sketches

```java
// FeatureConfig: Immutable context replacing static Config.java
public final class FeatureConfig {
    private final String modeName;
    private final boolean mopEnabled;
    private final boolean llmEnabled;
    private final int mopWeightDirect;
    private final List<String> enabledPasses;

    private FeatureConfig(Builder builder) {
        this.modeName = builder.modeName;
        this.mopEnabled = builder.mopEnabled;
        this.llmEnabled = builder.llmEnabled;
        this.mopWeightDirect = builder.mopWeightDirect;
        this.enabledPasses = List.copyOf(builder.enabledPasses);
    }
    // Getters only, zero static state
}

// GuidancePipeline: Clean scoring pipeline
public final class GuidancePipeline {
    private final List<ScoringPass> activePasses;

    public GuidancePipeline(FeatureConfig config, MopData mopData) {
        List<ScoringPass> passes = new ArrayList<>();
        if (config.isMopEnabled() && mopData != null) {
            passes.add(new MopGuidancePass(config, mopData));
        }
        if (config.isPassEnabled("form_completion")) {
            passes.add(new FormCompletionPass());
        }
        this.activePasses = Collections.unmodifiableList(passes);
    }

    public void applyScoring(State state, List<ModelAction> actions) {
        for (ScoringPass pass : activePasses) {
            pass.score(state, actions);
        }
    }
}

// ActionRouter: Replacing selectNewActionNonnull
public final class ActionRouter {
    private final List<RoutingStage> stages;

    public ActionRouter(FeatureConfig config, LlmRouter llm, MopLauncher mopLauncher, SataChain sata) {
        List<RoutingStage> list = new ArrayList<>();
        if (config.isLlmEnabled()) {
            list.add(new LlmRoutingStage(llm));
        }
        if (config.isMopEnabled()) {
            list.add(new MopLauncherRoutingStage(mopLauncher));
        }
        list.add(new SataFallbackRoutingStage(sata));
        this.stages = Collections.unmodifiableList(list);
    }

    public ModelAction selectAction(State state, GUITree tree) {
        for (RoutingStage stage : stages) {
            Optional<ModelAction> action = stage.trySelect(state, tree);
            if (action.isPresent()) {
                return action.get();
            }
        }
        throw new IllegalStateException("No routing stage produced an action");
    }
}
```

#### Resolution of §2.2 Open Questions
1. **`mop` preset default:** Includes `aperv` enhancements (AndroidX, form completion) by default, declared via manifest inheritances (`mop extends aperv`).
2. **Widget vs. Frontier MOP:** Treated as parameterized scoring passes within `GuidancePipeline` rather than separate global modes.
3. **LLM Fallback:** Explicitly configured in the manifest as `llm.fallback = "aperv"` or `llm.fallback = "mop"`.
4. **Mode concept:** "Mode" is a named preset over an explicit `FeatureConfig` schema validated at boot time.

#### Driver Satisfaction (D1–D10)
- **D1 (Baseline):** `ape_pure` is expressed as a preset that disables all RV passes and activates upstream compatibility flags.
- **D2 (Mode):** Discoverable via `PresetRegistry.getAvailablePresets()`. Invalid combinations fail fast at startup with detailed validation errors.
- **D3 (Features):** Explicitly declared dependencies in feature manifests.
- **D4 (Spaghetti):** `SataAgent.selectNewActionNonnull` is replaced by `ActionRouter` stage iterations.
- **D5 (Testing):** All pipeline components accept `FeatureConfig` via constructor injection; zero static state.
- **D6 (Memory):** `Model.actionHistory` converted to an indexed ring buffer storing lightweight action IDs rather than full `GUITree` instances.
- **D7 (Traceability):** `StructuredLogger` outputs escape-safe JSON-lines (`{"step": 12, "src": "MOP", "ts": 1722510000}`) to stdout or `/sdcard/ape_trace.jsonl`.
- **D8 (Resilience):** External supervisor monitors execution; state graph checkpoints written to disk every N steps in binary format.
- **D9 (Ablation):** Automated matrix generation: python script generates preset manifests for full factorial or fractional designs.
- **D10 (Simplicity):** Low learning curve; uses standard Object-Oriented pipeline patterns.

#### Weaknesses & Trade-offs
- Pipeline evaluation remains sequential and synchronous.
- High initial refactoring cost to eliminate all static `Config.` references across 173 files.

---

### Candidate 2: Substrate Engine vs. Guidance Agent Decorator Architecture (SGDA)

#### Organizing Principle
*“Isolate the stock Dalvik exploration engine into an unencumbered Substrate, and implement all APE-RV features as Layered Agent Decorators.”*

SGDA strictly separates the physical device driver and basic GUI state explorer (`ApeSubstrate Engine`) from research-specific guidance logic. Stock APE runs completely untouched as `StockApeAgent`. `APE-RV` modes are constructed by decorating `ApeAgent` with strategy wrappers (`MopGuidedAgent`, `LlmGuidedAgent`).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             HybridGuidedAgent                               │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │                          LlmGuidedAgent                               │  │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │  │
│  │  │                       MopGuidedAgent                            │  │  │
│  │  │  ┌───────────────────────────────────────────────────────────┐  │  │  │
│  │  │  │                      StockApeAgent                        │  │  │  │
│  │  │  │  ┌─────────────────────────────────────────────────────┐  │  │  │  │
│  │  │  │  │                ApeSubstrate Engine                  │  │  │  │  │
│  │  │  │  │     (Accessibility Driver, Window Manager)          │  │  │  │  │
│  │  │  │  └─────────────────────────────────────────────────────┘  │  │  │  │
│  │  │  └───────────────────────────────────────────────────────────┘  │  │  │
│  │  └─────────────────────────────────────────────────────────────────┘  │
│  └───────────────────────────────────────────────────────────────────────┘
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Module & Package Structure
```
com.android.commands.monkey.ape
├── substrate
│   ├── ApeSubstrate.java
│   ├── DeviceDriver.java
│   └── WindowTracker.java
├── agent
│   ├── ApeAgentStrategy.java
│   ├── StockApeAgent.java
│   ├── AbstractAgentDecorator.java
│   ├── MopGuidedAgent.java
│   ├── LlmGuidedAgent.java
│   └── AgentFactory.java
├── memory
│   ├── BoundedStateGraph.java
│   └── TreeStore.java
└── telemetry
    └── TelemetrySink.java
```

#### Concrete Java Sketches

```java
// Base Strategy Interface
public interface ApeAgentStrategy {
    ModelAction selectAction(State currentState, GUITree currentTree);
    void onStepComplete(StepResult result);
}

// Stock Untouched Agent
public class StockApeAgent implements ApeAgentStrategy {
    private final ApeSubstrate substrate;

    public StockApeAgent(ApeSubstrate substrate) {
        this.substrate = substrate;
    }

    @Override
    public ModelAction selectAction(State currentState, GUITree currentTree) {
        // Pure upstream SATA selection algorithm
        return selectSataAction(currentState, currentTree);
    }

    @Override
    public void onStepComplete(StepResult result) {
        // Core state model update
    }
}

// Abstract Decorator
public abstract class AbstractAgentDecorator implements ApeAgentStrategy {
    protected final ApeAgentStrategy wrappedAgent;

    public AbstractAgentDecorator(ApeAgentStrategy wrappedAgent) {
        this.wrappedAgent = wrappedAgent;
    }

    @Override
    public ModelAction selectAction(State currentState, GUITree currentTree) {
        return wrappedAgent.selectAction(currentState, currentTree);
    }
}

// MOP Guidance Decorator
public class MopGuidedAgent extends AbstractAgentDecorator {
    private final MopData mopData;
    private final MopScorer scorer;

    public MopGuidedAgent(ApeAgentStrategy wrappedAgent, MopData mopData) {
        super(wrappedAgent);
        this.mopData = mopData;
        this.scorer = new MopScorer(mopData);
    }

    @Override
    public ModelAction selectAction(State currentState, GUITree currentTree) {
        List<ModelAction> candidates = currentState.getActions();
        scorer.boostActions(candidates, currentTree);
        // Delegate back to inner agent or select top boosted action
        return super.selectAction(currentState, currentTree);
    }
}
```

#### Resolution of §2.2 Open Questions
1. **`mop` preset default:** `MopGuidedAgent` decorates `ApervBaseAgent` (which includes UI fixes) or `StockApeAgent` explicitly depending on constructor wrapping.
2. **Widget vs. Frontier MOP:** Implemented as two distinct Decorators: `MopWidgetDecorator` and `MopFrontierDecorator`, composable at will.
3. **LLM Fallback:** Fallback is cleanly defined as calling `super.selectAction(currentState, currentTree)` when LLM yields no action.
4. **Mode concept:** Modes map directly to Java agent decoration chains built by `AgentFactory.createAgent(ModeEnum)`.

#### Driver Satisfaction (D1–D10)
- **D1 (Baseline):** Guarantees 100% `ape_pure` baseline fidelity by executing `StockApeAgent` with zero decorator overhead.
- **D2 (Mode):** First-class `Mode` enum used in `AgentFactory`. `--ape mop` instantiates `new MopGuidedAgent(new StockApeAgent(...))`.
- **D3 (Features):** Features are modular decorator classes.
- **D4 (Spaghetti):** Completely dissolves `if/else` checks in `SataAgent`.
- **D5 (Testing):** Substrate can be mocked via `ApeSubstrate` interface, allowing full strategy unit testing on standard JVM.
- **D6 (Memory):** `BoundedStateGraph` uses LRU eviction for `GUITree` node subtrees beyond 50 active states.
- **D7 (Traceability):** Each Decorator appends its specific metadata to `StepResult` before sending to `TelemetrySink`.
- **D8 (Resilience):** Clean exception handling in decorator chain; if a decorator fails, it logs and falls back to `super.selectAction()`.
- **D9 (Ablation):** Ablation is achieved by programmatically changing the decorator wrapping stack.
- **D10 (Simplicity):** Highly intuitive for Java developers familiar with Decorator/Gang-of-Four design patterns.

#### Weaknesses & Trade-offs
- Deep decoration stacks (e.g., 5+ layers) can increase call-stack depth and make debugging tracebacks slightly harder.

---

### Candidate 3: Event-Driven Reactive Kernel & Asynchronous Decision Engine (ERKE)

#### Organizing Principle
*“Exploration is an Event Loop; Guidance capabilities are Asynchronous Subscribers; Actions are Arbitrated by Priority and Deadline Constraints.”*

ERKE moves away from synchronous blocking step evaluation. The core exploration loop emits state events (`ScreenCapturedEvent`, `StagnationEvent`). Guidance modules (MOP Evaluator, Asynchronous LLM Worker, Coverage Tracker) reactively compute recommendations. An `ActionArbiter` selects the best candidate action within a strict per-step deadline (e.g., 100ms), decoupling LLM HTTP latency from exploration step throughput.

```
                  ┌────────────────────────────────────────┐
                  ▼                                        │
        [ ExplorationKernel ]                              │
   (Emits ScreenCapturedEvent)                             │
                  │                                        │ (Executes Action)
        ┌─────────┴─────────┐                              │
        ▼                   ▼                              │
 [ MOP Subscriber ]  [ Async LLM Worker ]                  │
 (Computes Scores)   (HTTP Call in Background)             │
        │                   │                              │
        └─────────┬─────────┘                              │
                  ▼                                        │
          [ ActionArbiter ] ───────────────────────────────┘
     (Enforces 100ms Deadline)
```

#### Module & Package Structure
```
com.android.commands.monkey.ape
├── kernel
│   ├── ExplorationKernel.java
│   ├── EventBus.java
│   └── ActionArbiter.java
├── events
│   ├── ScreenCapturedEvent.java
│   ├── StagnationEvent.java
│   └── ActionProposedEvent.java
├── capabilities
│   ├── CapabilityModule.java
│   ├── MopCapability.java
│   ├── AsyncLlmCapability.java
│   └── CoverageCapability.java
└── store
    ├── RingBufferLogWriter.java
    └── OffHeapStateIndex.java
```

#### Concrete Java Sketches

```java
// Event Bus & Kernel Abstractions
public interface EventBus {
    <T> void publish(T event);
    <T> void subscribe(Class<T> eventType, Consumer<T> handler);
}

// Async LLM Capability Module
public class AsyncLlmCapability implements CapabilityModule {
    private final LlmClient client;
    private final ActionArbiter arbiter;

    public AsyncLlmCapability(EventBus bus, LlmClient client, ActionArbiter arbiter) {
        this.client = client;
        this.arbiter = arbiter;
        bus.subscribe(ScreenCapturedEvent.class, this::onScreenCaptured);
    }

    private void onScreenCaptured(ScreenCapturedEvent event) {
        if (event.isStagnant() || event.isNewState()) {
            // Trigger asynchronous background HTTP call
            CompletableFuture.supplyAsync(() -> client.query(event.getScreenshot()))
                .thenAccept(llmResponse -> {
                    ModelAction action = parseResponse(llmResponse, event);
                    arbiter.proposeAction(new ProposedAction(action, Priority.HIGH, "LLM_ASYNC"));
                });
        }
    }
}

// Action Arbiter with Hard Deadline
public class ActionArbiter {
    private final PriorityBlockingQueue<ProposedAction> proposalQueue = new PriorityBlockingQueue<>();

    public void proposeAction(ProposedAction action) {
        proposalQueue.offer(action);
    }

    public ModelAction selectActionWithDeadline(long timeoutMs, ModelAction fallbackDefault) {
        try {
            ProposedAction best = proposalQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            return (best != null) ? best.getAction() : fallbackDefault;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackDefault;
        } finally {
            proposalQueue.clear(); // Reset for next step
        }
    }
}
```

#### Resolution of §2.2 Open Questions
1. **`mop` preset default:** MOP capability registers as a synchronous event listener on `ScreenCapturedEvent`, prioritizing MOP-boosted widgets before deadline expiry.
2. **Widget vs. Frontier MOP:** Independent capability modules subscribing to state events.
3. **LLM Fallback:** Completely natural: if `AsyncLlmCapability` does not deliver a proposal before the 100ms arbiter deadline, the arbiter seamlessly falls back to SATA proposal.
4. **Mode concept:** Modes are defined as active Capability subscriptions to the EventBus.

#### Driver Satisfaction (D1–D10)
- **D1 (Baseline):** Baseline mode registers zero extra capability subscribers, running at maximum Dalvik event loop speed.
- **D2 (Mode):** Modes are dynamic Capability Assemblies.
- **D3 (Features):** Features are fully decoupled reactive capability subscribers.
- **D4 (Spaghetti):** Completely eliminates sequential `if/else` and monolithic ordering logic.
- **D5 (Testing):** Highly testable via event injection into `EventBus` without UI dependencies.
- **D6 (Memory & Throughput):** Solves the LLM throughput penalty (§3.11) by making LLM calls non-blocking. Exploration continues while LLM processes.
- **D7 (Traceability):** Structured event tracing automatically writes every published event to an async ring-buffer JSON log file.
- **D8 (Resilience):** Capability failures do not block the event loop; timeout deadline guarantees step execution.
- **D9 (Ablation):** Unsubscribing specific capability modules allows instant feature ablation.
- **D10 (Simplicity):** Higher initial conceptual complexity (async event bus), but eliminates throughput bottlenecks.

#### Weaknesses & Trade-offs
- Concurrency management in Android Dalvik environment requires careful thread handling to avoid race conditions.

---

## Part D — Comparative Evaluation & Recommendation

### Comparative Scoring Matrix (Drivers D1–D10)
Weights reflect thesis priorities: **Internal Validity (D1, D9: 25%)**, **Maintainability & Modularity (D2, D3, D4, D5: 35%)**, **Performance & Observability (D6, D7: 25%)**, **Resilience & Simplicity (D8, D10: 15%)**.

| Driver | Weight | Current Architecture | Candidate 1: DPMA | Candidate 2: SGDA | Candidate 3: ERKE |
|---|---|---|---|---|---|
| **D1: Baseline Fidelity** | 12.5% | 6 / 10 | 9 / 10 | **10 / 10** | 8 / 10 |
| **D2: Mode First-Class** | 10.0% | 2 / 10 | **10 / 10** | 9 / 10 | 8 / 10 |
| **D3: Feature Management** | 10.0% | 3 / 10 | **10 / 10** | 9 / 10 | 8 / 10 |
| **D4: Kill Spaghetti** | 8.5% | 1 / 10 | 9 / 10 | **10 / 10** | 9 / 10 |
| **D5: Testability** | 6.5% | 3 / 10 | 9 / 10 | **10 / 10** | 8 / 10 |
| **D6: Memory & Throughput** | 12.5% | 3 / 10 | 7 / 10 | 8 / 10 | **10 / 10** |
| **D7: Traceability** | 12.5% | 4 / 10 | 9 / 10 | 9 / 10 | **10 / 10** |
| **D8: Resilience** | 7.5% | 6 / 10 | 8 / 10 | 8 / 10 | **10 / 10** |
| **D9: Ablation Support** | 12.5% | 4 / 10 | **10 / 10** | 9 / 10 | 8 / 10 |
| **D10: Simplicity** | 7.5% | 4 / 10 | **9 / 10** | 8 / 10 | 5 / 10 |
| **Weighted Total** | **100%** | **3.60 / 10** | **9.12 / 10** | **9.08 / 10** | **8.42 / 10** |

---

### Architectural Recommendation

We recommend a **Hybrid Staged Synthesis: Candidate 2 (SGDA) core with Candidate 1 (DPMA) Feature Manifests**.

#### Rationale
1. **Candidate 2 (SGDA)** provides the cleanest scientific guarantee for **Driver D1 (Baseline Fidelity)** by isolating `StockApeAgent` as an unencumbered base strategy, ensuring that `ape_pure` remains scientifically defensible for the PhD thesis.
2. **Candidate 1 (DPMA)** provides the best declarative structure for **Driver D2/D9 (Mode & Ablation)** via JSON/YAML feature preset manifests, eliminating multi-repository configuration drift with `rv-android`.
3. **Async LLM Integration (from Candidate 3):** To resolve the critical §3.11 throughput bottleneck (where LLM calls penalize exploration steps by ~33%), the `LlmGuidedAgent` decorator from Candidate 2 should internally adopt Candidate 3's non-blocking `CompletableFuture` execution pattern.

---

### Migration & Implementation Roadmap

```
Phase 1: Substrate Isolation & Baseline Lockout (D1, D5)
├── Extract ApeSubstrate interface from AndroidDevice and MonkeySourceApe.
└── Implement StockApeAgent and verify byte-for-byte behavioral parity with upstream APE.

Phase 2: Decorator Core & Config Elimination (D2, D3, D4)
├── Replace static Config.java reads with immutable FeatureConfig context.
├── Convert MOP, Form Completion, and Scorer passes into Layered Agent Decorators.
└── Introduce PresetRegistry for explicit preset loading (ape, aperv, mop, llm, llm_mop).

Phase 3: Bounded Memory & JSON Telemetry (D6, D7)
├── Replace actionHistory in Model.java with bounded indexed ring buffer.
├── Implement StructuredLogger emitting escaping-safe JSON-lines.
└── Inject step correlation IDs into telemetry stream.

Phase 4: Non-Blocking LLM & Resilience (D6, D8, D11)
├── Refactor LlmRouter to perform non-blocking async inference calls.
└── Implement periodic state-graph checkpointing to disk for crash recovery.
```

---

## Part E — Risks, Unknowns, and Next Steps

### E1. Critical Measurements Needed
1. **Heap High-Water Mark Profiling:** Measure memory retention over 600-second continuous runs on Android emulator using `jcmd` / `dumpsys meminfo` to validate the proposed `BoundedStateGraph` eviction threshold.
2. **Async LLM Latency & Overlap:** Benchmark on-device step throughput when executing Qwen3-VL queries asynchronously versus synchronously.

### E2. Key Decisions & Owner Directives
1. **Baseline Invariants (DECIDED):** The project owner confirmed that **original APE (`8f51b99`) must be used strictly as the baseline**. For `ape_pure`, all ungated substrate modifications (such as seed source, binarySearch modifications in naming, and enum ordinal mutations) must be strictly reverted or bypassed so that the control arm produces byte-for-byte and behavioral equivalence with upstream APE.
2. **Telemetry Transport:** Should telemetry switch from stdout (`logcat`) to a dedicated on-device log file (`/sdcard/aperv_trace.jsonl`) to completely eliminate logcat buffer truncation risks?
3. **LLM Fallback Scope:** When the LLM fails or circuit-breaks, should the fallback be parameterizable as a base strategy layer (`llm` on top of `ape`, `aperv`, or `mop`)?

---

