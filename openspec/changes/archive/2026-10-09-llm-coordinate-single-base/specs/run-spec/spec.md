# Delta: Run Spec (llm-coordinate-single-base)

## Purpose

`llm-coordinate-single-base` retires one configuration key, `ape.llmBoundaryBottomPct`. The key named a band of the screen's bottom 6 % meant to keep the LLM off the navigation bar. The fraction was written for a 1920-high screen, but the band is applied to the display, whose 1794 px already exclude the navigation bar. On the display, every value of the key below 1.0 rejects app content, and every value from 1.0 up never fires (`llm-routing` INV-RTR-22). A key that is simply deleted aborts as `unknown_key`. Listing it among the retired keys gives the abort a reason a researcher holding an old properties file can act on, which is what the retired class exists for.

Part B of the change (MOP guidance by static distance and handler stamp, design D18–D20) adds six keys, all owned by the `MOP` feature: `ape.mopScoring` selects the scoring mode (`flag`, the E6 yes/no mark and the default, or `distance`), `ape.mopWeightD1`/`D2`/`D3` (500/400/300) are the weights of the `distance` mode for a nearest unretired target at `d ≤ 1`, `d = 2` and `d = 3`, `ape.mopRetireAfter` (3) is the number of first interactions after which a target retires, and `ape.mopLauncherDmax` (6) bounds the distances the activity launcher orders by. Every value except the derive's K = 3 is thereby a plan key, because the plan's decision 10 values are provisional (design, Risks).

Two things in the plan are new. First, `ape.mopScoring` is the first string key whose value set is closed, so a value outside `{flag, distance}` aborts resolution like a non-boolean value of a boolean key does. Second, inertness now has a second cause. Until now a key was inert only because its feature was absent from the plan (INV-RUN-05). With two scoring modes, the `MOP` feature is active while some of its keys parameterize nothing: `ape.mopWeightDirect`/`ape.mopWeightTransitive` under `distance`, the five distance keys under `flag`. The design has `RUN_START.inert` report them (D18). Unlike the keys of an absent feature, they are accepted at any well-typed value, because the `mop` and `llm_mop` presets carry `ape.mopWeightDirect=500` and `ape.mopWeightTransitive=300`, and the distance arm is written as `ape.preset=mop` plus `ape.mopScoring=distance`. No preset changes: the default stays `flag` because `mop` and `llm_mop` define the E6 arms, and the distance arm is selected by the harness explicitly (D18).

## Invariants

- **INV-RUN-09**: When the `MOP` feature is in the plan, `ape.mopScoring` SHALL select exactly one of two disjoint key sets as read: `ape.mopWeightDirect`, `ape.mopWeightTransitive` under `flag`; `ape.mopWeightD1`, `ape.mopWeightD2`, `ape.mopWeightD3`, `ape.mopRetireAfter`, `ape.mopLauncherDmax` under `distance`. A key of the set the mode does not select SHALL be accepted at any value of its declared type, SHALL have no effect on the run, and, when the plan states it (in a properties file or through the preset vector), SHALL be listed in `RUN_START.inert`. All other `MOP` keys, `ape.mopScoring` among them, SHALL be read in both modes. When the `MOP` feature is absent, the six Part B keys fall under INV-RUN-05 like every other sub-parameter of an absent feature. Coverage: `RunSpecResolveTest` (inert lists per mode, a non-neutral mode-inert value accepted) and `RunSpecEchoTest`.

## MODIFIED Requirements

### Requirement: Total Fail-Fast Validation

`RunSpec.resolve` SHALL abort (nonzero exit, `[APE-RUNSPEC-ABORT] reason=<class> key=<key> detail=<message>` to stderr and stdout, before step 1) on every one of the following input classes — and the abort SHALL happen before any agent, event source, or device interaction is constructed:

1. **Unknown key**: an `ape.*` key in a properties file that no owner declares.
2. **Foreign key**: any non-`ape.` key in a properties file.
3. **Retired key** (dedicated message naming the replacement): `ape.apePureMode` (purity is structural — owner decision D3), `ape.modelFile`, `ape.saveObjModel`, `ape.saveDotGraph`, `ape.saveVisGraph`, `ape.saveStates` (persistence protocol removed), `ape.enableXPathAction` (injection channel removed), `ape.mopWeightActivity` (dead key), `ape.llmBoundaryBottomPct` (on the display base the jar maps answers in, the navigation bar is outside every reachable pixel, so the band only rejected app content — `llm-coordinate-single-base`), `ape.agentType` and `ape.replayLog` when present in a **file** (CLI-only values — closes the `/sdcard` agent-swap hole).
4. **Invalid type**: a non-numeric value for a numeric key; a boolean key whose value is not literally `true`/`false` (case-insensitive); a value of `ape.mopScoring` other than exactly `flag` or `distance` (the one string key with a closed value set; the diagnostic names the valid set — `llm-coordinate-single-base`, design D18). Documented value-semantics clamps (cadence `<= 0`, launch cap `< 0`, percentage clamps, `-1` sentinel) remain clamps, not aborts.
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

#### Scenario: retired bottom boundary band aborts with its reason

- **WHEN** `ape.properties` contains `ape.llmBoundaryBottomPct=0.94`
- **THEN** the process SHALL abort with `reason=retired_key key=ape.llmBoundaryBottomPct`
- **AND** the detail SHALL state that the display excludes the navigation bar and that the status-bar band `ape.llmBoundaryTopPct` is the only boundary band

#### Scenario: unknown MOP scoring mode aborts

- **WHEN** `ape.properties` contains `ape.mopDataPath=/data/local/tmp/mop-artifact.json` and `ape.mopScoring=nearest`
- **THEN** the process SHALL abort with `[APE-RUNSPEC-ABORT] reason=invalid_type key=ape.mopScoring` and a detail naming the valid set `{flag, distance}`
- **AND** the run SHALL NOT fall back to `flag`

### Requirement: Feature Model with Declared Dependencies

`Feature` SHALL be an enum whose constants carry, as constructor data: an activation rule (a key plus the value shape that activates it, or a derived predicate such as "`ape.mopDataPath` present" for `MOP` and "`ape.llmUrl` present" for `LLM`), the set of sub-parameter keys the feature owns, the declared dependencies as an `EnumSet<Feature>` (at minimum: `MENU_GATEWAY` requires `MODEL_MENU` and `MOP`; `WTG`, `FRONTIER`, `MOP_FRONTIER`, `ACTIVITY_TRIGGER`, `COMPONENT_TRIGGER`, `MOP_ACTIVITY_SOURCE` require `MOP`; `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_RANDOM` require `LLM`), and the neutral (off/zero/sentinel) value of each owned key.

Key ownership SHALL be total: every `ape.*` key is owned by exactly one of `ExplorationParams`, one `Feature`, or the resolver itself (`ape.preset`, `ape.runId`, `ape.corpusBasis`); a totality test SHALL fail if a key the jar reads is unowned. The test SHALL derive the roster from the source tree rather than from a maintained list — a list would be written from the same documents that omit a key, which is how `ape.baseNaming` and `ape.nopActionThrottle` went undeclared through three audits of this change. The three string registries (`rvForcedOffValues`, `rvUnsetKeys`, `rvExemptReasons`) and the `apePureMode` forcing mechanism SHALL NOT exist; the `Feature` metadata is the single, compiler-visible replacement.

Dependency validation SHALL distinguish explicit input from defaults: an **explicitly-set** activation key or non-neutral sub-parameter whose feature's dependencies are unmet aborts (INV-RUN-02); a **default-valued** key never activates a feature whose dependencies are unmet — the feature is simply absent from the plan.

**The MOP scoring keys** (`llm-coordinate-single-base`, design D18–D20). The `MOP` feature SHALL own six further sub-parameters, declared in `KeyOwnership` beside `ape.mopWeightDirect`/`ape.mopWeightTransitive` (`KeyOwnership.java:189-193`), registered with their neutral values in `Feature.MOP.own(...)` (`Feature.java:150-151`), and read through `RunSpec.MopParams`:

| Key | Type | Default | Neutral | Read under |
|---|---|---|---|---|
| `ape.mopScoring` | string, `flag` \| `distance` | `flag` | `flag` | both modes |
| `ape.mopWeightD1` | int | `500` | `0` | `distance` (weight for a nearest unretired target at `d ≤ 1`) |
| `ape.mopWeightD2` | int | `400` | `0` | `distance` (`d = 2`) |
| `ape.mopWeightD3` | int | `300` | `0` | `distance` (`d = 3`) |
| `ape.mopRetireAfter` | int | `3` | `3` | `distance` (first interactions after which a target retires, `mop-guidance` "Target Retirement") |
| `ape.mopLauncherDmax` | int | `6` | `6` | `distance` (largest distance the launcher orders by, `component-triggering` "Cadence-Based MOP Activity Launch") |

`ape.mopWeightDirect` (500) and `ape.mopWeightTransitive` (300) are read under `flag` only. The neutral value is the one INV-RUN-05 accepts when `MOP` is absent: zero for a weight, as for `ape.mopWeightDirect`, and the default for the mode, the retirement count and the launcher bound, as for `ape.mopTargetPickCap`.

**Inert by mode** (INV-RUN-09). With `MOP` in the plan, a `MOP` key that the selected mode does not read SHALL be accepted at any value of its declared type and SHALL be listed in `RUN_START.inert` when the plan states it, whether in a properties file or through the preset vector. It is not subject to INV-RUN-05's neutral-value rule, which concerns an absent feature: here the feature exists, and the plan may state both modes' weights so that one preset serves both arms. The key keeps its effective value in the plan, so it appears in `params` when non-default and enters the plan digest; `inert` is what says it parameterizes nothing. `ape.mopScoring` itself is never inert while `MOP` is in the plan.

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

#### Scenario: the six MOP scoring keys resolve to their defaults

- **WHEN** a properties file sets `ape.mopDataPath` and none of the six keys
- **THEN** `MopParams` SHALL report scoring mode `flag`, weights 500/400/300, retirement after 3 and launcher bound 6
- **AND** `RUN_START.inert` SHALL list none of them, since the plan states none

#### Scenario: distance arm from the mop preset

- **WHEN** `ape.properties` contains `ape.preset=mop`, `ape.mopDataPath=/data/local/tmp/mop-artifact.json` and `ape.mopScoring=distance`
- **THEN** resolution SHALL succeed with `MOP` active and scoring mode `distance`
- **AND** `ape.mopWeightDirect` (500) and `ape.mopWeightTransitive` (300), stated by the preset vector, SHALL be accepted and listed in `RUN_START.inert`

#### Scenario: distance keys stated under flag are inert, not rejected

- **WHEN** a properties file sets `ape.mopDataPath`, `ape.mopScoring=flag`, `ape.mopWeightD2=450` and `ape.mopRetireAfter=5`
- **THEN** resolution SHALL succeed, both keys SHALL be listed in `RUN_START.inert`, and the run SHALL score with `ape.mopWeightDirect`/`ape.mopWeightTransitive` and retire no target

#### Scenario: scoring keys of an absent MOP feature

- **WHEN** a properties file without `ape.mopDataPath` sets `ape.mopScoring=flag`
- **THEN** resolution SHALL succeed and list the key in `RUN_START.inert`, `flag` being its neutral value
- **AND WHEN** the same file instead sets `ape.mopScoring=distance`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mopScoring` (INV-RUN-05)

### Requirement: Level-0 RUN_START Echo

Immediately after successful resolution and before any exploration output, the jar SHALL emit `RUN_START` as a single JSON object line to stdout: `type`, format version `v`, `run_id`, `t0` (device epoch milliseconds at emission — the base against which the stage-4 step records' relative `t` offsets resolve), `seed`, `agent`, `preset` (name or `"explicit"`), `features` (sorted names), `params` (every effective non-default parameter plus every active feature's activation key, as `ape.*` keys with effective values), `inert` (sorted; the stated keys that parameterize nothing in this run: the accepted neutral keys of inactive features, INV-RUN-05, and the `MOP` keys the selected `ape.mopScoring` mode does not read, INV-RUN-09), optional `corpus_basis`, `digest`, `props_digest`, and `build` (`sha`, `time` from the build stamp).

The format version `v` is not decoration and is worth one sentence of justification, because its absence has already cost something. Traces from different campaigns of this study carry materially different `[APE-STEP]` schemas — the earlier calibration corpus has no `mop_frontier`, `pick_channel`, `patched` or counterfactual fields, and its `mop=` realises `{0,300}` where the decisive campaign realises `{0,500}` — and nothing in either trace says which schema it is. `v` is what makes a cross-campaign comparison fail loudly instead of quietly comparing incomparable fields. The same holds within one jar from `llm-coordinate-single-base` on: the `mop` boost of a step means a flag weight under `ape.mopScoring=flag` and a distance weight under `distance`, so the scoring mode is part of what the echo must let an analyst read. It is, because `ape.mopScoring` is a parameter of the `MOP` feature: present in `params` when it is `distance`, and `flag` by its absence.

The line SHALL be produced by a serializer that escapes quotes, backslashes, and control characters and never emits a raw newline inside the record (one-record-one-line by construction) — the same serializer the stage-4 NDJSON sink will grow from, so `RUN_START`'s format survives stage 4 unchanged. The echo is write-only provenance (owner decision D1, level 0, definitive): no validation of the line anywhere, no Python change, no new communication channel — the physical flow remains `adb push` + stdout capture. In stage 2 the line precedes every APE-RV record and all exploration output; pre-existing AOSP Monkey banner lines may still precede it until the stage-4 sink replaces the remaining emitters.

#### Scenario: the line alone reconstructs the arm

- **WHEN** a run executes the current `sata_mop_llm` arm properties
- **THEN** the `RUN_START` line SHALL contain the agent type, seed, all active features (baseline + `MOP` family + `LLM` family), the MOP scoring mode (by `ape.mopScoring` in `params`, or by its absence for `flag`) and the MOP weights that mode reads, the LLM sampling parameters and url, and the build sha
- **AND** an analyst reading only that line SHALL be able to state which arm ran, without `tool.py`, including whether the MOP weights in force are the flag weights or the distance weights

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

- **WHEN** a run resolves `ape.preset=llm_mop` with `ape.mopScoring=distance`
- **THEN** `RUN_START.params` SHALL carry `ape.mopScoring:"distance"`
- **AND** `RUN_START.inert` SHALL list `ape.mopWeightDirect` and `ape.mopWeightTransitive`
