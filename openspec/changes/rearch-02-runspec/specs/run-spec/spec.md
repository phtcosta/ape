# Delta Specification: run-spec (NEW capability)

## Purpose

The run-spec capability makes the behavioral plan of a run a first-class, validated, echoed value. Before this capability, "what this run is" was scattered across a silent global (`Config`, 113 public static fields loaded from two device files with empty catch blocks and no unknown-key detection), a Python dict in another repository (the `tool.py` arm definitions), three string registries the compiler could not check, and a CLI argument that fell back silently to `SataAgent` on any unrecognized value. Nothing validated the whole; nothing recorded what was actually in effect; a stray `/sdcard/ape.properties` could change the agent type of a scientific run without a trace.

After this capability, every run begins by resolving a single immutable `RunSpec` from `ape.properties` plus the CLI, exactly once, before the first exploration step. The `RunSpec` carries the preset name (informative), the seed, the run identity, the always-present exploration parameters, the set of active `Feature`s, per-feature parameter objects that are null exactly when the feature is absent, and two digests (effective plan; raw properties input). Resolution is total and fail-fast: an unknown key, a retired key, a type-invalid value, a missing feature dependency, or an invalid combination aborts the process with a diagnostic line before step 1. Presets (`aperv`, `mop`, `llm`, `llm_mop`) reside in the jar and are selected by an optional `ape.preset` key; when no preset is named — the case for the entire current Python deployment, which this capability changes by exactly one key — the plan is derived from the explicit keys exactly as the jar interprets them today, then validated.

The plan is echoed as the run's provenance: `RUN_START`, a single JSON object line emitted before any exploration output, carrying the effective plan, both digests, the seed, the run id, and the jar's build stamp. The echo is **level 0 by owner decision D1 (final)**: write-only provenance, no automatic validation anywhere, no Python reader — drift auditing is post-hoc analysis over the traces. The line's completeness bar is that it alone reconstructs the arm without consulting `tool.py` (report Sec. 9.6).

`RunContext` is introduced as the owner of per-run mutable state. Its stage-2 scope is deliberately small — the `RunSpec`, the run identity, and the seeded RNG — with the rest of the mutable state migrating in stage 3. Purity becomes structural: a feature absent from the plan does not exist in the run — no kill-switch, no forced-off registry, no exempt list. This is the substitute for the dissolved INV-ARCH-06: a sub-parameter of an absent feature has nothing to be inert *about*, because the mechanism it would parameterize was never constructed.

## Data Contracts

### Input

- `/data/local/tmp/ape.properties`, `/sdcard/ape.properties` — the only behavioral input files; loaded in that order (later wins); every entry validated (source: device filesystem, pushed by the harness)
- `--ape <type>: String` — agent type; valid set `{sata, random, replay}`; CLI-only (source: Monkey command line)
- `-s <seed>: long` — run seed, also seeding `RandomHelper` (source: Monkey command line)
- `--ape-replay <log>: String` — replay log path, required iff `--ape replay` (source: Monkey command line)
- `ape.preset: String (optional)` — `aperv | mop | llm | llm_mop`; absent in every current deployment
- `ape.runId: String (optional)` — host-supplied run identity; absent ⇒ self-generated

### Output

- `RUN_START: one JSON object line` — effective plan + digests + seed + runId + t0 + build stamp; written to stdout (the host-captured trace); write-only (consumer: post-hoc analysis only)
- `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` — on validation failure, to stderr and stdout, followed by nonzero process exit

### Side-Effects

- **[Process]**: on any validation failure the process exits nonzero before the first exploration step; no agent, event source, or device interaction is constructed.
- **[RNG]**: `RunContext.initialize` performs the single `RandomHelper.seed(seed)` call for the run.

### Error

- `RunSpecException(reason, key, detail)` — raised by `RunSpec.resolve` for every invalid input class; never caught into a degraded run.

## Invariants

- **INV-RUN-01**: The `RunSpec` SHALL be resolved exactly once per process, before the first exploration step, and SHALL be immutable thereafter: no field of `RunSpec` or its parameter objects may change after `resolve` returns, and no component SHALL re-read `ape.properties` to make a behavioral decision after resolution.
- **INV-RUN-02**: Every invalid configuration input — unknown key, non-`ape.` key in a properties file, retired key, type-invalid value, missing feature dependency, invalid combination, unknown `--ape` value, unknown preset name — SHALL abort the process with a nonzero exit and an `[APE-RUNSPEC-ABORT]` diagnostic **before step 1**. No invalid input SHALL degrade to a default.
- **INV-RUN-03**: `RUN_START` SHALL be emitted exactly once, as a single JSON object line, before any exploration output, and its content alone SHALL suffice to reconstruct the arm (active features, effective parameters, agent type, seed) without consulting `tool.py` or any external source. It SHALL be write-only: no runtime component, Java or Python, reads it (owner decision D1, level 0, definitive).
- **INV-RUN-04**: The plan `digest` SHALL be a deterministic function of the effective plan (same effective parameters ⇒ same digest, independent of key order, file split, or preset-vs-explicit expression) and SHALL exclude seed, runId, the corpus basis, and the preset name. *(The preset name is named explicitly because it is the exclusion the invariance clause forces and the one an implementation is most likely to hash by accident: a digest that separated `ape.preset=mop` from the same keys written out by hand would record how the plan was written rather than what it is.)* The `propertiesDigest` SHALL be a deterministic function of the raw bytes of the properties files actually read, so that a file differing only in comments or key order is distinguishable from the one the plan digest cannot tell apart.
- **INV-RUN-05**: A feature absent from `features` SHALL have a null parameter object and SHALL have no constructed mechanism in the run (no pass, no router, no tracker, no launcher). A sub-parameter key explicitly present for an inactive feature SHALL be accepted only at its declared neutral value (recorded as `inert` in the echo); any non-neutral value SHALL abort per INV-RUN-02. *(This is the recorded substitute for the dissolved INV-ARCH-06: exemption lists are unnecessary because the sub-parameter of an absent feature parameterizes nothing.)*
- **INV-RUN-06**: The jar SHALL read no behavioral input from `/sdcard` other than `/sdcard/ape.properties`. In particular `/sdcard/ape.xpath`, `/sdcard/ape.xpath.actions`, and `/sdcard/ape.strings` SHALL NOT be read by any code path (owner decision D6).
- **INV-RUN-07**: No artifact produced by a previous run SHALL be read by the explorer (R3): there is no model/graph deserialization path, no resume, no read-back of trace or telemetry. Retry after failure is wholly the responsibility of the Python supervisor.
- **INV-RUN-08**: All run-scoped randomness SHALL derive from the single seeded stream owned by `RunContext` (seeded from `-s`); no component SHALL use `ThreadLocalRandom`, `Math.random`, or an unseeded `Random` for any decision or generated input.

**Coverage of these invariants, and the two places it is thin.** Six of the eight are carried by a named test: INV-RUN-01 by `RunSpecResolveTest` (immutability, input-map isolation) and `RunContextTest` (a second `initialize` throws); INV-RUN-02 by `RunSpecAbortTest` across all eight input classes, with the abort *composition* in `Monkey.run` reachable only on a device (task 8.2, recorded in task 3.4); INV-RUN-03 by `RunSpecEchoTest`; INV-RUN-04 by `RunSpecResolveTest`'s six digest tests; INV-RUN-05 by `FeatureDerivationTest` and `RunSpecResolveTest`'s inert-key tests; INV-RUN-06 by `DeviceInputChannelAbsenceTest`. The remaining two are recorded here as they stand rather than counted as covered — a stated invariant with no guard is a finding, not a box to tick.

- **INV-RUN-07 has no test.** The retired keys that gated the persistence protocol (`ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates`) are covered by `RunSpecAbortTest.everyRetiredKeyAbortsWithItsOwnReason`, and the deletion of `Graph.readGraph` and the `--ape-model` branch is compile-enforced — no caller can name an API that no longer exists. Neither fact proves the *absence* of a deserialization or read-back path, which is what the invariant asserts. A manual scan of `src/main/java` at this change's HEAD finds no `ObjectInputStream`, no `readObject` and no `readGraph`, so the tree satisfies the invariant today; what is missing is a guard against re-introduction, of the kind `DeviceInputChannelAbsenceTest` provides for INV-RUN-06.
- **INV-RUN-08 is partially covered.** `DeviceInputChannelAbsenceTest.noUnseededGeneratorSurvivesInProductionCode` scans `src/main/java` for `ThreadLocalRandom` — one of the three mechanisms the invariant names. `Math.random` and unseeded `new Random()` are unscanned. Manual audit at this change's HEAD: no `Math.random` anywhere in production code; two `new Random(` sites, both seeded (`Monkey.java` from `mSeed`, `RandomHelper.seed` from `RunContext.initialize`); and `RandomHelper`'s field initializer `new Random()`, which is unseeded but is replaced by `RunContext.initialize` before any exploration component draws from it. Behavioural coverage exists through `RunContextTest` (establishing the context seeds the run's stream) and `StringCacheSeededTest` (same seed ⇒ same string sequence). The gap is the scan's breadth, not a known live violation.

## ADDED Requirements

### Requirement: Single Plan Resolution at Bootstrap

`RunSpec.resolve` SHALL run in `Monkey.run` (the `--ape` branch) before `MonkeySourceApe` or any agent is constructed. It SHALL consume: the union of the entries of `/data/local/tmp/ape.properties` and `/sdcard/ape.properties` (load order preserved, later file wins; file-loaded entries only — JVM system properties are not a configuration channel), and the CLI values (`--ape`, `-s`, `--ape-replay`). It SHALL produce an immutable `RunSpec` value carrying: `presetName` (the `ape.preset` value, or `"explicit"` when absent), `seed`, `runId`, `agentType`, `ExplorationParams` (always present), `Set<Feature>`, `MopParams`/`LlmParams`/`TelemetryParams` (each null iff its feature is absent), `digest`, and `propertiesDigest`.

After resolution, `RunSpec` is the sole behavioral authority: static `Config` remains only as the loading mechanism, and its values are guaranteed valid because `RunSpec.resolve` validated the same entries before any consumer read them. The `RunSpec` SHALL be reachable via `RunContext.current().spec()`.

#### Scenario: plan resolved once before the loop

- **WHEN** the process is launched with `--ape sata -s 42` and a valid `ape.properties`
- **THEN** `RunSpec.resolve` SHALL complete before `MonkeySourceApe` is constructed
- **AND** exactly one `RunSpec` SHALL exist for the process lifetime
- **AND** no code path SHALL mutate it or re-resolve it

#### Scenario: later file wins, both validated

- **WHEN** `/data/local/tmp/ape.properties` sets `ape.defaultEpsilon=0.05` and `/sdcard/ape.properties` sets `ape.defaultEpsilon=0.10`
- **THEN** the effective `ExplorationParams` epsilon SHALL be `0.10`
- **AND** every key of both files SHALL have been validated

### Requirement: Feature Model with Declared Dependencies

`Feature` SHALL be an enum whose constants carry, as constructor data: an activation rule (a key plus the value shape that activates it, or a derived predicate such as "`ape.mopDataPath` present" for `MOP` and "`ape.llmUrl` present" for `LLM`), the set of sub-parameter keys the feature owns, the declared dependencies as an `EnumSet<Feature>` (at minimum: `MENU_GATEWAY` requires `MODEL_MENU` and `MOP`; `WTG`, `FRONTIER`, `MOP_FRONTIER`, `ACTIVITY_TRIGGER`, `COMPONENT_TRIGGER`, `MOP_ACTIVITY_SOURCE` require `MOP`; `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_RANDOM` require `LLM`), and the neutral (off/zero/sentinel) value of each owned key.

Key ownership SHALL be total: every `ape.*` key is owned by exactly one of `ExplorationParams`, one `Feature`, or the resolver itself (`ape.preset`, `ape.runId`, `ape.corpusBasis`); a totality test SHALL fail if a key the jar reads is unowned. The test SHALL derive the roster from the source tree rather than from a maintained list — a list would be written from the same documents that omit a key, which is how `ape.baseNaming` and `ape.nopActionThrottle` went undeclared through three audits of this change. The three string registries (`rvForcedOffValues`, `rvUnsetKeys`, `rvExemptReasons`) and the `apePureMode` forcing mechanism SHALL NOT exist; the `Feature` metadata is the single, compiler-visible replacement.

Dependency validation SHALL distinguish explicit input from defaults: an **explicitly-set** activation key or non-neutral sub-parameter whose feature's dependencies are unmet aborts (INV-RUN-02); a **default-valued** key never activates a feature whose dependencies are unmet — the feature is simply absent from the plan.

#### Scenario: dependent feature validated

- **WHEN** `ape.properties` sets `ape.mopWeightOpenMenu=250` and `ape.modelMenuEnabled=false` with `ape.mopDataPath` present
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `MENU_GATEWAY` and `MODEL_MENU`

#### Scenario: default does not activate an impossible feature

- **WHEN** no properties file sets `ape.activityTriggerEnabled` (jar default `true`) and `ape.mopDataPath` is absent
- **THEN** resolution SHALL succeed with `ACTIVITY_TRIGGER` absent from `features`
- **AND** no launcher mechanism SHALL be constructed

#### Scenario: neutral inert sub-parameter accepted, non-neutral aborts

- **WHEN** a non-LLM arm's `ape.properties` contains `ape.llmPercentageNoSubstrate=-1` (the neutral sentinel pushed by every current baseline arm) and no `ape.llmUrl`
- **THEN** resolution SHALL succeed, the key SHALL appear in the echo's `inert` list, and `LlmParams` SHALL be null
- **AND WHEN** the same file instead contains `ape.llmOnNewState=true` with no `ape.llmUrl`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM`

### Requirement: Presets Resident in the Jar

`Presets.resolve(name)` SHALL return the base key vector for `aperv`, `mop`, `llm`, and `llm_mop`, defined in the jar as the translation of the current `tool.py` campaign arm property sets (`sata`, `sata_mop_widget`, `sata_llm`, `sata_mop_llm` respectively): `aperv` = the baseline arm-defining flags (RV exploration on, MOP/reach/LLM off) with throttle 200 and agent `sata`; `mop` = `aperv` plus the MOP weight substrate (direct 500, transitive 300, openMenu 250, wtg 200); `llm` = `aperv` plus the LLM sampling block; `llm_mop` = `mop` plus `llm`. Deployment-specific values (`ape.mopDataPath`, `ape.llmUrl`, seed, runId) SHALL NOT be part of any preset and MUST come explicitly.

No preset SHALL carry an agent type. The `aperv` arm's agent is `sata`, and that is a property of the resolved plan rather than an entry of the vector: the agent type is a command-line value, `ape.agentType` is retired as a file key, and `sata` is what an absent `--ape` already resolves to — so a preset entry for it could only duplicate the default or contradict the command line.

A preset that states a feature's gates ON while the mechanism they parameterize is absent SHALL abort like any other plan. In particular `ape.preset=llm` with no explicit `ape.llmUrl` aborts, because the preset carries `ape.llmOnNewState=true` and `ape.llmOnStagnation=true`; a preset that resolved instead to "LLM off" would be the silent degradation this capability exists to end.

When `ape.preset` is present, explicit keys SHALL override the preset vector, and the merged result SHALL pass the same validation as an explicit plan. An unknown preset name SHALL abort. Ablations are expressed as overrides on a preset, never as new presets.

#### Scenario: preset plus override

- **WHEN** `ape.properties` contains `ape.preset=mop`, `ape.mopDataPath=/data/local/tmp/static_analysis.json`, and `ape.mopWeightDirect=0`
- **THEN** the effective plan SHALL be the `mop` preset with `mopWeightDirect` overridden to `0`
- **AND** the echo's `preset` field SHALL be `"mop"` and `params` SHALL show the override

#### Scenario: unknown preset aborts

- **WHEN** `ape.properties` contains `ape.preset=mop_v2`
- **THEN** resolution SHALL abort with an unknown-preset diagnostic listing the four valid names

### Requirement: Explicit-Key Resolution When No Preset Is Named

When `ape.preset` is absent — the case for the entire current Python deployment — the plan SHALL be derived from the explicit keys exactly as the jar interprets them today (same defaults, same clamps), then validated. The properties files pushed by the **stage-2 `tool.py`** (today's arm definitions with `ape_pure_mode` removed, the single Python edit of this stage) for the four campaign arms (`sata`, `sata_mop_widget`, `sata_llm`, `sata_mop_llm`) SHALL resolve successfully and produce behavior identical to HEAD under the rearch-01 parity goldens. The `ape_pure` arm SHALL also resolve: its purity comes from the 17 arm-defining flags it already sets to their off values explicitly, not from a kill-switch key. `preset + overrides` becomes the Python-side contract only at stage 5.

#### Scenario: current campaign arm resolves unchanged

- **WHEN** the properties file is byte-identical to the `_push_properties` output of the `sata_mop_widget` arm (MOP path + weights + the 17 baseline flags that survive the `ape_pure_mode` removal + throttle)
- **THEN** resolution SHALL succeed with `preset="explicit"`, `features` including `MOP`, `WTG`, `MENU_GATEWAY`, and the baseline features
- **AND** the run's action-selection behavior SHALL match the rearch-01 golden for the `mop` preset

#### Scenario: the Python edit precedes the jar

- **WHEN** the `ape_pure_mode` removal has landed in `tool.py` and the stage-2 jar is deployed after it
- **THEN** the four campaign arms and `ape_pure` SHALL run end-to-end, with `_push_properties` and every other arm-dict entry untouched
- **AND WHEN** the stage-2 jar is instead deployed against a `tool.py` that still pushes `ape.apePureMode`
- **THEN** every arm that pushes the key SHALL abort with `reason=retired_key key=ape.apePureMode` before step 1 — the ordering is a deployment precondition, not a preference

### Requirement: Total Fail-Fast Validation

`RunSpec.resolve` SHALL abort (nonzero exit, `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` to stderr and stdout, before step 1) on every one of the following input classes — and the abort SHALL happen before any agent, event source, or device interaction is constructed:

1. **Unknown key**: an `ape.*` key in a properties file that no owner declares.
2. **Foreign key**: any non-`ape.` key in a properties file.
3. **Retired key** (dedicated message naming the replacement): `ape.apePureMode` (purity is structural — owner decision D3), `ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates` (persistence protocol removed), `ape.enableXPathAction` (injection channel removed), `ape.mopWeightActivity` (dead key), `ape.agentType` and `ape.replayLog` when present in a **file** (CLI-only values — closes the `/sdcard` agent-swap hole).
4. **Invalid type**: a non-numeric value for a numeric key; a boolean key whose value is not literally `true`/`false` (case-insensitive). Documented value-semantics clamps (cadence `<= 0`, launch cap `< 0`, percentage clamps, `-1` sentinel) remain clamps, not aborts.
5. **Missing dependency / invalid combination**: per the Feature Model requirement; plus `--ape replay` without `--ape-replay`.
6. **Unknown `--ape` value**: any value outside `{sata, random, replay}` — including `bfs`, `dfs`, and `ape` — aborts with a diagnostic naming the valid set. The silent `SataAgent` fallback SHALL NOT exist.

#### Scenario: unknown key aborts before step 1

- **WHEN** `ape.properties` contains `ape.graphStableRestartThreshld=200` (typo)
- **THEN** the process SHALL exit nonzero with `[APE-RUNSPEC-ABORT] reason=unknown_key key=ape.graphStableRestartThreshld`
- **AND** no exploration step SHALL have run and no agent SHALL have been constructed

#### Scenario: invalid type aborts instead of degrading

- **WHEN** `ape.properties` contains `ape.doFuzzing=ture`
- **THEN** the process SHALL abort with `reason=invalid_type`, instead of silently reading `false` as today

#### Scenario: missing dependency aborts

- **WHEN** `ape.properties` contains `ape.activityTriggerEnabled=true` and no `ape.mopDataPath`
- **THEN** the process SHALL abort with `reason=missing_dependency` naming `ACTIVITY_TRIGGER` and `MOP`

#### Scenario: invalid combination aborts

- **WHEN** the process is launched with `--ape replay` and no `--ape-replay` argument
- **THEN** the process SHALL abort through the same `[APE-RUNSPEC-ABORT]` path (no ad-hoc `System.exit`)

#### Scenario: unknown agent type aborts loudly

- **WHEN** the process is launched with `--ape bfs`
- **THEN** the process SHALL exit nonzero with a diagnostic naming the valid set `{sata, random, replay}`
- **AND** no `SataAgent` SHALL be silently constructed

#### Scenario: retired kill-switch key aborts with its decision

- **WHEN** `ape.properties` contains `ape.apePureMode=true` (a hand-written file — after the stage-2 Python edit no arm pushes the key, at either value)
- **THEN** the process SHALL abort with `reason=retired_key key=ape.apePureMode` and a detail referencing structural purity (a feature absent from the plan does not exist)

### Requirement: Level-0 RUN_START Echo

Immediately after successful resolution and before any exploration output, the jar SHALL emit `RUN_START` as a single JSON object line to stdout: `type`, format version `v`, `run_id`, `t0` (device epoch milliseconds at emission — the base against which the stage-4 step records' relative `t` offsets resolve), `seed`, `agent`, `preset` (name or `"explicit"`), `features` (sorted names), `params` (every effective non-default parameter plus every active feature's activation key, as `ape.*` keys with effective values), `inert` (accepted neutral keys of inactive features), optional `corpus_basis`, `digest`, `props_digest`, and `build` (`sha`, `time` from the build stamp).

The format version `v` is not decoration and is worth one sentence of justification, because its absence has already cost something. Traces from different campaigns of this study carry materially different `[APE-STEP]` schemas — the earlier calibration corpus has no `mop_frontier`, `pick_channel`, `patched` or counterfactual fields, and its `mop=` realises `{0,300}` where the decisive campaign realises `{0,500}` — and nothing in either trace says which schema it is. `v` is what makes a cross-campaign comparison fail loudly instead of quietly comparing incomparable fields.

The line SHALL be produced by a serializer that escapes quotes, backslashes, and control characters and never emits a raw newline inside the record (one-record-one-line by construction) — the same serializer the stage-4 NDJSON sink will grow from, so `RUN_START`'s format survives stage 4 unchanged. The echo is write-only provenance (owner decision D1, level 0, definitive): no validation of the line anywhere, no Python change, no new communication channel — the physical flow remains `adb push` + stdout capture. In stage 2 the line precedes every APE-RV record and all exploration output; pre-existing AOSP Monkey banner lines may still precede it until the stage-4 sink replaces the remaining emitters.

#### Scenario: the line alone reconstructs the arm

- **WHEN** a run executes the current `sata_mop_llm` arm properties
- **THEN** the `RUN_START` line SHALL contain the agent type, seed, all active features (baseline + `MOP` family + `LLM` family), the MOP weights, the LLM sampling parameters and url, and the build sha
- **AND** an analyst reading only that line SHALL be able to state which arm ran, without `tool.py`

#### Scenario: echo precedes exploration output

- **WHEN** any valid run starts
- **THEN** `RUN_START` SHALL appear in the trace before any `[APE-*]` line and before any step output

#### Scenario: hostile values cannot break the line

- **WHEN** a properties value contains a quote or would carry a control character
- **THEN** the emitted `RUN_START` SHALL remain exactly one line and SHALL parse as JSON

#### Scenario: stale-jar drift visible (gh71 class)

- **WHEN** an outdated jar is deployed to the device
- **THEN** the `build.sha` of every trace it produces identifies the stale build, making the drift diagnosable post-hoc from the traces alone

### Requirement: Run Identity and Seed

`RunSpec.seed` SHALL be the seed `Monkey` resolved — the `-s` value when one was passed, and otherwise the value `Monkey` derived for itself — and `RunContext.initialize` SHALL be the single point that calls `RandomHelper.seed(seed)` (INV-EXPL-14 unchanged). `runId` SHALL be the `ape.runId` value when present (a recognized key; no current deployment pushes it) and otherwise self-generated, deterministic in format (`<utc-compact>-<seed>-<digest-prefix>`), and echoed in `RUN_START`.

**Recording the seed and choosing it are two different things, and today only the first is in hand.** *(Corrected at apply time, group 4. The earlier text here said `RUN_START` would faithfully record the seed's **absence**. It will not, because there is no absence to record.)* The harness appends `-s <seed>` only when a seed is configured (`tool.py:1380-1382`) and none was — which is why the flag appears in none of the decisive campaign's 360 traces except as a widget resource id in one application, and why none of the 115 `ape.*` keys in the configuration echo is a seed, RNG or determinism key. But `Monkey` does not run unseeded when `-s` is absent: it manufactures one, `mSeed = System.currentTimeMillis() + System.identityHashCode(this)` (`Monkey.java:679-681`), before the `--ape` branch is reached, and both `mRandom` and `RandomHelper` are built from that value. `RunSpec.seed` is therefore always a real number and `RUN_START.seed` is always full.

What has been missing is not the seed but **control over it**, and the distinction changes what this echo buys. From this stage on a run is individually reproducible from its own trace — bounded by two things a seed does not reach: the trajectory depends on the observed GUI tree and therefore on device timing, and at least one unseeded `Random` remains in the tree until INV-RUN-08 closes it. The parity oracle's premise — the same decisions *under the same seed* — is thus satisfiable per run as of stage 2. What the thin-arms stage still owes is a seed that is **designed rather than incidental**: an incidental seed is reproducible only after the fact, and cannot be used to pair two arms on the same randomness.

**When that change is made, a single constant seed across the campaign SHALL NOT be it.** The three replicas per (application, arm) are today the only estimator of run-to-run variation the design has; pinning one seed for all runs would collapse that estimator to zero and buy per-run reproducibility with the loss of the noise floor every effect size is read against. The seed SHALL be a deterministic function of the run's identity — application, arm and replica — so that each run is individually reproducible while replicas still sample the nondeterminism. Note also that reproducibility is bounded from the other side regardless: `RandomHelper` is not the only source of variation, since the trajectory depends on the observed GUI tree and therefore on device timing, and at least one unseeded `Random` exists in the tree (INV-RUN-08 is what closes that second hole, and it is specified here precisely because the seed alone does not).

**`corpus_basis`.** `RUN_START` SHALL carry `corpus_basis` — an identifier plus a `sha256` of the application name-list the run was drawn from — when the `ape.corpusBasis` key is present. It is a recognized key like `ape.runId`, supplied by the harness and echoed unread, and it is omitted when absent rather than defaulted. Two strings once per run retire a class of error this corpus has already produced: the same study has counted its analysis basis as 163, 181 and 219 applications in different documents, and the reconciliation cost falls out of every analysis that has to re-derive which list a run belonged to. This is deliberately *not* a per-application block of frozen static facts: the basis is a property of the corpus, one hash identifies it, and freezing per-app copies would put the same truth in forty places.

#### Scenario: self-generated identity when the host supplies none

- **WHEN** the `tool.py` deployment launches a run (it pushes no `ape.runId`, before or after the stage-2 edit)
- **THEN** the jar SHALL generate a `run_id` and echo it in `RUN_START`

#### Scenario: no seed configured on the command line

- **WHEN** the harness launches a run without `-s`, as every run of the decisive campaign was launched
- **THEN** `RUN_START` SHALL record the seed `Monkey` derived for the run, which is the value both `mRandom` and `RandomHelper` were built from — so the trace states the randomness the run actually had, rather than an empty field the reader must interpret
- **AND** no component SHALL substitute a *different* seed for the echo than the one in force, in either direction: neither a placeholder to fill the field nor a blank to signal that nobody chose it

#### Scenario: corpus basis echoed when supplied

- **WHEN** the harness pushes `ape.corpusBasis=subset40:<sha256>`
- **THEN** `RUN_START` SHALL carry `corpus_basis` with that value, and no runtime component SHALL read it (INV-RUN-03)
- **AND** when the key is absent the member SHALL be omitted entirely

### Requirement: RunContext Ownership (stage-2 scope)

`RunContext` SHALL own, for stage 2: the resolved `RunSpec`, the run identity, and the seeded RNG stream. It SHALL be initialized exactly once at bootstrap (a second `initialize` throws), with a test-only installation path so JVM tests construct `RunSpec` values directly instead of writing property files or mutating static fields — the five formerly non-final `Config` fields (`mopWeightOpenMenu`, `fuzzInputTyped`, `mopStrictPackageMatch`, `activityTriggerEnabled`, `mopFrontierWeight`) SHALL NOT exist, and their read sites SHALL consult `RunContext.current().spec()`.

The remaining mutable run state (`Model`, `Graph`, trackers, LLM client, counters) stays where it is until stage 3; untouched code paths continue to read static-final `Config` values, which are guaranteed valid by INV-RUN-01/02. The authority boundary of stage 2 is: `RunSpec` decided what every value is allowed to be before anything read it.

#### Scenario: tests construct plans, not property files

- **WHEN** a JVM test needs `mopWeightOpenMenu=0`
- **THEN** it SHALL build a `RunSpec` via the test factory and install it with `RunContext.installForTest`
- **AND** no test SHALL mutate a `Config` field (all fields final)

#### Scenario: double initialization is a loud bug

- **WHEN** `RunContext.initialize` is called a second time in one process
- **THEN** it SHALL throw `IllegalStateException`

### Requirement: Build Provenance Stamp

The build SHALL embed a `BuildInfo` class generated at build time (Maven git-commit-id + templating plugins filtering a `java-templates` source) exposing `GIT_SHA` (abbreviated commit of the built tree; `unknown` when no git directory) and `JAR_BUILT` (UTC build time). The constants SHALL be compiled into the dex (verifiable via `strings` over `classes.dex`), add no jar resources, and be consumed by the `RUN_START` echo. No runtime `[APE-BUILD]` banner is emitted — `RUN_START.build` is the banner.

#### Scenario: stamp present and filtered

- **WHEN** `mvn package` completes
- **THEN** the generated `BuildInfo` SHALL contain no unfiltered `${…}` placeholders
- **AND** the short sha SHALL be findable in `classes.dex` of `target/ape-rv.jar`

#### Scenario: buildable outside git

- **WHEN** the tree is built without a `.git` directory
- **THEN** the build SHALL succeed and `GIT_SHA` SHALL be `unknown`
