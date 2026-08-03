# Architecture Study: Re-Architecting APE-RV

**Model:** opencode/mimo-v2-5-free  
**Date:** 2026-08-01  
**Commit analysed:** `5dcf225976b26ce78d8b31dd88d7f858dad29d43`  
**Method:** Subagent-based parallel exploration covering (a) upstream-vs-fork entanglement, (b) control-flow archaeology in hotspot files, (c) memory ownership and object lifetime, (d) observability and provenance, (e) resilience and process lifecycle, (f) prior art scan, (g) research context. Each area was explored by a dedicated subagent; findings were cross-checked against §3 of the prompt document and against the codebase directly.

---

## Part A — Diagnosis

### A1. What APE-RV Is Today, Structurally

APE-RV is a fork of APE (ETH Zurich, ICSE 2019) running on-device as a Dalvik process via `app_process`. The codebase is 173 Java files / ~36,414 LOC in `src/main/java`, with 74 test files / 785 `@Test`.

**The real layering** is a four-layer cake, but not the one the code aspires to:

```
Layer 4: Python arm system (rv-android aperv-tool)
         — 26 arms, 52 key mappings, 18 arm-defining keys
         — THE de facto mode system, in a different language/repo

Layer 3: Agent hierarchy (ApeAgent → StatefulAgent → SataAgent)
         — 4,131 LOC across three classes
         — owns model, state machine, scoring pipeline, telemetry, LLM router

Layer 2: Event bridge (MonkeySourceApe → Monkey)
         — 1,467 LOC + 1,500 LOC
         — owns screen capture, event injection, crash handling, guards

Layer 1: Android runtime (AndroidDevice, ApeAPIAdapter)
         — static handles, reflection-based version compat
         — the only layer that touches real Android APIs
```

The actual control flow crosses these layers in a single per-step path:

```
Monkey.runMonkeyCycles()
  → MonkeySourceApe.generateEvents()          [Layer 2]
    → ApeAgent.updateStateWrapper()            [Layer 3]
      → StatefulAgent.updateStateInternal()    [Layer 3]
        → SataAgent.selectNewActionNonnull()   [Layer 3] — THE EPICENTER
          → LlmRouter.selectAction()           [Layer 3]
          → ScoringPipeline.apply()            [Layer 3]
      → StatefulAgent.resolveNewAction()       [Layer 3]
    → MonkeySourceApe.generateEventsForAction() [Layer 2]
      → AndroidDevice.injectEvent()            [Layer 1]
```

Every layer reads `Config` (112 `public static` fields, loaded once at class-load from system properties and two property files). There are ~200 read-sites concentrated in five files: `StatefulAgent` (54), `SataAgent` (30), `GUITreeBuilder` (26), `LlmRouter` (25), `MonkeySourceApe` (24). The Config is frozen at class-load; `Config.set()` only has effect before the first read of any field (`Config.java:414`). There is no schema, no unknown-key detection — a typo in `ape.properties` is silently swallowed (`Config.java:453-454`).

**The inheritance chain:**

```
ApeAgent (abstract, 465 LOC)
  — owns step loop, input generation, restart logic, crash handling
  └── StatefulAgent (abstract, 1,904 LOC) implements GraphListener
        — owns model, state machine, scoring pipeline, telemetry, LLM router
        ├── SataAgent (1,762 LOC)  — SATA heuristic, epsilon-greedy
        └── RandomAgent            — priority-weighted random baseline
```

`SataAgent.selectNewActionNonnull` (`SataAgent.java:449-589`, 141 LOC, nesting depth 5) is the decision epicenter. It is a linear cascade of 12 guarded blocks, each with its own `return`, their precedence encoded **solely by textual order** and documented in 5–8-line comments. The four independent subsystems (LLM, MOP launcher, component trigger, SATA chain) are interleaved in this single method with no abstraction boundary between them.

### A2. The Mode/Feature Control Surface

Today, "mode" lives in three disconnected places:

1. **The `--ape` CLI flag** (`ApeAgent.createAgent:68-95`): dispatches on a raw `String` with five `if`s. Unknown values silently fall through to `SataAgent`. Usage text advertises 2 agent types; the factory accepts 3. `System.exit(1)` on unrecognized value.

2. **The 117 Config flags** (`Config.java`): frozen at class-load, no manifest, no unknown-key detection. Five fields were demoted from `final` purely for testability (`Config.java:149,151,153,165,245`).

3. **The Python arm system** (`tool.py:427-659`): 26 hand-written arm dictionaries composed from 6 layer-spread blocks (`_BASELINE_ARM_FLAGS`, `_APE_PURE_ARM_FLAGS`, `_MOP_SUBSTRATE`, `_LLM_FLAGS`, `_FRONTIER_SUBSTRATE`, `_CAL_LLM_COMMON`). Guarded by 83 `test_` functions that validate a Python constant against itself (INV-APV-14).

**The historical reason this is where it is:** this is a fork that grew by accretion under experimental deadlines. Each new feature (MOP, LLM, form completion, activity trigger) was added as a Config flag and an `if` block in the hotspot methods. The 2026-07-08 architecture (`docs/20260708_arquitetura_separacao_aperv.md`) addressed scoring composition (the `ScoringPass` pipeline) and the `apePureMode` kill-switch, but explicitly deferred mode management, routing, perception, and input generation. The LLM integration (Phase 5) landed on top of this structure and absorbed new behaviour into the two worst methods.

**The split-brain:** Python owns *which named combinations exist* (26 arms), the key-name translation (52 pairs), value serialisation, the deploy contract, and the hard timeout. Java owns *what each flag means*, its default, its clamps, and the `apePureMode` forcing list. Neither side is the single source of truth, and the seam has no contract. A rename in `Config.java` produces a silent misconfiguration in every arm. A stale committed jar once made the MOP boost fire in 0 of 147,153 evaluations (gh71).

### A3. Seven Architectural Tensions

**T1. Baseline fidelity vs. observability.** The pure baseline (`apePureMode=true`) emits zero `[APE-STEP]` and zero `[APE-OUTCOME]` lines by construction (`Config.java:346`, INV-ARCH-01). This means the control arm is the least observable arm — you cannot reconstruct its action sequence from its log. The parity argument for this is sound (the baseline should behave like upstream APE, which has no telemetry). The observability argument is equally sound (you need to debug the baseline). These two requirements contradict each other in the current design.

**Evidence:** `Config.java:287` forces `stepTelemetryEnabled=false`; `StatefulAgent.java:1491` gates `[APE-STEP]` on this flag.

**T2. Feature composition vs. silent defaults.** `activityTriggerEnabled=true` and `frontierBoostWeight=200` are on by default but are no-ops without MopData. A "plain aperv" arm that merely omits `mopDataPath` neutralises them by accident, not by construction. The fair-test obligation is enforced by a comment (`Config.java:239,164`), not by the architecture. Similarly, `fuzzInputTyped=true` requires MopData to do typed input; without it, it degrades silently to heuristic input.

**Evidence:** `Config.java:239` comment; `ApeAgent.java:235-252` degradation path.

**T3. Decision precedence vs. testability.** The precedence of LLM-before-launcher-before-SATA is encoded as statement order in one 141-line method. Changing the order requires editing `SataAgent.selectNewActionNonnull`. Testing a different precedence requires duplicating the method. The `telemetry-proof-llm-efficacy` change added the stagnation state to a *third* class (`StatefulAgent.java:128`, re-armed at `:1436`, burned at `SataAgent.java:499`) — the decision is now split across three classes.

**Evidence:** `SataAgent.java:449-589`; stagnation state at `StatefulAgent.java:128,1436` and `SataAgent.java:499`.

**T4. Memory retention vs. run length.** Three independent unbounded retention vectors grow throughout the run: `Model.actionHistory` (one `ActionRecord` per step, retaining `GUITreeAction → GUITree → GUITreeNode`), `Graph.treeTransitionHistory` (one `GUITreeTransition` per transition, retaining source + target GUITrees), and `GUITreeBuilder.namingToGUITreeNodeCache` (never cleaned by `release()`). The `maxStatesPerActivity` and `maxGUITreesPerState` thresholds gate refinement, not eviction — no GUITree is ever freed during a run.

**Evidence:** `Model.java:136-137` ("TODO: may be the cause of OOM"); `Graph.java:118`; `GUITreeBuilder.java:679,693-701`.

**T5. Telemetry as stdout vs. traceability as thesis requirement.** All observability goes through `System.out.format` with no timestamps (except `[APE-STEP].clock`), no run ID, no file sink, no escaping. Traces carry NUL bytes (requiring binary reads with `errors="replace"`). 5 of 880 tasks in the calibration campaign lost their `LLM Summary` to teardown truncation. The thesis needs a key tying RVSEC violation events in `.logcat` back to APE steps in `.trace` — this key does not exist.

**Evidence:** `Logger.java:22` (compile-time debug flag); `[APE-STEP]` at `StatefulAgent.java:1396-1419`; no run ID in any channel.

**T6. Crash recovery vs. sample integrity.** The Python layer already provides crash-safe resume at task granularity and treats timeout as the only exit. But the jar does not checkpoint during the run, and a SIGKILL loses the model entirely. More subtly: a run that dies at second 120 of 600 is recorded as COMPLETED by the Python layer (`tool.py:1121-1125`, debug-logged only), lost as a sample while counted as a success.

**Evidence:** `tool.py:1121-1125`; `Graph.java:1166-1174` (type mismatch between `readGraph`/`saveGraph`); no `catch(OutOfMemoryError)` in the exploration loop.

**T7. Arm definition in Python vs. jar.** The Python arm dictionaries and the Java Config registry disagree on the kill-switch keys (`_APE_PURE_ARM_FLAGS` has 18 keys; `Config.rvForcedOffValues()` has 26). There is no drift test between them. Dead keys survive as archaeology (`mopWeightActivity` removed from `Config.java` but still mapped in `tool.py:91-95`). New Config flags are invisible to Python's INV-APV-14 guards.

**Evidence:** `tool.py:264-283` vs `Config.java:343-364`; `tool.py:91-95` (dead key).

**Cross-cutting observation:** T1 and T5 are the same problem seen from two angles — the telemetry system is torn between parity (baseline must be quiet) and traceability (all arms must be observable). T2 and T7 are the same problem — feature defaults and arm definitions are not derived from a single source. T3 and T6 share a root cause — behaviour that should be structural (precedence, persistence) is encoded imperatively in methods that have no place to put it.

### A4. What the Current Design Gets Right

1. **Teardown isolation invariants** (`INV-EXPL-16`, `INV-EXPL-29`). Each teardown step is wrapped in `safeStep(label, Runnable)` catching `Throwable`. Nothing in the `finally` may throw. This is the best-engineered part of the system and must be preserved.

2. **Fail-fast on MOP load** (`INV-MOP-22`). If `mopDataPath` is set but `MopData.load` fails, the run aborts via `StopTestingException`. This prevents an arm from silently running as "no MOP" and contaminating the experiment.

3. **The step/clock join design.** `[APE-STEP].step` joins with `[APE-OUTCOME].step` and `[APE-LLM-TEL].step`. The wall-clock stamp (`clock=epochMs`) enables offline temporal joins with logcat. This design made the calibration report (`20260724_relatorio_calibracao_aperv.md`) possible — 39,341 LLM call records reconstructed from raw traces. Any redesign must preserve this analysis capability.

4. **The ScoringPipeline no-op-when-disabled contract.** A disabled pass is a strict no-op. The pipeline is assembled once and applied at a single injection point (`StatefulAgent.adjustActionsByGUITree:1523`). This is the one clean abstraction boundary in the system.

5. **The arm-explicitness pytest guards** (`INV-APV-14`). Every non-exempt arm must set every arm-defining key. This is enforced by reflection-based tests. The property is valuable even if the mechanism (Python constant validated against itself) is flawed.

6. **Continuous-mode crash resilience.** `Monkey.java:1367-1375` clears `mAbort` in timed mode, so app crashes never terminate a timed run. Combined with the `IActivityController` layer, this gives reliable crash containment.

7. **The counterfactual mechanism** (`MopCounterfactual.java`). A zero-RNG-draw replay of the roulette with MOP weights zeroed, enabling per-step attribution of MOP guidance. This is a genuinely novel telemetry capability.

---

## Part B — Design Space

Before proposing candidates, let me map the major design axes and their trade-offs.

### B1. Where Mode Lives

| Option | Pros | Cons |
|--------|------|------|
| **In the jar** (feature registry) | Single source of truth; compile-time validation; drift impossible | Jar becomes larger; Python must query jar for valid combinations |
| **In Python** (arm dicts) | Already works; easy to iterate; no jar rebuild for arm changes | Split-brain continues; silent drift; dead keys accumulate |
| **Shared artifact** (JSON/YAML manifest) | Both sides read same file; drift detectable | Cross-language contract; format versioning; deployment coupling |
| **No modes at all** (capability-based) | Maximum flexibility; arms emerge from capability combinations | Hard to reason about; no named presets; experiment design harder |

### B2. How Features Are Represented

| Option | Pros | Cons |
|--------|------|------|
| **Boolean flags** (today) | Simple; debuggable | No dependencies; no conflicts; no type safety |
| **Feature objects** (registry with deps/conflicts) | Composable; discoverable; testable | More concepts; registry maintenance |
| **Capability declarations** (component advertises what it does) | Loose coupling; emergent composition | Hard to reason about; no explicit arm definition |
| **Data-driven specs** (JSON/YAML arm definitions) | Versionable; diffable; cross-language | Parser complexity; runtime validation |

### B3. How Decisions Are Dispatched

| Option | Pros | Cons |
|--------|------|------|
| **Textual if-chain** (today) | Explicit; all in one place | 141 LOC; untestable; precedence implicit |
| **Chain of responsibility** (priority-ordered handlers) | Each handler testable in isolation; order explicit | Handler boundaries must be designed; overhead |
| **Decision pipeline** (filter → score → select) | Composable; parallel scoring | More complex; harder to debug precedence |
| **Data-driven dispatch** (table of conditions → actions) | Trivially modifiable; auditable | Less expressive; hard for complex logic |

### B4. How State Is Owned and Bounded

| Option | Pros | Cons |
|--------|------|------|
| **Unbounded in-memory** (today) | Simple; fast | OOM on long runs; no resume |
| **Bounded LRU** (like UICoverageTracker) | Predictable footprint | Eviction loses information; LRU policy tuning |
| **Append-only log + in-memory index** | Bounded; resumable; auditable | More complex; disk I/O on device |
| **Hybrid** (hot in memory, cold on disk) | Best of both | Implementation complexity |

### B5. How Observability Is Produced

| Option | Pros | Cons |
|--------|------|------|
| **stdout key=value** (today) | Simple; parsed by harness | Lossy; no escaping; no timestamps; no run ID |
| **Structured JSON lines** (one JSON object per event) | Parseable; extensible; schema-evolvable | Larger; slower to emit; harness must change |
| **Binary event log** (compact; indexed) | Smallest; fastest | Opaque; requires custom parser; harder to debug |
| **Dual sink** (stdout for harness + file for analysis) | Best of both; survives adb drop | More code; file management on device |

### B6. Where the Experiment Matrix Is Defined

| Option | Pros | Cons |
|--------|------|------|
| **Python arm dicts** (today) | Already works; pytest guards | Split-brain; no jar awareness |
| **Jar-emitted manifest** (jar declares valid arms) | Single source; drift-proof | Jar must be queried; Python becomes a consumer |
| **Generated from feature registry** (feature matrix → arms) | Trivially scalable; ablation by construction | Requires feature registry first; combinatorial explosion |
| **Shared manifest** (JSON read by both) | Cross-language; versionable | Contract maintenance; format evolution |

---

## Part C — Candidate Architectures

### Candidate 1: Feature Surface

**Organising principle:** Features are the atoms; modes are named sets of features; the engine queries a feature context at every decision point. The feature context is the single source of truth for what is enabled.

**What it deletes:** The 117 raw `Config` fields (replaced by typed feature objects); the `apePureMode` kill-switch (replaced by "no features enabled"); the 26 Python arm dictionaries (replaced by named presets over the feature set); the `ScoringPipeline` as a standalone concept (folded into the feature registry).

#### Module Structure

```
ape/
  feature/
    Feature.java              — interface: name(), dependencies(), conflicts(), isEnabled()
    FeatureContext.java        — holds the active Feature set; queried by all decision points
    FeatureRegistry.java       — static registry of all Feature implementations
    FeatureSpec.java           — data class: name, deps, conflicts, configBindings
    features/
      MopScoring.java         — F9/F10/F11/F13/F14: MOP widget + frontier + WTG scoring
      LlmSteering.java        — F17/F18/F19: LLM routing, circuit breaker, coord repair
      ActivityTrigger.java     — F15: activity-trigger launcher + component triggering
      FormCompletion.java      — F6b: form completion scoring + input generation
      CoverageBoost.java       — F3: UI-coverage boost
      FrontierBoost.java       — F12: frontier boost (MOP-coupled)
      ActivityBudget.java      — F4: activity budget gate
      ModelMenu.java           — F2: MODEL_MENU action
      TreeEnhancements.java    — F1: AndroidX ViewPager/WebView prune
      Telemetry.java           — F8: structured telemetry emission
      ...
  agent/
    ActionProvider.java        — interface: canHandle(context), provide(context): Action
    ActionChain.java           — ordered list of ActionProviders; first non-null wins
    providers/
      LlmOverlayProvider.java  — wraps LlmRouter; checks LlmSteering feature
      MopLauncherProvider.java — wraps MOP launcher; checks MopScoring + ActivityTrigger
      SataProvider.java        — the SATA heuristic chain; always enabled
  model/
    Model.java                 — unchanged
    State.java                 — unchanged
    Graph.java                 — bounded: treeTransitionHistory gets LRU eviction
```

#### FeatureContext: The Core Abstraction

```java
public class FeatureContext {
    private final Map<Class<? extends Feature>, Feature> active;
    
    public <T extends Feature> boolean isEnabled(Class<T> type) {
        Feature f = active.get(type);
        return f != null && f.isEnabled();
    }
    
    public <T extends Feature> T get(Class<T> type) {
        return type.cast(active.get(type));
    }
    
    public static FeatureContext fromPreset(String presetName) {
        return FeatureRegistry.getPreset(presetName);
    }
    
    public static FeatureContext empty() {
        // The "ape_pure" baseline: no features enabled
        return new FeatureContext(Collections.emptyMap());
    }
}
```

#### How Modes Are Expressed

"Mode" becomes a named preset — a `Map<Class<? extends Feature>, Boolean>`:

```java
// In FeatureRegistry.java
public static final Map<String, Map<Class<?>, Boolean>> PRESETS = Map.of(
    "ape",       Map.of(),                                          // no features = upstream APE
    "aperv",     Map.of(                                           // aperv exploration features only
        TreeEnhancements.class, true,
        ModelMenu.class, true,
        ActivityBudget.class, true,
        Telemetry.class, true
    ),
    "mop",       Map.of(                                           // aperv + MOP scoring
        TreeEnhancements.class, true,
        ModelMenu.class, true,
        MopScoring.class, true,
        ActivityTrigger.class, true,
        ActivityBudget.class, true,
        Telemetry.class, true
    ),
    "llm",       Map.of(                                           // aperv + LLM steering
        TreeEnhancements.class, true,
        ModelMenu.class, true,
        LlmSteering.class, true,
        ActivityBudget.class, true,
        Telemetry.class, true
    ),
    "llm_mop",   Map.of(                                           // everything
        TreeEnhancements.class, true,
        ModelMenu.class, true,
        MopScoring.class, true,
        LlmSteering.class, true,
        ActivityTrigger.class, true,
        ActivityBudget.class, true,
        Telemetry.class, true
    )
);
```

The `--ape` CLI flag selects a preset. Additional flags can override individual features: `--ape mop --no-activity-trigger` removes ActivityTrigger from the mop preset.

#### Answers to §2.2 Open Questions (in this candidate's terms)

- **Does `mop` include `aperv` exploration features?** Yes, by construction: the `mop` preset includes `TreeEnhancements`, `ModelMenu`, etc. The alternative (`ape + MOP only`) would be a different preset (`mop_minimal`). The feature registry makes both expressible; the experiment design chooses which to run.
- **Widget-level vs. frontier-level MOP guidance?** These are separate features (`MopWidgetScoring` vs. `FrontierBoost` vs. `MopFrontierBoost`). The `mop` preset enables all three; an ablation preset could enable only one. Each is independently toggleable.
- **LLM fallback mode?** The `LlmSteering` feature has a `fallbackBase` property: `"ape"` (fallback to upstream SATA), `"aperv"` (fallback to aperv SATA), or `"mop"` (fallback to MOP-scored SATA). This makes the fallback a feature parameter, not a separate mode.
- **Is "mode" the right primitive?** In this candidate, modes are convenience presets. The real primitive is the feature set. Anyone can define a new preset; the feature registry validates dependencies and conflicts.

#### How Each Driver Is Satisfied

| Driver | How |
|--------|-----|
| **D1 (Baseline fidelity)** | `FeatureContext.empty()` is the pure baseline. No features means no fork behaviour. The always-on bug fixes (§3.1) are accepted as "APE + non-behavioural fixes" — documented, defensible, and testable via `ApePureModeAlwaysOnExceptionsTest`. |
| **D2 (Mode as first-class)** | Modes are named presets in `FeatureRegistry`. They are declarative, discoverable (`PRESETS.keySet()`), loggable (`[APE-ARCH] features=[...]`), diffable (preset comparison), and testable (each preset is a unit test). |
| **D3 (Feature management)** | Features declare `dependencies()` and `conflicts()`. `FeatureContext.fromPreset()` validates before activation. Invalid combinations fail at startup with a clear error. |
| **D4 (Killing spaghetti)** | `selectNewActionNonnull` is replaced by `ActionChain` — an ordered list of `ActionProvider` instances. Each provider checks `featureContext.isEnabled(MyFeature.class)` before acting. The chain is assembled once at startup; order is explicit in code. |
| **D5 (Testability)** | Each `Feature` is a unit testable object. `FeatureContext` can be constructed in tests with a subset of features. `ActionProvider` implementations are testable against a mock `FeatureContext`. The 5 `Config` fields demoted from `final` can be restored — feature toggling replaces runtime Config mutation. |
| **D6 (Memory)** | `Graph.treeTransitionHistory` gets LRU eviction (like `UICoverageTracker`). `Model.actionHistory` is converted to an append-only file with an in-memory index of the last N entries. `GUITreeBuilder.namingToGUITreeNodeCache` gets a `release(GUITree)` call. Feature: bounded memory with configurable limits. |
| **D7 (Traceability)** | `Telemetry` feature emits structured JSON lines (one JSON object per event). Each line includes `run_id`, `step`, `clock`, `decision_source`, `feature_context` (which features were active). The pure baseline emits telemetry too (the feature is always on in aperv+ presets; in the `ape` preset, it emits a minimal startup/shutdown manifest only). |
| **D8 (Resilience)** | Periodic checkpointing (every N steps) writes a lightweight `checkpoint.bin` (graph snapshot + step counter). On restart, the checkpoint is loaded if present. The Python layer detects truncated runs via a `run_complete` flag in the trace. |
| **D9 (Feature impact)** | The feature registry can generate an arm matrix: for N features, enumerate 2^N combinations (or a fractional factorial). Each arm is a `FeatureContext` with a specific subset. The trace includes `feature_context` for post-hoc analysis. |
| **D10 (Simplicity)** | New concepts: `Feature`, `FeatureContext`, `FeatureRegistry`, `ActionProvider`, `ActionChain`. LOC delta: ~+800 (feature classes + chain), ~-400 (simplified Config, removed if-chains). Net: ~+400. A newcomer must understand: features, presets, and the chain. |

#### Migration Path

1. **Stage 1 (weeks 1-2):** Define `Feature` interface and `FeatureContext`. Port 3 features as proof of concept (MopScoring, LlmSteering, Telemetry). Wire `FeatureContext` into `selectNewActionNonnull` alongside existing Config reads. Both paths coexist.
2. **Stage 2 (weeks 3-4):** Port remaining features. Replace `selectNewActionNonnull` with `ActionChain`. Remove `apePureMode` kill-switch (replaced by empty preset).
3. **Stage 3 (weeks 5-6):** Bounded memory (LRU on `treeTransitionHistory`, action history to file). Structured telemetry. Checkpointing.
4. **Stage 4 (week 7):** Python layer: replace arm dicts with preset names + feature overrides. Add drift detection (jar emits feature manifest; Python validates against it).

Cross-repo impact: Python `tool.py` changes in stage 4. The property surface changes (features replace raw flags). The pytest guards need updating.

#### Honest Weaknesses

- **Adds indirection.** Every Config read becomes `featureContext.isEnabled(FeatureX.class)` + `featureContext.get(FeatureX.class).someProperty()`. This is more code per read site, even if it's more principled.
- **Feature granularity is a design decision that must be made upfront.** If features are too coarse (one feature = "MOP"), ablation is weak. If too fine-grained (one feature = "each scoring weight"), the registry explodes. The right granularity is not obvious and will require iteration.
- **The ActionChain introduces a new failure mode:** a provider that silently swallows exceptions could break the chain. Each provider must be audited for exception safety.
- **The shared-manifest approach (stage 4) requires cross-repo coordination.** The manifest format must be versioned; both Java and Python parsers must agree. This is a new deployment coupling.
- **Checkpointing adds per-step I/O cost.** On a device with flash storage, writing a graph snapshot every 50 steps might cost 10-50 ms per write. At 0.037-0.052 pp of MOP coverage per step (§3.11), this is a measurable experimental variable.

---

### Candidate 2: Component Chain

**Organising principle:** The agent is decomposed into independent components, each owning one responsibility. Components communicate through a typed event bus. The decision pipeline is a chain of components, each with a priority and a canHandle/handle contract.

**What it deletes:** The monolithic `selectNewActionNonnull` (141 LOC → 12 independent ~20 LOC components); the scattered Config reads (centralised in component constructors); the `ScoringPipeline` as a separate concept (folded into the scoring components); the `LlmRouter` as a god object (split into `LlmScreenshot`, `LlmCaller`, `LlmCoordinateMapper`, `LlmDeadPairTracker`).

#### Module Structure

```
ape/
  engine/
    ExplorerEngine.java        — owns the step loop; replaces MonkeySourceApe + ApeAgent + StatefulAgent
    StepContext.java            — immutable data class: state, action, featureFlags, telemetry
    EventBus.java               — typed publish/subscribe; replaces direct method calls
    ComponentChain.java         — ordered list of DecisionComponents; first non-null action wins
  components/
    DecisionComponent.java      — interface: priority(), canHandle(StepContext), handle(StepContext): Action
    ActivityBudgetGate.java     — priority 0: budget exhaustion check
    LlmOverlay.java             — priority 10: LLM routing (new-state, stagnation, random)
    MopLauncher.java            — priority 20: activity-trigger launcher
    ComponentTrigger.java       — priority 30: broadcast/service/provider triggering
    SataChain.java              — priority 40: the full SATA heuristic chain (buffer, back, early-stage, epsilon-greedy)
    FallbackAction.java         — priority 100: random valid action or BadStateException
  scoring/
    ScoringComponent.java       — interface: score(StepContext, List<Action>): List<Action>
    MopWidgetScorer.java        — widget-level MOP scoring
    FrontierScorer.java         — frontier + MOP-frontier scoring
    CoverageScorer.java         — UI-coverage scoring
    ...
  telemetry/
    TelemetryEmitter.java       — structured JSON lines; replaces Logger + all [APE-*] emission
    RunId.java                  — unique run identifier; emitted in every line
    StepRecord.java             — data class for [APE-STEP] equivalent
    OutcomeRecord.java          — data class for [APE-OUTCOME] equivalent
  llm/
    LlmCaller.java              — HTTP client; replaces SglangClient
    LlmScreenshot.java          — screenshot capture + image processing
    LlmCoordinateMapper.java    — coordinate normalization, boundary reject, snap, off-tree tap
    LlmDeadPairTracker.java     — dead-pair ban state
    LlmCircuitBreaker.java      — unchanged
```

#### The ComponentChain: Replacing selectNewActionNonnull

```java
public class ComponentChain {
    private final List<DecisionComponent> components; // sorted by priority()
    
    public Action select(StepContext ctx) {
        for (DecisionComponent c : components) {
            if (c.canHandle(ctx)) {
                Action a = c.handle(ctx);
                if (a != null) {
                    ctx.telemetry().recordDecisionSource(c.name());
                    return a;
                }
            }
        }
        throw new BadStateException("No component handled the step");
    }
}
```

Assembly at startup:

```java
ComponentChain chain = new ComponentChain(
    new ActivityBudgetGate(budgetTracker),
    new LlmOverlay(llmCaller, llmCoordMapper, deadPairTracker, circuitBreaker),
    new MopLauncher(mopData, activityTrigger),
    new ComponentTrigger(broadcastCatalog),
    new SataChain(model, scoringComponents),
    new FallbackAction()
);
```

Each component is independently testable:

```java
@Test
void llmOverlaySkipsWhenCircuitBreakerOpen() {
    var ctx = StepContext.builder()
        .featureFlags(FeatureFlags.of(LlmSteering.class))
        .circuitBreakerState(CircuitBreaker.State.OPEN)
        .build();
    var overlay = new LlmOverlay(mockCaller, mockMapper, mockTracker, mockBreaker);
    assertFalse(overlay.canHandle(ctx));
}
```

#### The EventBus: Decoupling Components

```java
public class EventBus {
    private final Map<Class<?>, List<Consumer<?>>> subscribers = new HashMap<>();
    
    public <T> void subscribe(Class<T> type, Consumer<T> handler) { ... }
    public <T> void publish(T event) { ... }
}
```

Events:
- `StepStarted` — emitted at the beginning of each step
- `ActionSelected` — emitted when a component selects an action
- `StepCompleted` — emitted after action execution and graph update
- `StateDiscovered` — emitted when a new state is found
- `CrashDetected` — emitted on app crash
- `RestartRequested` — emitted when stagnation triggers a restart

This replaces the direct method calls between `StatefulAgent`, `SataAgent`, `LlmRouter`, and `MonkeySourceApe`. Components subscribe to events they care about and react independently.

#### How Modes Are Expressed

Modes are **configuration profiles** for the `ComponentChain`:

```java
public class ChainProfiles {
    public static ComponentChain ape(MonkeySourceApe ape, Graph graph) {
        return new ComponentChain(
            new SataChain(model, List.of()),  // no scoring components
            new FallbackAction()
        );
    }
    
    public static ComponentChain mop(MonkeySourceApe ape, Graph graph, MopData mop) {
        return new ComponentChain(
            new ActivityBudgetGate(budget),
            new MopLauncher(mop, activityTrigger),
            new SataChain(model, List.of(
                new MopWidgetScorer(mop),
                new FrontierScorer(mop),
                new CoverageScorer(coverage)
            )),
            new FallbackAction()
        );
    }
    
    public static ComponentChain llm_mop(MonkeySourceApe ape, Graph graph, MopData mop, LlmCaller llm) {
        return new ComponentChain(
            new ActivityBudgetGate(budget),
            new LlmOverlay(llm, coordMapper, deadPair, breaker),
            new MopLauncher(mop, activityTrigger),
            new SataChain(model, List.of(
                new MopWidgetScorer(mop),
                new FrontierScorer(mop),
                new CoverageScorer(coverage)
            )),
            new FallbackAction()
        );
    }
}
```

The `--ape` flag selects a profile. The profile constructs the chain. Different profiles have different components and different scoring mixes.

#### Answers to §2.2 Open Questions (in this candidate's terms)

- **Does `mop` include `aperv` features?** Yes: the `mop` profile includes `ActivityBudgetGate` and `MopLauncher`. The alternative is a `mop_minimal` profile with only `MopWidgetScorer` in the chain.
- **Widget-level vs. frontier-level MOP?** These are separate `ScoringComponent` instances. The profile decides which to include. An ablation arm includes only one.
- **LLM fallback?** The `LlmOverlay` component has a `fallbackProvider` — when the LLM declines/fails, it delegates to the next component in the chain (typically `SataChain`). The fallback is structural (the chain order), not a configuration parameter.
- **Is "mode" the right primitive?** In this candidate, modes are chain profiles — named ways to assemble the chain. The real primitive is the set of available components and their ordering.

#### How Each Driver Is Satisfied

| Driver | How |
|--------|-----|
| **D1** | The `ape` profile includes only `SataChain` + `FallbackAction` — no MOP, no LLM, no budget. It is the pure baseline. |
| **D2** | Modes are named chain profiles. They are discoverable (list methods on `ChainProfiles`), testable (each profile is a unit test), and diffable (compare chain compositions). |
| **D3** | Features are components. Dependencies are chain ordering (LlmOverlay before SataChain). Conflicts are handled by the profile (never include both LlmOverlay and a no-LLM profile). |
| **D4** | The 141-LOC `selectNewActionNonnull` becomes 12 independent ~20-LOC components. Each is testable. The precedence is explicit in the chain order. |
| **D5** | Each component takes its dependencies via constructor injection. No static singletons. `StepContext` is immutable and testable. |
| **D6** | `EventBus` enables event-sourced logging. `TelemetryEmitter` writes structured JSON to a file sink (not just stdout). `StepRecord` includes run_id, step, clock. |
| **D7** | `TelemetryEmitter` is a component that subscribes to `ActionSelected` and `StepCompleted` events. It emits one JSON line per event, including run_id and feature context. The pure baseline emits telemetry too (the emitter is always in the chain). |
| **D8** | The `EventBus` can be extended with a `CheckpointListener` that periodically snapshots the graph. The Python layer reads the `run_complete` flag from the trace. |
| **D9** | Each component is independently toggleable via the profile. Ablation = remove a component from the chain. The trace records which components were in the chain. |
| **D10** | New concepts: `DecisionComponent`, `ScoringComponent`, `ComponentChain`, `EventBus`, `StepContext`. LOC delta: ~+1200 (components + chain + bus + context), ~-800 (removed monolithic methods). Net: ~+400. |

#### Migration Path

1. **Stage 1 (weeks 1-3):** Extract `StepContext` and `ComponentChain`. Port `ActivityBudgetGate` and `SataChain` as proof of concept. Wire alongside existing `selectNewActionNonnull`. Both paths coexist.
2. **Stage 2 (weeks 4-6):** Port `LlmOverlay`, `MopLauncher`, `ComponentTrigger`. Introduce `EventBus` for telemetry. Replace `Logger` with `TelemetryEmitter`.
3. **Stage 3 (weeks 7-9):** Split `LlmRouter` into `LlmCaller`, `LlmCoordinateMapper`, `LlmDeadPairTracker`. Port scoring components. Remove monolithic methods.
4. **Stage 4 (weeks 10-11):** Bounded memory. Checkpointing. Python layer updates.

Cross-repo impact: Python `tool.py` changes in stage 4 (arm dicts → profile names). The property surface changes (component configs replace raw flags).

#### Honest Weaknesses

- **More moving parts.** 12 components + event bus + chain = more concepts than the feature registry. A newcomer must understand the chain, the bus, the context, and how they interact.
- **Chain ordering is load-bearing.** If `LlmOverlay` is after `SataChain`, the LLM never fires. If `MopLauncher` is before `LlmOverlay`, the launcher takes precedence. The ordering must be documented and tested.
- **The event bus introduces async complexity.** If events are processed synchronously (as proposed), the bus is just a method-call wrapper. If async, it introduces concurrency, which is dangerous in a single-threaded exploration loop.
- **Splitting `LlmRouter` into 4 classes is risky.** The current `LlmRouter.selectAction` (286 LOC) has complex state interactions (circuit breaker state, dead-pair state, screenshot state). Splitting it may break these interactions.
- **This candidate is larger than Candidate 1 in LOC and concepts.** The complexity budget (D10) is worse.

---

### Candidate 3: Declarative Arm Specification

**Organising principle:** Arms are data, not code. An arm is a JSON document that declares which features are active, what weights they use, and what policies they follow. The engine interprets the arm spec at startup and configures itself accordingly. The Python layer and the jar share the same spec format.

**What it deletes:** The 26 hand-written Python arm dictionaries; the Config flag registry; the `apePureMode` kill-switch; the `ScoringPipeline.fromConfig()` method; most of `Config.java` (replaced by the arm spec).

#### The Arm Spec Format

```json
{
  "name": "sata_mop_act_frontier",
  "description": "SATA with MOP scoring, activity trigger, and frontier boost",
  "features": {
    "treeEnhancements": true,
    "modelMenu": true,
    "activityBudget": { "enabled": true, "base": 50, "perWidget": 5 },
    "mopScoring": {
      "widgetWeight": 500,
      "transitiveWeight": 300,
      "targetPickCap": 3,
      "openMenuWeight": 250
    },
    "frontierBoost": { "weight": 200 },
    "activityTrigger": { "enabled": true, "stagnationStep": 50 },
    "coverageBoost": { "weight": 100, "maxStates": 2000 },
    "telemetry": { "stepEnabled": true, "outcomeEnabled": true }
  },
  "scoringPasses": [
    "MopWidget", "MenuGateway", "WTG", "Frontier", "MopFrontier", "Coverage", "FormCompletion"
  ],
  "decisionPrecedence": [
    "ActivityBudget", "LlmNewState", "LlmStagnation", "LlmRandom",
    "MopLauncher", "ComponentTrigger", "SataChain"
  ],
  "telemetry": {
    "format": "jsonl",
    "runId": "auto",
    "channels": ["step", "outcome", "llmTel", "mopData", "arch"]
  },
  "memory": {
    "actionHistoryMax": 10000,
    "treeTransitionHistoryMax": 5000,
    "checkpointInterval": 50
  }
}
```

#### The Engine: Interpreting the Spec

```java
public class ArmEngine {
    private final ArmSpec spec;
    private final FeatureContext features;
    private final ComponentChain chain;
    private final TelemetrySink telemetry;
    
    public ArmEngine(ArmSpec spec) {
        this.spec = spec;
        this.features = FeatureContext.fromSpec(spec.getFeatures());
        this.chain = ChainBuilder.build(spec.getDecisionPrecedence(), features);
        this.telemetry = TelemetrySink.create(spec.getTelemetry());
    }
    
    public void run() {
        // The exploration loop, driven by the spec
    }
}
```

#### How the Python Layer Works

The Python layer pushes the arm spec JSON to the device:

```python
# In tool.py
def execute_tool_specific_logic(self):
    arm_spec = self._build_arm_spec()  # from variant dict
    spec_path = "/data/local/tmp/arm_spec.json"
    self._push_file(spec_path, json.dumps(arm_spec))
    
    cmd = (
        f"adb -s {serial} shell "
        f"CLASSPATH=/data/local/tmp/ape-rv.jar /system/bin/app_process /system/bin "
        f"com.android.commands.monkey.Monkey -p {pkg} "
        f"--running-minutes {timeout // 60} "
        f"--arm-spec {spec_path}"
    )
```

The Python arm dicts become thin wrappers over the spec format:

```python
# In tool.py
_SATA_MOP_FRONTIER = {
    "features": {
        "treeEnhancements": True,
        "modelMenu": True,
        "mopScoring": {"widgetWeight": 500, "transitiveWeight": 300},
        "frontierBoost": {"weight": 200},
        "activityTrigger": {"enabled": True},
    },
    "scoringPasses": ["MopWidget", "MenuGateway", "WTG", "Frontier", "MopFrontier", "Coverage", "FormCompletion"],
}

def get_variants():
    return {
        "sata_mop_act_frontier": {**_BASELINE, **_SATA_MOP_FRONTIER},
        "ape_pure": {"features": {}},  # empty features = pure APE
        # ...
    }
```

#### How Modes Are Expressed

Modes are spec files. The `--ape` flag loads a named spec from the jar's resources. The `--arm-spec` flag loads a spec from a file path. The spec IS the mode.

```
--ape sata_mop       → loads ResourceArmLoader.load("sata_mop.json")
--arm-spec /sdcard/arm.json  → loads FileArmLoader.load("/sdcard/arm.json")
```

#### Answers to §2.2 Open Questions (in this candidate's terms)

- **Does `mop` include `aperv` features?** The spec declares it. `sata_mop.json` includes `treeEnhancements: true`. `sata_mop_minimal.json` does not. The spec is the truth.
- **Widget-level vs. frontier-level MOP?** The spec declares `mopScoring.widgetWeight` and `frontierBoost.weight` as separate fields. An ablation spec sets one to 0.
- **LLM fallback?** The spec declares `llmSteering.fallback: "sata"` or `"mop"`. The engine reads it.
- **Is "mode" the right primitive?** In this candidate, there are no modes. There are specs. Named presets are specs stored as jar resources. The concept of "mode" is dissolved into "spec".

#### How Each Driver Is Satisfied

| Driver | How |
|--------|-----|
| **D1** | `ape_pure.json` is an empty spec: `{"features": {}}`. It is shipped as a jar resource. The jar validates it at startup. The Python layer can verify it against the jar. |
| **D2** | Specs are JSON files. They are declarative, discoverable (list resources), loggable (`[APE-ARCH] arm=sata_mop_act_frontier features=[...]`), diffable (`diff spec1.json spec2.json`), and testable (load spec, verify engine config). |
| **D3** | The spec schema defines dependencies and conflicts. `ArmSpecValidator` checks them at load time. Invalid specs fail with clear errors. |
| **D4** | The engine reads `decisionPrecedence` from the spec and constructs the chain. Adding a new decision point means adding a new entry to the spec and a new `DecisionHandler` implementation. |
| **D5** | Each `DecisionHandler` is independently testable. `ArmEngine` is testable against a spec. `ArmSpecValidator` is testable against valid/invalid specs. |
| **D6** | The spec declares `memory.actionHistoryMax`, `memory.treeTransitionHistoryMax`, `memory.checkpointInterval`. The engine enforces these limits. |
| **D7** | The spec declares `telemetry.format: "jsonl"`, `telemetry.channels: [...]`, `telemetry.runId: "auto"`. The `TelemetrySink` implements the spec. The pure baseline emits telemetry if the spec says so. |
| **D8** | The spec declares `memory.checkpointInterval`. The engine checkpoints at that interval. The Python layer reads `run_complete` from the trace. |
| **D9** | The spec is the arm. Ablation = modify the spec. The spec format is designed for programmatic generation (Python can generate specs from a feature matrix). |
| **D10** | New concepts: `ArmSpec`, `ArmSpecValidator`, `ArmEngine`, `TelemetrySink`. LOC delta: ~+600 (spec parser + validator + engine), ~-500 (removed Config, apePureMode, ScoringPipeline.fromConfig). Net: ~+100. |

#### Migration Path

1. **Stage 1 (weeks 1-2):** Define `ArmSpec` data class and JSON schema. Implement `ArmSpecValidator`. Ship 5 named specs as jar resources (`ape.json`, `aperv.json`, `mop.json`, `llm.json`, `llm_mop.json`). Load spec at startup alongside existing Config. Both paths coexist.
2. **Stage 2 (weeks 3-4):** Wire `ArmSpec` into `FeatureContext` construction. Replace Config reads with spec reads. Remove `apePureMode` kill-switch.
3. **Stage 3 (weeks 5-6):** Bounded memory from spec. Telemetry from spec. Checkpointing from spec.
4. **Stage 4 (weeks 7-8):** Python layer: replace arm dicts with spec generation. Add `--arm-spec` CLI flag. Add drift detection (jar emits spec schema hash; Python validates).

Cross-repo impact: Python `tool.py` changes in stage 4. The property surface is replaced by the spec surface. The pytest guards need updating (validate spec format, not Config keys).

#### Honest Weaknesses

- **JSON on Dalvik.** The jar must include a JSON parser. The current codebase uses `org.json` (Android's built-in). This works, but JSON parsing has a cost — at ~1 KB per spec, it's negligible, but the precedent of adding a parser to the boot path should be weighed.
- **Spec versioning.** As features are added, the spec schema evolves. Both the jar and Python must agree on the schema version. This is a new deployment coupling (similar to Candidate 1's shared manifest).
- **The spec is a new source of truth that must be maintained.** Today, Config defaults serve as the fallback when a key is absent. With a spec, the engine must handle missing fields gracefully (default values in the spec, or defaults in the engine). This is a design decision that must be made upfront.
- **The `decisionPrecedence` field in the spec is load-bearing.** If someone writes a spec with `LlmRandom` before `ActivityBudget`, the budget gate is never checked. The validator should enforce ordering constraints, but defining those constraints requires understanding the semantics of each handler.
- **Spec-driven design can lead to "spec creep"** — the spec grows to include every possible knob, becoming as complex as the Config it replaces. The schema must be designed with constraint: some things belong in the spec (arm-defining weights, feature toggles), others belong in the engine (defaults, validation, fallback logic).

---

## Part D — Comparison and Recommendation

### Scoring Table

Weights: D1 (Baseline fidelity) = 10, D2 (Mode) = 8, D3 (Feature mgmt) = 8, D4 (Kill spaghetti) = 7, D5 (Testability) = 6, D6 (Memory) = 7, D7 (Traceability) = 8, D8 (Resilience) = 6, D9 (Impact measurement) = 9, D10 (Simplicity) = 5. Total weight = 74.

| Driver | Weight | Candidate 1 (Feature Surface) | Candidate 2 (Component Chain) | Candidate 3 (Declarative Spec) |
|--------|--------|------------------------------|------------------------------|-------------------------------|
| D1 Baseline fidelity | 10 | 9 — empty preset is clean | 8 — ape profile is clean | 10 — empty spec is the purest |
| D2 Mode as first-class | 8 | 9 — presets over features | 7 — profiles over chains | 9 — spec IS the mode |
| D3 Feature management | 8 | 9 — features with deps/conflicts | 6 — components have implicit deps | 8 — spec schema validates |
| D4 Kill spaghetti | 7 | 8 — ActionChain replaces if-chain | 9 — components are independently small | 7 — engine reads precedence from spec |
| D5 Testability | 6 | 8 — FeatureContext mockable | 9 — components are unit-testable | 8 — spec + engine testable |
| D6 Memory | 7 | 7 — bounded via feature config | 6 — needs separate bounded-memory work | 8 — spec declares limits |
| D7 Traceability | 8 | 8 — Telemetry feature, structured | 9 — EventBus + TelemetryEmitter | 8 — TelemetrySink from spec |
| D8 Resilience | 6 | 7 — checkpoint via feature config | 6 — needs CheckpointListener | 8 — spec declares checkpoint interval |
| D9 Impact measurement | 9 | 9 — feature registry generates arm matrix | 7 — ablation by removing components | 10 — spec variants are trivially generated |
| D10 Simplicity | 5 | 7 — 5 new concepts, +400 LOC | 5 — 8 new concepts, +400 LOC | 8 — 4 new concepts, +100 LOC |
| **Weighted total** | **74** | **60.3** | **54.4** | **64.1** |

### Recommendation

**Candidate 3 (Declarative Arm Specification) is the strongest candidate on the scoring axes, but Candidates 1 and 3 are compatible and can be adopted together.**

The recommended path is:

1. **Start with Candidate 3's spec format** — it is the smallest change that solves the split-brain (D7/T7), makes modes first-class (D2), and enables trivial arm generation (D9). It also has the smallest complexity budget (D10).

2. **Layer Candidate 1's Feature concept** on top of the spec — the spec declares feature toggles; the engine interprets them via a FeatureContext. This gives dependency/conflict validation (D3) and a clean preset system (D2).

3. **Defer Candidate 2's Component Chain** — it is the most ambitious decomposition but also the largest change. It can be adopted incrementally by extracting components from the monolithic methods one at a time, without requiring the full EventBus infrastructure upfront.

**What to adopt independently:**

- The **structured telemetry** (JSON lines with run_id, step, clock) from Candidate 2's TelemetryEmitter is valuable regardless of which candidate wins. Adopt it first.
- The **bounded memory** (LRU eviction on `treeTransitionHistory`, action history to file) from any candidate is critical for long runs. Adopt it early.
- The **checkpointing** mechanism is independent of the architecture choice. Adopt it when resilience becomes a priority.

### The Key Open Question

The choice between candidates depends on one question the owner must answer:

> **Is the Python arm layer a temporary scaffold that will be replaced, or a permanent part of the system?**

If temporary: Candidate 3 (spec in the jar) is correct — the spec absorbs the Python layer's responsibilities. If permanent: Candidate 1 (feature registry in the jar, Python as a consumer) is correct — the Python layer continues to own arm definitions, and the jar validates them.

Both paths converge on the same end state: a single source of truth for arm definitions, with the jar as the authority.

---

## Part E — Risks, Unknowns, and Next Steps

### What I Could Not Determine

1. **Actual heap high-water mark over a 600s run.** The `printMemoryUsage` logs exist but I have no access to run them. This would discriminate between "memory is fine" and "memory is the urgent problem."

2. **Per-step telemetry cost.** The calibration report says each LLM call costs ~1s. But the cost of emitting a `[APE-STEP]` line (format + write to stdout) is unknown. On a device, `System.out.format` may be slow. This would inform whether structured JSON lines are affordable.

3. **Real distribution of run terminations.** The truncation bug (`tool.py:1121-1125`) means some runs are silently lost. The true rate is unknown and would inform the resilience design.

4. **Whether the ScoringPipeline's decorative Config parameter could be made functional.** If the pipeline accepted a typed config object instead of reading static fields, it would be a cleaner abstraction. The effort to change this is unknown.

### Cheap Experiments to Discriminate Candidates

1. **Spec-first prototype (1 day):** Define `ArmSpec` as a data class, serialize 5 arm configs to JSON, load them at startup alongside Config, and log `[APE-ARCH] loaded spec: <name>`. No behavior change. Tests: does the spec load correctly? Does the engine log the right features?

2. **FeatureContext prototype (2 days):** Wrap 3 Config reads in `featureContext.isEnabled()` calls. Both paths coexist. Tests: does `ape_pure` produce identical behavior? Does `mop` produce identical behavior?

3. **Telemetry prototype (1 day):** Replace `Logger.java` with a `TelemetryEmitter` that writes JSON lines to a file. Add `run_id` (UUID generated at startup) and `clock` (epoch millis) to every line. Tests: can the calibration report's parser still parse the output?

### Questions for the Owner

1. Should `ape_pure` emit telemetry? (Y/N — this is D1 vs T1)
2. Is the Python arm layer temporary or permanent? (Temporary/Permanent — this selects Candidate 3 vs Candidate 1)
3. How many features should the initial feature set include? (5 core / 10 full / 15+ exhaustive — this affects D3 and D10)
4. Is checkpointing needed for the first phase-3 campaign, or can it wait? (Now/Later — this affects D8 scheduling)
5. What is the acceptable per-step overhead budget? (0 ms / 5 ms / 10 ms — this affects D6 and D7 design)

### What This Analysis Might Be Wrong About

1. **I assumed the ScoringPipeline's Config parameter is purely decorative.** If there are paths where the injected `Config` differs from the static `Config` (e.g., in tests), the Feature Surface approach would need to handle this. I found no evidence of such paths, but the codebase is large.

2. **I assumed the Python layer will change.** If the owner decides the Python layer is permanent and immutable, Candidate 3 loses its cross-repo advantage and Candidate 1 becomes stronger.

3. **I underestimated the cost of structured telemetry.** If `System.out.format` is significantly slower than direct `System.out.println`, the JSON-line approach may add measurable per-step overhead. A microbenchmark would resolve this.

4. **I did not fully account for the interaction between checkpointing and naming refinement.** The naming hierarchy is a complex, stateful structure. Checkpointing it correctly requires serializing the entire naming manager, which may be large. This could make checkpointing impractical for the naming subsystem.

5. **I may have over-weighted D9 (impact measurement).** The thesis's RQs are not yet fixed, so designing for maximum flexibility in arm generation is a bet on future questions. If the RQs crystallise early, a simpler ablation design (one-factor-at-a-time) may suffice, and the spec-driven approach is over-engineered.

---

*End of report.*
