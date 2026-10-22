# run-spec — delta: gh-tbd-base-equals-ape

## MODIFIED Requirements

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

