# run-spec Specification

## Purpose
The run-spec capability makes the behavioral plan of a run a first-class, validated, echoed value, so that "what this run is" is decided in one place: no silently loaded global, harness-side arm definition, unchecked string registry, stray `/sdcard/ape.properties` or CLI fallback can change the plan of a scientific run without a trace.

Every run begins by resolving a single immutable `RunSpec` from `ape.properties` plus the CLI, exactly once, before the first exploration step. The `RunSpec` carries the preset name (informative), the seed, the run identity, the always-present exploration parameters, the set of active `Feature`s, per-feature parameter objects that are null exactly when the feature is absent, and two digests (effective plan; raw properties input). Resolution is total and fail-fast: an unknown key, a retired key, a type-invalid value, a missing feature dependency, or an invalid combination aborts the process with a diagnostic line before step 1. The plan is organised in five behaviour groups (`guidance-groups`): BASE, which has no key, and ADDONS, STATIC, MOP and LLM, each activated by one boolean group key (`ape.addons`, `ape.static`, `ape.mop`, `ape.llm`). Every member feature declares its group as a dependency, so the feature-derivation rule decides membership with no further mechanism: a default-valued key never activates a feature whose dependencies are unmet, and an explicit one aborts. `STATIC_DATA`, activated by `ape.staticDataPath`, is the only feature that needs the static-analysis artifact; STATIC and MOP depend on it and still switch independently, so the `as` cell reads the artifact with MOP off. Its parameters are the `StaticParams` record, beside `MopParams` and `LlmParams`. The six cell presets (`base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`), each the union of the blocks of its groups, reside in the jar and are selected by an optional `ape.preset` key; when no preset is named the base key vector is empty and the plan is derived from the explicit keys exactly as the jar interprets them — same defaults, same clamps — then validated by the same rules a preset-resolved plan passes. There is one resolution path, not two: the no-preset case is what a run with no properties file at all resolves to, which is what makes a bare standalone invocation of the jar a valid run.

The plan is echoed as the run's provenance: `RUN_START`, a single JSON object line emitted before any exploration output, carrying the effective plan, both digests, the seed, the run id, the harness variant name (`ape.variant`, a resolver key echoed as `RUN_START.variant` and kept out of the plan digest), and the jar's build stamp; its format `v` is `2` (design D-B14.22). The echo is **level 0 by owner decision D1 (final)**: write-only provenance, no automatic validation anywhere, no Python reader — drift auditing is post-hoc analysis over the traces. The line's completeness bar is that it alone reconstructs the arm without consulting `tool.py` (report Sec. 9.6).

`RunContext` holds what is per-run and not exploration state: the `RunSpec`, the run identity, the seeded RNG and the four `StepRng` streams, the event sink, the termination reason and, when `LLM` is in the plan, the LLM units; the model and its trackers stay with the agent ("RunContext Ownership"). Purity is structural: a feature absent from the plan does not exist in the run — no kill-switch, no forced-off registry, no exempt list. This is the substitute for INV-ARCH-06 of the former `scoring-pipeline` text (every RV flag registered in a registry and forced off), which is dissolved: a sub-parameter of an absent feature has nothing to be inert *about*, because the mechanism it would parameterize was never constructed; it is accepted at its neutral value, and an activation key also at any value its feature's own rule reads as off (`<= 0` for a POSITIVE feature such as `WTG`), both reported as inert (design D-B22). The dose keys `ape.llmPercentage`, `ape.mopCoinPercentage` and `ape.componentPercentage` are clamped to `[0, 1]` rather than rejected. Inertness has a second cause, inside a feature that is present: the `MOP` scoring mode (`ape.mopScoring`) selects which of the feature's weight keys are read, and a key the selected mode does not read is accepted at any well-typed value and reported in `RUN_START.inert` (INV-RUN-09).

## Data Contracts

### Input

- `/data/local/tmp/ape.properties`, `/sdcard/ape.properties` — the only behavioral input files `RunSpec.resolve` reads; loaded in that order (later wins); every entry validated (source: device filesystem, pushed by the harness). JVM system properties are not a plan input: `RunSpec` reads none. `Config`, which keeps fields only for keys `src/main` reads through it, falls back to a JVM system property for such a key when no file sets it ("Single Plan Resolution at Bootstrap")
- `--ape <type>: String` — agent type; valid set `{sata, random, replay}`; CLI-only (source: Monkey command line)
- `-s <seed>: long` — run seed; `Monkey.mRandom` is `new Random(seed)` and `RandomHelper` is seeded with `seed ^ RANDOM_HELPER`; absent ⇒ the seed `Monkey` derives for itself (source: Monkey command line)
- `--ape-replay <log>: String` — replay log path, required iff `--ape replay` (source: Monkey command line)
- `ape.preset: String (optional)` — `base | mop | as | as_mop | as_llm | as_mop_llm`; explicit keys override the cell's vector
- `ape.variant: String (optional)` — the harness's variant name; echoed, never read by a decision
- `ape.addons`, `ape.static`, `ape.mop`, `ape.llm: boolean` — group keys, jar default `false`
- `ape.staticDataPath: String (optional)` — the static-analysis artifact path; activates `STATIC_DATA`
- `ape.runId: String (optional)` — host-supplied run identity; absent ⇒ self-generated

### Output

- `RUN_START: one JSON object line` (format `v` 2) — effective plan + digests + seed + runId + t0 + build stamp, plus `variant` when `ape.variant` is set; written to stdout (the host-captured trace); write-only (consumer: post-hoc analysis only)
- `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` — on validation failure, to stderr and stdout, followed by nonzero process exit

### Side-Effects

- **[Process]**: on any validation failure the process exits nonzero before the first exploration step; no agent, event source, or device interaction is constructed.
- **[RNG]**: `RunContext.initialize` performs the single `RandomHelper.seed(seed ^ RANDOM_HELPER)` call for the run; `Monkey.mRandom` keeps `new Random(seed)`, and the four `StepRng` streams are derived from the same seed (INV-RUN-08).

### Error

- `RunSpecException(reason, key, detail)` — raised by `RunSpec.resolve` for every invalid input class; never caught into a degraded run.

## Invariants

- **INV-RUN-01**: The `RunSpec` SHALL be resolved exactly once per process, before the first exploration step, and SHALL be immutable thereafter: no field of `RunSpec` or its parameter objects may change after `resolve` returns, and no component SHALL re-read `ape.properties` to make a behavioral decision after resolution.
- **INV-RUN-02**: Every invalid configuration input — unknown key, non-`ape.` key in a properties file, retired key, type-invalid value, missing feature dependency, invalid combination, unknown `--ape` value, unknown preset name — SHALL abort the process with a nonzero exit and an `[APE-RUNSPEC-ABORT]` diagnostic **before step 1**. No invalid input SHALL degrade to a default.
- **INV-RUN-03**: `RUN_START` SHALL be emitted exactly once, as a single JSON object line, before any exploration output, and its content alone SHALL suffice to reconstruct the arm (active features, effective parameters, agent type, seed) without consulting `tool.py` or any external source. It SHALL be write-only: no runtime component, Java or Python, reads it (owner decision D1, level 0, definitive).
- **INV-RUN-04**: The plan `digest` SHALL be a deterministic function of the effective plan (same effective parameters ⇒ same digest, independent of key order, file split, or preset-vs-explicit expression) and SHALL exclude seed, runId, the corpus basis, the preset name, and the harness variant name `ape.variant` (INV-RUN-10). *(The preset name is named explicitly because it is the exclusion the invariance clause forces and the one an implementation is most likely to hash by accident: a digest that separated `ape.preset=mop` from the same keys written out by hand would record how the plan was written rather than what it is.)* The `propertiesDigest` SHALL be a deterministic function of the raw bytes of the properties files actually read, so that a file differing only in comments or key order is distinguishable from the one the plan digest cannot tell apart.
- **INV-RUN-05**: A feature absent from `features` SHALL have a null parameter object and SHALL have no constructed mechanism in the run (no pass, no stage, no LLM unit, no tracker, no launcher). A sub-parameter key explicitly present for an inactive feature SHALL be accepted at its declared neutral value, and, when it is that feature's activation key, also at any value the feature's activation rule reads as off (`<= 0` for a POSITIVE feature); either is recorded as `inert` in the echo, and any other value SHALL abort per INV-RUN-02 (design D-B22). *(This is the recorded substitute for INV-ARCH-06 of the former `scoring-pipeline` text, which required every RV flag to be registered and forced off and is dissolved: exemption lists are unnecessary because the sub-parameter of an absent feature parameterizes nothing.)*
- **INV-RUN-06**: The jar SHALL read no behavioral input from `/sdcard` other than `/sdcard/ape.properties`. In particular `/sdcard/ape.xpath`, `/sdcard/ape.xpath.actions`, and `/sdcard/ape.strings` SHALL NOT be read by any code path (owner decision D6).
- **INV-RUN-07**: No artifact produced by a previous run SHALL be read by the explorer (R3): there is no model/graph deserialization path, no resume, no read-back of trace or telemetry. Retry after failure is wholly the responsibility of the Python supervisor.
- **INV-RUN-08**: All run-scoped randomness SHALL derive from the run seed owned by `RunContext` (the `-s` value, or the seed `Monkey` derives when `-s` is absent). `Monkey.mRandom` SHALL be `new Random(seed)` and `RandomHelper` SHALL be seeded with `seed ^ RANDOM_HELPER`, a fixed odd 64-bit constant distinct from the four `StepRng` constants, so that the two generators do not produce one sequence at two offsets. Besides these two, the only further streams SHALL be the four `StepRng` streams, each a `Random` seeded with `seed ^ k` for a fixed constant `k` per stream: `llmCoin`, `mopCoin`, `componentCoin` and the fuzz toss (design D-B9). No component SHALL use `ThreadLocalRandom`, `Math.random`, or an unseeded `Random` for any decision or generated input. Coverage: `RngNeutralityTest`, and the existing static guard on bare `Random` sites, which SHALL admit the `StepRng` construction as its one seeded addition.
- **INV-RUN-09**: When the `MOP` feature is in the plan, `ape.mopScoring` SHALL select exactly one of two disjoint key sets as read: `ape.mopWeightDirect`, `ape.mopWeightTransitive` under `flag`; `ape.mopWeightD1`, `ape.mopWeightD2`, `ape.mopWeightD3`, `ape.mopRetireAfter`, `ape.mopLauncherDmax` under `distance`. A key of the set the mode does not select SHALL be accepted at any value of its declared type, SHALL have no effect on the run, and, when the plan states it (in a properties file or through the preset vector), SHALL be listed in `RUN_START.inert`. All other `MOP` keys, `ape.mopScoring` among them, SHALL be read in both modes. When the `MOP` feature is absent, the six Part B keys fall under INV-RUN-05 like every other sub-parameter of an absent feature. Coverage: `RunSpecResolveTest` (inert lists per mode, a non-neutral mode-inert value accepted) and `RunSpecEchoTest`.
- **INV-RUN-10**: `ape.variant` SHALL be owned by the resolver, SHALL be echoed verbatim as `RUN_START.variant` (omitted when absent), SHALL NOT be read by any component that decides an action or builds a mechanism, and SHALL NOT enter the plan `digest`. Two runs that differ only in `ape.variant` SHALL have equal digests. Coverage: `RunSpecEchoTest`, `RunSpecResolveTest`.
- **INV-RUN-11**: The dose keys `ape.llmPercentage` (`p_L`), `ape.mopCoinPercentage` (`p_M`) and `ape.componentPercentage` SHALL be clamped to `[0, 1]` at resolution and SHALL NOT abort for an out-of-range value of their declared type. The clamped value is the effective value echoed in `params` and hashed in the digest. Coverage: `RunSpecResolveTest`.

**Coverage of these invariants.** All eleven are carried by a named test; INV-RUN-08 to INV-RUN-11 name tests in their own text. INV-RUN-01 by `RunSpecResolveTest` (immutability, input-map isolation) and `RunContextTest` (a second `initialize` throws); INV-RUN-02 by `RunSpecAbortTest` across all eight input classes, with the abort *composition* in `Monkey.run` reachable only on a device and deferred to a coordinated end-to-end run after all planned changes on both sides land (recorded in task 3.4); INV-RUN-03 by `RunSpecEchoTest`; INV-RUN-04 by `RunSpecResolveTest`'s six digest tests; INV-RUN-05 by `FeatureDerivationTest` and `RunSpecResolveTest`'s inert-key tests; INV-RUN-06, INV-RUN-07 and INV-RUN-08 by `DeviceInputChannelAbsenceTest`, whose scans read comments out first — so a line that *discusses* a removed mechanism stays legal — and carry a minimum-yield floor, because a scan that silently matches nothing passes by vacuity.

Two of those guards assert more than an absence, and what they assert is worth stating precisely:

- For INV-RUN-07, `noCodePathReadsBackAnArtifactOfAPreviousRun` scans `src/main/java` for `ObjectInputStream`, `readObject` and `readGraph`, and requires none. The retired persistence keys already aborted via `RunSpecAbortTest.everyRetiredKeyAbortsWithItsOwnReason`, and the deletion of `Graph.readGraph` and the `--ape-model` branch is compile-enforced — but neither fact guards against a *new* read-back path, which is what a future "resume" feature would introduce and what this invariant forbids.
- For INV-RUN-08, three mechanisms, three assertions. `ThreadLocalRandom` and `Math.random` must not appear at all. A bare `new Random()` must appear **exactly once**, in `RandomHelper`: that field initializer is genuinely unseeded, but nothing draws from the instance in a real run, because `RunContext.initialize` calls `RandomHelper.seed` before any exploration component exists. Pinning the site is what makes a *second* bare `Random` a failure rather than a silent second stream — an empty-set assertion would be false, and a "no more than N" weakening would stop naming which site is new. Behavioural coverage sits alongside it in `RunContextTest` (establishing the context seeds the run's stream) and `StringCacheSeededTest` (same seed ⇒ same string sequence).

## Requirements

### Requirement: Single Plan Resolution at Bootstrap

`RunSpec.resolve` SHALL run in `Monkey.run` (the `--ape` branch) before `MonkeySourceApe` or any agent is constructed. It SHALL consume: the union of the entries of `/data/local/tmp/ape.properties` and `/sdcard/ape.properties` (load order preserved, later file wins; `Monkey.resolveRunSpec` reads the two files' bytes and nothing else, so no JVM system property enters the plan), and the CLI values (`--ape`, `-s`, `--ape-replay`). It SHALL produce an immutable `RunSpec` value carrying: `presetName` (the `ape.preset` value, or `"explicit"` when absent), `variant` (the `ape.variant` value, or null), `seed`, `runId`, `agentType`, `ExplorationParams` (always present), `Set<Feature>`, `StaticParams`/`MopParams`/`LlmParams` (each null iff its feature — `STATIC_DATA`, `MOP`, `LLM` — is absent), `TelemetryParams` (always present), `digest`, and `propertiesDigest`.

After resolution, `RunSpec` is the behavioral authority. `Config` keeps a final static field only for a key that `src/main` reads through it (design D-B24). It loads the same two files into a `Properties` whose defaults are `System.getProperties()` (its static initializer, inherited from upstream APE): a file entry `Config` reads is therefore one `RunSpec.resolve` validated first, but a `Config`-read key that no file sets falls back to a JVM system property of the same name when one is set (for example `-Dape.defaultGUIThrottle=…` on the `app_process` command line), and that value is neither validated by `RunSpec` nor echoed in `RUN_START`. No deployment sets such a property; the exception is recorded as an open question (design OQ2). The `RunSpec` SHALL be reachable via `RunContext.current().spec()`. Whether a mechanism of a behaviour group acts SHALL be read from the plan, never from `Config` (`guidance-groups` INV-GRP-06). The same holds for three BASE keys that `Config` still mirrored (design D-B6, decision A10): `ape.doFuzzing` and `ape.fuzzingRate` SHALL be read as `spec.has(Feature.FUZZING)` and `ExplorationParams.fuzzingRate()` (the `FUZZING`-owned key) in `MonkeySourceApe.generateEvents`, and `ape.checkRestart` SHALL be read from `ExplorationParams.checkRestart()` in `ApeAgent.checkRestart`. The values and defaults are unchanged (`true`, `0.02`, `true`), so no behaviour changes; `Config` SHALL NOT keep twins of these keys.

#### Scenario: plan resolved once before the loop

- **WHEN** the process is launched with `--ape sata -s 42` and a valid `ape.properties`
- **THEN** `RunSpec.resolve` SHALL complete before `MonkeySourceApe` is constructed
- **AND** exactly one `RunSpec` SHALL exist for the process lifetime
- **AND** no code path SHALL mutate it or re-resolve it

#### Scenario: BASE fuzzing and restart switches come from the plan

- **WHEN** a properties file sets `ape.doFuzzing=false` and `ape.checkRestart=false` and nothing else
- **THEN** `features` SHALL NOT contain `FUZZING`, `MonkeySourceApe` SHALL generate no fuzzing event, and the `RESTART` guard SHALL NOT be mounted (design D-B14.12)
- **AND** no site SHALL read `Config.doFuzzing`, `Config.fuzzingRate` or `Config.checkRestart`

#### Scenario: later file wins, both validated

- **WHEN** `/data/local/tmp/ape.properties` sets `ape.defaultEpsilon=0.05` and `/sdcard/ape.properties` sets `ape.defaultEpsilon=0.10`
- **THEN** the effective `ExplorationParams` epsilon SHALL be `0.10`
- **AND** every key of both files SHALL have been validated

#### Scenario: parameter records follow their features

- **WHEN** a properties file sets `ape.staticDataPath=/data/local/tmp/mop-artifact.json` and `ape.static=true`, and no `ape.mop` or `ape.llm`
- **THEN** `StaticParams` SHALL be non-null and SHALL report the path `/data/local/tmp/mop-artifact.json`
- **AND** `MopParams` and `LlmParams` SHALL be null

#### Scenario: a JVM system property is not a plan input

- **WHEN** the process runs with a JVM system property `ape.graphStableRestartThreshld=200` set and no properties file names that key
- **THEN** resolution SHALL succeed, because `RunSpec.resolve` reads only the two files
- **AND** the key SHALL appear neither in `RUN_START.params` nor in `RUN_START.inert`

### Requirement: Feature Model with Declared Dependencies

`Feature` SHALL be an enum whose constants carry, as constructor data: an activation rule (a key plus the value shape that activates it, or a derived predicate such as "`ape.staticDataPath` present" for `STATIC_DATA`), the set of sub-parameter keys the feature owns, the declared dependencies as an `EnumSet<Feature>`, and the neutral (off/zero/sentinel) value of each owned key.

The declared dependencies SHALL be at minimum:

- `STATIC` and `MOP` require `STATIC_DATA`; `ADDONS`, `LLM` and `STATIC_DATA` are roots;
- `MODEL_MENU`, `FORM_COMPLETION`, `TREE_ENHANCEMENTS`, `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`, `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD`, `COVERAGE_BOOST`, `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART` and `APE_FIXES` require `ADDONS`;
- `NAV_FRONTIER`, `TYPED_FUZZ` and `COMPONENT_TRIGGER` require `STATIC`;
- `WTG`, `MOP_FRONTIER`, `MOP_LAUNCHER`, `MOP_ACTIVITY_SOURCE` and `MOP_COIN` require `MOP`; `MENU_GATEWAY` requires `MOP` and `MODEL_MENU`;
- `LLM_NEW_STATE`, `LLM_STAGNATION` and `LLM_COIN` require `LLM`;
- `FUZZING` has no dependency (BASE).

`Feature.ACTIVITY_TRIGGER` is renamed `MOP_LAUNCHER`, because it mounts only `MopLauncherStage`; its activation key `ape.activityTriggerEnabled` and its sub-parameters keep their names (decision A6). `MopParams.activityTriggerEnabled()`, which has no caller, SHALL be deleted: the launcher's presence is `spec.has(Feature.MOP_LAUNCHER)`.

`Feature.FRONTIER`, `Feature.LLM_RANDOM` and `Feature.LEAST_VISITED_TIEBREAK` SHALL NOT exist; `NAV_FRONTIER` and `LLM_COIN` replace the first two under their unchanged keys, and the scorer-sum tie X1 (`action-selection`) replaces the third.

Key ownership SHALL be total: every `ape.*` key is owned by exactly one of `ExplorationParams`, one `Feature`, or the resolver itself (`ape.preset`, `ape.variant`, `ape.runId`, `ape.corpusBasis`); a totality test SHALL fail if a key the jar reads is unowned. The test SHALL derive the roster from the source tree rather than from a maintained list — a list would be written from the same documents that omit a key, which is how `ape.baseNaming` and `ape.nopActionThrottle` went undeclared through three audits of this change. The three string registries (`rvForcedOffValues`, `rvUnsetKeys`, `rvExemptReasons`) and the `apePureMode` forcing mechanism SHALL NOT exist; the `Feature` metadata is the single, compiler-visible replacement.

Dependency validation SHALL distinguish explicit input from defaults: an **explicitly-set** activation key or non-neutral sub-parameter whose feature's dependencies are unmet aborts (INV-RUN-02); a **default-valued** key never activates a feature whose dependencies are unmet — the feature is simply absent from the plan.

**Group, data and member keys** (`isolate-guidance-groups`, design D-B1, D-B5, D-B8, D-B10):

| Key | Owner | Type | Jar default | Neutral | Notes |
|---|---|---|---|---|---|
| `ape.addons` | `ADDONS` (TRUE) | bool | `false` | `false` | |
| `ape.static` | `STATIC` (TRUE) | bool | `false` | `false` | |
| `ape.mop` | `MOP` (TRUE) | bool | `false` | `false` | |
| `ape.llm` | `LLM` (TRUE) | bool | `false` | `false` | requires `ape.llmUrl` |
| `ape.staticDataPath` | `STATIC_DATA` (PRESENT) | path | null | — | formerly `ape.mopDataPath`; inert when neither `STATIC` nor `MOP` is mounted (D-B14.20) |
| `ape.mopStrictPackageMatch` | `STATIC_DATA` | bool | `false` | `false` | keeps its name; moves from `MOP` because the package check is on the static model (D-B14.3) |
| `ape.llmUrl` | `LLM` | url | null | none | a plan with `LLM` and no URL aborts |
| `ape.mopCoinPercentage` | `MOP_COIN` (POSITIVE) | double | `1.0` | `0` | `p_M`, clamped to `[0,1]` |
| `ape.mopCoinLedgerMaxInteractions` | `MOP_COIN` | int | `1` | `1` | executions per ledger key the coin may take; `< 1` clamped to `1` at resolution, like the other plan clamps (`mop-guidance`, design D-B17) |
| `ape.llmGateMinActions` | `LLM` | int | `3` | `3` | `0` = gate not mounted |
| `ape.llmBreakerFailures` | `LLM` | int | `3` | `3` | `0` = gate not mounted |
| `ape.llmBreakerOpenMs` | `LLM` | long | `60000` | `60000` | |
| `ape.backMenuPickCap` | `BACK_MENU_CAP` (POSITIVE) | int | `3` | `0` | formerly a base key; absent ⇒ `backMenuPickCap()` returns `0` (no cap) |
| `ape.activityStableRestartThreshold` | `ACTIVITY_STABLE_RESTART` (POSITIVE) | int | `200` | `0` | formerly a base key; absent ⇒ `Integer.MAX_VALUE` |
| `ape.apeFixes` | `APE_FIXES` (TRUE) | bool | `true` | `false` | the fork's corrections of APE's own behaviour (`guidance-groups` "Declared Consequences of the Cell Composition"); absent ⇒ APE's code at each of its points; the ADDONS block states `true` |
| `ape.frontierBoostWeight` | `NAV_FRONTIER` (POSITIVE) | int | `200` | `0` | formerly owned by `FRONTIER` (MOP) |
| `ape.fuzzInputTyped` | `TYPED_FUZZ` (TRUE) | bool | `true` | `false` | |
| `ape.componentPercentage` | `COMPONENT_TRIGGER` (POSITIVE) | double | `0.0` | `0` | formerly owned under `MOP`; the STATIC block sets `0.03` (D-B13) |
| `ape.llmPercentage` | `LLM_COIN` (POSITIVE) | double | `0.02` | `0` | `p_L`, clamped to `[0,1]` |
| `ape.mopWeightWtg` | `WTG` (POSITIVE) | int | `200` | `0` | a value `<= 0` leaves `WTG` out of the plan, with no abort, on a plan with or without `MOP` (design D-B22); no feature uses a non-zero rule (`Rule.NONZERO` does not exist) |

The other keys of the former addon features keep their names and owners; those features gain the `ADDONS` dependency. The MOP family keeps its keys and gains no other dependency than `MOP` (and `MODEL_MENU` for `MENU_GATEWAY`); `COMPONENT_TRIGGER` leaves it for `STATIC`, with its keys unchanged.

**The MOP scoring keys** (`llm-coordinate-single-base`, design D18–D20). The `MOP` feature SHALL own six further sub-parameters, declared in `KeyOwnership` beside `ape.mopWeightDirect`/`ape.mopWeightTransitive`, registered with their neutral values in `Feature.MOP`, and read through `RunSpec.MopParams`:

| Key | Type | Default | Neutral | Read under |
|---|---|---|---|---|
| `ape.mopScoring` | string, `flag` \| `distance` | `flag` | `flag` | both modes |
| `ape.mopWeightD1` | int | `500` | `0` | `distance` (weight for a nearest unretired target at `d ≤ 1`) |
| `ape.mopWeightD2` | int | `400` | `0` | `distance` (`d = 2`) |
| `ape.mopWeightD3` | int | `300` | `0` | `distance` (`d = 3`) |
| `ape.mopRetireAfter` | int | `3` | `3` | `distance` (first interactions after which a target retires, `mop-guidance` "Target Retirement") |
| `ape.mopLauncherDmax` | int | `6` | `6` | `distance` (largest distance the launcher orders by, `component-triggering` "Cadence-Based MOP Activity Launch") |

`ape.mopWeightDirect` (500) and `ape.mopWeightTransitive` (300) are read under `flag` only. The neutral value is the one INV-RUN-05 accepts when `MOP` is absent: zero for a weight, as for `ape.mopWeightDirect`, and the default for the mode, the retirement count and the launcher bound.

**Inert by mode** (INV-RUN-09). With `MOP` in the plan, a `MOP` key that the selected mode does not read SHALL be accepted at any value of its declared type and SHALL be listed in `RUN_START.inert` when the plan states it, whether in a properties file or through the preset vector. It is not subject to INV-RUN-05's neutral-value rule, which concerns an absent feature: here the feature exists, and the plan may state both modes' weights. The key keeps its effective value in the plan, so it appears in `params` when non-default and enters the plan digest; `inert` is what says it parameterizes nothing. `ape.mopScoring` itself is never inert while `MOP` is in the plan.

#### Scenario: dependent feature validated

- **WHEN** `ape.properties` sets `ape.addons=true`, `ape.mop=true`, `ape.staticDataPath=/data/local/tmp/mop-artifact.json`, `ape.mopWeightOpenMenu=250` and `ape.modelMenuEnabled=false`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `MENU_GATEWAY` and `MODEL_MENU`

#### Scenario: default does not activate an impossible feature

- **WHEN** no properties file sets `ape.activityTriggerEnabled` (jar default `true`) and `ape.mop` is absent
- **THEN** resolution SHALL succeed with `MOP_LAUNCHER` absent from `features`
- **AND** no launcher mechanism SHALL be constructed

#### Scenario: neutral inert sub-parameter accepted, non-neutral aborts

- **WHEN** a properties file without `ape.llm` contains `ape.llmGateMinActions=3` (the key's neutral value)
- **THEN** resolution SHALL succeed, the key SHALL appear in the echo's `inert` list, and `LlmParams` SHALL be null
- **AND WHEN** the same file instead contains `ape.llmOnNewState=true` with no `ape.llm`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM`
- **AND WHEN** the same file instead contains `ape.llmPercentageNoSubstrate=-1`, the former neutral sentinel that every baseline arm used to push
- **THEN** resolution SHALL abort with `reason=retired_key key=ape.llmPercentageNoSubstrate` (design D-B24): the key is no longer an inert sub-parameter but a retired key, whatever its value

#### Scenario: a negative WTG weight leaves WTG out without aborting

- **WHEN** a plan with `MOP` states `ape.mopWeightWtg=-50`
- **THEN** resolution SHALL succeed with `WTG` absent from `features`, and `WtgPass` SHALL NOT be constructed
- **AND WHEN** a plan without `MOP` states `ape.mopWeightWtg=-50`
- **THEN** resolution SHALL succeed with `WTG` absent, and SHALL NOT abort with `missing_dependency`, because a non-positive value does not activate the feature (design D-B22)

#### Scenario: the six MOP scoring keys resolve to their defaults

- **WHEN** a properties file sets `ape.staticDataPath`, `ape.mop=true` and none of the six keys
- **THEN** `MopParams` SHALL report scoring mode `flag`, weights 500/400/300, retirement after 3 and launcher bound 6
- **AND** `RUN_START.inert` SHALL list none of them, since the plan states none

#### Scenario: distance arm from the mop preset

- **WHEN** `ape.properties` contains `ape.preset=mop` and `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** resolution SHALL succeed with `MOP` active and scoring mode `distance`, which the MOP block states
- **AND** `RUN_START.inert` SHALL list neither `ape.mopWeightDirect` nor `ape.mopWeightTransitive`, since no cell vector states them
- **AND WHEN** the same file adds `ape.mopWeightDirect=500`
- **THEN** resolution SHALL succeed and `RUN_START.inert` SHALL list `ape.mopWeightDirect`

#### Scenario: distance keys stated under flag are inert, not rejected

- **WHEN** a properties file sets `ape.staticDataPath`, `ape.mop=true`, `ape.mopScoring=flag`, `ape.mopWeightD2=450` and `ape.mopRetireAfter=5`
- **THEN** resolution SHALL succeed, both keys SHALL be listed in `RUN_START.inert`, and the run SHALL score with `ape.mopWeightDirect`/`ape.mopWeightTransitive` and retire no target

#### Scenario: scoring keys of an absent MOP feature

- **WHEN** a properties file without `ape.mop` sets `ape.mopScoring=flag`
- **THEN** resolution SHALL succeed and list the key in `RUN_START.inert`, `flag` being its neutral value
- **AND WHEN** the same file instead sets `ape.mopScoring=distance`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mopScoring` (INV-RUN-05)

#### Scenario: the frontier depends on STATIC, not on MOP

- **WHEN** a properties file sets `ape.staticDataPath`, `ape.static=true`, `ape.frontierBoostWeight=200` and no `ape.mop`
- **THEN** `features` SHALL contain `NAV_FRONTIER` and no MOP member
- **AND WHEN** the file instead sets `ape.frontierBoostWeight=200` with no `ape.static`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.frontierBoostWeight` naming `STATIC`

#### Scenario: component triggering needs STATIC, not MOP

- **WHEN** a properties file sets `ape.staticDataPath`, `ape.static=true` and `ape.componentPercentage=0.03`, and no `ape.mop`
- **THEN** `features` SHALL contain `COMPONENT_TRIGGER`
- **AND WHEN** the file instead sets `ape.mop=true` and `ape.componentPercentage=0.03` with no `ape.static`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.componentPercentage` naming `STATIC`

#### Scenario: an absent BACK/MENU cap reads as no cap

- **WHEN** the `base` cell resolves, so `BACK_MENU_CAP` is absent from `features`
- **THEN** the plan's `backMenuPickCap()` SHALL return `0`, the neutral value, and not the jar default `3`
- **AND** no BACK or MENU action SHALL be filtered by a pick cap

#### Scenario: APE_FIXES follows ADDONS

- **WHEN** a properties file sets `ape.addons=true` and does not set `ape.apeFixes`
- **THEN** resolution SHALL succeed with `APE_FIXES` in `features`, its jar default `true` activating it
- **AND WHEN** a properties file sets neither `ape.addons` nor `ape.apeFixes`
- **THEN** resolution SHALL succeed with `APE_FIXES` absent and no abort
- **AND WHEN** a properties file sets `ape.apeFixes=true` without `ape.addons`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.apeFixes` naming `ADDONS`

#### Scenario: an LLM gate parameter at zero unmounts the gate

- **WHEN** a plan with `LLM` sets `ape.llmGateMinActions=0`
- **THEN** resolution SHALL succeed, `LlmParams` SHALL report `0`, and the minimum-actions gate SHALL NOT be mounted (`llm-routing`)

### Requirement: Presets Resident in the Jar

`Presets.resolve(name)` SHALL return the base key vector for exactly six cell presets: `base`, `mop`, `as`, `as_mop`, `as_llm` and `as_mop_llm`. Each vector SHALL be the union of the group blocks of its cell, as tabulated in `guidance-groups` "Cell Presets as Unions of Group Blocks": `base` = no block (the empty vector); `mop` = MOP; `as` = ADDONS + STATIC; `as_mop` = ADDONS + STATIC + MOP; `as_llm` = ADDONS + STATIC + LLM; `as_mop_llm` = all four. The presets `aperv`, `llm` and `llm_mop`, and the former content of `mop`, SHALL NOT exist. Deployment-specific values (`ape.staticDataPath`, `ape.llmUrl`, `ape.variant`, seed, runId) SHALL NOT be part of any preset and MUST come explicitly.

No preset SHALL carry an agent type. A cell's agent is `sata`, and that is a property of the resolved plan rather than an entry of the vector: the agent type is a command-line value, `ape.agentType` is retired as a file key, and `sata` is what an absent `--ape` already resolves to — so a preset entry for it could only duplicate the default or contradict the command line.

A preset that states a feature's gates ON while the mechanism they parameterize is absent SHALL abort like any other plan. In particular `ape.preset=as_llm` with no explicit `ape.llmUrl` aborts, because the preset carries `ape.llm=true`; and `ape.preset=as` with no `ape.staticDataPath` aborts, because the preset carries `ape.static=true`. A preset that resolved instead to "LLM off" or "STATIC off" would be the silent degradation this capability exists to end.

When `ape.preset` is present, explicit keys SHALL override the preset vector, and the merged result SHALL pass the same validation as an explicit plan. An unknown preset name SHALL abort. Ablations and dose variants are expressed as overrides on a cell, never as new presets.

#### Scenario: preset plus override

- **WHEN** `ape.properties` contains `ape.preset=mop`, `ape.staticDataPath=/data/local/tmp/mop-artifact.json`, and `ape.mopWeightWtg=0`
- **THEN** the effective plan SHALL be the `mop` cell with `mopWeightWtg` overridden to `0`
- **AND** the echo's `preset` field SHALL be `"mop"` and `params` SHALL show the override

#### Scenario: unknown preset aborts

- **WHEN** `ape.properties` contains `ape.preset=llm_mop`
- **THEN** resolution SHALL abort with an unknown-preset diagnostic listing the six valid names `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`

#### Scenario: a cell without its data aborts

- **WHEN** `ape.properties` contains `ape.preset=as` and no `ape.staticDataPath`
- **THEN** resolution SHALL abort with `reason=missing_dependency` before step 1
- **AND** the run SHALL NOT resolve to a plan without `STATIC`

### Requirement: Explicit-Key Resolution When No Preset Is Named

When `ape.preset` is absent, the plan SHALL be derived from the explicit `ape.*` keys exactly as the jar interprets them — same defaults, same clamps — and then validated against the same rules a preset-resolved plan passes. There is one resolution path: the preset, when named, contributes a base key vector; its absence means the base vector is empty, not that a different resolver runs.

This case is not a compatibility affordance and does not depend on any particular caller. It is what a run with no properties file resolves to, and it is the reason a bare standalone invocation of the jar is a valid run: the plan is the jar's declared defaults, validated like any other. Because every group key defaults to `false`, the jar's defaults are the `base` cell's plan.

#### Scenario: current campaign arm resolves unchanged

- **WHEN** a properties file sets `ape.staticDataPath`, `ape.mop=true` and `ape.mopWeightDirect=500` and names no `ape.preset`
- **THEN** resolution SHALL succeed with `preset="explicit"`, the `MOP` feature active, and every unset key at its jar default
- **AND** the resulting plan SHALL pass exactly the validation a preset-resolved plan passes

#### Scenario: the Python edit precedes the jar

- **WHEN** a properties file carrying `ape.mopDataPath` reaches this jar — the state a deployment is in whenever
  this jar is installed against an `aperv-tool` that still writes the former key
- **THEN** resolution SHALL abort with `reason=retired_key key=ape.mopDataPath` before step 1,
  rather than resolving and running as some other plan
- **AND** the ordering SHALL therefore be a deployment precondition and not a preference

#### Scenario: bare standalone run

- **WHEN** the jar is launched with no properties file at all
- **THEN** resolution SHALL succeed from the jar defaults alone, with `features` equal to `[FUZZING]`, the `base` cell's set
- **AND** the effective plan SHALL be echoed in `RUN_START` like any other run

### Requirement: Total Fail-Fast Validation

`RunSpec.resolve` SHALL abort (nonzero exit, `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` to stderr and stdout, before step 1) on every one of the following input classes — and the abort SHALL happen before any agent, event source, or device interaction is constructed:

1. **Unknown key**: an `ape.*` key in a properties file that no owner declares.
2. **Foreign key**: any non-`ape.` key in a properties file.
3. **Retired key** (dedicated message naming the replacement): `ape.apePureMode` (purity is structural — owner decision D3), `ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates` (persistence protocol removed), `ape.enableXPathAction` (injection channel removed), `ape.mopWeightActivity` (dead key), `ape.llmBoundaryBottomPct` (on the display base the jar maps answers in, the navigation bar is outside every reachable pixel, so the band only rejected app content — `llm-coordinate-single-base`), `ape.agentType` and `ape.replayLog` when present in a **file** (CLI-only values — closes the `/sdcard` agent-swap hole), and the four keys retired by `isolate-guidance-groups`:
   - `ape.mopDataPath` — "renamed ape.staticDataPath: the artifact feeds STATIC and MOP (isolate-guidance-groups)";
   - `ape.leastVisitedPriorityTiebreak` — "replaced by the scorer-sum tie (X1/R2), which equals APE with zero scorers";
   - `ape.mopTargetPickCap` — "replaced by `ape.mopCoinLedgerMaxInteractions`, the MOP coin's budget of picks per activity and widget (default 1)";
   - `ape.llmPercentageNoSubstrate` — "it never had a consumer: the widgetless-substrate predicate it waited for was never derived" (design D-B24). The abort is on the key's name, like every retired key, not on its value.
4. **Invalid type**: a non-numeric value for a numeric key; a boolean key whose value is not literally `true`/`false` (case-insensitive); a value of `ape.mopScoring` other than exactly `flag` or `distance` (the one string key with a closed value set; the diagnostic names the valid set — `llm-coordinate-single-base`, design D18). Documented value-semantics clamps (cadence `<= 0`, launch cap `< 0`, `ape.mopCoinLedgerMaxInteractions < 1`, the percentage clamps of `ape.llmPercentage`, `ape.mopCoinPercentage` and `ape.componentPercentage` to `[0,1]`) remain clamps, not aborts (INV-RUN-11).
5. **Missing dependency / invalid combination**: per the Feature Model requirement — including a group key stated `true` whose data root or URL is missing (`ape.static`/`ape.mop` without `ape.staticDataPath`, `ape.llm` without `ape.llmUrl`); plus `--ape replay` without `--ape-replay`.
6. **Unknown `--ape` value**: any value outside `{sata, random, replay}` — including `bfs`, `dfs`, and `ape` — aborts with a diagnostic naming the valid set. The silent `SataAgent` fallback SHALL NOT exist.

#### Scenario: unknown key aborts before step 1

- **WHEN** `ape.properties` contains `ape.graphStableRestartThreshld=200` (typo)
- **THEN** the process SHALL exit nonzero with `[APE-RUNSPEC-ABORT] reason=unknown_key key=ape.graphStableRestartThreshld`
- **AND** no exploration step SHALL have run and no agent SHALL have been constructed

#### Scenario: invalid type aborts instead of degrading

- **WHEN** `ape.properties` contains `ape.doFuzzing=ture`
- **THEN** the process SHALL abort with `reason=invalid_type`, instead of silently reading `false`

#### Scenario: missing dependency aborts

- **WHEN** `ape.properties` contains `ape.activityTriggerEnabled=true` and no `ape.mop`
- **THEN** the process SHALL abort with `reason=missing_dependency` naming `MOP_LAUNCHER` and `MOP`

#### Scenario: an LLM URL without the LLM group aborts

- **WHEN** `ape.properties` contains `ape.llmUrl=http://10.0.2.2:30000/v1` and no `ape.llm`
- **THEN** the process SHALL abort with `[APE-RUNSPEC-ABORT] reason=missing_dependency key=ape.llmUrl` and a detail naming `LLM` (design D-B14.4), because the URL has no neutral value
- **AND** the run SHALL NOT resolve to a plan without `LLM` that ignores the URL

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

#### Scenario: retired bottom boundary band aborts with its reason

- **WHEN** `ape.properties` contains `ape.llmBoundaryBottomPct=0.94`
- **THEN** the process SHALL abort with `reason=retired_key key=ape.llmBoundaryBottomPct`
- **AND** the detail SHALL state that the display excludes the navigation bar and that the status-bar band `ape.llmBoundaryTopPct` is the only boundary band

#### Scenario: unknown MOP scoring mode aborts

- **WHEN** `ape.properties` contains `ape.staticDataPath=/data/local/tmp/mop-artifact.json`, `ape.mop=true` and `ape.mopScoring=nearest`
- **THEN** the process SHALL abort with `[APE-RUNSPEC-ABORT] reason=invalid_type key=ape.mopScoring` and a detail naming the valid set `{flag, distance}`
- **AND** the run SHALL NOT fall back to `flag`

#### Scenario: keys retired by the group split abort with their reasons

- **WHEN** `ape.properties` contains `ape.leastVisitedPriorityTiebreak=true`
- **THEN** the process SHALL abort with `reason=retired_key key=ape.leastVisitedPriorityTiebreak` and a detail naming the scorer-sum tie (X1/R2)
- **AND WHEN** it contains `ape.mopTargetPickCap=3` instead
- **THEN** the process SHALL abort with `reason=retired_key key=ape.mopTargetPickCap` and a detail naming `ape.mopCoinLedgerMaxInteractions`
- **AND WHEN** it contains `ape.llmPercentageNoSubstrate=0.5` instead
- **THEN** the process SHALL abort with `reason=retired_key key=ape.llmPercentageNoSubstrate` and a detail stating that the key had no consumer

#### Scenario: an out-of-range dose is clamped, not rejected

- **WHEN** a plan with `MOP` sets `ape.mopCoinPercentage=1.5`
- **THEN** resolution SHALL succeed with an effective `p_M` of `1.0`
- **AND WHEN** it sets `ape.mopCoinPercentage=abc`
- **THEN** resolution SHALL abort with `reason=invalid_type key=ape.mopCoinPercentage`

### Requirement: Level-0 RUN_START Echo

Immediately after successful resolution and before any exploration output, the jar SHALL emit `RUN_START` as a single JSON object line to stdout: `type`, format version `v`, `run_id`, `t0` (device epoch milliseconds at emission — the base against which the step records' relative `t` offsets resolve), `seed`, `agent`, `preset` (name or `"explicit"`), `variant` (the `ape.variant` value, omitted when the key is absent; INV-RUN-10), `features` (sorted names), `params` (every effective non-default parameter plus every active feature's activation key, as `ape.*` keys with effective values, except an inert `ape.staticDataPath`, which is listed in `inert` only), `inert` (sorted; the stated keys that parameterize nothing in this run: the accepted neutral keys of inactive features, INV-RUN-05, the `MOP` keys the selected `ape.mopScoring` mode does not read, INV-RUN-09, and `ape.staticDataPath` when neither `STATIC` nor `MOP` is mounted, design D-B14.20), optional `corpus_basis`, `digest`, `props_digest`, and `build` (`sha`, `time` from the build stamp).

The format version `v` SHALL be `2` from `isolate-guidance-groups` on (`RunSpecEcho.FORMAT_VERSION`, design D-B14.22): `dec.src` changes meaning from "largest boost" to "executed owner", and the reader keys its strict mode on `v`. The format version `v` is not decoration and is worth one sentence of justification, because its absence has already cost something. Traces from different campaigns of this study carry materially different `[APE-STEP]` schemas — the earlier calibration corpus has no `mop_frontier`, `pick_channel`, `patched` or counterfactual fields, and its `mop=` realises `{0,300}` where the decisive campaign realises `{0,500}` — and nothing in either trace says which schema it is. `v` is what makes a cross-campaign comparison fail loudly instead of quietly comparing incomparable fields. The same holds within one jar from `llm-coordinate-single-base` on: the `mop` boost of a step means a flag weight under `ape.mopScoring=flag` and a distance weight under `distance`, so the scoring mode is part of what the echo must let an analyst read. It is, because `ape.mopScoring` is a parameter of the `MOP` feature: present in `params` when it is `distance`, and `flag` by its absence. From `isolate-guidance-groups` on, `features` also names the groups (`ADDONS`, `STATIC`, `MOP`, `LLM`) and the data root (`STATIC_DATA`), so the cell is readable from the line; `variant` names the harness variant that produced it, and `build` tells a pre-split jar, whose `dec.src` means "largest boost", from a post-split one, whose `dec.src` is the executed owner.

The line SHALL be produced by a serializer that escapes quotes, backslashes, and control characters and never emits a raw newline inside the record (one-record-one-line by construction) — the same serializer the NDJSON sink uses. The echo is write-only provenance (owner decision D1, level 0, definitive): no validation of the line anywhere, no new communication channel — the physical flow remains `adb push` + stdout capture. The line precedes every APE-RV record and all exploration output; pre-existing AOSP Monkey banner lines may still precede it.

#### Scenario: the line alone reconstructs the arm

- **WHEN** a run executes the `as_mop_llm` cell with `ape.variant=as_mop_llm`
- **THEN** the `RUN_START` line SHALL contain the agent type, seed, all active features (`ADDONS` and its members, `STATIC_DATA`, `STATIC` and its members, `MOP` and its members, `LLM` and its members), the MOP scoring mode (`ape.mopScoring:"distance"` in `params`) and the MOP weights that mode reads, the doses `ape.llmPercentage`, `ape.mopCoinPercentage` and `ape.componentPercentage`, the LLM sampling parameters and url, `variant:"as_mop_llm"`, and the build sha
- **AND** an analyst reading only that line SHALL be able to state which cell ran, without `tool.py`, including whether the MOP weights in force are the flag weights or the distance weights

#### Scenario: echo precedes exploration output

- **WHEN** any valid run starts
- **THEN** `RUN_START` SHALL appear in the trace before any `[APE-*]` line and before any step output

#### Scenario: hostile values cannot break the line

- **WHEN** a properties value contains a quote or would carry a control character
- **THEN** the emitted `RUN_START` SHALL remain exactly one line and SHALL parse as JSON

#### Scenario: stale-jar drift visible (gh71 class)

- **WHEN** an outdated jar is deployed to the device
- **THEN** the `build.sha` of every trace it produces identifies the stale build, making the drift diagnosable post-hoc from the traces alone

#### Scenario: weights the mode does not read are marked inert

- **WHEN** a run resolves `ape.preset=as_mop_llm` (scoring mode `distance`) and the properties file adds `ape.mopWeightDirect=500` and `ape.mopWeightTransitive=300`
- **THEN** `RUN_START.params` SHALL carry `ape.mopScoring:"distance"`
- **AND** `RUN_START.inert` SHALL list `ape.mopWeightDirect` and `ape.mopWeightTransitive`

#### Scenario: the variant is echoed and kept out of the digest

- **WHEN** two runs resolve `ape.preset=as` with the same artifact path, one with `ape.variant=as` and one with `ape.variant=as_rerun`
- **THEN** their `RUN_START.variant` members SHALL be `"as"` and `"as_rerun"`
- **AND** their `digest` values SHALL be equal
- **AND WHEN** a run states no `ape.variant`
- **THEN** its `RUN_START` SHALL have no `variant` member

### Requirement: Run Identity and Seed

`RunSpec.seed` SHALL be the seed `Monkey` resolved — the `-s` value when one was passed, and otherwise the value `Monkey` derived for itself — and `RunContext.initialize` SHALL be the single point that seeds `RandomHelper`, with `RandomHelper.seed(seed ^ RANDOM_HELPER)`, where `RANDOM_HELPER` is a fixed odd 64-bit constant distinct from the four `StepRng` constants (INV-EXPL-14). `Monkey.mRandom` SHALL remain `new Random(seed)`, as APE builds it, so that the two generators do not produce one sequence at two offsets; `RUN_START.seed` SHALL be the run seed, not `seed ^ RANDOM_HELPER`. `runId` SHALL be the `ape.runId` value when present (a recognized key; no current deployment pushes it) and otherwise self-generated, deterministic in format (`<utc-compact>-<seed>-<digest-prefix>`), and echoed in `RUN_START`.

**Recording the seed and choosing it are two different things.** The harness appends `-s <seed>` only when a seed is configured (`tool.py`), and the decisive campaign configured none — the flag appears in none of its 360 traces except as a widget resource id in one application. `Monkey` does not run unseeded when `-s` is absent: it derives one, `mSeed = System.currentTimeMillis() + System.identityHashCode(this)`, in `Monkey.run` before the `--ape` branch is reached, and both `mRandom` and, through `RunContext.initialize`, `RandomHelper` are built from that value: `mRandom` from the value itself, `RandomHelper` from the value XOR `RANDOM_HELPER`. `RUN_START` records no *absence* of a seed, because there is none: `RunSpec.seed` is always a real number and `RUN_START.seed` is always full.

What a derived seed lacks is not reproducibility but **control**. A run is individually reproducible from its own trace, bounded by what a seed does not reach: the trajectory depends on the observed GUI tree and therefore on device timing. Every draw comes from a seeded stream (INV-RUN-08: the one bare `new Random()`, `RandomHelper`'s field initializer, is replaced by `RunContext.initialize` before any exploration component exists), so the parity oracle's premise — the same decisions *under the same seed* — is satisfiable per run. An incidental seed, however, is reproducible only after the fact, and cannot be used to pair two arms on the same randomness; that needs a seed the harness designs.

**When the harness designs seeds, a single constant seed across the campaign SHALL NOT be the design.** The three replicas per (application, arm) are the only estimator of run-to-run variation the design has; pinning one seed for all runs would collapse that estimator to zero and buy per-run reproducibility with the loss of the noise floor every effect size is read against. The seed SHALL be a deterministic function of the run's identity — application, arm and replica — so that each run is individually reproducible while replicas still sample the nondeterminism of device timing, which no seed reaches.

**`corpus_basis`.** `RUN_START` SHALL carry `corpus_basis` — an identifier plus a `sha256` of the application name-list the run was drawn from — when the `ape.corpusBasis` key is present. It is a recognized key like `ape.runId`, supplied by the harness and echoed unread, and it is omitted when absent rather than defaulted. Two strings once per run retire a class of error this corpus has already produced: the same study has counted its analysis basis as 163, 181 and 219 applications in different documents, and the reconciliation cost falls out of every analysis that has to re-derive which list a run belonged to. This is deliberately *not* a per-application block of frozen static facts: the basis is a property of the corpus, one hash identifies it, and freezing per-app copies would put the same truth in forty places.

#### Scenario: self-generated identity when the host supplies none

- **WHEN** the `tool.py` deployment launches a run (it pushes no `ape.runId`, before or after the stage-2 edit)
- **THEN** the jar SHALL generate a `run_id` and echo it in `RUN_START`

#### Scenario: no seed configured on the command line

- **WHEN** the harness launches a run without `-s`, as every run of the decisive campaign was launched
- **THEN** `RUN_START` SHALL record the seed `Monkey` derived for the run, which is the value `mRandom` was built from and the value `RandomHelper`'s seed was derived from (`seed ^ RANDOM_HELPER`) — so the trace states the randomness the run actually had, rather than an empty field the reader must interpret
- **AND** no component SHALL substitute a *different* seed for the echo than the one in force, in either direction: neither a placeholder to fill the field nor a blank to signal that nobody chose it

#### Scenario: the helper and Monkey generators are separate streams

- **WHEN** a run starts with `-s 42`
- **THEN** `Monkey.mRandom` SHALL be seeded with `42` and `RandomHelper` with `42 ^ RANDOM_HELPER`
- **AND** `RUN_START.seed` SHALL be `42`
- **AND** the first values drawn from the two generators SHALL differ from each other, and the sequence of either SHALL NOT be a shifted copy of the other's

#### Scenario: corpus basis echoed when supplied

- **WHEN** the harness pushes `ape.corpusBasis=subset40:<sha256>`
- **THEN** `RUN_START` SHALL carry `corpus_basis` with that value, and no runtime component SHALL read it (INV-RUN-03)
- **AND** when the key is absent the member SHALL be omitted entirely

### Requirement: RunContext Ownership

`RunContext` SHALL own, for the run:
- the resolved `RunSpec` (`spec()`), the run identity (`runId()`, the host-supplied `ape.runId` or a generated `<utc>-<seed>-<digest prefix>`) and the epoch base `t0()`;
- the seeded run stream (`rng()`) and the four `StepRng` streams (`stepRng()`, INV-RUN-08);
- the event sink (`sink()`): `NdjsonSink` on `System.out` on every production path, and a substitute only through `installForTest` (`event-sink` INV-SNK-07);
- the termination reason and detail (`terminated(reason, detail)`, read by `RUN_END`);
- exactly when `LLM` is in the plan, the LLM units it constructs once (`llmClient()`, `llmTelemetry()`, `coordinateMapper()`, `llmEngine()`, the engine holding its `ScreenshotStep`, `ApePromptBuilder` with the mounted prompt contributions, and `ToolCallParser`; `llm-routing` "LLM Unit Lifecycle and Ownership"); otherwise each of them is null.

It SHALL be initialized exactly once at bootstrap (a second `initialize` throws), with test-only installation paths (`installForTest`, `resetForTest`) so JVM tests construct `RunSpec` values directly instead of writing property files or mutating static fields — the five formerly non-final `Config` fields (`mopWeightOpenMenu`, `fuzzInputTyped`, `mopStrictPackageMatch`, `activityTriggerEnabled`, `mopFrontierWeight`) SHALL NOT exist, and their read sites SHALL consult `RunContext.current().spec()`.

The exploration state (`Model`, `Graph`, the trackers, the run's `MopContext`) is not owned by `RunContext`: the agent holds it. Code paths that read `Config` read final static fields, loaded from the files `RunSpec` validated (with the system-property fallback stated in "Single Plan Resolution at Bootstrap").

#### Scenario: tests construct plans, not property files

- **WHEN** a JVM test needs `mopWeightOpenMenu=0`
- **THEN** it SHALL build a `RunSpec` via the test factory and install it with `RunContext.installForTest`
- **AND** no test SHALL mutate a `Config` field (all fields final)

#### Scenario: double initialization is a loud bug

- **WHEN** `RunContext.initialize` is called a second time in one process
- **THEN** it SHALL throw `IllegalStateException`

#### Scenario: LLM units exist only with LLM

- **WHEN** the resolved plan does not contain `LLM`
- **THEN** `RunContext.current().llmEngine()`, `llmClient()`, `llmTelemetry()` and `coordinateMapper()` SHALL each be null
- **AND WHEN** it contains `LLM`
- **THEN** each SHALL be non-null and the same instance for the whole run

### Requirement: Build Provenance Stamp

The build SHALL embed a `BuildInfo` class generated at build time (Maven git-commit-id + templating plugins filtering a `java-templates` source) exposing `GIT_SHA` (abbreviated commit of the built tree; `unknown` when no git directory) and `JAR_BUILT` (UTC build time). The constants SHALL be compiled into the dex (verifiable via `strings` over `classes.dex`), add no jar resources, and be consumed by the `RUN_START` echo. No runtime `[APE-BUILD]` banner is emitted — `RUN_START.build` is the banner.

#### Scenario: stamp present and filtered

- **WHEN** `mvn package` completes
- **THEN** the generated `BuildInfo` SHALL contain no unfiltered `${…}` placeholders
- **AND** the short sha SHALL be findable in `classes.dex` of `target/ape-rv.jar`

#### Scenario: buildable outside git

- **WHEN** the tree is built without a `.git` directory
- **THEN** the build SHALL succeed and `GIT_SHA` SHALL be `unknown`

### Requirement: Static Data Parameters

`RunSpec` SHALL carry a `StaticParams` record, non-null exactly when `STATIC_DATA` is in the plan, with the fields `dataPath` (from `ape.staticDataPath`), `strictPackageMatch` (from `ape.mopStrictPackageMatch`, which keeps its name and is owned by `STATIC_DATA`), `frontierBoostWeight`, `fuzzInputTyped` and `componentPercentage` (design D-B14.3). The artifact loader (`guidance-groups` "Static Data Root and Split Providers") SHALL take the path and the strict-package flag from `StaticParams` and from nowhere else. `MopParams` SHALL NOT carry the artifact path or the strict-package flag. A plan that contains `STATIC_DATA` and neither `STATIC` nor `MOP` is valid; in it the path is inert: it SHALL be listed in `RUN_START.inert`, and the artifact SHALL NOT be loaded, so no `MOP_DATA` census and no rejection follow (design D-B14.20).

#### Scenario: the path reaches the loader through StaticParams

- **WHEN** a properties file sets `ape.preset=mop` and `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** `StaticParams` SHALL report the path `/data/local/tmp/mop-artifact.json`
- **AND** the loader SHALL open that path

#### Scenario: a path with no group mounted is inert
- **WHEN** a properties file sets `ape.staticDataPath=/data/local/tmp/mop-artifact.json` and neither `ape.static` nor `ape.mop`
- **THEN** resolution SHALL succeed, `StaticParams` SHALL be non-null, and `RUN_START.inert` SHALL list `ape.staticDataPath`
- **AND** `RUN_START.params` SHALL NOT carry `ape.staticDataPath`
- **AND** the loader SHALL NOT open the path and no `MOP_DATA` record SHALL be written

#### Scenario: no data, no record

- **WHEN** the `base` cell resolves
- **THEN** `StaticParams` SHALL be null and no artifact SHALL be opened
