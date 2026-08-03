# Preliminary Architecture Study: Re-architecting APE-RV

---
model: gpt-5
date: 2026-08-01
commit: 5dcf225976b26ce78d8b31dd88d7f858dad29d43
scope: architecture analysis only
---

## Executive summary

APE-RV's central architectural problem is not the number of flags or the size of `SataAgent`. It is that the effective experiment treatment is implicit. It is jointly determined by Java defaults, Python dictionaries, textual control-flow order, substrate availability, and several always-on model changes. No single artifact can currently answer the most important audit question: **what exact explorer did this task run?** The mode spaghetti, configuration drift, weak baseline claim, difficult testing, and incomplete provenance are different symptoms of that same missing concept.

This study considers three genuinely different architectures:

1. **Compiled Run Plan and Decision Kernel**: the experiment layer compiles a versioned, typed, immutable plan; a small Java kernel executes explicit decision policies. This is the lowest-risk structural redesign.
2. **Fossilised Stock Lane and Independent APE-RV Lane**: original APE is a pinned executable baseline rather than the negative configuration of an evolving fork. This provides the strongest thesis control.
3. **Event-Sourced, Bounded Explorer**: decisions and transitions are a durable journal and the in-memory model is a bounded projection. This best addresses memory, traceability, and recovery, but has the highest migration risk.

The recommendation is deliberately compositional: use Candidate 1 as the organising architecture, adopt Candidate 2's strict stock lane for the control arm, and initially take only Candidate 3's durable event stream and explicit tree ownership. Do not begin with a wholesale rewrite of the naming/refinement core.

The five proposed names should survive only as presets over `base explorer × feature axes × parameters`. `mop` should mean `aperv + MOP` for the main incremental comparison; `ape + MOP only` should be a separately named bridge/sensitivity arm. Widget MOP and frontier MOP are independent feature axes. LLM fallback must be declared in the plan: normally `aperv` for `llm`, and the configured MOP policy for `llm_mop`.

For resilience, the Python plugin should supervise successive `app_process` epochs under one absolute task deadline and one continuous emulator/logcat session. Java should supply bounded, versioned checkpoints. A restarted execution is one sample only if its plan, seed and PRNG state, deadline, device session, and checkpoint lineage are preserved and recorded.

For scientific attribution, timestamps alone cannot prove that an APE action caused an RVSEC violation. Exact linkage requires monitor/instrumentation cooperation so that RVSEC events echo a shared `run_id` and action epoch. Without that, the system must label the relationship as temporal attribution with explicit ambiguity, not causality.

## Method and evidence status

The repository was inspected at `5dcf225`. The worktree was already dirty; no existing changes were touched. Three read-only parallel investigations covered: (1) control flow, feature management and baseline fidelity; (2) memory ownership, persistence and process lifecycle; and (3) thesis context, telemetry, the `rv-android` consumer and ablation design. The main analysis also read the supplied dossier, the 2026-07-08 design, `CLAUDE.md`, hotspot code, the open telemetry change, and selected thesis/paper outputs.

The following dossier claims were directly spot-checked and found substantively correct:

- unknown agent names silently become SATA (`src/main/java/com/android/commands/monkey/ape/agent/ApeAgent.java:68-95`);
- configuration is frozen during static initialisation and malformed numeric values silently fall back (`src/main/java/com/android/commands/monkey/ape/utils/Config.java:30-44,448-481`);
- decision precedence is textual (`src/main/java/com/android/commands/monkey/ape/agent/SataAgent.java:463-588`);
- action history retains rich action objects indefinitely (`src/main/java/com/android/commands/monkey/ape/model/Model.java:136-173`);
- `State` retains GUI trees without a general eviction policy (`src/main/java/com/android/commands/monkey/ape/model/State.java:56,405-408,556-560`);
- the node naming cache is not cleared by `GUITreeBuilder.release` (`src/main/java/com/android/commands/monkey/ape/tree/GUITreeBuilder.java:670-715`);
- stock resume reads a `Graph`, whereas teardown serialises a `Model` (`src/main/java/com/android/commands/monkey/ape/model/Graph.java:1166-1173`; `src/main/java/com/android/commands/monkey/ape/agent/StatefulAgent.java:1855-1870`);
- non-zero APE process exit is only debug-logged by the plugin (`rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py:1111-1138`).

Some dossier line numbers are stale after the open telemetry change: for example, teardown/save code is now around `StatefulAgent.java:1802-1902`, and the `GUITreeBuilder` caches are around `:670-715`. The claims remain correct.

External-tool comparisons are used only to map the design space. DroidBot separates device state, input policy, input management, and a UI transition graph; Humanoid adds a learned prioritiser but reports that prioritisation alone had limited long-horizon effect; Stoat separates model construction from guided sampling; Fastbot2 emphasises a reusable model and a selectable learning policy. These systems support the general separation of perception, model, and policy, but none supplies APE-RV's experimental provenance contract. APE-RV therefore should borrow separation, not copy an architecture wholesale.

# Part A — Diagnosis

## A1. What APE-RV structurally is today

APE-RV is a vertically integrated on-device explorer embedded in the AOSP Monkey process, coupled to a host-side experiment compiler and supervisor.

On the device, `Monkey` owns the loop and deadline, `MonkeySourceApe` bridges actions to Monkey events, `StatefulAgent` owns most exploration state, `SataAgent` selects actions, and the naming/model/tree packages implement APE's abstraction-refinement contribution. Extensions enter at several unrelated levels:

- perception in `GUITreeNode`/`GUITreeBuilder`;
- scoring through seven `ScoringPass` implementations;
- routing and precedence in `SataAgent`;
- LLM transport, parsing and mapping in `LlmRouter`;
- input in both `ApeAgent` and scoring/form completion;
- process guards and dispatch in `MonkeySourceApe`;
- configuration through static global fields;
- telemetry through direct `Logger` calls.

Off device, the `aperv-tool` plugin defines named variants, maps Python names to Java properties, pushes files, launches `app_process`, applies the hard timeout, and streams stdout into the task trace (`rv-android/modules/aperv-tool/src/aperv_tool/tools/aperv/tool.py:72-192,427-659,928-991`). `rv-platform` owns emulator lifecycle, logcat, task identity and atomic task persistence. Consequently, the real system boundary is already process-and-file based; the jar is not the whole product.

The current layering is therefore:

```text
campaign / statistical design
  -> Python arm dictionaries and task matrix
    -> files + command line
      -> static Config fields
        -> cross-cutting Java conditionals
          -> APE naming/model core
```

It is not the aspirational “APE core + optional RV passes”. The scoring pipeline is one well-defined island inside a broader accreted system.

## A2. Why the control surface grew this way

The design is historically understandable. The fork grew under experimental deadlines. Early changes were local improvements; MOP then required weights and a static-analysis substrate; LLM added three invocation occasions, transport and repair; calibration exposed new evidential gaps; the open telemetry change correctly fixed those gaps at the nearest available sites. In this context, static flags and local guarded blocks were cheap, reviewable and easy to backport.

The 2026-07-08 redesign correctly recognised that inline scoring was becoming unmanageable. It introduced one jar, flag composition, a no-op pass contract, a kill-switch, and explicit Python variants (`docs/20260708_arquitetura_separacao_aperv.md:18-48`). That decision was reasonable for its scope. Its limitation is scope: it treated scoring as the extension boundary while later changes accumulated in routing, perception, state ownership and telemetry.

The most recent change illustrates the causal mechanism. It adds counterfactual fields, dead-pair handling, new routing state, snapping behaviour and schema constraints, but adds no configuration flag. Correct behaviour had no architectural home, so it became always-on. The result is not developer negligence; it is a control surface that makes feature declaration optional and expensive.

## A3. Architectural tensions

### T1. Treatment is implicit, while the thesis requires explicit treatments (D1, D2, D3, D9)

Python owns names and combinations; Java owns defaults and meaning. Neither can validate the other. `ARM_DEFINING_KEYS` can only check a Python constant against Python dictionaries. Java ignores unknown properties and malformed numbers. A stale key can therefore produce a valid-looking but scientifically false arm.

This tension causes both mode spaghetti and provenance failure: a treatment that is not a first-class value cannot be cleanly dispatched or faithfully logged.

### T2. Negative configuration cannot prove historical equivalence (D1)

`apePureMode` overwrites a manually maintained property set before static fields initialise (`Config.java:30-44,343-412`). This is effective for configured extensions, but flags cannot undo enum additions, model object fields, altered cache/refinement behaviour, changed randomness, or core error tolerance. The archived invariant claiming upstream equivalence admits only a subset of exceptions (`openspec/changes/archive/2026-07-08-rv-scoring-pipeline/specs/scoring-pipeline/spec.md:124-130`).

The baseline question is thus not “which flags are off?” but “which executable semantics are the control?”

### T3. Eligibility, precedence, fallback and side effects are interleaved (D3, D4, D5)

`SataAgent.selectNewActionNonnull` mixes an advisory budget decision, repeated LLM eligibility, stateful stagnation semantics, a launcher, an effect-only component trigger, and the original SATA chain (`SataAgent.java:463-588`). The LLM predicates and breaker are in `LlmRouter`, while common eligibility and ordering remain in `SataAgent` (`src/main/java/com/android/commands/monkey/ape/llm/LlmRouter.java:232-279`). `null` is overloaded as decline, failure and fallback.

Essential conditionals exist, but orchestration-by-source-order makes their interaction invisible and difficult to test.

### T4. Rich object identity serves the algorithm and accidentally becomes retention policy (D5, D6)

The naming algorithm legitimately needs historical examples. But actions, graph histories, states, static caches and cloned naming maps all retain overlapping object graphs. There is no unique owner for a `GUITree`, no explicit liveness rule, and no global budget. `maxGUITreesPerState` limits refinement rather than memory. The result is three independent retention planes, so local pruning cannot establish a heap bound.

### T5. Observability is behaviourally entangled yet transported as diagnostics (D1, D6, D7, D9)

The step/outcome join is a strong design: the selected step is buffered until its transition becomes known (`StatefulAgent.java:117-124,1024-1039`). Wall-clock timestamps were deliberately added for offline joins (`StatefulAgent.java:1484-1506`). Calibration showed that these records can explain mechanisms.

But the records are unescaped `key=value`, written to stdout through a lossy path. The baseline disables the richest telemetry, creating a parity/observability conflict. The architecture treats evidence as logging even though it is part of the experimental product.

### T6. “Only timeout ends a run” spans two failure domains (D8)

Java can contain exceptions and isolate teardown, but cannot survive its own process death. Python can restart processes and persists task state, but currently classifies early APE exit weakly. An in-jar restart cannot handle SIGKILL; a task retry discards elapsed work; a checkpoint restart changes sample semantics unless seed, PRNG and deadline are preserved.

The correct abstraction is a task consisting of one or more explorer epochs under one external deadline.

### T7. Mechanism attribution is available; causal attribution is not (D7, D9)

`decision_source`, boost fields, `PickChannel` and the one-step MOP counterfactual describe how the policy selected an action. They do not show the trajectory that would have occurred without the feature, and they do not identify which asynchronous monitor event an action caused. The counterfactual contract correctly warns that it is one-step myopic (`StatefulAgent.java:1166-1176`).

This distinction must be architectural: events need to say whether a relationship is a decision fact, temporal attribution, counterfactual estimate or experimentally identified effect.

## A4. What must be preserved

Several current properties are architectural laws worth retaining:

- configured-but-unloadable MOP data fails loudly rather than silently degrading a labelled MOP arm (`openspec/specs/mop-guidance/spec.md:760-768`);
- disabled scoring passes are strict no-ops and pass order is deterministic;
- seeded randomness and zero-extra-draw counterfactual computation protect paired designs;
- app crashes do not terminate timed exploration;
- teardown steps are isolated so one failed artifact does not suppress later artifacts (`StatefulAgent.java:1788-1813`);
- guards retain explicit fail-open/fall-through semantics;
- step and outcome share a deliberate join key;
- primary outcome metrics remain independently reconstructed from instrumented-app logcat;
- Python arms explicitly set treatment-defining values rather than inheriting defaults.

Some current invariants should dissolve rather than be preserved literally. `INV-ARCH-01` should become an executable baseline provenance/equivalence contract, not “zero step lines plus a kill-switch”. The fixed seven-pass scoring list can become a compiled ordered policy list as long as determinism and no-op semantics remain. The current `Graph`/`Model` Java-serialisation mismatch should be replaced, not made compatible merely to preserve `INV-EXPL-03`.

# Part B — Design space

## B1. Where modes and arms live

- **Jar-owned presets** make standalone use strong but duplicate experiment design and require every campaign change to rebuild Java.
- **Python-owned presets** fit the task matrix but cannot validate Java semantics alone; this is today's split-brain.
- **Shared declarative manifest** fits the file boundary. Python should compile campaign intent; Java should validate and echo the resolved plan. A checked-in schema/version and generated documentation make drift loud without sharing runtime types.

The selected direction is the third option. Python remains the experiment compiler, while Java is authoritative for supported feature definitions and semantic validation. Requested and effective plan digests must match.

## B2. Feature representation

One universal plugin interface would be deceptively simple. Perception, scoring, routing, input and effects have different contracts. The useful common unit is a **capability descriptor**—identity, version, dependencies, conflicts and parameters—while execution uses a small number of phase-specific interfaces.

```java
interface PerceptionPolicy { UiSnapshot perceive(DevicePort d); }
interface ScorePolicy { void score(DecisionContext c, ScoreVector out); }
interface DecisionPolicy { DecisionResult propose(DecisionContext c); }
interface InputPolicy { InputValue generate(InputContext c); }
interface GuardPolicy { GuardResult inspect(DeviceObservation o); }
```

This avoids both extremes: flags everywhere and a framework-like universal extension system.

## B3. Decision dispatch

- plain conditionals preserve local clarity but do not expose precedence;
- chain of responsibility makes precedence explicit but often overloads `null`;
- a policy kernel with typed results makes decline, action and side effect distinct;
- a general rules engine is unjustified on Dalvik and would hide control flow again.

A small data-driven Java table with typed results is sufficient.

## B4. State ownership

- retain the full object graph: simplest, unbounded;
- weak references: nondeterministic and not a real bound;
- global deterministic arena/store: explicit ownership and bounded eviction;
- full event sourcing: strongest recovery and auditability, highest migration cost.

The minimum acceptable target is an arena with stable IDs, hard caps and explicit refinement working sets.

## B5. Observability transport

- stdout only is simple but lossy;
- one device-side file per run is durable but requires pull/cleanup;
- a bounded append-only spool plus low-volume stdout combines evidence and operability;
- a database or network service is unnecessary and violates the simplicity/runtime constraints.

NDJSON is human-inspectable and dependency-free; length-delimited records are more robust and compact. A short benchmark should choose between them. Either must have a versioned envelope and checksums.

## B6. Failure containment

- in-process catches reduce local failure but cannot survive VM death;
- whole-task retry is simple and scientifically clean but wastes work;
- host-supervised epochs plus checkpoint preserve the deadline and work;
- restarting the entire emulator per epoch duplicates `rv-platform` responsibility and breaks continuous logcat context.

The narrow seam is `ApeRVTool.execute_tool_specific_logic`: keep the existing task/emulator/logcat session and supervise multiple `app_process` epochs inside it.

## B7. Experiment matrix

Handwritten arms maximise explicitness but drift. Full factorial generation is intractable. The feature manifest should generate legal candidate rows, while the study design selects a scientifically affordable subset: confirmatory contrasts plus fractional/D-optimal screening.

# Part C — Candidate architectures

## Candidate 1 — Compiled Run Plan and Decision Kernel

### Organising principle

Before exploration starts, all requested behaviour is compiled into one immutable, validated `RunPlan`. Runtime code does not ask global flags what mode it is in; it executes the plan through a small phase-specific kernel.

### Structure and data flow

```text
ape.plan        RunPlan, FeatureSpec, PlanLoader, PlanValidator
ape.runtime     ExplorerSession, Clock, RandomSource, RunIdentity
ape.device      DevicePort, AndroidDeviceAdapter
ape.perception  PerceptionPolicy, package guards
ape.decision    DecisionKernel, DecisionPolicy, DecisionResult
ape.scoring     ScorePolicy implementations
ape.input       InputPolicy chain
ape.model       existing naming/model core behind SessionModel
ape.telemetry   EventSink, typed event payloads
```

```java
final class RunPlan {
    String schemaVersion;
    String preset;
    BaseExplorer base;
    List<PolicySpec> decisionOrder;
    Map<String, FeatureConfig> features;
    String fallbackPolicy;
    String requestedDigest;
}

interface DecisionPolicy {
    String id();
    DecisionResult evaluate(DecisionContext context);
}

interface DecisionResult {}
final class Selected implements DecisionResult { Action action; Evidence evidence; }
final class Declined implements DecisionResult { Reason reason; }
final class Effect implements DecisionResult { DeviceEffect effect; Evidence evidence; }
```

The initial policy table reproduces the current order exactly: budget, LLM-new, LLM-stagnation, LLM-random, activity launcher, component effect, then SATA base channels. `DecisionKernel` alone owns iteration, logging and fallback. Essential conditionals remain within small predicates.

The current scoring passes may be retained temporarily, but their constructors receive typed settings and no longer read static `Config`. Ultimately they become pure `ScorePolicy` values in the plan.

### Modes and a sixth mode

Presets are manifest aliases:

```text
ape      = artifact/engine stock-ape, no RV capabilities
aperv    = sata + aperv perception/input/guards/scoring substrate
mop      = aperv + mop.widget + mop.menu + mop.wtg
llm      = aperv + llm.routes(fallback=aperv)
llm_mop  = aperv + selected MOP axes + llm.routes(fallback=mop-policy)
future_x = aperv + frontier.mop + async_llm + new_input_policy
```

Unknown preset, feature, parameter or property is a startup error. A hypothetical sixth mode is a new manifest preset; it requires no factory branch.

`mop` includes the APE-RV substrate by default. `ape_mop_bridge` is an explicit sensitivity preset. Widget and frontier mechanisms are axes. LLM fallback is mandatory configuration.

### Drivers

- **D1:** improves auditability but needs Candidate 2's strict lane or an upstream oracle for the strongest claim.
- **D2:** `RunPlan` is declarable, diffable, validated and echoed.
- **D3:** capability metadata expresses dependencies such as menu-gateway → MODEL_MENU + MopData.
- **D4:** kernel rows replace orchestration conditionals; semantic guards remain local.
- **D5:** immutable plan, injected clock/random/device, and pure policies run on a plain JVM.
- **D6:** enables ownership injection but does not by itself bound the legacy model.
- **D7:** kernel emits typed evidence at the decision point.
- **D8:** session/checkpoint ports integrate with Python supervision.
- **D9:** the feature manifest can generate legal design matrices and exact provenance.
- **D10:** limited vocabulary, no runtime framework and no reflection-based plugin discovery.

### Migration

1. Add a read-only effective-config adapter around today's `Config`; reject unknown mapped keys and emit a digest.
2. Introduce `RunPlan` while generating it from current flags, preserving every current arm.
3. Extract the SATA base chain into typed rows with golden seeded tests.
4. Move the three LLM hooks and launcher into policy rows, preserving current order.
5. Remove static reads from scoring and input.
6. Make Python emit the versioned plan and compare requested/effective digests.
7. Delete the old raw-string agent fallback and, after the baseline lane exists, the kill-switch duplication.

Both repositories change. Existing property files can be accepted through a time-limited compatibility translator. Comparability is preserved while the effective plan digest maps old and new representations to identical semantics.

Rough effort: 4–7 engineer-weeks plus device validation. Net code growth should be about 300–700 LOC after duplicated branches and registries are removed.

### Weaknesses

The manifest can become a miniature configuration language if feature granularity is uncontrolled. Policy rows can also degenerate into many tiny classes. This candidate punishes changes that truly cross phases; those should be admitted as coordinated capabilities rather than disguised as one plugin. It should not be chosen alone if strict upstream fidelity is non-negotiable.

### Complexity budget

Five principal concepts: RunPlan, capability descriptor, phase policy, DecisionResult and DecisionKernel. A newcomer learns one assembly path and five phase interfaces. No dependency injection framework, annotation processor or runtime registry is introduced.

## Candidate 2 — Fossilised Stock Lane and Independent APE-RV Lane

### Organising principle

The historical control is executable provenance, not a negative feature set. Original APE is pinned to upstream `8f51b99`; APE-RV evolves separately. Shared infrastructure may launch and observe both, but RV behaviour cannot enter the stock lane.

### Structure

Two viable packaging forms should be prototyped cheaply:

1. `ape-stock.jar` and `ape-rv.jar`, both built from source in the Docker image; or
2. one delivered jar with isolated `stock.*` and `aperv.*` source sets and a minimal neutral launcher.

The first is scientifically clearer; the second conforms more literally to the current single-artifact deployment. In either form, the plan names an engine and its build digest. Stock rejects RV feature fields.

The only shared runtime layer should be behaviour-neutral: argument parsing, run identity, external heartbeat and terminal classification. Device behaviour, action enums, model serialisation and naming remain inside each lane.

### Modes

`ape` selects the stock lane. All other presets select APE-RV and are composed inside that lane, preferably using Candidate 1. A sixth RV mode does not touch stock. An attempted `ape + llm` is a validation error.

The scientific meaning is crisp:

- stock APE estimates continuity with Phase 2;
- `aperv` estimates the substrate delta;
- `mop` estimates MOP's incremental delta over that substrate;
- bridge arms such as stock-like base + MOP test transportability, not the headline treatment.

### Drivers

- **D1:** strongest candidate; source commit and artifact hash are directly defensible.
- **D2–D4:** does not solve RV composition by itself; pair with a small plan/kernel.
- **D5:** enables upstream-oracle differential tests but duplicates some fixtures.
- **D6:** stock retains upstream memory behaviour; campaign timeouts limit exposure, but no bound is created.
- **D7:** stock should receive only behaviour-neutral external metadata; mechanism telemetry differs by design.
- **D8:** the same Python supervisor can manage both artifacts, but checkpoint compatibility is lane-specific.
- **D9:** provides the essential control and a clean substrate contrast.
- **D10:** conceptually simple, operationally more expensive.

### Baseline observability

Injecting `DecisionSource` into stock would invalidate the reason for this candidate. Baseline parity should instead use:

- host-assigned run/attempt IDs;
- artifact and source hashes;
- launch/deadline/exit events;
- unchanged RVSEC logcat primary metrics;
- optional external action observation only after measuring perturbation.

Mechanism-level stock telemetry is less rich. This is an honest trade: experimental outcomes remain comparable, while internal mediation comparisons are restricted to RV arms.

### Migration

1. Reconstruct and pin upstream `8f51b99` in a dedicated source set.
2. Apply only toolchain/package relocation outside behavioural code; document every deviation.
3. Build in Docker from source and stamp source/artifact hashes.
4. Run paired upstream-oracle tests on representative apps and seeds; compare action/event sequences where Android nondeterminism permits.
5. Add plugin artifact selection and fail closed on digest mismatch.
6. Re-label today's `ape_pure` as `ape_compat` until its scientific role is retired.

Rough effort: 2–4 weeks for baseline extraction and deployment, plus ongoing dual-lane maintenance. A second artifact is a cross-repo deployment change but does not reintroduce a committed binary.

### Weaknesses

The lane accumulates security/platform compatibility debt. A strict stock process may crash for bugs fixed in APE-RV; that is part of baseline behaviour but operationally painful. If Android compatibility requires behavioural patches, the report must distinguish `ape_stock` from a separately named `ape_stock_patched`. This candidate is excessive if the thesis owner accepts “APE plus frozen fixes” as the control.

### Complexity budget

Only two new concepts—engine lane and artifact provenance—but duplicated source/build/test paths. Estimated permanent maintenance cost is higher than its LOC delta.

## Candidate 3 — Event-Sourced, Bounded Explorer

### Organising principle

The durable truth is an append-only sequence of typed events. The in-memory explorer is a bounded projection optimised for the next decision, not an archival object graph.

### Structure and ownership

```text
ape.session       ExplorerSession, SessionState, deterministic PRNG
ape.store         TreeStore, CompactGraph, RefinementWorkingSet
ape.journal       EventWriter, sequence/checksum, checkpoint manager
ape.reducer       pure state transition reducers
ape.policy        policies consuming immutable DecisionSnapshot
ape.device        Android adapter and effects
```

`TreeStore` is the sole owner of `GUITree`. Graphs and actions carry integer IDs. Retention is explicit:

- current tree;
- a small refinement working set;
- at most K representative trees per state;
- a hard global tree/node byte or count cap;
- deterministic eviction with complete index/cache removal.

`Model.actionHistory` becomes scalar `ActionExecuted` records plus a short ring for diagnostics/LLM. `stateTransitionHistory` and `treeTransitionHistory` are eliminated or bounded rings. `NameManager`, `StringCache` and `GUITreeBuilder` caches become session-scoped and capped. Weak maps are not the primary policy because GC-dependent eviction would damage reproducibility.

The compact checkpoint stores only decision-critical state: schema and plan digest, compact graph, naming/refinement metadata, counters, current IDs, PRNG state, journal offset and deadline metadata. It uses temp+atomic rename, checksum and double slots, not Java serialisation.

### Event model

Every event has:

```text
schema_version, run_id, attempt_id, epoch_id, seq,
device_elapsed_ms, wall_ms, step_id?, event_type,
plan_digest, payload
```

Typed events include `RunStarted`, `EffectivePlan`, `DecisionProposed`, `DecisionCommitted`, `ActionExecuted`, `TransitionObserved`, `GuidanceInvocation`, `CheckpointWritten`, `Failure`, `EpochResumed` and `RunEnded`. Screenshots and trees are content-addressed side artifacts referenced by digest.

The device-side spool is canonical. Python pulls and deduplicates by `(run_id, epoch_id, seq)`. Segment checksums and explicit gap events make loss visible. Aggregate summaries are derived, never sole evidence.

### Modes

Modes almost disappear. Different policies and reducers project the same event contract. A sixth configuration is another plan. Because this candidate reorganises state rather than merely features, it can host stock-compatible and RV projections, but achieving byte-faithful stock behaviour would be much harder than Candidate 2.

### Resilience

The plugin holds one absolute monotonic deadline and launches new `app_process` epochs with remaining time. It does not restart the `TaskExecutor`, emulator or logcat session. Java checkpoints every bounded interval—for example the minimum of 50 steps or 10 seconds, plus immediately after refinement—subject to measurement.

An unexpected exit produces `EARLY_EXIT_RETRIED`; only external deadline expiry produces `TIMEOUT_COMPLETE`. Invalid checkpoints or exhausted restart limits produce `UNRECOVERABLE`, never `COMPLETED`. Downtime is not added back to the budget.

A resurrected run is one randomised sample only when it retains the same emulator/app session, original seed and restored PRNG state, plan digest, absolute deadline and checkpoint lineage. Otherwise it is a new attempt. Sensitivity analysis should exclude restarted runs as a predeclared secondary check.

### Drivers

- **D1:** good provenance, weak behavioural stock equivalence unless paired with Candidate 2.
- **D2–D4:** plans/policies become event producers; control is explicit.
- **D5:** reducers and compact graph are plain-JVM testable.
- **D6:** strongest bounded-memory and storage design.
- **D7:** strongest typed, loss-detectable evidence chain.
- **D8:** strongest checkpoint/restart model aligned with the process boundary.
- **D9:** rich mediation, time-to-first-event and saturation analyses become routine.
- **D10:** weakest; substantial conceptual and migration cost.

### Migration

1. Add the event spool alongside current logs and prove the calibration report can be regenerated.
2. Stream scalar action history while preserving the legacy list; compare outputs.
3. Introduce stable IDs and `TreeStore` for new trees, initially without eviction.
4. Add deterministic release and caps with naming/refinement equivalence tests.
5. Replace whole-model serialisation with compact checkpoints.
6. Add Python epoch supervision.
7. Remove legacy histories, static caches and teardown-only graph products; generate visualisations host-side.

Rough effort: 8–14 engineer-weeks and a dedicated on-device equivalence/performance campaign. The naming core should not be restructured until traces demonstrate which historical trees refinement actually needs.

### Weaknesses

Event sourcing can become architecture for architecture's sake. Rebuilding projections, schema evolution and checkpoint compatibility create permanent obligations for one maintainer. The largest risk is silently changing naming/refinement behaviour while replacing object identity with IDs. Do not select the full candidate before measuring heap high-water marks and historical-tree use.

### Complexity budget

Seven or more new concepts and approximately 1,500–3,000 LOC before deletions. It is justified only if long runs, process resurrection and future unknown RQs are firm requirements.

# Part D — Comparison and recommendation

## D1–D10 scoring

Scores are 1–5. Weights reflect the thesis context: D1 and D9 are highest; D7 is next. The owner should change them if operational simplicity outweighs scientific defensibility.

| Driver | Weight | Run Plan/Kernel | Stock Lane | Event-Sourced |
|---|---:|---:|---:|---:|
| D1 baseline fidelity | 15 | 3 | 5 | 3 |
| D2 first-class mode/plan | 8 | 5 | 4 | 5 |
| D3 feature management | 10 | 5 | 3 | 5 |
| D4 conditional structure | 8 | 5 | 3 | 5 |
| D5 testability | 8 | 5 | 4 | 5 |
| D6 memory/storage | 10 | 3 | 2 | 5 |
| D7 traceability | 12 | 5 | 4 | 5 |
| D8 resilience | 9 | 4 | 3 | 5 |
| D9 impact measurement | 15 | 5 | 5 | 5 |
| D10 simplicity | 5 | 4 | 3 | 2 |
| **Weighted total / 500** | **100** | **444** | **376** | **452** |

The numerical winner, Event-Sourced, is not the practical recommendation because the scoring table does not capture migration risk adequately. For a live one-maintainer research instrument, the efficient frontier is:

1. Candidate 1 for control and feature composition;
2. Candidate 2 specifically for D1;
3. Candidate 3 selectively for event durability and memory ownership.

This hybrid introduces a clear top-level model:

```text
Experiment compiler (Python)
  -> requested RunPlan + run identity
  -> selected engine lane
     stock APE: behaviourally frozen
     APE-RV: Decision Kernel + bounded session state
  -> durable typed events + independent RVSEC logcat
  -> result validator compares requested/effective provenance
```

## Ablation design

The architecture should generate legal configurations; it should not automatically run a factorial. A defensible confirmatory family is:

1. stock APE;
2. APE-RV substrate;
3. substrate + widget MOP;
4. substrate + frontier MOP;
5. substrate + widget and frontier MOP;
6. substrate + LLM;
7. substrate + selected MOP stack + LLM.

This estimates the substrate increment, both MOP mechanisms and the key interactions without `2^k`. Remaining substrate features should use a constrained resolution-IV fractional factorial or D-optimal blocked design on a screening subset, followed by full-dataset confirmation of selected contrasts. Plackett–Burman is cheaper but aliases interactions that are especially plausible here; OFAT misses them entirely.

Runs should be blocked by APK, timeout and repetition/seed, with arm order randomised within blocks where infrastructure permits. The app remains the clustering unit. Primary inference remains the frozen negative-binomial pipeline over per-run unique misuse counts. App-code uniques, direct-MOP coverage, time-to-first unique violation, saturation curves, action throughput and restart sensitivity are predeclared secondary analyses.

Per-step sources and counterfactuals are mechanism checks, not independent causal estimates. Conditioning the primary efficacy claim on post-treatment steps, visited screens or LLM calls would bias the treatment comparison. Report fixed-wall-time intention-to-treat first; per-step and per-call efficiency explain it.

# Part E — Risks, unknowns and next steps

## Measurements needed before commitment

1. Heap high-water mark and retained-object histogram over representative 300 s and 600 s runs, including apps near the 48 MB static-analysis maximum.
2. Number and age distribution of GUI trees actually consulted by later refinement. This determines whether K representatives/state is safe.
3. Per-step CPU and I/O cost of NDJSON versus length-delimited records, with buffered flush and several fsync intervals. Every lost step has measured scientific cost.
4. Distribution of process exit reasons, elapsed time at exit and teardown completeness across existing campaigns.
5. Checkpoint size/write latency at 50-step/10-second intervals and after refinement.
6. Whether `app_process` restart can restore required Android handles without restarting the app/emulator.
7. Perturbation introduced by propagating an action epoch into RVSEC monitor events.
8. On-device verification of the open `telemetry-proof-llm-efficacy` change; task 17.4 remains unchecked (`openspec/changes/telemetry-proof-llm-efficacy/tasks.md:103-117`).

## Cheap discriminating experiments

- Build a read-only `EffectivePlan` prototype from current flags and compare requested/effective digests for all 26 existing variants. This cheaply measures drift and dead-key incidence.
- Extract only the SATA base chain into a typed kernel behind a golden seeded test. If semantics cannot be preserved locally, Candidate 1 is riskier than estimated.
- Run stock upstream and current `ape_pure` on 5–10 deterministic fixtures/apps with identical seeds and compare selected action/event traces. This quantifies the practical baseline gap.
- Stream scalar action records while retaining legacy history and compare heap after 600 seconds. This estimates the largest easy memory win.
- Emit dual telemetry for 20 tasks and reproduce the 2026-07-24 calibration tables solely from the new stream. This is the acceptance test for D7.
- Prototype two supervised epochs in the plugin with a synthetic forced exit; verify continuous deadline/logcat, checkpoint lineage and terminal classification.

## Decisions required from the owner

1. Must the headline control be byte/behaviour-faithful upstream APE, or is “APE plus a frozen, published patch list” acceptable?
2. Is a second build artifact acceptable, or must strict stock and APE-RV coexist inside one jar?
3. May RVSEC instrumentation be changed to echo `run_id` and action epoch, accepting a small measured instrumentation cost?
4. Is crash downtime part of the fixed treatment budget? The recommendation is yes: never extend the deadline after restart.
5. Is mid-run recovery required for the final campaign, or is fail-loud whole-task retry sufficient for the first stage?

## What could change this recommendation

The recommendation would move toward Candidate 3 if heap profiling shows rapid unbounded growth within normal campaign timeouts, or if real process deaths are common enough that task retry materially wastes the grid. It would move toward Candidate 1 alone if upstream-vs-`ape_pure` action traces are empirically indistinguishable and the thesis committee accepts a documented patched baseline. It would move toward Candidate 2 alone plus minimal cleanup if the remaining experiment window is too short for a kernel migration.

The strongest uncertainty is the naming core's true dependence on retained historical GUI trees. The proposed ownership bounds are architectural targets, not claims that arbitrary eviction is safe. Evidence that refinement revisits most historical trees would require a compact lossless representation rather than bounded exemplars.

## Final recommendation

Make the **effective run plan** the new centre of APE-RV. Let Python compile experiment intent, let Java validate and execute a typed immutable plan, and make requested/effective digest equality a precondition for a valid sample. Protect the thesis with a truly isolated stock APE lane. Move mechanism evidence from stdout diagnostics to a durable versioned event stream, while preserving logcat as the independent source of primary outcomes. Bound memory through explicit tree ownership and scalar histories before attempting a full event-sourced model rewrite. Finally, supervise `app_process` from the existing Python task boundary under one immutable deadline, with checkpointed epochs and visible restart provenance.

This architecture subtracts the most dangerous things in the current system: implicit modes, silent fallback, duplicated kill-switches, static configuration reads in policies, rich-object audit histories, teardown-only persistence, and stdout as the evidence store. It adds only the concepts that correspond directly to the thesis's validity obligations.
