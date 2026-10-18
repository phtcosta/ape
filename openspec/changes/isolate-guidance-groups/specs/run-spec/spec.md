# Delta: Run Spec (isolate-guidance-groups)

## Purpose

`isolate-guidance-groups` organises the plan in five behaviour groups (`guidance-groups`): BASE, ADDONS, STATIC,
MOP and LLM. The run-spec capability changes in five places.

1. **Group keys and the data root.** `ape.addons`, `ape.static`, `ape.mop` and `ape.llm` are boolean plan keys
   that activate the group features. `STATIC_DATA`, activated by `ape.staticDataPath`, is the only feature that
   needs the static-analysis artifact. `MOP` is no longer activated by the artifact's presence: the `as` cell
   reads the artifact with MOP off. Every member feature declares its group as a dependency, so the existing
   rule (a default-valued key never activates a feature whose dependencies are unmet; an explicit one aborts)
   decides membership with no new mechanism.
2. **Renamed, new and retired keys.** `ape.mopDataPath` is renamed `ape.staticDataPath` (design D-B2).
   `Feature.FRONTIER` becomes `NAV_FRONTIER` and depends on `STATIC` instead of `MOP` (D-A1). `LLM_RANDOM` becomes
   `LLM_COIN`. Two former base keys become ADDONS members (`BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART`, D-B5). The
   MOP coin (`ape.mopCoinPercentage`) and the LLM gate parameters (`ape.llmGateMinActions`,
   `ape.llmBreakerFailures`, `ape.llmBreakerOpenMs`) are new. `COMPONENT_TRIGGER` moves from `MOP` to `STATIC` with its key
   `ape.componentPercentage` (D-B13). `ape.mopDataPath`,
   `ape.leastVisitedPriorityTiebreak` and `ape.mopTargetPickCap` are retired, each with the reason that names what
   replaced it.
3. **Six cell presets** replace `aperv`, `mop`, `llm` and `llm_mop`: `base`, `mop`, `as`, `as_mop`, `as_llm`,
   `as_mop_llm`. Each is the union of the blocks of its groups (`guidance-groups`).
4. **`StaticParams`**, a parameter record for the data root, beside `MopParams` and `LlmParams`.
5. **`ape.variant`**, a resolver key carrying the harness's variant name, echoed as `RUN_START.variant` and kept
   out of the plan digest. `RUN_START.v` becomes `2` (design D-B14.22).

## Data Contracts

### Input

- `ape.preset: String (optional)` — `base | mop | as | as_mop | as_llm | as_mop_llm`
- `ape.variant: String (optional)` — the harness's variant name; echoed, never read by a decision
- `ape.addons`, `ape.static`, `ape.mop`, `ape.llm: boolean` — group keys, jar default `false`
- `ape.staticDataPath: String (optional)` — the static-analysis artifact path; activates `STATIC_DATA`

### Output

- `RUN_START.variant` — the `ape.variant` value, omitted when the key is absent

## Invariants

- **INV-RUN-10**: `ape.variant` SHALL be owned by the resolver, SHALL be echoed verbatim as `RUN_START.variant`
  (omitted when absent), SHALL NOT be read by any component that decides an action or builds a mechanism, and
  SHALL NOT enter the plan `digest`. Two runs that differ only in `ape.variant` SHALL have equal digests. Coverage:
  `RunSpecEchoTest`, `RunSpecResolveTest`.
- **INV-RUN-11**: The dose keys `ape.llmPercentage` (`p_L`), `ape.mopCoinPercentage` (`p_M`) and
  `ape.componentPercentage` SHALL be clamped to
  `[0, 1]` at resolution and SHALL NOT abort for an out-of-range value of their declared type. The clamped value is
  the effective value echoed in `params` and hashed in the digest. Coverage: `RunSpecResolveTest`.
- **INV-RUN-04** (amended): the plan `digest` SHALL exclude, besides seed, runId, the corpus basis and the preset
  name, the harness variant name `ape.variant` (INV-RUN-10). The rest of INV-RUN-04 is unchanged.
- **INV-RUN-08** (amended): All run-scoped randomness SHALL derive from the run seed owned by `RunContext` (seeded
  from `-s`). Besides the agent and helper streams, the only further streams SHALL be the four `StepRng` streams,
  each a `Random` seeded with `seed ^ k` for a fixed constant `k` per stream: `llmCoin`, `mopCoin`, `componentCoin`
  and the fuzz toss (design D-B9). No component SHALL use `ThreadLocalRandom`, `Math.random`, or an unseeded
  `Random` for any decision or generated input. Coverage: `RngNeutralityTest`, and the existing static guard on
  bare `Random` sites, which SHALL admit the `StepRng` construction as its one seeded addition.

## MODIFIED Requirements

### Requirement: Single Plan Resolution at Bootstrap

`RunSpec.resolve` SHALL run in `Monkey.run` (the `--ape` branch) before `MonkeySourceApe` or any agent is constructed. It SHALL consume: the union of the entries of `/data/local/tmp/ape.properties` and `/sdcard/ape.properties` (load order preserved, later file wins; file-loaded entries only — JVM system properties are not a configuration channel), and the CLI values (`--ape`, `-s`, `--ape-replay`). It SHALL produce an immutable `RunSpec` value carrying: `presetName` (the `ape.preset` value, or `"explicit"` when absent), `variant` (the `ape.variant` value, or null), `seed`, `runId`, `agentType`, `ExplorationParams` (always present), `Set<Feature>`, `StaticParams`/`MopParams`/`LlmParams` (each null iff its feature — `STATIC_DATA`, `MOP`, `LLM` — is absent), `TelemetryParams` (always present), `digest`, and `propertiesDigest`.

After resolution, `RunSpec` is the sole behavioral authority: static `Config` remains only as the loading mechanism, and its values are guaranteed valid because `RunSpec.resolve` validated the same entries before any consumer read them. The `RunSpec` SHALL be reachable via `RunContext.current().spec()`. Whether a mechanism of a behaviour group acts SHALL be read from the plan, never from `Config` (`guidance-groups` INV-GRP-06). The same holds for three BASE keys that `Config` still mirrored (design D-B6, decision A10): `ape.doFuzzing` and `ape.fuzzingRate` SHALL be read as `spec.has(Feature.FUZZING)` and that feature's parameter at `MonkeySourceApe.java:876`, and `ape.checkRestart` SHALL be read from `ExplorationParams` at `agent/ApeAgent.java:280`. The values and defaults are unchanged (`true`, `0.02`, `true`), so no behaviour changes; `Config` SHALL NOT keep twins of these keys.

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

### Requirement: Feature Model with Declared Dependencies

`Feature` SHALL be an enum whose constants carry, as constructor data: an activation rule (a key plus the value shape that activates it, or a derived predicate such as "`ape.staticDataPath` present" for `STATIC_DATA`), the set of sub-parameter keys the feature owns, the declared dependencies as an `EnumSet<Feature>`, and the neutral (off/zero/sentinel) value of each owned key.

The declared dependencies SHALL be at minimum:

- `STATIC` and `MOP` require `STATIC_DATA`; `ADDONS`, `LLM` and `STATIC_DATA` are roots;
- `MODEL_MENU`, `FORM_COMPLETION`, `TREE_ENHANCEMENTS`, `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`, `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD`, `COVERAGE_BOOST`, `BACK_MENU_CAP` and `ACTIVITY_STABLE_RESTART` require `ADDONS`;
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
| `ape.frontierBoostWeight` | `NAV_FRONTIER` (POSITIVE) | int | `200` | `0` | formerly owned by `FRONTIER` (MOP) |
| `ape.fuzzInputTyped` | `TYPED_FUZZ` (TRUE) | bool | `true` | `false` | |
| `ape.componentPercentage` | `COMPONENT_TRIGGER` (POSITIVE) | double | `0.0` | `0` | formerly owned under `MOP`; the STATIC block sets `0.03` (D-B13) |
| `ape.llmPercentage` | `LLM_COIN` (POSITIVE) | double | `0.02` | `0` | `p_L`, clamped to `[0,1]` |

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

- **WHEN** a properties file without `ape.llm` contains `ape.llmPercentageNoSubstrate=-1` (the neutral sentinel)
- **THEN** resolution SHALL succeed, the key SHALL appear in the echo's `inert` list, and `LlmParams` SHALL be null
- **AND WHEN** the same file instead contains `ape.llmOnNewState=true` with no `ape.llm`
- **THEN** resolution SHALL abort with a missing-dependency diagnostic naming `LLM`

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
3. **Retired key** (dedicated message naming the replacement): `ape.apePureMode` (purity is structural — owner decision D3), `ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates` (persistence protocol removed), `ape.enableXPathAction` (injection channel removed), `ape.mopWeightActivity` (dead key), `ape.llmBoundaryBottomPct` (on the display base the jar maps answers in, the navigation bar is outside every reachable pixel, so the band only rejected app content — `llm-coordinate-single-base`), `ape.agentType` and `ape.replayLog` when present in a **file** (CLI-only values — closes the `/sdcard` agent-swap hole), and the three keys retired by `isolate-guidance-groups`:
   - `ape.mopDataPath` — "renamed ape.staticDataPath: the artifact feeds STATIC and MOP (isolate-guidance-groups)";
   - `ape.leastVisitedPriorityTiebreak` — "replaced by the scorer-sum tie (X1/R2), which equals APE with zero scorers";
   - `ape.mopTargetPickCap` — "replaced by `ape.mopCoinLedgerMaxInteractions`, the MOP coin's budget of picks per activity and widget (default 1)".
4. **Invalid type**: a non-numeric value for a numeric key; a boolean key whose value is not literally `true`/`false` (case-insensitive); a value of `ape.mopScoring` other than exactly `flag` or `distance` (the one string key with a closed value set; the diagnostic names the valid set — `llm-coordinate-single-base`, design D18). Documented value-semantics clamps (cadence `<= 0`, launch cap `< 0`, `ape.mopCoinLedgerMaxInteractions < 1`, the percentage clamps of `ape.llmPercentage`, `ape.mopCoinPercentage` and `ape.componentPercentage` to `[0,1]`, `-1` sentinel) remain clamps, not aborts (INV-RUN-11).
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

## ADDED Requirements

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
