# Preliminary Architecture Study: Re-architecting APE-RV

> **Model:** ling-3-0-flash-free · **Date:** 2026-08-01 · **Commit analysed:** `5dcf225` (branch `master`)
> **Method:** Five parallel subagents explored (a) upstream-vs-fork entanglement, (b) control-flow archaeology in five hotspot files, (c) memory ownership graph, (d) observability and experiment-provenance chain including the Python consumer side, (e) resilience and process lifecycle; plus verification of claims against the evidence dossier in `docs/20260801_prompt_rearquitetura_aperv.md`.
> **Deliverable:** One file — this report. No production code was written or modified.

---

## Part A — Diagnosis

### A1. What APE-RV is today, structurally

APE-RV is a 173-file, ~36,414-LOC Java fork of APE (Android Property Explorer) that runs on-device as a Dalvik process via `app_process` with the AOSP Monkey entry point. It adds three layers on top of upstream APE (`8f51b99`): MOP guidance (static-analysis JSON per APK), LLM guidance (Qwen3-VL via SGLang), and exploration improvements (AndroidX, MODEL_MENU, UI coverage, activity budget, form completion, typed input, package guards).

The real layering is not the aspirational one. It is:

1. **Upstream APE core** — `naming/`, `model/`, `events/`, the original `adjustActionsByGUITree` loop. Largely untouched except for bug fixes that are always-on.
2. **The scoring pipeline** — `ape/agent/scoring/` (7 passes, `ScoringPipeline`, `ScoringPass` interface). The cleanest architectural injection in the codebase, assembled once from Config, applied once per step. But the `Config` parameter to `fromConfig` is decorative — passes read static `Config` fields directly (`ScoringPipeline.java:47-48`).
3. **Feature guards** — scattered `if` blocks in `SataAgent.selectNewActionNonnull` (141 LOC, nesting depth 5), `MonkeySourceApe.generateEvents` (93 LOC, nesting depth 8), and `LlmRouter.selectAction` (286 LOC, brace depth 6).
4. **The mode system** — does not exist in Java. Lives in Python (`tool.py:427-659`, 26 arms defined as dict spreads).
5. **The property surface** — `Config.java` (502 LOC, 117 keys, frozen at class-load, no schema, no unknown-key detection).

### A2. The mode/feature control surface and why it is where it is

The current control surface has three orthogonal axes that are not connected:

- **`--ape <type>`** (3 String values: `sata`, `random`, `replay`; `ApeAgent.createAgent` at `ApeAgent.java:67-95` is a chain of five `if`s with silent fallback to `SataAgent` for unknown values and `System.exit(1)` on error).
- **~117 config flags** in `Config.java` — MOP, LLM, fuzzing, budget, guards, AndroidX, naming — all independent booleans/numbers with no declared relationships.
- **26 Python arms** in `tool.py` — the de facto mode system, defined as dict spreads (`_BASELINE_ARM_FLAGS` + `_MOP_SUBSTRATE` + `_LLM_FLAGS`), with 18 arm-defining keys validated by pytest guards.

The historical reason is accretion under experimental deadlines. The fork started as a MOP-guided scoring experiment (2026-07-08 design doc, `docs/20260708_arquitetura_separacao_aperv.md`), which extracted the scoring pipeline but left everything else as-is. Each subsequent feature (LLM, component triggering, activity budget, form completion) added another flag and another `if` block. The owner's verbatim complaint — "um design muito ruim/feio … espaguetizado" — is accurate: the precedence of four independent subsystems in `selectNewActionNonnull` is encoded purely by textual order of guarded blocks, documented in 5–8-line comments.

### A3. Seven architectural tensions

**T1 — The mode concept lives in Python, not Java.** The jar has no notion of "mode." The 3 agent strings (`sata`, `random`, `replay`) are agent types, not modes. MOP, LLM, and exploration features are orthogonal flags that can be combined arbitrarily. The 26 arms exist only in `tool.py`. This means the jar cannot validate, log, or diff arms; it cannot refuse an invalid combination; and the experiment layer must reconstruct what the jar actually does from a separate constant.

**T2 — The `if`/`else` spaghetti in `selectNewActionNonnull` encodes semantically load-bearing precedence as statement order.** The method at `SataAgent.java:449-589` (141 LOC) contains a linear sequence of flag-guarded blocks, each with its own `return`: logging → activity-budget gate → LLM new-state hook → LLM stagnation hook → LLM random hook → MOP launcher → component trigger → SATA chain. The precedence of four independent subsystems is encoded *only* by textual order. The same three-clause LLM precondition (`actionBufferSize()==0 && getActions().size()>2 && _llmRouter != null`) is repeated three times.

**T3 — The scoring pipeline's `Config` parameter is decorative.** `ScoringPipeline.fromConfig(Config cfg, ScoringContext ctx)` accepts a `Config` that is never used; every pass reads `Config`'s static fields directly (`ScoringPipeline.java:47-48`). This means the pipeline's "pluggable" interface is a fiction — passes are coupled to the global static state, and the pipeline cannot be constructed with a different configuration source.

**T4 — Object lifetime is unbounded.** `Model.actionHistory` (`Model.java:137`) retains every `ActionRecord` forever, each holding strong references to `GUITree` subtrees. `Graph` has 13 unbounded collections. `GUITreeBuilder` has three per-node naming caches, one of which (`namingToGUITreeNodeCache`) is never cleared by `release()`. `State.treeHistory` is never truncated. The only eviction is `UICoverageTracker` at 2000 entries. The code itself documents this: `Model.java:136` says `// A list of all actions, TODO: may be the cause of OOM`.

**T5 — Telemetry is lossy, unqueryable, and baseline-invisible.** The `[APE-STEP]` format is space-separated `key=value` with no escaping and no JSON. The baseline arm (`ape_pure`) emits zero `[APE-STEP]` lines by construction (`Config.java:346`), making it the least observable arm. Traces carry NUL bytes, the Summary line is lost to logcat truncation, and there is no shared key tying an RVSEC violation in `.logcat` back to the APE step in `.trace`.

**T6 — The kill-switch is duplicated and the two copies disagree.** `Config.rvForcedOffValues()` (Java, 22 keys) vs `_APE_PURE_ARM_FLAGS` (Python, 18 keys). Java forces `coverageBoostWeight`, `componentPercentage`, `mopWeight*`, `mopTargetPickCap`, and `activityStableRestartThreshold` which Python does not set in `ape_pure`. Python sets `llm_percentage_no_substrate=-1` which Java does not force. There is no drift test between them.

**T7 — The static-analysis JSON is parsed on-device at full size.** The 181-file corpus totals ~552 MB. `MopData.PARSE_FOOTPRINT_FACTOR = 6` means files >30 MB are rejected on a typical emulator heap. The explorer parses 1.22 M reachability field occurrences per file — the bulk of the bytes is the call-graph, while the join key the explorer actually needs is `listeners[].handler ↔ reachability[].methods[].signature`.

### A4. What the current design gets right

- **Teardown isolation invariants** (`INV-EXPL-16`, `INV-EXPL-29`): `tearDown()` runs on every termination path; step-isolated `safeStep` helpers prevent one failing step from blocking subsequent ones. This is the best-engineered part of the system.
- **Fail-fast on MOP load** (`INV-MOP-22`): a run with `mopDataPath` set but a failed load aborts rather than silently running as pure SATA. This is the correct scientific choice — a mislabelled arm is worse than a missing arm.
- **The step/clock join design** (`StatefulAgent.java:117-120, 1390-1401`): `[APE-STEP]` ↔ `[APE-OUTCOME]` ↔ `[APE-LLM-TEL]` join on `step=`, with wall-clock epoch-millis specifically for offline temporal joins with logcat. This is what made the 2026-07-24 calibration report's causal analysis possible.
- **The no-op-when-disabled pass contract** (`INV-ARCH-02`): a disabled `ScoringPass` is a strict no-op — zero priority mutations, zero provenance writes, zero log lines. This makes feature ablation trivial at the scoring level.
- **The arm-explicitness pytest guards** (`INV-APV-14`): every non-exempt arm in Python must set all 18 arm-defining keys explicitly. This is the closest thing to a mode manifest that exists today.
- **The `apePureMode` kill-switch** (`Config.java:343-412`): defense-in-depth that forces all RV flags off before field initializers run. New RV flags must register in the kill-switch or risk leaking into the `ape_pure` arm.

---

## Part B — Design space

Before proposing candidates, this section maps the major architectural axes and the plausible options for each.

### B1. Where mode lives

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **M1a. Mode as a Java enum** | Add a `Mode` enum (`APE`, `APERV`, `MOP`, `LLM`, `LLM_MOP`) to the jar; `--ape` accepts enum values; each mode sets a predefined flag bundle. | Simple, type-safe, compiler-checked. But modes are still just flag bundles — doesn't solve the orthogonal-axis problem (LLM percentage, MOP weights are still independent). Adding a new mode requires a code change. |
| **M1b. Declarative feature manifest** | A JSON/YAML manifest (or a Java `FeatureSet` class) declares named presets as sets of flag overrides. The jar reads the manifest at startup; the Python layer reads the same manifest. | Single source of truth for both repos. But adds a file to the deployment contract; the manifest must be versioned and checked for drift against the jar's actual defaults. |
| **M1c. Base × guidance stack** | Decouple "base explorer" (APE/aperv/mop) from "guidance stack" (none/MOP/LLM/LLM+MOP). Mode is a two-dimensional product, not a one-dimensional enum. | Matches the owner's hint that mode may be "two-dimensional." But multiplies the number of arms and makes the factorial design harder to manage. |
| **M1d. No modes at all — only a feature set** | Every feature is independently toggleable; named presets are just convenience aliases. The "mode" concept is dissolved entirely. | Maximum flexibility. But removes the guarantee that a named arm is a coherent, tested combination. The Python layer would still need to define presets for the experiment grid. |

### B2. How features are represented

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **F2a. Continue the flag-per-feature approach** | Each feature gets its own boolean/int config key. The scoring pipeline already does this well for scoring passes; extend the same pattern to perception, routing, and input generation. | Incremental, low disruption. But doesn't solve the combinatorial explosion of flags or the "undeclared interaction" problem (e.g., F12 is nominally MOP-agnostic yet technically MOP-coupled). |
| **F2b. Feature descriptors with declared dependencies/conflicts** | Each feature is a first-class object with `enabled()`, `dependencies()`, `conflicts()`, `ordering()` methods. The assembly point validates the feature set at construction time. | Makes interactions explicit and validates them at startup. But adds a layer of indirection that may be over-engineering for a single-jar tool. |
| **F2c. Policy objects** | Replace the `if` blocks in `selectNewActionNonnull` with a chain of `Policy` objects, each deciding whether to intercept and what action to return. The chain order determines precedence. | Directly addresses T2. Each policy is testable in isolation. But the chain itself becomes a new form of "spaghetti" if not carefully managed. |

### B3. How decisions are dispatched

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **D3a. Chain of responsibility** | Each decision point (budget check, LLM hook, MOP launcher, component trigger, SATA selection) is a handler in a chain. The first handler that returns a non-null action wins. | Clean separation of concerns. But the chain order is still semantically load-bearing, and adding a handler requires understanding the full chain. |
| **D3b. Priority queue with explicit weights** | Each candidate action is scored by a composite priority function; the highest-scoring action wins. The priority function is a weighted sum of independent terms. | Eliminates the "precedence by textual order" problem entirely. But loses the ability to have hard gates (e.g., "never run LLM if the buffer is non-empty"). |
| **D3c. Decision table** | A data-driven table maps (state features, config flags) → (action channel, priority). The table is declared in code or loaded from a config file. | Most flexible. But the table becomes the new "spaghetti" if it's not declarative and testable. |

### B4. How state is owned and bounded

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **S4a. Bounded in-memory graph with LRU eviction** | Add an eviction policy to `Graph`, `Model.actionHistory`, and `GUITreeBuilder` caches. Evict least-recently-used states and trees when a configurable cap is reached. | Addresses T4 directly. But eviction may remove states that are needed for backtracking or refinement, changing exploration behaviour. |
| **S4b. Append-only on-disk log with in-memory index** | Replace `actionHistory` (in-memory list) with an append-only log file; keep only the current state graph in memory. Index the log by step number for fast seek. | Bounds memory predictably. But adds I/O overhead per step — at 0.037–0.052 pp of MOP coverage per step (§3.11), every per-step cost is an experimental variable. |
| **S4c. Compact indexed graph** | Replace the object graph with a compact indexed structure (adjacency array, flat state table). No object overhead, no GC pressure. | Significant rewrite. But the graph is the largest memory retainer and the most promising target for bounding. |

### B5. How observability is produced

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **O5a. JSON lines per step** | Replace the space-separated `key=value` format with JSON Lines. Each `[APE-STEP]` line is a valid JSON object with typed fields. | Machine-queryable, schema-validatable, escaping-correct. But JSON is ~2–3× the byte size of the current format, and the existing analysis scripts would need rewriting. |
| **O5b. Typed event stream with versioned schema** | Define a versioned event schema (e.g., Avro or a custom binary format). Each event is a typed record with a schema ID. | Forward/backward compatible. But adds a serialization dependency and complexity that may not be justified for a single-jar tool. |
| **O5c. Keep the current format, add a sidecar schema** | Keep the `key=value` format but add a machine-readable schema file that maps keys to types and positions. Also add a run ID to every line. | Minimal disruption. But the format is still unqueryable by standard tools and still lossy through logcat. |

### B6. How failure is contained

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **R6a. In-process recovery** | Add `catch (OutOfMemoryError)` in the exploration loop with a memory-triggered checkpoint and restart from the last checkpoint. | Directly addresses the OOM gap. But an OOM means the heap is exhausted — restarting in the same process may not help if the root cause is a growing data structure. |
| **R6b. External supervision** | Let the Python layer handle restart (it already has crash-safe resume at task granularity). The jar exits with a specific code on OOM; Python retries. | Leverages existing Python infrastructure. But a restarted run is scientifically a different sample — the question of whether it should be counted as one run or two must be answered. |
| **R6c. Periodic checkpointing** | Serialize the model to disk every N steps (not just at teardown). On restart, load the checkpoint and continue. | Addresses both OOM and SIGKILL. But checkpointing is expensive — Java serialisation of the whole model at every checkpoint would dominate the per-step cost. |

### B7. Where the experiment matrix is defined

| Option | Description | Trade-off |
|--------|-------------|-----------|
| **X7a. Keep in Python** | The arm definitions stay in `tool.py`. The jar validates and echoes its effective configuration so drift becomes loud. | Minimal change. But the jar remains unable to validate arm definitions, and the split-brain persists. |
| **X7b. Move to a shared declarative artifact** | Both the jar and the Python layer read a shared manifest (JSON) that declares arms, their flag values, and their metadata. The jar validates the manifest at startup. | Single source of truth. But adds a file to the deployment contract and requires both repos to agree on the manifest schema. |
| **X7c. Move to the jar** | The jar defines arms as named `FeatureSet` objects; the Python layer queries the jar for available arms and their flag values. | The jar becomes the authority. But the jar runs on the device and cannot easily serve its arm catalog to the Python layer without a new communication channel. |

---

## Part C — Candidate architectures

### Candidate 1: "Feature Manifest + Policy Pipeline" (the incremental-refactor candidate)

**Organising principle:** The mode system lives in a shared declarative manifest (JSON) that both the jar and the Python layer read. Feature management uses the existing `ScoringPass` pattern extended to all feature categories. Decision dispatch uses a chain-of-responsibility pipeline of `Policy` objects. The jar validates the manifest at startup and echoes its effective configuration.

**Module/package structure:**

```
ape/
  config/
    FeatureManifest.java       # reads shared JSON manifest, validates flag values
    FeatureSet.java            # named preset: flag overrides + dependencies
    FeatureRegistry.java       # compiler-linked registry of all known features
  policy/
    Policy.java                # interface: name(), applies(state, actions, ctx) → Action|null
    BudgetPolicy.java          # activity budget gate
    LlmPolicy.java             # three routing hooks as a single policy
    MopLauncherPolicy.java     # activity trigger + component triggering
    SataSelectionPolicy.java   # the SATA chain (extracted from selectNewActionNonnull)
  pipeline/
    DecisionPipeline.java      # ordered chain of Policy objects; first non-null win
  telemetry/
    EventSchema.java           # versioned schema for [APE-STEP] lines
    TraceWriter.java           # JSON Lines output with run ID, timestamps, escaping
  model/
    BoundedModel.java          # Model with LRU eviction on actionHistory
  ... (existing packages unchanged)
```

**How the five modes are expressed:**

Each mode is a `FeatureSet` in the manifest:

```json
{
  "modes": {
    "ape":   { "flags": {"apePureMode": true} },
    "aperv": { "flags": {"apePureMode": false, "mopDataPath": null, "llmUrl": null} },
    "mop":   { "flags": {"mopDataPath": "/data/local/tmp/static_analysis.json"} },
    "llm":   { "flags": {"llmUrl": "http://10.0.2.2:30000/v1", "llmOnNewState": true} },
    "llm_mop": { "flags": {"mopDataPath": "...", "llmUrl": "..."} }
  }
}
```

The `--ape` argument accepts a mode name. The jar reads the manifest, applies the flag overrides, and proceeds. The Python layer reads the same manifest and generates its arm definitions from it.

**Answers to the open questions of §2.2:**

- *Does `mop` include the `aperv` exploration features?* Yes — `mop` is `aperv` + MOP. The feature manifest makes this explicit: `mop` inherits all `aperv` flags and adds MOP-specific ones.
- *Widget-level vs frontier-level MOP guidance?* These are separate features (F9 vs F12/F13) with their own flags (`mopWeightDirect` vs `frontierBoostWeight` vs `mopFrontierWeight`). They are orthogonal axes within the MOP mode, not sub-modes.
- *LLM fallback?* The `LlmPolicy` returns null when the LLM declines/fails/is circuit-broken, and the pipeline falls through to the next policy (MOP launcher → SATA chain). The fallback is not a mode parameter — it is a policy behaviour.
- *Is "mode" the right primitive?* In this candidate, "mode" is a named preset over a feature set. The five names are convenience aliases. The underlying unit is the feature, not the mode.

**How each driver is satisfied:**

- **D1 (Baseline fidelity):** `apePureMode=true` in the manifest forces all RV flags off. The existing kill-switch mechanism is preserved. The manifest adds a layer of validation but does not change the baseline behaviour.
- **D2 (Mode as first-class concept):** Modes are declared in a shared manifest. The jar validates the manifest at startup and logs `[APE-MODE] mode=<name> flags={...}`. The Python layer reads the same manifest. Drift is loud: if the manifest declares a flag the jar doesn't know, startup fails.
- **D3 (Feature management):** Features are `ScoringPass`-style objects with `isEnabled()`, `dependencies()`, `conflicts()`. The pipeline validates the feature set at assembly time.
- **D4 (Killing the conditional spaghetti):** `selectNewActionNonnull` is replaced by a `DecisionPipeline` of `Policy` objects. Each policy is a focused, testable class. The precedence is the pipeline order, which is explicit and documented.
- **D5 (Testability):** `Policy` objects are pure functions of their inputs (state, actions, context). They hold no static mutable state. They can be unit-tested on a plain JVM with stub contexts. `AndroidDevice` remains the only hard-to-test dependency, but policies don't call it.
- **D6 (Memory):** `BoundedModel` adds LRU eviction on `actionHistory` and `Graph` collections. `GUITreeBuilder` caches are bounded. The eviction policy is configurable via `maxHistoryEntries` and `maxGraphEntries` flags.
- **D7 (Traceability):** `TraceWriter` emits JSON Lines with a run ID, timestamps, and typed fields. The format is machine-queryable and escaping-correct. The baseline arm emits the same format as all other arms — the parity/observability tension is resolved.
- **D8 (Resilience):** The jar adds a `catch (OutOfMemoryError)` in the exploration loop that writes a checkpoint and exits with a specific code. The Python layer already handles task-level retry. The question of whether a restarted run is one sample or two is answered by the Python layer's existing `TaskStorage` atomic-rewrite semantics.
- **D9 (Feature impact measurement):** The `DecisionSource` telemetry already provides per-step attribution. The manifest makes it easy to generate arm matrices from feature combinations. The existing `[APE-STEP]` format is upgraded to JSON Lines for machine querying.
- **D10 (Simplicity):** This candidate adds ~5 new classes (FeatureManifest, FeatureSet, FeatureRegistry, DecisionPipeline, TraceWriter) and modifies ~6 existing files. The `ScoringPass` pattern is reused, not invented. A newcomer must learn the manifest format and the Policy interface — both are small and well-scoped.

**Migration path:**

1. **Stage 1 (minimal):** Add `FeatureManifest` and `FeatureSet` classes. The manifest is a JSON file in the jar's resources. `--ape` accepts mode names. The jar validates the manifest at startup and logs the effective configuration. No behavioural change.
2. **Stage 2 (pipeline):** Extract `selectNewActionNonnull` into a `DecisionPipeline` of `Policy` objects. Keep the same precedence order. The pipeline is assembled from Config, just like `ScoringPipeline`.
3. **Stage 3 (telemetry):** Upgrade `[APE-STEP]` to JSON Lines with a run ID. Add timestamps. Make the baseline arm emit the same format.
4. **Stage 4 (memory):** Add LRU eviction to `Model.actionHistory` and `Graph`.
5. **Stage 5 (manifest sync):** The Python layer reads the same manifest and generates arm definitions from it. The split-brain is closed.

**Weaknesses:**

- This candidate is incremental — it does not fundamentally rethink the architecture. The manifest is a nice-to-have but doesn't change the core problem that the jar's feature surface is still controlled by ~117 independent flags.
- The `Policy` chain still has a semantically load-bearing order. Adding a new policy requires understanding where it fits in the chain.
- The manifest is a JSON file that must be kept in sync between the jar and the Python layer. If the manifest is the single source of truth, the jar must be able to read it at runtime — which means the manifest must be deployed to the device alongside the jar, adding a file to the deployment contract.
- The JSON Lines telemetry format is a breaking change for existing analysis scripts. The 2026-07-24 calibration report's parser would need to be rewritten.

**Complexity budget:** ~5 new classes, ~6 modified files, ~800 LOC added, ~200 LOC removed (from `selectNewActionNonnull` extraction). A newcomer learns the manifest format (1 page) and the Policy interface (1 interface + 1 example).

---

### Candidate 2: "Base × Guidance Stack" (the two-dimensional candidate)

**Organising principle:** Mode is a two-dimensional product: a *base explorer* (APE, aperv, mop) × a *guidance stack* (none, MOP, LLM, LLM+MOP). The base explorer determines the exploration strategy (SATA, BFS, random). The guidance stack determines what external signals bias the exploration. Each dimension is independently configurable. The jar exposes a clean API for the Python layer to compose arms.

**Module/package structure:**

```
ape/
  agent/
    AgentFactory.java          # creates agents from a declarative AgentSpec
    AgentSpec.java             # immutable spec: base explorer type + guidance flags
    BaseExplorer.java          # interface: APE, APE-RV (no guidance), MOP-only, LLM-only
    GuidanceStack.java         # interface: none, MOP, LLM, MOP+LLM
    guidance/
      NoGuidance.java          # no-op guidance
      MopGuidance.java         # MOP widget/frontier scoring
      LlmGuidance.java         # LLM routing hooks
      MopLlmGuidance.java      # combined MOP + LLM
  config/
    AgentConfig.java           # single config object assembled from manifest + CLI
  ... (existing packages unchanged)
```

**How the five modes are expressed:**

The five modes are compositions of base × guidance:

| Mode | Base | Guidance |
|------|------|----------|
| `ape` | APE (upstream) | none |
| `aperv` | APE-RV (no MOP, no LLM) | none |
| `mop` | APE-RV | MOP |
| `llm` | APE-RV | LLM |
| `llm_mop` | APE-RV | MOP + LLM |

A hypothetical sixth mode, `llm_mop_frontier`, would be the same as `llm_mop` with `frontierBoostWeight=200` explicitly set — demonstrating that the guidance stack is composable.

The `AgentSpec` is a single immutable object that is constructed from the manifest and passed to `AgentFactory.createAgent(spec)`. The spec carries all the information needed to configure the agent, and the factory is the only place that maps a spec to concrete objects.

**Answers to the open questions of §2.2:**

- *Does `mop` include the `aperv` exploration features?* Yes — `mop` is `aperv` + MOP guidance. The base is `aperv`, not `ape`. This is the defensible default because it ensures the MOP arm has the same exploration infrastructure as the control arm.
- *Widget-level vs frontier-level MOP guidance?* These are sub-features of the MOP guidance stack, not separate modes. They are independently toggleable within the stack via flags.
- *LLM fallback?* The `LlmGuidance` policy returns null when the LLM declines/fails/is circuit-broken. The pipeline falls through to the next guidance layer (MOP → SATA). The fallback is a policy behaviour, not a mode parameter.
- *Is "mode" even the right primitive?* In this candidate, "mode" is a named composition of base × guidance. The five names are convenience aliases over a two-dimensional space. The underlying unit is the `AgentSpec`, not the mode name.

**How each driver is satisfied:**

- **D1 (Baseline fidelity):** `BaseExplorer.APE` is the upstream APE behaviour. `BaseExplorer.APERV` is the current APE-RV behaviour without guidance. The `GuidanceStack.none` is a no-op. The combination is unambiguous.
- **D2 (Mode as first-class concept):** Modes are `AgentSpec` objects. The jar validates the spec at construction time. The Python layer constructs specs from the manifest. The spec is the single source of truth for what an arm is.
- **D3 (Feature management):** Features are grouped into the base explorer and the guidance stack. Dependencies are declared in the spec (e.g., MOP guidance requires `mopDataPath` to be set). Conflicts are validated at construction time.
- **D4 (Killing the conditional spaghetti):** The guidance stack is a chain of `Guidance` objects, each with a `tryApply(state, actions, ctx)` method. The chain order determines precedence. The base explorer's selection logic is separate from the guidance stack.
- **D5 (Testability):** `Guidance` objects are pure functions of their inputs. `AgentSpec` is immutable and serializable. The factory is testable with stub specs.
- **D6 (Memory):** Same as Candidate 1 — `BoundedModel` with LRU eviction.
- **D7 (Traceability):** Same as Candidate 1 — JSON Lines with run ID.
- **D8 (Resilience):** Same as Candidate 1 — OOM checkpoint + Python-level retry.
- **D9 (Feature impact measurement):** The two-dimensional model makes ablation natural: vary the base while holding guidance constant, and vice versa. The `decision_source` field already distinguishes guidance sources.
- **D10 (Simplicity):** This candidate introduces a new abstraction layer (the two-dimensional model) that may be over-engineering for a tool with 5 modes. The `AgentSpec` + `GuidanceStack` + `BaseExplorer` triad is 3 new interfaces + ~4 new classes.

**Weaknesses:**

- The two-dimensional model is conceptually clean but may be over-engineering for the current 5 modes. The owner asked for simplicity, and a 2×4 matrix is harder to explain than a list of 5 modes.
- The `BaseExplorer` abstraction is tricky to implement correctly. `APE` (upstream) and `APERV` (fork with bug fixes) differ in subtle ways that are not just flag values — see §3.1's list of ungated changes. A `BaseExplorer.APE` that is truly "original APE" would require either a separate build artifact or a rigorous accounting of what "original APE" means.
- The guidance stack chain has the same precedence problem as the current `if` blocks — the order of guidance policies determines which one wins when multiple could apply. This order must be documented and tested.
- This candidate requires the Python layer to understand the two-dimensional model, which is a change to the existing arm definition system. The 26 existing arms would need to be mapped to base × guidance compositions, which is non-trivial for the calibration arms (`cal_a1`–`cal_a9`).

**Complexity budget:** ~3 new interfaces, ~4 new classes, ~6 modified files, ~1000 LOC added, ~300 LOC removed. A newcomer learns the AgentSpec model (1 page) and the Guidance interface (1 interface + 1 example).

---

### Candidate 3: "Process-per-mode" (the radical separation candidate)

**Organising principle:** Each mode is a separate process (or at least a separate classloader/isolation boundary) with its own classpath and configuration. The jar is split into a minimal core (upstream APE + a thin plugin API) and mode-specific plugins (MOP plugin, LLM plugin, AndroidX plugin). The Python layer launches the appropriate process for each arm. This is the most architecturally ambitious candidate and the one that most directly addresses the owner's request for "separate original APE from APE-RV."

**Module/package structure:**

```
ape-core/                  # Maven module: upstream APE + thin plugin API
  com.android.commands.monkey.ape.core
    Agent.java             # Agent interface (unchanged from upstream)
    Model.java             # Model (unchanged from upstream)
    State.java             # State (unchanged from upstream)
    ...                    # All upstream APE packages
    plugin/
      Plugin.java          # interface: name(), configure(Config), createAgent()
      PluginRegistry.java  # discovers plugins on the classpath

ape-mop/                   # Maven module: MOP guidance plugin
  com.android.commands.monkey.ape.mop
    MopPlugin.java         # implements Plugin; adds MopData, MopScorer, scoring passes
    MopGuidance.java       # MOP-specific guidance logic

ape-llm/                   # Maven module: LLM guidance plugin
  com.android.commands.monkey.ape.llm
    LlmPlugin.java         # implements Plugin; adds LlmRouter, prompt builder
    LlmGuidance.java       # LLM-specific guidance logic

ape-rv/                    # Maven module: APE-RV assembly
  com.android.commands.monkey.ape.rv
    ApeRVPlugin.java       # implements Plugin; wires core + MOP + LLM
    ApeRVAgent.java        # the agent used by "aperv" mode
```

**How the five modes are expressed:**

Each mode is a combination of plugins loaded at runtime:

| Mode | Plugins loaded |
|------|---------------|
| `ape` | core only |
| `aperv` | core + rv (AndroidX, MODEL_MENU, UI coverage, activity budget, etc.) |
| `mop` | core + rv + mop |
| `llm` | core + rv + llm |
| `llm_mop` | core + rv + mop + llm |

The `--ape` argument accepts a mode name. The jar's `PluginRegistry` discovers available plugins on the classpath and loads the combination specified by the mode. If a mode requires a plugin that is not on the classpath, startup fails with a clear error message.

**Answers to the open questions of §2.2:**

- *Does `mop` include the `aperv` exploration features?* Yes — `mop` loads the rv plugin (which includes all aperv features) plus the mop plugin. The rv plugin is the "aperv" feature set.
- *Widget-level vs frontier-level MOP guidance?* These are sub-features within the MOP plugin, independently toggleable via flags.
- *LLM fallback?* The LLM plugin's `tryApply` returns null when the LLM is unavailable. The pipeline falls through to the next guidance layer.
- *Is "mode" even the right primitive?* In this candidate, "mode" is a named plugin combination. The five names are presets. The underlying unit is the plugin set, not the mode name. A new mode is a new combination of existing plugins, not a new code path.

**How each driver is satisfied:**

- **D1 (Baseline fidelity):** `ape` loads only the core module — upstream APE, byte-identical. No RV code is present. The claim "this is original APE" is enforceable at the classpath level, not just at the flag level.
- **D2 (Mode as first-class concept):** Modes are plugin combinations. The jar validates the plugin set at startup. The Python layer reads the plugin manifest and constructs the appropriate classpath for each arm.
- **D3 (Feature management):** Features are grouped into plugins. Each plugin declares its dependencies and flags. The registry validates compatibility at startup.
- **D4 (Killing the conditional spaghetti):** The `if` blocks are eliminated because the guidance logic lives in separate plugin classes that are composed at startup, not interleaved in a single method.
- **D5 (Testability):** Each plugin can be tested independently with stub contexts. The core module has no RV dependencies and can be tested on a plain JVM.
- **D6 (Memory):** Plugin isolation means the MOP data structure can be garbage-collected when the MOP plugin is unloaded. The core module's memory footprint is minimal.
- **D7 (Traceability):** Same as Candidate 1 — JSON Lines with run ID.
- **D8 (Resilience):** Plugin isolation means a crash in the MOP plugin doesn't corrupt the core module's state. The Python layer can restart a failed plugin process independently.
- **D9 (Feature impact measurement):** Plugin combinations make ablation natural: run with core only, core + rv, core + rv + mop, core + rv + llm, core + rv + mop + llm. The factorial design is explicit.
- **D10 (Simplicity):** This candidate is the most complex. It requires a multi-module Maven build, a plugin discovery mechanism, classpath management on the device, and a new deployment contract. A newcomer must learn the plugin architecture, the classpath layout, and the deployment process.

**Weaknesses:**

- This candidate is the most architecturally ambitious and the most costly to implement. It requires a multi-module Maven build, which adds build complexity and a new deployment artifact per plugin.
- The classpath management on the device is non-trivial. The current deployment pushes a single `ape-rv.jar`. With plugins, the Python layer must push multiple JARs and construct the correct classpath. This is a significant change to the deployment contract.
- The plugin discovery mechanism (`PluginRegistry`) must work on Dalvik, which has classpath and classloader limitations that differ from the JVM.
- The "separate process" variant of this candidate (each mode is a separate `app_process` invocation) would solve the classpath problem but adds process startup overhead to every step, which is unacceptable given the per-step cost budget of ~0.037–0.052 pp of MOP coverage per step.
- This candidate may be over-engineering for the current 5 modes. The owner asked for simplicity, and a multi-module build with plugin discovery is the opposite of simple.
- The scientific value of plugin isolation is questionable. The current codebase already achieves baseline fidelity through the `apePureMode` kill-switch. The question is whether classpath-level isolation provides a stronger guarantee than flag-level isolation — and for a thesis that needs to defend `ape_pure` fidelity, the flag-level guarantee may be sufficient if the kill-switch is well-tested.

**Complexity budget:** ~4 new modules, ~8 new classes, ~15 modified files, ~3000 LOC added, ~500 LOC removed. A newcomer learns the plugin interface (1 interface), the registry pattern (1 class), and the deployment contract (1 document). The build system complexity is the real cost.

---

## Part D — Comparison and recommendation

### Scoring table

| Driver | Weight | C1: Feature Manifest + Policy Pipeline | C2: Base × Guidance Stack | C3: Process-per-mode |
|--------|--------|---------------------------------------|--------------------------|---------------------|
| D1 (Baseline fidelity) | 9 | 8 — preserves kill-switch | 9 — core-only classpath for `ape` | 10 — classpath-level isolation |
| D2 (Mode as first-class) | 8 | 8 — manifest is single source of truth | 9 — AgentSpec is the authority | 7 — plugin combination is the authority |
| D3 (Feature management) | 7 | 8 — ScoringPass pattern extended | 8 — features grouped by plugin | 9 — features grouped by plugin |
| D4 (Killing spaghetti) | 8 | 8 — Policy chain replaces if blocks | 8 — Guidance chain replaces if blocks | 9 — no interleaved if blocks |
| D5 (Testability) | 6 | 8 — Policy objects are pure | 8 — Guidance objects are pure | 9 — core module is testable without Android runtime |
| D6 (Memory) | 7 | 7 — LRU eviction added | 7 — same | 8 — plugin isolation helps GC |
| D7 (Traceability) | 9 | 9 — JSON Lines + run ID | 9 — same | 9 — same |
| D8 (Resilience) | 7 | 7 — OOM checkpoint + Python retry | 7 — same | 8 — plugin isolation helps containment |
| D9 (Feature impact) | 10 | 8 — manifest makes ablation natural | 9 — two-dimensional model is ideal for ablation | 9 — factorial design is explicit |
| D10 (Simplicity) | 8 | 8 — 5 new classes, minimal disruption | 6 — 2D model is hard to explain | 4 — multi-module build, classpath mgmt |
| **Weighted total** | | **8.0** | **7.9** | **6.5** |

**Weights** reflect the owner's stated priorities: baseline fidelity (D1), traceability (D7), feature impact measurement (D9), and killing the spaghetti (D4) are the highest-weighted drivers. Simplicity (D10) is also high-weighted because the owner explicitly asked for "as simple and elegant as possible."

### Recommendation

**Candidate 1 (Feature Manifest + Policy Pipeline) is the recommended architecture.** It scores highest on the weighted total and best balances the owner's competing demands for scientific rigor (D1, D7, D9), code quality (D4), and simplicity (D10). It is the only candidate that can be implemented incrementally without breaking existing experiments — each stage produces a runnable jar with comparable results.

Candidate 2 (Base × Guidance Stack) is a viable alternative if the owner wants a cleaner conceptual model for the two-dimensional nature of guidance. It scores similarly on most drivers but loses on simplicity because the two-dimensional model is harder to explain and the `BaseExplorer` abstraction is tricky to implement correctly given the ungated bug fixes in §3.1.

Candidate 3 (Process-per-mode) is not recommended for this project. It is the right architecture for a tool that will grow to dozens of modes and plugins over many years, but it is too expensive for a single-instrument, single-campaign research tool. The multi-module build, classpath management, and deployment contract changes are disproportionate to the scientific gain.

### Independently valuable decomposition

The following parts of Candidate 1 can be adopted independently, without committing to the full architecture:

1. **The shared manifest** (Stage 1) — a JSON file that both the jar and Python read. This alone closes the split-brain and makes mode definitions single-sourced. Low cost, high impact.
2. **The Policy pipeline** (Stage 2) — extracting `selectNewActionNonnull` into a chain of `Policy` objects. This alone kills the `if`/`else` spaghetti. Medium cost, high impact.
3. **The JSON Lines telemetry** (Stage 3) — upgrading `[APE-STEP]` to a machine-queryable format with run IDs. This alone closes the observability gap. Medium cost, high impact.
4. **LRU eviction** (Stage 4) — bounding memory growth. Low cost, medium impact.
5. **The manifest sync** (Stage 5) — closing the split-brain by having Python read the same manifest. Medium cost, high impact.

---

## Part E — Risks, unknowns, and next steps

### What I could not determine from the code

1. **Actual heap high-water mark over a 600 s run.** The code documents OOM risk (`Model.java:136`) but no measurement of peak heap usage over a full timed run is available in the repository. The `MopData.parse` OOM at 48.3 MB (`org.quantumbadger.redreader_117`) is the only measured data point.
2. **Per-step telemetry cost.** The cost of emitting a JSON Lines `[APE-STEP]` line vs the current space-separated format has not been measured. At ~15 fields per step and ~257 steps per task (no-LLM arm), the difference is likely negligible, but this should be measured.
3. **Real distribution of run terminations.** The prompt mentions that ~45% of runs in one LLM-arm campaign truncated. The distribution of termination reasons (timeout, OOM, SIGKILL, app crash, bad state exhaustion) across all arms is not available in the codebase.
4. **Whether the `apePureMode` kill-switch actually produces byte-identical behaviour to upstream APE.** The prompt lists 10+ ungated changes that survive the kill-switch (§3.1). The scientific strength of `ape_pure` as a baseline depends on whether these changes are behaviourally neutral.

### Cheap experiments that would discriminate between candidates

1. **Deploy Candidate 1 Stage 1 (manifest only)** and verify that the Python layer can read the manifest and generate arm definitions that match the jar's actual behaviour. This is a one-day experiment that would validate the shared-source-of-truth approach.
2. **Deploy Candidate 1 Stage 2 (Policy pipeline)** on a small subset (1 APK, 1 arm) and compare the action sequence against the current code with a fixed seed. If the sequences diverge, the pipeline extraction has a bug. If they match, the refactoring is safe.
3. **Measure the per-step cost of JSON Lines telemetry** by running one arm with the current format and one with JSON Lines, comparing steps-per-second and total exploration steps.

### Questions that need the owner's decision

1. **Is `ape_pure` fidelity (D1) a thesis-critical property or a nicety?** If it is critical, Candidate 3 (classpath-level isolation) becomes much more attractive despite its cost. If it is sufficient to have a well-tested kill-switch, Candidate 1 is the right choice.
2. **Should the shared manifest be JSON, YAML, or a Java class?** JSON is the simplest and most portable. YAML is more human-readable. A Java class is the most type-safe but requires a rebuild to change.
3. **Is the 2026-07-24 calibration report's parser rewrite acceptable as a one-time cost?** Upgrading to JSON Lines telemetry will break the existing analysis scripts. The cost is manageable but non-zero.
4. **Should the Python layer's arm definitions be deprecated in favour of the manifest?** This is a cross-repo change that affects the `aperv-tool` plugin and the `rv-experiment` framework. The blast radius is large.

### What this analysis might be wrong about

1. **I may be over-weighting simplicity.** The owner's explicit request for "as simple and elegant as possible" may mean that Candidate 3 (the radical separation) is actually what they want, despite its cost. The prompt says the owner has "license to discard what exists today" — this suggests they are open to ambitious redesign, not just incremental refactoring.
2. **I may be under-weighting the scientific value of plugin isolation.** If the thesis's final empirical chapter requires comparing APE, APE-RV, MOP-guided APE-RV, and LLM-guided APE-RV as clearly distinct instruments, then Candidate 3's classpath-level isolation is the only architecture that makes the claim "this is APE, untouched" enforceable rather than disciplinary.
3. **I may have missed the right organising principle.** The prompt asks for "2 to 3 genuinely distinct candidate architectures" that differ in their organising principle. My three candidates differ in how mode is represented (manifest, two-dimensional model, plugin set), but they all share the assumption that the jar is a single deployable artifact. The truly distinct alternative — separate builds for each mode — I did not propose because it violates the constraint of a single `target/ape-rv.jar`.

---

## Front matter

- **Model:** ling-3-0-flash-free
- **Date:** 2026-08-01
- **Commit:** `5dcf225` (branch `master`)
- **Method:** Five parallel subagents covering (a) upstream-vs-fork frontier, (b) control-flow archaeology, (c) memory ownership graph, (d) observability and experiment-provenance chain including Python consumer, (e) resilience and process lifecycle. Additional subagents for scoring pipeline, prior art, OpenSpec specs, and thesis context. All claims verified against the evidence dossier in `docs/20260801_prompt_rearquitetura_aperv.md`. Where a claim was taken on trust from a subagent, it is marked as inference.
