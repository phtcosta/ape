## ADDED Requirements

### Requirement: Calibrated MOP Defaults

`KeyOwnership` SHALL give four MOP keys the values of calibration v6's finalist F1 (`rvsec-calibracao`, change `2026-11-aperv-calibracao-v6`, adopted by the author on 2026-11-11): `ape.mopWeights=194,146,97`, `ape.mopRetireAfter=10`, `ape.mopCoinLedgerMaxInteractions=4` and `ape.activityTriggerStagnationStep=85`. F1 has three levels, so the cut stays c = 3 (`mop-guidance` "Distance-Pair Cut at Load").

#### Scenario: A MOP cell without stated keys runs F1
- **WHEN** the `mop` cell resolves with only the harness keys (`ape.staticDataPath`, `ape.variant`)
- **THEN** `MopParams.weights()` SHALL be `[194, 146, 97]`, the retirement count 10, `coinLedgerMaxInteractions()` 4 and `activityTriggerStagnationStep()` 85
- **AND** `RUN_START.params` SHALL carry none of the four keys

### Requirement: Calibrated Defaults Are Not Preset Keys

No preset SHALL state the four calibrated keys. A preset key counts as explicit (`RunSpec.resolve`, step 2), and an explicit launcher or coin key at a non-neutral value aborts a plan that leaves its feature out (INV-RUN-05), as `ape.mopWeightOpenMenu` would in the `mop` cell (`guidance-groups`, design D-B14.1). A jar default is not explicit, so a variant that unmounts the launcher or the coin resolves.

#### Scenario: A variant without the launcher resolves
- **WHEN** a properties file states `ape.preset=mop`, the artifact path and `ape.activityTriggerEnabled=false` (rv-android's `mop_nolaunch`)
- **THEN** resolution SHALL succeed with `MOP_LAUNCHER` absent from `features`
- **AND WHEN** the same file also states `ape.activityTriggerStagnationStep=85`
- **THEN** resolution SHALL succeed and `RUN_START.inert` SHALL list the key

### Requirement: Neutral Values Follow the Calibrated Defaults

The neutral values of `ape.mopRetireAfter`, `ape.mopCoinLedgerMaxInteractions` and `ape.activityTriggerStagnationStep` SHALL be their defaults, 10, 4 and 85 (`Feature`; for a sub-parameter the neutral is the jar default, INV-RUN-05). The neutral of `ape.mopWeights` SHALL stay the one-level ladder `0`. The rule compares the clamped value: a k below 1 is floored to 1 and aborts without the coin, a cadence `<= 0` is clamped to 85 and stays inert.

#### Scenario: Neutral values follow the defaults
- **WHEN** a properties file without `ape.mop` states `ape.mopRetireAfter=10`
- **THEN** resolution SHALL succeed and list the key as inert
- **AND WHEN** it states `ape.mopRetireAfter=3`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mopRetireAfter`
- **AND WHEN** a `mop` plan states `ape.activityTriggerEnabled=false` and `ape.activityTriggerStagnationStep=50`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.activityTriggerStagnationStep`
- **AND WHEN** a `mop` plan states `ape.mopCoinPercentage=0`, which leaves `MOP_COIN` out, and `ape.mopCoinLedgerMaxInteractions=1`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mopCoinLedgerMaxInteractions`
- **AND WHEN** the same plan states `ape.mopCoinLedgerMaxInteractions=0` instead, which the floor clamps to 1
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mopCoinLedgerMaxInteractions`
- **AND WHEN** a `mop` plan states `ape.activityTriggerEnabled=false` and `ape.activityTriggerStagnationStep=0`, which the clamp maps to 85
- **THEN** resolution SHALL succeed and list the key as inert

### Requirement: Calibrated Defaults Described as Equivalent

The documentation of the four defaults (`CLAUDE.md` and the `KeyOwnership` comment) SHALL describe them as equivalent to the E7 values with the best point estimate, not as a measured improvement: F1 − E7 at 900 s in the `mop` cell, S +0.53, 95% CI [−0.55, +1.64]. It SHALL say that the calibration searched only these four keys (its decision D22).

#### Scenario: Documentation states the evidence
- **WHEN** `CLAUDE.md` describes `ape.mopWeights`, `ape.mopRetireAfter`, `ape.mopCoinLedgerMaxInteractions` or `ape.activityTriggerStagnationStep`
- **THEN** it SHALL name calibration v6's F1 as the source and give the interval `[−0.55, +1.64]`
- **AND** it SHALL NOT call the defaults an improvement

### Requirement: E7 Plan by Explicit Keys

A MOP cell SHALL resolve every key to the effective value of the E7 jar `bc4bc1b8` when its properties state `ape.mopWeights=500,400,300`, `ape.mopRetireAfter=3`, `ape.mopCoinLedgerMaxInteractions=1` and `ape.activityTriggerStagnationStep=50`, `ape.mopWeights` standing for the three keys it replaced. `RUN_START.params` then carries the four keys. Study 3's E7 `mop` arm stated `ape.mopCoinLedgerMaxInteractions=2` through its harness, so its reproduction states 2.

#### Scenario: E7 values stated
- **WHEN** the `mop` cell resolves with the harness keys and `ape.mopWeights=500,400,300`, `ape.mopRetireAfter=3`, `ape.mopCoinLedgerMaxInteractions=1` and `ape.activityTriggerStagnationStep=50`
- **THEN** `MopParams.weights()` SHALL be `[500, 400, 300]`, the retirement count 3, `coinLedgerMaxInteractions()` 1 and `activityTriggerStagnationStep()` 50
- **AND** `RUN_START.params` SHALL carry the four keys, `ape.mopWeights` as `[500,400,300]`

### Requirement: E7 Recipe Omits Keys of Absent Features

A plan that leaves `MOP_LAUNCHER` out (`ape.activityTriggerEnabled=false`) SHALL omit `ape.activityTriggerStagnationStep` from the E7 recipe, and a plan that leaves `MOP_COIN` out (`ape.mopCoinPercentage=0`) SHALL omit `ape.mopCoinLedgerMaxInteractions`: at the E7 value either key is non-neutral for an absent feature and aborts (INV-RUN-05).

#### Scenario: E7 without the launcher omits the cadence
- **WHEN** a properties file states `ape.preset=mop`, the artifact path, `ape.activityTriggerEnabled=false`, `ape.mopWeights=500,400,300`, `ape.mopRetireAfter=3` and `ape.mopCoinLedgerMaxInteractions=2`
- **THEN** resolution SHALL succeed with `MOP_LAUNCHER` absent and the three stated values effective

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
| `ape.mopCoinLedgerMaxInteractions` | `MOP_COIN` | int | `4` | `4` | executions per ledger key the coin may take; `< 1` clamped to `1` at resolution, like the other plan clamps (`mop-guidance`, design D-B17) |
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

**The MOP scoring keys** (`llm-coordinate-single-base`, design D18–D20). The `MOP` feature SHALL own four further sub-parameters, declared in `KeyOwnership` beside `ape.mopWeightDirect`/`ape.mopWeightTransitive`, registered with their neutral values in `Feature.MOP`, and read through `RunSpec.MopParams`:

| Key | Type | Default | Neutral | Read under |
|---|---|---|---|---|
| `ape.mopScoring` | string, `flag` \| `distance` | `flag` | `flag` | both modes |
| `ape.mopWeights` | int list, 1–10 non-negative integers ("Distance Ladder Key") | `194,146,97` | `0` | `distance` (the ladder: element ℓ weighs level ℓ = max(d, 1); the length is the cut c, `mop-guidance` "MopScorer — Priority Boost", "Distance-Pair Cut at Load") |
| `ape.mopRetireAfter` | int | `10` | `10` | `distance` (first interactions after which a target retires, `mop-guidance` "Target Retirement") |
| `ape.mopLauncherDmax` | int | `6` | `6` | `distance` (largest distance the launcher orders by, `component-triggering` "Cadence-Based MOP Activity Launch") |

`ape.mopWeightDirect` (500) and `ape.mopWeightTransitive` (300) are read under `flag` only. The neutral value is the one INV-RUN-05 accepts when `MOP` is absent: zero for a weight, as for `ape.mopWeightDirect`, and the one-level ladder `0` for `ape.mopWeights`, and the default for the mode, the retirement count and the launcher bound.

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

- **WHEN** a properties file sets `ape.staticDataPath`, `ape.mop=true` and none of the four MOP scoring keys (`ape.mopScoring`, `ape.mopWeights`, `ape.mopRetireAfter`, `ape.mopLauncherDmax`; the scenario's title counts the six keys the plan had before `ape.mopWeights` replaced `ape.mopWeightD1..D3`)
- **THEN** `MopParams` SHALL report scoring mode `flag`, the ladder `[194, 146, 97]`, retirement after 10 and launcher bound 6 ("Calibrated MOP Defaults")
- **AND** `RUN_START.inert` SHALL list none of them, since the plan states none

#### Scenario: distance arm from the mop preset

- **WHEN** `ape.properties` contains `ape.preset=mop` and `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** resolution SHALL succeed with `MOP` active and scoring mode `distance`, which the MOP block states
- **AND** `RUN_START.inert` SHALL list neither `ape.mopWeightDirect` nor `ape.mopWeightTransitive`, since no cell vector states them
- **AND WHEN** the same file adds `ape.mopWeightDirect=500`
- **THEN** resolution SHALL succeed and `RUN_START.inert` SHALL list `ape.mopWeightDirect`

#### Scenario: distance keys stated under flag are inert, not rejected

- **WHEN** a properties file sets `ape.staticDataPath`, `ape.mop=true`, `ape.mopScoring=flag`, `ape.mopWeights=450,400,300` and `ape.mopRetireAfter=5`
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

### Requirement: Distance Ladder Key

The `MOP` feature SHALL own `ape.mopWeights`, the `distance` ladder: 1 to 10 non-negative integers, element ℓ the weight of level ℓ, the length the cut c (`mop-guidance` "MopScorer — Priority Boost"). Default `194,146,97`, calibration v6's finalist F1 ("Calibrated MOP Defaults"), neutral `0` (INV-RUN-05). It is read only under `distance`, and is inert under `flag` (INV-RUN-09). `RunSpec.MopParams.weights()` returns a copy as `int[]`. No `Config` field mirrors it. An increasing or flat list is accepted (author, 2026-11-02).

#### Scenario: Default
- **WHEN** a `mop` run does not set `ape.mopWeights`
- **THEN** `MopParams.weights()` SHALL be `[194, 146, 97]`, so the effective ladder is 194, 146, 97 and the cut is 3
- **AND** `RUN_START.params` SHALL NOT carry `ape.mopWeights`

#### Scenario: Inert under flag, neutral without MOP
- **WHEN** a properties file sets `ape.staticDataPath`, `ape.mop=true`, `ape.mopScoring=flag` and `ape.mopWeights=100`
- **THEN** resolution SHALL succeed and `RUN_START.inert` SHALL list `ape.mopWeights`
- **AND WHEN** a file without `ape.mop` sets `ape.mopWeights=0`
- **THEN** resolution SHALL succeed and list the key as inert
- **AND WHEN** a file without `ape.mop` sets `ape.mopWeights=500`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mopWeights` (INV-RUN-05)
- **AND WHEN** a file without `ape.mop` sets `ape.mopWeights=0,0,0`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mopWeights`: the neutral value is the one-element ladder `0`, and lists are compared element by element (author, 2026-11-03, review WR-02)

#### Scenario: Increasing ladder accepted
- **WHEN** a `mop` run sets `ape.mopWeights=300,500`
- **THEN** resolution SHALL succeed and `MopParams.weights()` SHALL be `[300, 500]`

### Requirement: Integer-List Rendering

The canonical form of an `INT_LIST` value, used by the plan digest and by `sameValue`, SHALL be its elements joined by `,` without spaces (INV-RUN-04). `RUN_START.params` SHALL carry `ape.mopWeights` only when its effective value differs from the default, as a JSON array of integers.

#### Scenario: Echo and digest of a stated ladder
- **WHEN** a `mop` run sets `ape.mopWeights=500, 400`
- **THEN** `RUN_START.params` SHALL carry `"ape.mopWeights":[500,400]`
- **AND** the digest SHALL equal that of the same plan written `ape.mopWeights=500,400`
- **AND WHEN** a `mop` run sets `ape.mopWeights=194,146,97`
- **THEN** its digest SHALL equal that of the `mop` run that does not set the key

### Requirement: Total Fail-Fast Validation

`RunSpec.resolve` SHALL abort (nonzero exit, `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` to stderr and stdout, before step 1) on every one of the following input classes — and the abort SHALL happen before any agent, event source, or device interaction is constructed:

1. **Unknown key**: an `ape.*` key in a properties file that no owner declares.
2. **Foreign key**: any non-`ape.` key in a properties file.
3. **Retired key** (dedicated message naming the replacement): `ape.apePureMode` (purity is structural — owner decision D3), `ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates` (persistence protocol removed), `ape.enableXPathAction` (injection channel removed), `ape.mopWeightActivity` (dead key), `ape.llmBoundaryBottomPct` (on the display base the jar maps answers in, the navigation bar is outside every reachable pixel, so the band only rejected app content — `llm-coordinate-single-base`), `ape.agentType` and `ape.replayLog` when present in a **file** (CLI-only values — closes the `/sdcard` agent-swap hole), and the four keys retired by `isolate-guidance-groups`:
   - `ape.mopDataPath` — "renamed ape.staticDataPath: the artifact feeds STATIC and MOP (isolate-guidance-groups)";
   - `ape.leastVisitedPriorityTiebreak` — "replaced by the scorer-sum tie (X1/R2), which equals APE with zero scorers";
   - `ape.mopTargetPickCap` — "replaced by `ape.mopCoinLedgerMaxInteractions`, the MOP coin's budget of picks per activity and widget";
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

## REMOVED Requirements

### Requirement: Default Plan Equivalence With the E7 Jar

**Reason**: The four MOP defaults are now calibration v6's finalist F1 ("Calibrated MOP Defaults"), so a MOP cell at its defaults no longer resolves to the plan of `bc4bc1b8`.

**Migration**: "E7 Plan by Explicit Keys" states the four keys that reproduce that plan. The tests that pinned E7 equivalence through the defaults state those values instead.
