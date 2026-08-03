# Design: rearch-02-runspec

## Context

Source of truth: `docs/analise_fable-selecao.md` rev. 3 (Sec. 6.2 `RunSpec`, Sec. 6.4 `RunContext`, Sec. 6.6 deletion table, Sec. 12 owner decisions D1/D6 — **final, do not reopen**). This is stage 2 of 7 of the "Disposable Run Kernel" re-architecture (`docs/plans/20260802_rearchitecture_roadmap.md`), gated by the `rearch-01-parity-oracle` goldens. **One ordered Python edit** (D-4): `tool.py` stops pushing `ape.apePureMode`, a key this change retires but which 23 of the 29 arms currently set to `false`, and that edit lands *before* the stage-2 jar. Apart from that single key, `tool.py` keeps pushing exactly the properties it pushes today, and the four campaign presets (`aperv`, `mop`, `llm`, `llm_mop`, as expressed by the `sata`/`sata_mop_widget`/`sata_llm`/`sata_mop_llm` arm dicts) must run unchanged.

Current state, verified at HEAD `5dcf225` (file:line):

1. **Config is a silent global authority** (V7): `Config.java` declares **113 `public static` fields** (108 final + 5 non-final), loaded in a static block (`Config.java:32-44`) from `/data/local/tmp/ape.properties` then `/sdcard/ape.properties`, over `System.getProperties()` as defaults. The numeric getters swallow `NumberFormatException` with empty catch blocks (`Config.java:448-482`); `getBoolean` (`:439-446`) has **no error path at all** — any typo degrades to `false` (finding 3.3-2). There is no unknown-key detection of any kind.
2. **Kill-switch as string registries** (V8): `forceApePureModeInto` (`Config.java:403-412`) overwrites the `Properties` before field initializers run; the registries are `rvForcedOffValues()` = 27 keys (`:343-364`), `rvUnsetKeys()` = 2 (`:366-370`), `rvExemptReasons()` = 21 (`:372-398`) — string literals the compiler cannot bind to the fields they name. Collateral (V25): `apePureMode` silently forces `ape.activityStableRestartThreshold=Integer.MAX_VALUE` (`:362`).
3. **Silent agent fallback** (V9): `ApeAgent.createAgent` (`ApeAgent.java:68-96`) falls through to `new SataAgent` at `:95` for any unknown `ape.agentType` value — `--ape bfs`, `--ape mop`, or a typo all silently run SATA. `System.exit(1)` exists only for replay-without-log (`:91`). Worse (finding 3.2, "orthogonality" inverted): `--ape` writes into the same `Properties` (`Monkey.java:924` `Config.set("ape.agentType", ...)`), so a stray `/sdcard/ape.properties` can swap the agent type.
4. **Persistence broken by construction** (V14, finding 3.3-6): `StatefulAgent.saveGraph` (`StatefulAgent.java:1855-1866`) writes the `Model` (`oos.writeObject(model)`, `sataModel.obj`); `Graph.readGraph` (`Graph.java:1166-1174`) casts to `Graph`, swallows the `ClassCastException` (`catch (Exception e)`), and returns `new Graph()` — a `--ape-model` run would silently start from zero.
5. **`/sdcard` behavioral inputs** (V22): `GUITreeBuilder.java:91` reads `/sdcard/ape.xpath` in a static block; `XPathActionController.java:52` reads `/sdcard/ape.xpath.actions` in a static block (consumed via `StatefulAgent.java:480-482` behind `Config.enableXPathAction`); `StringCache.java:74-87` reads `/sdcard/ape.strings` in a static initializer that **throws `RuntimeException` on read failure**. No arm uses any of them; `tool.py` never pushes them. Owner decision D6: remove.
6. **Seedability hole** (V23): `StringCache.nextString()` draws its list index from `ThreadLocalRandom.current()` (`StringCache.java:118`) — outside the seeded `RandomHelper` stream (INV-EXPL-14).
7. **Teardown chain today** (`StatefulAgent.java:1802-1814`): `llmSummary → superTearDown → coverageDump → saveGraph → saveActionHistory → actionCounters → activityNodes → namingDump → modelCounters`, each in `safeStep`. The coverage dump precedes the model serialization (INV-COV-10, telemetry-proof-llm-efficacy A10) — that ordering property must survive `saveGraph`'s removal.
8. **Build provenance stamp does not exist.** The gh14 `BuildInfo`/`[APE-BUILD]` change was **archived without implementation** (commit `6b7b96f`, "discard build-provenance-stamp before implementation" — judged redundant with build-time provenance in rvsec B-1). The rev. 3 report re-requires a jar version/build-hash *inside the trace* (`RUN_START`, Sec. 6.2, test 9.6; the gh71 stale-jar incident — MOP boost 0× in 147k evaluations — is the motivating case). This change therefore (re)introduces the stamp; the gh14 archived design is reused as the mechanism.
9. **Python contract** (`rvsec/rv-android/.../aperv/tool.py`): `APERV_PROPERTY_MAPPING` maps 52 Python keys to `ape.*` keys (`tool.py:77-165`); `_push_properties` writes only keys present in both the arm dict and the mapping (`:1154-1169`), plus `ape.mopDataPath` when the static-analysis JSON was pushed. The seed travels as Monkey `-s` (`:1380-1382`), never in `ape.properties`. No arm pushes a preset name, a run id, or any `/sdcard/ape.*` input file. The dead key `mop_weight_activity → ape.mopWeightActivity` is mapped (`:97`) but **set by no arm** (finding 3.3-7).

   `ape_pure_mode → ape.apePureMode` is the opposite case and the two must not be conflated. It is mapped (`:120`); `_BASELINE_ARM_FLAGS` sets it to `False` (`:256`), which every arm that spreads that constant inherits — **23 of the 29 arms, including all four campaign arms**; `_APE_PURE_ARM_FLAGS` sets it to `True` (`:280`); and it is a member of `ARM_DEFINING_KEYS` (`:175`), whose INV-APV-13/14 guard tests require every member to be present in the mapping and set explicitly by every non-exempt variant. `_push_properties` writes one line per key present in both the arm dict and the mapping, with no value filter (`:1159-1168`). Retiring the key on the jar side alone would therefore abort every campaign arm before step 1 — which is why stage 2 carries the Python edit (D-4).

Constraints: R1–R9 (report Sec. 2), especially R5 (total fail-fast config), R6 (observable determinism), R3 (no read-back). P1/P3/P4. Dalvik/`app_process`, Java 11 — no records, no sealed classes, no external dependencies.

## Architecture

```text
Monkey.main → processOptions (--ape / -s / --running-minutes; --ape-model DELETED)
      │
      ▼  (mUseApe branch of Monkey.run, before any agent/source construction)
RunSpec.resolve(loaded Properties entries, CLI values)
      │   validates: known keys, types, feature dependencies, combinations
      │   FAIL → [APE-RUNSPEC-ABORT] line + nonzero exit, before step 1
      ▼
BuildInfo (generated constants: GIT_SHA, JAR_BUILT)
      │
      ▼
RUN_START  ── single JSON object line, first APE-RV record of the trace
      │       (effective plan + digest + properties digest + build stamp)
      ▼
RunContext (RunSpec, runId, seeded RNG)  ──►  MonkeySourceApe → ApeAgent.createAgent(spec.agentType)
      │                                              (no fallback: type already validated)
      ▼
exploration loop (unchanged this stage) … tearDown WITHOUT saveGraph:
  llmSummary → superTearDown → coverageDump → saveActionHistory → counters → naming → modelCounters
```

`Config` survives as a **loading detail**: the two-file property load and the typed getters remain the mechanism by which values are read, but `RunSpec.resolve` is the only component allowed to interpret them into behavior-defining decisions, and it runs first. The ~100 untouched static-final read sites keep working because both they and `RunSpec` read the same validated `Properties` — stage 3 migrates the read sites themselves.

### Key Components

| Component | Responsibility | Input | Output |
|-----------|---------------|-------|--------|
| `RunSpec.resolve(props, cli)` | one-time plan resolution + total validation | loaded `Properties` entries (file-only view), CLI values | immutable `RunSpec` or `RunSpecException` |
| `Feature` (enum) | key ownership, activation predicate, declared dependencies, neutral values — metadata as data | `ape.*` key set | derived `Set<Feature>` + per-key classification |
| `Presets.resolve(name)` | jar-resident presets `aperv`/`mop`/`llm`/`llm_mop` | preset name | base key/value vector (overrides applied on top) |
| `RunSpecEcho.emit(spec, buildInfo)` | level-0 `RUN_START` line (single JSON object) | `RunSpec`, `BuildInfo` | one trace line, write-only |
| `RunContext` | owner of per-run mutable state (stage-2 scope: `RunSpec`, run id, seeded RNG) | `RunSpec`, seed | accessors for the migrated read sites |
| `BuildInfo` (generated) | build provenance constants | Maven filtering at build time | `GIT_SHA`, `JAR_BUILT` |
| `ApeAgent.createAgent` | agent construction from validated type | `RunSpec.agentType` | `SataAgent`/`RandomAgent`/`ReplayAgent` — no fallback path |
| `StringCache` | run-scoped string pool, seeded draws | on-screen text only | `nextString()` on the seeded stream |

## Mapping: Spec -> Implementation -> Test

| Requirement / Invariant | Implementation | Test |
|-------------|---------------|------|
| run-spec: Single Resolution at Bootstrap (INV-RUN-01) | `RunSpec.resolve` called once in `Monkey.run` before `MonkeySourceApe` | `RunSpecResolveTest` (JVM) |
| run-spec: Feature Dependency Validation (INV-RUN-05) | `Feature` enum metadata + `RunSpec` validator | `FeatureDependencyTest` |
| run-spec: Presets in the Jar | `Presets.resolve` | `PresetsTest` (content vs. the four arm vectors) |
| run-spec: Explicit-Key Resolution (no preset key) | `RunSpec.resolve` default path | `RunSpecCompatTest` (fixture = `_push_properties` output per arm) |
| run-spec: Total Fail-Fast (INV-RUN-02) | validator + `Monkey.run` abort path | `RunSpecAbortTest` (unknown key / bad type / dependency / combination / retired key) |
| run-spec: RUN_START echo (INV-RUN-03/04) | `RunSpecEcho` + minimal JSON serializer | `RunStartEchoTest` (content reconstructs the arm; single line; escaping) |
| run-spec: Build Provenance Stamp | `BuildInfo` template + pom plugins | `BuildInfoTest` + dex `strings` check |
| run-spec: RunContext ownership (INV-RUN-06/07) | `RunContext` + removal of `/sdcard` readers | `StringCacheSeededTest`; grep-guard test for forbidden paths |
| exploration: Strategy Selection (validated) | `createAgent(spec)` rewrite | `RunSpecAbortTest` (`--ape bfs` aborts) |
| exploration: Output Persistence (saveGraph removed, INV-COV-10 restated) | `StatefulAgent.tearDown` chain edit | `TearDownOrderTest` (existing, updated) |
| model: no deserialization / no XPath injection | delete `Graph.readGraph`, `--ape-model`, `xpathaction` package | compile + absence guard |
| heuristic-input: seeded generation (INV-INP-07) | `StringCache.nextString` on `RandomHelper` | `StringCacheSeededTest` |
| ui-tree: no XPathlet overlay | delete `GUITreeBuilder` static overlay block | compile + absence guard |
| scoring-pipeline: kill-switch removed | delete registries + `forceApePureModeInto` + `apePureMode` field | `ConfigTest` update; `RunSpecAbortTest` (retired key) |
| Gate: per-preset parity | — | rearch-01 golden suite green per preset |

## Goals / Non-Goals

**Goals:**

- One immutable, validated, echoed plan per run; everything invalid aborts before step 1 (R5).
- Presets resident in the jar; the stage-2 jar deployable against a Python side that changed by exactly one key (D-4).
- Purity as structural absence (a feature not in the plan does not exist), replacing the three string registries and the `apePureMode` Properties overwrite.
- D6 executed: no `/sdcard/ape.xpath`, `/sdcard/ape.xpath.actions`, `/sdcard/ape.strings`; input-string draws on the run's seeded RNG (V23 closed).
- Legacy persistence protocol deleted (V14); teardown coverage-dump ordering preserved (INV-COV-10).
- Level-0 `RUN_START` echo (D1): write-only provenance; the line alone reconstructs the arm.

**Non-Goals:**

- No `DecisionPipeline`, no relocation of episode state, no `ScoringPipeline` injection (stage 3).
- No NDJSON step telemetry, no `RUN_END`, no heartbeat, no removal of `[APE-STEP]`/`action-history.log`/`produce.log`/`consume.log`/`sataTimeline.vis.js` (stage 4). `RUN_START` is the only new trace record.
- No Python changes beyond the single `ape_pure_mode` removal that the retired key forces (D-4): the arm-definition contract, the `preset + overrides` hand-off, the `bfs`/`dfs` variants and the `ape_pure` arm itself all remain stage-5 work. No echo-vs-intention validation ever (D1 is level 0, definitive).
- No memory work (stage 6), no static-artifact compaction (stage 7).
- No migration of the ~100 untouched static-final `Config` read sites (stage 3); only the sites this change must touch move to `RunContext`.

## Decisions

### D-1 — `RunSpec` shape (report Sec. 6.2, literal)

Java 11, hand-rolled immutability: `final` class, all fields `final`, private constructor, static factory `resolve`. Nested parameter holders are also final classes:

- `ExplorationParams` — always present: epsilon family, throttles, restart thresholds, caps (`backMenuPickCap`, `mopTargetPickCap` lives in `MopParams`), fuzzing, guards, tree flags, budget flags — i.e., every key not owned by an optional feature.
- `MopParams` — `null` ⇔ `!features.contains(Feature.MOP)`: `mopDataPath`, the four weights, `mopTargetPickCap`, `mopStrictPackageMatch`, `mopActivitySourceComponents`, frontier weights, trigger cadence/cap.
- `LlmParams` — `null` ⇔ `!features.contains(Feature.LLM)`: url, model, sampling, timeout, percentages, prompt variant, J1b/J1c knobs.
- `TelemetryParams` — step-telemetry gate (stage 4 will grow it).
- `presetName` — `"aperv" | "mop" | "llm" | "llm_mop"` when `ape.preset` was given, else `"explicit"` (informative only).
- `seed` (from Monkey `-s`, same value that seeds `RandomHelper`), `runId` (see D-7), `agentType` (validated, see D-5), `features` (unmodifiable `EnumSet` copy), `digest` and `propertiesDigest` (see D-6).

No setters, no mutable collections exposed, defensive copies at the boundary. `equals`/`hashCode` by value (used by tests).

### D-2 — `Feature` enum: metadata as data (kills the three registries)

Each constant declares, as constructor data the compiler binds: its **activation rule** (a key + the value shape that turns it on, or a derived predicate such as "`ape.mopDataPath` present"), its **owned sub-parameter keys**, its **declared dependencies** (`EnumSet<Feature>`), and its **neutral values** (the off/zero/sentinel value of each owned key). Roster (grounded in the current gates):

| Feature | Activation | Depends on |
|---|---|---|
| `MOP` | `ape.mopDataPath` present | — |
| `WTG` | `ape.mopWeightWtg != 0` | `MOP` |
| `MENU_GATEWAY` | `ape.mopWeightOpenMenu > 0` | `MODEL_MENU`, `MOP` |
| `FRONTIER` | `ape.frontierBoostWeight > 0` | `MOP` |
| `MOP_FRONTIER` | `ape.mopFrontierWeight > 0` | `MOP` |
| `ACTIVITY_TRIGGER` | `ape.activityTriggerEnabled=true` | `MOP` |
| `COMPONENT_TRIGGER` | `ape.componentPercentage > 0` | `MOP` |
| `MOP_ACTIVITY_SOURCE` | `ape.mopActivitySourceComponents=true` | `MOP` |
| `LLM` | `ape.llmUrl` present | — |
| `LLM_NEW_STATE` / `LLM_STAGNATION` | respective key `=true` | `LLM` |
| `LLM_RANDOM` | `ape.llmPercentage > 0` | `LLM` |
| `MODEL_MENU`, `FORM_COMPLETION`, `STEP_TELEMETRY`, `LEAST_VISITED_TIEBREAK`, `TREE_ENHANCEMENTS`, `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`, `TYPED_FUZZ` (`fuzzInputTyped`), `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD`, `COVERAGE_BOOST`, `FUZZING` | their boolean/weight key | — |

The exact roster is fixed at apply time against the full 113-field inventory; the design rule is total: **every `ape.*` key is owned by exactly one place** — `ExplorationParams` (base), one `Feature` (activation key or sub-param), or the resolver itself (`ape.preset`, CLI-internal values). The validator derives the feature set, then checks every active feature's dependencies. `rvForcedOffValues`/`rvUnsetKeys`/`rvExemptReasons` and `forceApePureModeInto` are deleted; nothing replaces the *mechanism* because the *need* is structural now (see D-4).

**Inert-key rule** (the substitute for `rvExemptReasons`, and the reason `INV-ARCH-06` dissolves): a sub-parameter key explicitly present while its owning feature is **inactive** is accepted **iff its value is the feature's declared neutral value** (e.g., `ape.llmPercentageNoSubstrate=-1`, which `_BASELINE_ARM_FLAGS` pushes on every arm including non-LLM ones — verified `tool.py:243-261`); it is recorded in the echo's `inert` list and enters no params object. A **non-neutral** value for an inactive feature is a missing-dependency abort (you may state OFF for an absent mechanism; you may not state ON). This keeps every current arm's `ape.properties` valid — checked against the actual `_push_properties` output of all 29 arms in `RunSpecCompatTest`.

**The rule does not reach `ape.apePureMode`, and must not be stretched to.** It admits a sub-parameter of a *feature*, judged against that feature's declared neutral value. `ape.apePureMode` owns no `Feature` — this change deletes the very mechanism such a key would parameterize — and retired-key classification is evaluated before any value semantics. `ape.apePureMode=false` is therefore not an inert value: it is a retired key, and it aborts. The 23 arms that push it are handled by removing it from `tool.py` (D-4), which is the honest resolution; admitting `false` as inert would keep a key alive for a mechanism the spec says SHALL NOT exist.

### D-3 — Presets in the jar, contents derived from the current arm dicts

`Presets.resolve(name)` returns the base key/value vector; explicit keys override on top; the result feeds the same validator. Contents (translated from `tool.py` at HEAD; frozen at apply time by the compat test):

- `aperv` ≙ arm `sata`: `_BASELINE_ARM_FLAGS` (`tool.py:243-261`) **minus `ape_pure_mode`** — the 17 flags that survive the D-4 edit — + `throttle 200` + agent `sata`; RV exploration ON, MOP/reach/LLM OFF. A preset may not carry a retired key: `Presets.resolve` output feeds the same validator as an explicit plan, so baking `ape.apePureMode` into `aperv` would make the preset abort on itself.
- `mop` ≙ arm `sata_mop_widget`: `aperv` + `_MOP_SUBSTRATE` weights (direct 500 / transitive 300 / openMenu 250 / wtg 200; `tool.py:291-297`). `ape.mopDataPath` is **not** part of the preset — it is deployment-specific and must come explicitly (the `MOP` feature therefore activates only when the path is pushed, same as today).
- `llm` ≙ arm `sata_llm`: `aperv` + the `_LLM_FLAGS` sampling block (`tool.py:299-309`) minus `llm_url`, which is deployment-specific and must come explicitly.
- `llm_mop` ≙ arm `sata_mop_llm`: `mop` + `llm`.

Ablation = named override on top of a preset, never a new preset (report Sec. 6.2).

### D-4 — No-preset-key compatibility, and the one ordered Python edit

`ape.preset` is a new, **optional** key. `tool.py` does not push it and will not until stage 5. Resolution:

- **Absent** (every current deployment): the feature set and params are derived from the explicit keys exactly as the jar interprets them today, then validated. Behavior under the four campaign arms is unchanged — the parity gate proves it.
- **Present**: `Presets.resolve(name)` supplies the base vector; explicit keys override; unknown preset name aborts.

`preset + overrides` becomes the *Python* contract only at stage 5; at stage 2 it is exercised by tests and available for manual runs.

**Purity note — `ape.apePureMode` is retired, and removing it from `tool.py` is stage-2 work that lands first.**

The key is retired by D-5, and `_push_properties` writes it from 23 of the 29 arms (Context fact 9). A jar-only retirement therefore aborts every campaign arm before step 1 — coverage 0, MOP violations 0, on every arm. The two ways out are to admit `false` as an inert value or to stop pushing the key; the owner chose the second, because the first keeps a key alive for a mechanism the same spec says SHALL NOT exist (see the inert-key rule in D-2, which does not reach this key).

The edit is four deletions in `tool.py` — `APERV_PROPERTY_MAPPING` (`:120`), `_BASELINE_ARM_FLAGS` (`:256`), `_APE_PURE_ARM_FLAGS` (`:280`), `ARM_DEFINING_KEYS` (`:175`) — plus the INV-APV-13/14/15 guard tests, whose arm-defining count falls from 18 to 17. It lives in a counterpart change of the `rvsec` repo, which owns its own OpenSpec artifacts; the roadmap sequences the two.

**Order: Python first, then the jar.** Both intermediate states are safe in that direction, and only in that direction:

- *Python edited, pre-stage-2 jar still deployed*: `Config.apePureMode` already defaults to `false` (`Config.java:287`), so for the 23 arms that pushed `false` explicitly the absent key resolves to the same value. No behavioral delta.
- *Jar deployed first, `tool.py` untouched*: every campaign arm aborts. This ordering is forbidden — the counterpart change is a hard predecessor of the stage-2 jar deploy, not a parallel task.

The `ape_pure` arm survives the edit intact, and the earlier reasoning here (that letting it work would require keeping the mechanism) was wrong: `_APE_PURE_ARM_FLAGS` (`tool.py:265-284`) already sets all 17 remaining arm-defining flags to their off values explicitly, and the comment above it states why — the original-APE baseline is auditable from `ape.properties` "without trusting the jar's `apePureMode` to force RV off". Purity was already structural on the Python side; this change makes the jar agree, rather than breaking the arm. Owner decision D3 is untouched: the stock-APE mode stays descoped, the campaign control is the minimal `aperv` preset, and stage 5 deletes the arm.

### D-5 — Fail-fast scope (R5), precisely

All aborts happen in `Monkey.run` before `MonkeySourceApe` is constructed — i.e., before any device interaction, agent creation, or step 1. Abort = one `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` line to **both** stderr and stdout (the trace is host-captured stdout; stderr for interactive use), then nonzero exit.

1. **Unknown `ape.*` key** in either properties file → abort (closes V7/3.3-2).
2. **Any non-`ape.` key** in either properties file → abort. The validated set is the file-loaded entries only — `System.getProperties()` defaults are not iterated (they are the JVM's, not the operator's). Consequence: `-D` system properties are no longer a configuration channel for `ape.*` behavior; `RunSpec` reads files + CLI only.
3. **Retired key** → abort with a dedicated message naming the replacement: `ape.apePureMode` (D3/D4: purity is structural), `ape.modelFile` (persistence removed), `ape.saveObjModel`/`ape.saveDotGraph`/`ape.saveVisGraph` (writers removed), `ape.enableXPathAction` (channel removed), `ape.mopWeightActivity` (dead since mop-fairtest; mapped in `tool.py:97` but set by **no** arm — verified), `ape.agentType`/`ape.replayLog` in a *file* (CLI-only: closes the `/sdcard` agent-swap hole, finding 3.2).
4. **Invalid type**: non-numeric value for a numeric key aborts (no more empty catch); a boolean key must be literally `true`/`false` (case-insensitive) — anything else aborts (closes 3.3-2's `getBoolean` hole). Existing load-time clamps (`activityTriggerStagnationStep <= 0`, `activityTriggerMaxPerRun < 0`, `llmPercentage`, `llmPercentageNoSubstrate` sentinel) remain **clamps, not aborts** — they are documented value semantics, not typos.
5. **Missing feature dependency / invalid combination**: an explicitly-set activation or non-neutral sub-param whose owning feature's dependencies are unmet → abort (e.g., `ape.llmOnNewState=true` without `ape.llmUrl`; `ape.mopWeightOpenMenu=250` with `ape.modelMenuEnabled=false`; `ape.activityTriggerEnabled=true` without `ape.mopDataPath`). Defaults never activate a feature whose dependencies are unmet — the feature is simply absent (this is what keeps a bare standalone run valid).
6. **CLI**: `--ape <unknown>` → abort naming the valid set `{sata, random, replay}` (replaces the silent `SataAgent` fallback, V9). `--ape replay` without `--ape-replay <log>` → same abort path (uniform, replacing the ad-hoc `System.exit(1)`). `--ape-model` is **deleted from the parser**, so it hits Monkey's existing unknown-option usage-and-exit path. `--ape` absent defaults to `sata` (a documented default is not a silent fallback; the aperv deployment always passes it).
   - Consequence, stated loudly: the Python variants `bfs` and `dfs` (in `APERV_AVAILABLE_STRATEGIES`) today silently run `SataAgent`; after this change they abort. No campaign preset uses them; making the lie loud is the point. Stage 5 removes them.

### D-6 — Digests

- `digest` (of the **effective plan**): SHA-256 (hex, first 16 chars) over a canonical rendering — sorted `key=value` lines of every effective parameter (post-preset, post-override, post-clamp), plus `agentType`, `presetName`, and the sorted feature names. Seed and runId are **excluded** (two runs of the same arm share the digest — the digest identifies the *arm as interpreted*, which is what drift auditing joins on).
- `propertiesDigest` (of the **input actually read**): SHA-256 over the raw bytes of `/data/local/tmp/ape.properties` and `/sdcard/ape.properties` in load order (absent file contributes a fixed sentinel). This is what proves *which file* the run saw.

Both are computed once inside `resolve` and echoed; nothing else consumes them (level 0 — write-only).

### D-7 — `RUN_START`: content, format, position

One line, one JSON object, emitted immediately after successful resolution and before agent/source construction:

```json
{"type":"RUN_START","v":1,"run_id":"<id>","seed":12345,"agent":"sata",
 "preset":"explicit","features":["MODEL_MENU","MOP","WTG","..."],
 "params":{"ape.defaultEpsilon":0.05,"ape.mopWeightDirect":500,"...":"..."},
 "inert":["ape.llmPercentageNoSubstrate"],
 "digest":"a1b2c3d4e5f60718","props_digest":"…",
 "build":{"sha":"<git-sha>","time":"<utc>"}}
```

- **Serializer**: a minimal hand-rolled JSON emitter (strings/numbers/booleans/flat maps and arrays) with real escaping of quote/backslash/control characters and a hard one-line guarantee. It is deliberately the seed of the stage-4 serializer (same class, grown later) — `RUN_START` is already shaped as one NDJSON record, so the stage-4 sink adopts it unchanged.
- **`params`** carries every effective non-default parameter plus the activation keys of every active feature — the completeness bar is report test 9.6: *the line alone reconstructs the arm without consulting `tool.py`*. Defaults are omitted (they are recoverable from `build.sha`).
- **Position**: first record of the APE-RV layer, guaranteed to precede every `[APE-*]` line, every step, and all agent construction output. AOSP Monkey's own pre-existing banner lines (e.g., the `:Monkey:` args echo) may still precede it in stage 2 — the trace becomes NDJSON-first-line only when stage 4 replaces the remaining emitters. The spec states the guarantee we can keep: strictly before any exploration output.
- **Level 0 is definitive** (D1): nothing reads this line at runtime; no Python change; drift auditing is post-hoc analysis.

### D-8 — Build provenance stamp (reinstating the gh14 mechanism)

`git-commit-id-maven-plugin` + `templating-maven-plugin` filter `src/main/java-templates/.../BuildInfo.java` into generated sources with `GIT_SHA` (abbrev) and `JAR_BUILT` (UTC) constants; sentinel `unknown` when no git dir. The gh14 archived change (`openspec/changes/archive/2026-06-21-gh14-build-provenance-stamp/`) documents the exact pom wiring; its `[APE-BUILD]` banner and `SCHEMA` constant are **not** revived — `RUN_START.build` is the banner now, and the MOP schema version belongs to stage 7. Divergence from the gh14 discard rationale is deliberate and owner-decided: build-time provenance (rvsec B-1) did not make *in-trace* provenance redundant — the rev. 3 report requires the trace to be self-describing (Sec. 6.2).

### D-9 — `/sdcard` readers removed; string generation on the run's seeded RNG (D6, V22, V23)

- `GUITreeBuilder`: the `xPathlets` static block (`:90-99`) is deleted along with the field and its uses — behavior is byte-identical to the only deployed condition (file absent ⇒ empty list).
- `XPathActionController` and the `ape.model.xpathaction` package (6 files) are deleted, with the `enableXPathAction` branch in `StatefulAgent.java:480-482` and the `Config.enableXPathAction` field (key retired).
- `StringCache`: the static initializer (`:73-87`) is deleted — no `/sdcard/ape.strings`, no `RuntimeException` on read failure, `maxStringListSize = Config.maxStringListSize` directly. The list is populated only by on-screen text (`cacheString(s, true)` callers, unchanged). `nextString()` replaces `ThreadLocalRandom.current().nextInt(size)` with the seeded `RandomHelper` draw — the last unseeded decision source dies (V23). String generation is thereby owned by the run's RNG, whose identity `RunContext` holds; a deeper relocation of `StringCache` state into `RunContext` is stage-3 work (R2 — process death — guarantees isolation meanwhile).
- **Parity note**: this is the one intentional behavioral delta of the change. On fixtures where the string list is empty (the deployed norm — nothing pushes `ape.strings`), `nextString()` behavior is unchanged (`RandomHelper.nextFormattedString()` before and after). The rearch-01 goldens are captured on such fixtures; a fixture that *does* populate the list exercises a sanctioned divergence and is excluded from the byte-parity set with a documented note.

### D-10 — Persistence protocol removal; teardown after-state (INV-COV-10)

Deleted: `StatefulAgent.saveGraph()` and its `safeStep`, `Graph.readGraph`, the `--ape-model` CLI branch, `ape.modelFile`, the three `save*` flags, and the model-file loading in `createAgent` (`ApeAgent.java:70-77` — `graph = new Graph()` unconditionally). `Graph.printDot`/`printVis` become caller-less and are deleted (verify at apply time). `sataModel.obj`, `sataGraph.dot`, `sataGraph.vis.js` cease to exist. Retry-on-failure remains wholly the Python supervisor's job (R1/R3); nothing replaces the protocol.

Teardown chain **after** this change:

```
llmSummary → superTearDown → coverageDump → saveActionHistory
           → actionCounters → activityNodes → namingDump → modelCounters
```

INV-COV-10's boundary is restated on what remains: the coverage dump runs strictly **before the first teardown artifact write** (`saveActionHistory`, the only remaining `/sdcard`-writing step until stage 4). The property that A10 bought — the fragile emission precedes the expensive write — is preserved; it gets *stronger* because the most expensive write (`sataModel.obj`) no longer exists. `action-history.log`, `produce.log`/`consume.log`, `sataTimeline.vis.js` and the counters stay untouched — they are stage-4 deletions (report Sec. 6.6).

### D-11 — The five non-final `Config` fields die; tests construct `RunSpec`s

`mopWeightOpenMenu`, `fuzzInputTyped`, `mopStrictPackageMatch`, `activityTriggerEnabled`, `mopFrontierWeight` (`Config.java:149,151,153,165,245`) are non-final solely so tests can toggle them. Their read sites (MopScorer, ApeFuzzer, MopData.load, SataAgent/StatefulAgent launcher gate, MopFrontierPass) move to accessors on `RunContext.current().spec()`; the fields are deleted from `Config`. Tests build a `RunSpec` via a package-visible test factory (`RunSpec` values, no property files) and install it with `RunContext.installForTest(spec)`. This is the *only* group of read-site migrations in stage 2 — chosen because it is forced (final-ness) and small.

### D-12 — `RunContext` stage-2 scope and the stage-3 seam, honestly

`RunContext` holds: the `RunSpec`, the run identity (`runId`: `ape.runId` if given — recognized key, unused by tool.py — else self-generated as `<utc-compact>-<seed>-<8 hex of digest>`), and the seeded RNG identity (it performs the `RandomHelper.seed(seed)` call that `Monkey.run` does today, becoming the single seeding point). Access is via a static holder `RunContext.current()`, set exactly once at bootstrap (test override provided). **This is still a static** — stated plainly: the stage-2 seam is "one static holder instead of 113 static fields plus four static file readers"; R2 (process-per-run) is what makes it safe, and stage 3 threads the context through constructors and migrates `Model`/`Graph`/trackers/LLM client into it. Everything not listed here still reads static `Config` finals; the authority boundary is: **`RunSpec` decided what those values are allowed to be before anything read them.**

## API Design

### `RunSpec.resolve(Map<String,String> fileEntries, CliValues cli) -> RunSpec`

- Pre: `fileEntries` = union of the two properties files' entries in load order (later file wins), file-loaded keys only; `cli` = `{agentTypeOrNull, seed, replayLogOrNull}`.
- Post: returns a fully-populated immutable `RunSpec`; every key consumed and classified; digests computed.
- Throws `RunSpecException(reason, key, detail)` on any D-5 case. Never returns a partially-valid spec.

### `Presets.resolve(String name) -> Map<String,String>`

- Pre: `name ∈ {aperv, mop, llm, llm_mop}`; anything else throws `RunSpecException(UNKNOWN_PRESET)`.
- Post: returns the preset's base key vector (a fresh mutable copy; caller overlays overrides).

### `Feature` (enum)

`activationKey()`, `ownedKeys()`, `dependencies() -> EnumSet<Feature>`, `neutralValue(String key)`, `isActive(Map<String,String> effective)`. Pure functions over data; no I/O.

### `RunContext`

`static current()`, `static initialize(RunSpec, long seed)` (once; second call throws), `static installForTest(RunSpec)`, `spec()`, `runId()`, `rng()` (the seeded `RandomHelper`-backed stream).

### `RunSpecEcho.emit(RunSpec spec, PrintStream out)`

Post: exactly one `\n`-terminated line; serializer escapes all control characters; emitting twice is a bug guarded by `RunContext.initialize`'s once-only contract.

## Data Flow

1. `tool.py` (unchanged) pushes `ape.properties` + jar, launches `app_process … Monkey -p <pkg> --running-minutes N --ape sata -s <seed>`.
2. `Monkey.processOptions` records CLI values (no more `Config.set` for agent type/model file).
3. `Monkey.run`, `mUseApe` branch, before anything else: load files (existing `Config` loader), `RunSpec.resolve`, on failure print abort line and return nonzero.
4. `RunContext.initialize(spec, seed)` → seeds the RNG → `RunSpecEcho.emit` writes `RUN_START` to stdout (the host-captured trace).
5. `MonkeySourceApe` constructed; `ApeAgent.createAgent` switches on `spec.agentType` — no fallback arm exists.
6. Exploration loop unchanged. Teardown runs the D-10 chain; nothing is read back (R3); process dies (R2).

## Error Handling

| Error | Source | Strategy | Recovery |
|-------|--------|----------|----------|
| `RunSpecException` (unknown/retired key, bad type, dependency, combination, unknown preset) | `RunSpec.resolve` at bootstrap | `[APE-RUNSPEC-ABORT]` line to stderr+stdout; nonzero exit before step 1 | operator/tool.py fixes the properties; supervisor sees a failed task (no silent degradation) |
| Unknown `--ape` value / replay without log | CLI validation in `resolve` | same abort path | same |
| `--ape-model` supplied | Monkey option parser (option deleted) | existing unknown-option usage + nonzero exit | remove the flag from the invocation |
| Properties file unreadable | existing `loadConfiguration` `RuntimeException` | unchanged (already fail-fast) | fix file/permissions |
| git info unavailable at build | `git-commit-id` plugin sentinel | `BuildInfo.GIT_SHA="unknown"`; RUN_START still emitted | none needed (provenance degraded, run valid) |
| `RunContext.initialize` called twice | programming error | throw `IllegalStateException` (fail loud) | fix the bug |

## Risks / Trade-offs

- [Parity gate vs. the seeded `nextString`] → the only intended behavioral delta; goldens captured on empty-string-list fixtures are unaffected; any list-exercising fixture documents the sanctioned divergence (D-9). Gate remains: per-preset goldens green.
- [`bfs` / `dfs` variants abort loudly] → deliberate (D3, V9): they silently run `SataAgent` today; no campaign preset uses them; stage 5 deletes them. Recorded here so a surprised operator finds the decision, not a bug. `ape_pure` is **not** in this list any more — the D-4 edit removes the retired key from the arm, which then resolves like any other.
- [Stage-2 jar deployed against a pre-edit `tool.py`] → every arm aborts before step 1: this is the failure the D-4 ordering exists to prevent, and it is the reason the counterpart change is a predecessor rather than a parallel task. Residual exposure is an operator deploying the jar out of order; the failure is loud and immediate (a failed task, never a degraded run — R5), and `RUN_START.build.sha` makes the jar-to-trace pairing auditable post-hoc.
- [`-D` system properties no longer configure `ape.*`] → intentional narrowing under R5; no deployment path uses it (tool.py pushes files; CLI carries seed/agent).
- [Static `RunContext.current()` is still a global] → admitted seam (D-12); bounded by R2 and dissolved in stage 3.
- [RUN_START not literally the first stdout line in stage 2] → AOSP banner lines precede it until stage 4; the spec claims only "before any exploration output", which is what analysis needs.
- [Retired-key list drifts from reality] → the list is exactly the keys whose mechanisms this change deletes, enumerated in one constant next to the validator, each with its reason string; `RunSpecAbortTest` covers every member.
- [Preset content drifts from tool.py before stage 5] → `RunSpecCompatTest` pins the four preset vectors against fixtures generated from the arm dicts at apply time; stage 5 replaces the fixtures with the real contract.

## Testing Strategy

| Layer | What to test | How | Count |
|-------|-------------|-----|-------|
| Unit (JVM) | `Feature` derivation/dependencies/neutral rule; `Presets` content; digest determinism; serializer escaping + one-line; runId shape; retired keys | pure JVM, no Android | ~25 |
| Unit (JVM) | abort matrix: unknown key, non-ape key, bad int, bad boolean, dependency, combination, unknown `--ape`, replay-no-log, unknown preset | `RunSpec.resolve` against crafted maps | ~12 |
| Compat | per-arm fixtures = exact `_push_properties` output of the 29 arms **after the D-4 edit** → the 4 campaign arms and `ape_pure` all resolve; hand-written fixtures carrying `ape.apePureMode` or `ape.mopWeightActivity` abort with the documented reason | fixture files in test tree | ~8 |
| Integration | teardown chain order (dump before `saveActionHistory`); `createAgent` on validated types; `StringCache` seeded determinism (same seed ⇒ same strings) | existing JVM test seams | ~6 |
| Gate | rearch-01 golden suite per preset, before/after | `mvn test` | existing |
| Build | `BuildInfo` filtered (no `${…}` residue); sha present in dex (`unzip -p … | strings`); no new jar resources | script + JUnit | 3 |
| Device (optional) | standalone smoke via `scripts/run_emulator.sh`: RUN_START present and well-formed; unknown-key abort observable; no `sataModel.obj` produced | manual validation | 1 session |

## Open Questions

None. Owner decisions D1–D6 are final (report Sec. 12); the two details left to apply time are mechanical: the exact 113-key → owner classification table (D-2, enumerated in code and locked by the totality test) and confirmation that `Graph.printDot`/`printVis` have no callers outside `saveGraph` (D-10).
