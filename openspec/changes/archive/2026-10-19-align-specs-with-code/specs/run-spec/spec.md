## Purpose
The run-spec capability makes the behavioral plan of a run a first-class, validated, echoed value, so that "what this run is" is decided in one place: no silently loaded global, harness-side arm definition, unchecked string registry, stray `/sdcard/ape.properties` or CLI fallback can change the plan of a scientific run without a trace.

Every run begins by resolving a single immutable `RunSpec` from `ape.properties` plus the CLI, exactly once, before the first exploration step. The `RunSpec` carries the preset name (informative), the seed, the run identity, the always-present exploration parameters, the set of active `Feature`s, per-feature parameter objects that are null exactly when the feature is absent, and two digests (effective plan; raw properties input). Resolution is total and fail-fast: an unknown key, a retired key, a type-invalid value, a missing feature dependency, or an invalid combination aborts the process with a diagnostic line before step 1. The plan is organised in five behaviour groups (`guidance-groups`): BASE, which has no key, and ADDONS, STATIC, MOP and LLM, each activated by one boolean group key (`ape.addons`, `ape.static`, `ape.mop`, `ape.llm`). Every member feature declares its group as a dependency, so the feature-derivation rule decides membership with no further mechanism: a default-valued key never activates a feature whose dependencies are unmet, and an explicit one aborts. `STATIC_DATA`, activated by `ape.staticDataPath`, is the only feature that needs the static-analysis artifact; STATIC and MOP depend on it and still switch independently, so the `as` cell reads the artifact with MOP off. Its parameters are the `StaticParams` record, beside `MopParams` and `LlmParams`. The six cell presets (`base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`), each the union of the blocks of its groups, reside in the jar and are selected by an optional `ape.preset` key; when no preset is named the base key vector is empty and the plan is derived from the explicit keys exactly as the jar interprets them — same defaults, same clamps — then validated by the same rules a preset-resolved plan passes. There is one resolution path, not two: the no-preset case is what a run with no properties file at all resolves to, which is what makes a bare standalone invocation of the jar a valid run.

The plan is echoed as the run's provenance: `RUN_START`, a single JSON object line emitted before any exploration output, carrying the effective plan, both digests, the seed, the run id, the harness variant name (`ape.variant`, a resolver key echoed as `RUN_START.variant` and kept out of the plan digest), and the jar's build stamp; its format `v` is `2` (design D-B14.22). The echo is **level 0 by owner decision D1 (final)**: write-only provenance, no automatic validation anywhere, no Python reader — drift auditing is post-hoc analysis over the traces. The line's completeness bar is that it alone reconstructs the arm without consulting `tool.py` (report Sec. 9.6).

`RunContext` holds what is per-run and not exploration state: the `RunSpec`, the run identity, the seeded RNG and the four `StepRng` streams, the event sink, the termination reason and, when `LLM` is in the plan, the LLM units; the model and its trackers stay with the agent ("RunContext Ownership"). Purity is structural: a feature absent from the plan does not exist in the run — no kill-switch, no forced-off registry, no exempt list. This is the substitute for INV-ARCH-06 of the former `scoring-pipeline` text (every RV flag registered in a registry and forced off), which is dissolved: a sub-parameter of an absent feature has nothing to be inert *about*, because the mechanism it would parameterize was never constructed; it is accepted at its neutral value, and an activation key also at any value its feature's own rule reads as off (`<= 0` for a POSITIVE feature such as `WTG`), both reported as inert (design D-B22). The dose keys `ape.llmPercentage`, `ape.mopCoinPercentage` and `ape.componentPercentage` are clamped to `[0, 1]` rather than rejected. Inertness has a second cause, inside a feature that is present: the `MOP` scoring mode (`ape.mopScoring`) selects which of the feature's weight keys are read, and a key the selected mode does not read is accepted at any well-typed value and reported in `RUN_START.inert` (INV-RUN-09).

## Data Contracts

### Input

- `/data/local/tmp/ape.properties`, `/sdcard/ape.properties` — the only behavioral input files `RunSpec.resolve` reads; loaded in that order (later wins); every entry validated (source: device filesystem, pushed by the harness). JVM system properties are not a plan input: `RunSpec` reads none. `Config`, which keeps fields only for keys `src/main` reads through it, falls back to a JVM system property for such a key when no file sets it ("Single Plan Resolution at Bootstrap")
- `--ape <type>: String` — agent type; valid set `{sata, random, replay}`; CLI-only (source: Monkey command line)
- `-s <seed>: long` — run seed, also seeding `RandomHelper`; absent ⇒ the seed `Monkey` derives for itself (source: Monkey command line)
- `--ape-replay <log>: String` — replay log path, required iff `--ape replay` (source: Monkey command line)
- `ape.preset: String (optional)` — `base | mop | as | as_mop | as_llm | as_mop_llm`; explicit keys override the cell's vector
- `ape.variant: String (optional)` — the harness's variant name; echoed, never read by a decision
- `ape.addons`, `ape.static`, `ape.mop`, `ape.llm: boolean` — group keys, jar default `false`
- `ape.staticDataPath: String (optional)` — the static-analysis artifact path; activates `STATIC_DATA`
- `ape.runId: String (optional)` — host-supplied run identity; absent ⇒ self-generated

## Invariants

- **INV-RUN-05**: A feature absent from `features` SHALL have a null parameter object and SHALL have no constructed mechanism in the run (no pass, no stage, no LLM unit, no tracker, no launcher). A sub-parameter key explicitly present for an inactive feature SHALL be accepted at its declared neutral value, and, when it is that feature's activation key, also at any value the feature's activation rule reads as off (`<= 0` for a POSITIVE feature); either is recorded as `inert` in the echo, and any other value SHALL abort per INV-RUN-02 (design D-B22). *(This is the recorded substitute for INV-ARCH-06 of the former `scoring-pipeline` text, which required every RV flag to be registered and forced off and is dissolved: exemption lists are unnecessary because the sub-parameter of an absent feature parameterizes nothing.)*
- **INV-RUN-08**: All run-scoped randomness SHALL derive from the run seed owned by `RunContext` (the `-s` value, or the seed `Monkey` derives when `-s` is absent). Besides the agent and helper streams, the only further streams SHALL be the four `StepRng` streams, each a `Random` seeded with `seed ^ k` for a fixed constant `k` per stream: `llmCoin`, `mopCoin`, `componentCoin` and the fuzz toss (design D-B9). No component SHALL use `ThreadLocalRandom`, `Math.random`, or an unseeded `Random` for any decision or generated input. Coverage: `RngNeutralityTest`, and the existing static guard on bare `Random` sites, which SHALL admit the `StepRng` construction as its one seeded addition.

The coverage paragraph that follows the invariants (from "**Coverage of these invariants.**" to the INV-RUN-08 guard) is replaced by the text below, which keeps its content and states no invariant in the definition form, so INV-RUN-07 and INV-RUN-08 are each defined once:

**Coverage of these invariants.** All eleven are carried by a named test; INV-RUN-08 to INV-RUN-11 name tests in their own text. INV-RUN-01 by `RunSpecResolveTest` (immutability, input-map isolation) and `RunContextTest` (a second `initialize` throws); INV-RUN-02 by `RunSpecAbortTest` across all eight input classes, with the abort *composition* in `Monkey.run` reachable only on a device and deferred to a coordinated end-to-end run after all planned changes on both sides land (recorded in task 3.4); INV-RUN-03 by `RunSpecEchoTest`; INV-RUN-04 by `RunSpecResolveTest`'s six digest tests; INV-RUN-05 by `FeatureDerivationTest` and `RunSpecResolveTest`'s inert-key tests; INV-RUN-06, INV-RUN-07 and INV-RUN-08 by `DeviceInputChannelAbsenceTest`, whose scans read comments out first — so a line that *discusses* a removed mechanism stays legal — and carry a minimum-yield floor, because a scan that silently matches nothing passes by vacuity.

Two of those guards assert more than an absence, and what they assert is worth stating precisely:

- For INV-RUN-07, `noCodePathReadsBackAnArtifactOfAPreviousRun` scans `src/main/java` for `ObjectInputStream`, `readObject` and `readGraph`, and requires none. The retired persistence keys already aborted via `RunSpecAbortTest.everyRetiredKeyAbortsWithItsOwnReason`, and the deletion of `Graph.readGraph` and the `--ape-model` branch is compile-enforced — but neither fact guards against a *new* read-back path, which is what a future "resume" feature would introduce and what this invariant forbids.
- For INV-RUN-08, three mechanisms, three assertions. `ThreadLocalRandom` and `Math.random` must not appear at all. A bare `new Random()` must appear **exactly once**, in `RandomHelper`: that field initializer is genuinely unseeded, but nothing draws from the instance in a real run, because `RunContext.initialize` calls `RandomHelper.seed` before any exploration component exists. Pinning the site is what makes a *second* bare `Random` a failure rather than a silent second stream — an empty-set assertion would be false, and a "no more than N" weakening would stop naming which site is new. Behavioural coverage sits alongside it in `RunContextTest` (establishing the context seeds the run's stream) and `StringCacheSeededTest` (same seed ⇒ same string sequence).

## RENAMED Requirements

- FROM: `### Requirement: RunContext Ownership (stage-2 scope)`
- TO: `### Requirement: RunContext Ownership`

## MODIFIED Requirements

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

### Requirement: Run Identity and Seed

`RunSpec.seed` SHALL be the seed `Monkey` resolved — the `-s` value when one was passed, and otherwise the value `Monkey` derived for itself — and `RunContext.initialize` SHALL be the single point that calls `RandomHelper.seed(seed)` (INV-EXPL-14 unchanged). `runId` SHALL be the `ape.runId` value when present (a recognized key; no current deployment pushes it) and otherwise self-generated, deterministic in format (`<utc-compact>-<seed>-<digest-prefix>`), and echoed in `RUN_START`.

**Recording the seed and choosing it are two different things.** The harness appends `-s <seed>` only when a seed is configured (`tool.py`), and the decisive campaign configured none — the flag appears in none of its 360 traces except as a widget resource id in one application. `Monkey` does not run unseeded when `-s` is absent: it derives one, `mSeed = System.currentTimeMillis() + System.identityHashCode(this)`, in `Monkey.run` before the `--ape` branch is reached, and both `mRandom` and, through `RunContext.initialize`, `RandomHelper` are built from that value. `RUN_START` records no *absence* of a seed, because there is none: `RunSpec.seed` is always a real number and `RUN_START.seed` is always full.

What a derived seed lacks is not reproducibility but **control**. A run is individually reproducible from its own trace, bounded by what a seed does not reach: the trajectory depends on the observed GUI tree and therefore on device timing. Every draw comes from a seeded stream (INV-RUN-08: the one bare `new Random()`, `RandomHelper`'s field initializer, is replaced by `RunContext.initialize` before any exploration component exists), so the parity oracle's premise — the same decisions *under the same seed* — is satisfiable per run. An incidental seed, however, is reproducible only after the fact, and cannot be used to pair two arms on the same randomness; that needs a seed the harness designs.

**When the harness designs seeds, a single constant seed across the campaign SHALL NOT be the design.** The three replicas per (application, arm) are the only estimator of run-to-run variation the design has; pinning one seed for all runs would collapse that estimator to zero and buy per-run reproducibility with the loss of the noise floor every effect size is read against. The seed SHALL be a deterministic function of the run's identity — application, arm and replica — so that each run is individually reproducible while replicas still sample the nondeterminism of device timing, which no seed reaches.

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
