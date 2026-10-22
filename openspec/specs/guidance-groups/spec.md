# guidance-groups Specification

## Purpose
APE-RV's behaviour is organised in five groups, and a run's plan says which of them are present. **BASE** is the
APE algorithm (SATA, Naming, the model) plus a declared list of bug fixes. **ADDONS** are the fork's own
improvements, identified A–N in plan `docs/plans/20261017_isolamento_grupos_aperv.md` §2.2: the BACK/MENU cap, the
`MODEL_MENU` action, the coverage pass, form completion, dynamic epsilon, the activity budget, the activity-stable
restart, the tree enhancements, heuristic input and the two safety guards. **STATIC** holds the consumers of the
static GUI model that the static analysis produces: the navigation frontier over the WTG, input typed from the
analysed `inputType`/`hint`, widget metadata in the LLM prompt, and component triggering over the manifest's
receivers, services and providers (design D-B13). **MOP** holds the consumers of the
specification targets: the flag and distance scorers, target retirement, the menu gateway, the MOP frontier,
`WtgPass`, the MOP variants of the navigation tie-breaks, the MOP coin, the activity launcher and the MOP part of
the LLM prompt. **LLM** holds the coin, the gates, the triggers, the call, the coordinate mapping and the fallback.
STATIC and MOP need the static-analysis JSON; BASE, ADDONS and LLM do not.

The separation exists so that every group's effect can be attributed against a clean control. Study 03's
campaign E6 v2 could not attribute C2 (guidance against "APE-RV unguided", IRR 1.05, not resolvable) because its
unguided arm loaded the static-analysis artifact and kept three channels open — the MOP-activity tie-break inside
SATA navigation, the WTG frontier at +200 and input typed from the artifact — and no "full tool without
specifications" configuration existed (plan §2.1; the detection sites are listed in the archived change's
`design.md` § Context). The `as` cell is that configuration, and a cell without MOP carries no MOP signal.

The groups are mounted by declaration. Each group has one boolean plan key, and every member feature declares its
group as a dependency in `Feature`, so the existing feature-derivation rule of `run-spec` decides membership: a
member whose group is off is absent from the plan, and a mechanism absent from the plan is not constructed. The
static-analysis JSON is a data root of its own (`STATIC_DATA`, activated by `ape.staticDataPath`), on which STATIC
and MOP depend. Without the JSON no STATIC or MOP member can be in the plan; with it, STATIC and MOP still switch
independently. Use sites do not branch on group membership. A site asks the plan whether its own member feature is
present, or reads that member's parameters (design D-B1, D-B6).

At load, wire format 2 is split into two read-only providers. `StaticGuiModel` holds the WTG, the widget
metadata and the manifest components; `MopData` holds the targets, distances, handlers and MOP activities. STATIC
reads only `StaticGuiModel`; MOP reads both. The wire format is format 2; each component intent filter may carry
an optional reduced `data` member (D-B13), and an artifact without it loads.

The experiment is run on six cell presets, each the union of the blocks of the groups it contains: `base`, `mop`,
`as`, `as_mop`, `as_llm`, `as_mop_llm` (plan §6.2). The block values reproduce E6 (design D-B3). The `base` cell is
not byte-for-byte the upstream APE: it carries the keyless bug fixes listed in plan §2.2 and the structural changes
of the group isolation (the `StepRng` streams, the restart guard, the pipeline order). That difference is declared here and is checked by an A/A against `ape.jar` (plan §6.4), outside
this capability.

## Data Contracts

### Input

- `ape.addons`, `ape.static`, `ape.mop`, `ape.llm: boolean` — one key per group; jar default `false` (source:
  `ape.properties`, or a cell preset)
- `ape.staticDataPath: String (path)` — the device path of the static-analysis artifact, wire format 2; activates
  `STATIC_DATA` (source: pushed by the harness; never part of a preset)
- `ape.preset: String` — one of `base`, `mop`, `as`, `as_mop`, `as_llm`, `as_mop_llm`
- the static-analysis artifact, wire format 2 (`formatVersion: 2`, generator `aperv-derive/2`), whose component
  intent filters may carry the additive `data` member (`schemes`, `hosts`, `paths`, `pathPrefixes`, `mimeTypes`,
  empty lists omitted; design D-B14.11) (source: rv-android `derive_mop_artifact.py`)
- `system-broadcast.json` — the broadcast catalog, loaded iff `COMPONENT_TRIGGER` is mounted (source: pushed by
  the harness for the four `as*` cells)

### Output

- `RunSpec.features` — the group features and the members whose group is in the plan
- `StaticGuiModel` — read-only WTG, widget-metadata and component queries (intent filters with their `data`), plus
  the artifact's package and main activity (consumers: the STATIC members, and the MOP members that need the WTG or
  the components — `WtgPass`, `MopFrontierPass`, `MopScorer`, `MopLauncherStage`; plan P-1, "MOP reads both")
- `MopData` — read-only target queries: targets, `dist`, `handlers`, `activityDist`, MOP activities, menu gateways
  (consumers: the MOP members only)
- `MOP_DATA` — the load census record

### Side-Effects

- **[Process]**: a group key stated `true` whose data or URL is missing aborts resolution before step 1
  (`run-spec` INV-RUN-02).

### Error

- `RunSpecException(MISSING_DEPENDENCY)` — `ape.static=true` or `ape.mop=true` without `ape.staticDataPath`;
  `ape.llm=true` without `ape.llmUrl`; an explicit member activation key, stated ON, whose group is off.
- artifact rejected at load — `MOP_DATA status=rejected`, fail-fast when a mounted group needs the data.

## Invariants

- **INV-GRP-01**: Every member feature of ADDONS, STATIC, MOP and LLM SHALL declare its group feature as a
  dependency in `Feature`, and the group features SHALL be the only roots besides `STATIC_DATA` and the BASE root
  `FUZZING`: `ADDONS` and `LLM` are roots, `STATIC` and `MOP` require `STATIC_DATA`. `FUZZING` is exempt: BASE has
  no group feature, so `FUZZING` is a root and not a group member. A member SHALL be in `features` only when its
  group is.
  Coverage: `FeatureDerivationTest`, `GroupMountingTest`.
- **INV-GRP-02**: When `ape.staticDataPath` is absent, no STATIC or MOP feature SHALL be in `features`. A plan that
  states `ape.static=true`, `ape.mop=true`, or an explicit activation key of a STATIC or MOP member, without
  `ape.staticDataPath`, SHALL abort with `reason=missing_dependency`. Coverage: `GroupMountingTest`.
- **INV-GRP-03**: No use site SHALL branch on group membership. Group features SHALL be read only by plan
  resolution and by composition roots that choose which implementation to mount (for example the `SataAgent`
  constructor mounting the MOP variants of X3 and X4). A use site SHALL read `spec.has(<member feature>)` or the
  member's parameters, or SHALL hold whatever implementation was mounted. Coverage: `GroupReadSiteTest`.
- **INV-GRP-04**: The vector of a cell preset SHALL be exactly the union of the blocks of the groups the cell
  contains. No cell vector SHALL carry `ape.staticDataPath`, `ape.llmUrl`, `ape.variant`, `ape.runId` or a seed.
  Coverage: `PresetsTest`, `CellPresetTest`.
- **INV-GRP-05**: `StaticArtifactLoader` SHALL parse the artifact at most once per run and yield both providers
  from that parse. No STATIC member SHALL hold or read a `MopData` reference. Coverage: `StaticGuiModelTest`,
  `MopDataTest`.
- **INV-GRP-06**: Every read site of an ADDONS, STATIC or MOP mechanism SHALL read the plan. No such site SHALL
  read the mechanism's switch from `Config`, and `Config` SHALL declare no field for a plan-read switch. In
  particular the `State` constructor (`MODEL_MENU`), `GUITreeBuilder.treeEnhancementsEnabled()` and its callers in
  `GUITreeBuilder` and `GUITreeNode` (`TREE_ENHANCEMENTS`), `ApeAgent.generateInputText` (`HEURISTIC_INPUT`),
  `MonkeySourceApe.generateEvents` (the foreign-activity and tree-package guards) and `StaticArtifactLoader.load`
  (`MOP_ACTIVITY_SOURCE`) SHALL read `spec.has(<member>)`. Coverage: `GroupReadSiteTest`.
- **INV-GRP-07**: A run whose plan does not contain `MOP` SHALL carry no MOP decision and no MOP field. The full
  list of trace fields is `event-sink` INV-SNK-18 (clean control): among them, no `ACT` dictionary entry SHALL carry
  a `mop` member; no `StepRecord` SHALL carry `dec.src` or `dec.own.i` equal to `mop` or `launcher`, a `mop`,
  `mopf`, `wtg`, `menu`, `mopd`, `mops`, `hnd`, `ret` or `mopx` member, `dec.own.uM`, a `dec.cfc` key for a MOP
  scorer or `dec.nav.c` equal to `"mop"`; `RUN_END.counters.coinMop`, `launches`, `stampHits` and `retired` SHALL be
  `0`. The UICOV coverage dump SHALL omit `mopReach` (design D-B14.21). No LLM prompt SHALL carry a MOP marker, the
  MOP count, the `MOP NAVIGATION` section or a MOP system-prompt sentence. Coverage: `CleanControlTest` on the cells
  `base`, `as` and `as_llm`.

## Requirements

### Requirement: Group Keys and Member Features

The plan SHALL have one boolean key per group: `ape.addons` (feature `ADDONS`), `ape.static` (`STATIC`), `ape.mop`
(`MOP`) and `ape.llm` (`LLM`). Each defaults to `false` in the jar and is activated by the value `true`. BASE has no
key and is always present.

The groups SHALL declare these dependencies:

| Group feature | Activation | Requires |
|---|---|---|
| `ADDONS` | `ape.addons=true` | — |
| `STATIC` | `ape.static=true` | `STATIC_DATA` |
| `MOP` | `ape.mop=true` | `STATIC_DATA` |
| `LLM` | `ape.llm=true` | — (owns `ape.llmUrl`, which has no neutral value) |
| `STATIC_DATA` | `ape.staticDataPath` present | — |

Every member SHALL declare its group as a dependency:

| Group | Members |
|---|---|
| BASE | `FUZZING` (a root, not a group member: BASE has no group feature; INV-GRP-01) |
| ADDONS | `MODEL_MENU`, `FORM_COMPLETION`, `TREE_ENHANCEMENTS`, `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`, `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD`, `COVERAGE_BOOST`, `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART` |
| STATIC | `NAV_FRONTIER`, `TYPED_FUZZ`, `COMPONENT_TRIGGER` |
| MOP | `WTG`, `MENU_GATEWAY` (also requires `MODEL_MENU`), `MOP_FRONTIER`, `MOP_LAUNCHER`, `MOP_ACTIVITY_SOURCE`, `MOP_COIN` |
| LLM | `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_COIN` |

A member whose group is off SHALL be absent from the plan without an abort when its activation key holds the jar
default. It SHALL abort with `MISSING_DEPENDENCY` when its activation key is stated explicitly (the existing
`run-spec` rule). The member keys, their owners and their defaults are specified in `run-spec`, "Feature Model with
Declared Dependencies".

The addon "least-visited priority tie-break" (G) SHALL NOT exist as a feature. The scorer-sum tie (X1, rule R2,
`action-selection`) replaces it. With zero scorers that tie equals upstream APE, so X1 has no key.

#### Scenario: a group off leaves its members out silently

- **WHEN** a properties file sets `ape.staticDataPath=/data/local/tmp/mop-artifact.json` and no group key
- **THEN** resolution SHALL succeed with `features` equal to `[FUZZING, STATIC_DATA]`
- **AND** `TYPED_FUZZ` (jar default `ape.fuzzInputTyped=true`), `NAV_FRONTIER` (jar default
  `ape.frontierBoostWeight=200`) and `MOP_LAUNCHER` (formerly `ACTIVITY_TRIGGER`; jar default
  `ape.activityTriggerEnabled=true`) SHALL be
  absent, with no abort
- **AND** the artifact SHALL NOT be loaded, no `MOP_DATA` record SHALL be written, and `ape.staticDataPath` SHALL be
  listed in `RUN_START.inert` (design D-B14.20)

#### Scenario: an explicit member key without its group aborts

- **WHEN** a properties file sets `ape.modelMenuEnabled=true` and does not set `ape.addons`
- **THEN** resolution SHALL abort with `[APE-RUNSPEC-ABORT] reason=missing_dependency key=ape.modelMenuEnabled`
  and a detail naming `ADDONS`

#### Scenario: STATIC and MOP switch independently over the same JSON

- **WHEN** a properties file sets `ape.staticDataPath=/data/local/tmp/mop-artifact.json`, `ape.static=true` and
  `ape.mop=false`
- **THEN** `features` SHALL contain `STATIC_DATA`, `STATIC`, `NAV_FRONTIER` and `TYPED_FUZZ`
- **AND** `features` SHALL contain no MOP member and `MopParams` SHALL be null

#### Scenario: a group without its data aborts

- **WHEN** a properties file sets `ape.mop=true` and no `ape.staticDataPath`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.mop` and a detail naming `STATIC_DATA`
- **AND WHEN** a properties file sets `ape.llm=true` and no `ape.llmUrl`
- **THEN** resolution SHALL abort with `reason=missing_dependency` and a detail naming `ape.llmUrl`

### Requirement: Static Data Root and Split Providers

`STATIC_DATA` SHALL be the only feature that reads the artifact path. When the plan mounts `STATIC` or `MOP` (both
require `STATIC_DATA`), `StaticArtifactLoader.load` SHALL parse the artifact once and yield two read-only providers.
When `STATIC_DATA` is in the plan and neither group is mounted, the path is inert: it SHALL be listed in
`RUN_START.inert`, and no load, no `MOP_DATA` census and no rejection follow (design D-B14.20). The providers are:

- `StaticGuiModel`: the WTG, the per-widget static metadata (`inputType`, `hint`, labels), the manifest components
  with their intent filters (including the filter's `data`), the artifact's package and main activity;
- `MopData`: `targets`, per-widget `dist`, `handlers`, `activityDist`, the MOP activity set and the menu gateways.

The loader SHALL hand `StaticGuiModel` to the mounted STATIC members and both providers to the mounted MOP members,
and nothing to a member that is not mounted. The MOP members that need the WTG or the components (`WtgPass`,
`MopFrontierPass`, `MopScorer`, `MopLauncherStage`) read `StaticGuiModel` for them and `MopData` for the targets
(plan P-1, "MOP reads both"). `NAV_FRONTIER` (`agent/scoring/NavFrontierPass`) and the typed-input
generator and the component-trigger pool SHALL read `StaticGuiModel` only. The wire format SHALL remain format 2.
`formatVersion`, the generator string and the `source.digest` chain are unchanged, and so is the format apart from
the additive intent-filter `data` member (D-B13): the derive adds one optional member, the reduced `data` of each
component intent filter, which the loader reads as "no data" when absent.
`MOP_DATA` remains the load census.

#### Scenario: STATIC reads no MOP data

- **WHEN** the `as` cell runs with `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** `NavFrontierPass` and the typed-input generator SHALL be constructed with a `StaticGuiModel`
- **AND** no constructed component of the run SHALL hold a `MopData` reference

#### Scenario: one parse feeds both groups

- **WHEN** the `as_mop` cell runs on `cryptoapp.apk.mop.json`
- **THEN** the artifact SHALL be parsed exactly once
- **AND** `NavFrontierPass` and `WtgPass` SHALL read the same WTG instance from `StaticGuiModel`

#### Scenario: format 2 is still the only format read

- **WHEN** `ape.staticDataPath` names an artifact with `formatVersion: 1`
- **THEN** `StaticArtifactLoader.load` SHALL reject it with `version-mismatch` (its `formatVersion` gate), and
  neither provider SHALL be built

### Requirement: Cell Presets as Unions of Group Blocks

`Presets.resolve` SHALL know exactly six names, the cells, and each cell's vector SHALL be the union of the blocks of
its groups (INV-GRP-04). Explicit keys override the vector, as for any preset.

| Block | Keys |
|---|---|
| ADDONS | `ape.addons=true`, `ape.backMenuPickCap=3`, `ape.foreignActivityGuard=true`, `ape.treePackageGuard=true`, `ape.dynamicEpsilon=true`, `ape.heuristicInput=true`, `ape.formCompletionEnabled=true`, `ape.modelMenuEnabled=true`, `ape.treeEnhancementsEnabled=true`, `ape.activityBudgetEnabled=true`, `ape.activityStableRestartThreshold=200`, `ape.coverageBoostWeight=100` |
| STATIC | `ape.static=true`, `ape.frontierBoostWeight=200`, `ape.fuzzInputTyped=true`, `ape.componentPercentage=0.03` |
| MOP | `ape.mop=true`, `ape.mopScoring=distance`, `ape.mopWeightWtg=200`, `ape.mopFrontierWeight=200`, `ape.activityTriggerEnabled=true`, `ape.mopActivitySourceComponents=true`, `ape.mopCoinPercentage=1.0` |
| LLM | `ape.llm=true`, `ape.llmOnNewState=true`, `ape.llmOnStagnation=true`, `ape.llmPercentage=0.9`, `ape.llmPromptVariant=v13`, `ape.llmTemperature=0`, `ape.llmTopP=1.0`, `ape.llmTopK=-1`, `ape.llmSnapTolerancePx=150`, `ape.llmModel=Qwen/Qwen3-VL-4B-Instruct-FP8`, `ape.llmTimeoutMs=15000` |

| Cell | ADDONS | STATIC | MOP | LLM | The harness passes |
|---|:-:|:-:|:-:|:-:|---|
| `base` | – | – | – | – | `ape.variant` |
| `mop` | – | – | ✓ | – | `ape.staticDataPath`, `ape.variant` |
| `as` | ✓ | ✓ | – | – | `ape.staticDataPath`, `ape.variant` |
| `as_mop` | ✓ | ✓ | ✓ | – | `ape.staticDataPath`, `ape.variant` |
| `as_llm` | ✓ | ✓ | – | ✓ | `ape.staticDataPath`, `ape.llmUrl`, `ape.variant` |
| `as_mop_llm` | ✓ | ✓ | ✓ | ✓ | `ape.staticDataPath`, `ape.llmUrl`, `ape.variant` |

The ADDONS block is the former `aperv` preset; the STATIC block is the frontier at 200, typed input and component triggering at 0.03 (D-B13); the MOP
block is E6 arm 3's MOP configuration (`mopd_on_llm_off`) plus `p_M = 1.0`; the LLM block is E6 arm 4's sampling
configuration (`mopd_on_llm_90`) with `p_L = 0.9`. The doses `ape.llmPercentage`, `ape.mopCoinPercentage` and
`ape.componentPercentage` MAY be overridden per variant by explicit keys.

The resolved `features` per cell SHALL be:

| Cell | `features` |
|---|---|
| `base` | `FUZZING` |
| `mop` | `FUZZING`, `STATIC_DATA`, `MOP`, `WTG`, `MOP_FRONTIER`, `MOP_LAUNCHER`, `MOP_ACTIVITY_SOURCE`, `MOP_COIN` |
| `as` | `FUZZING`, `ADDONS`, the nine former addon features, `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART`, `STATIC_DATA`, `STATIC`, `NAV_FRONTIER`, `TYPED_FUZZ`, `COMPONENT_TRIGGER` |
| `as_mop` | the `as` features, the `mop` MOP features, and `MENU_GATEWAY` |
| `as_llm` | the `as` features, and `LLM`, `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_COIN` |
| `as_mop_llm` | the `as_mop` features and the four LLM features |

The exact `RUN_START` of each cell SHALL be committed as a test fixture,
`src/test/resources/cells/<cell>.run_start.json`, with `run_id`, `t0`, `seed`, `build` and `props_digest` masked.

#### Scenario: the base cell is the jar defaults

- **WHEN** a properties file contains `ape.preset=base` and `ape.variant=base`
- **THEN** resolution SHALL succeed with `features` equal to `[FUZZING]`
- **AND** `StaticParams`, `MopParams` and `LlmParams` SHALL be null

#### Scenario: the as_mop_llm cell resolves all four groups

- **WHEN** a properties file contains `ape.preset=as_mop_llm`,
  `ape.staticDataPath=/data/local/tmp/mop-artifact.json`, `ape.llmUrl=http://10.0.2.2:30000/v1` and
  `ape.variant=as_mop_llm`
- **THEN** `features` SHALL contain `ADDONS`, `STATIC`, `MOP`, `LLM`, `MENU_GATEWAY`, `MOP_COIN` and `LLM_COIN`
- **AND** the effective `ape.llmPercentage` SHALL be `0.9` and the effective `ape.mopCoinPercentage` `1.0`

#### Scenario: a dose overridden per variant

- **WHEN** a properties file contains `ape.preset=as_mop`, the artifact path and `ape.mopCoinPercentage=0.5`
- **THEN** the effective `p_M` SHALL be `0.5` and every other key SHALL keep the `as_mop` value

#### Scenario: the committed RUN_START fixture matches

- **WHEN** `CellPresetTest` resolves each of the six cells with the harness keys of its row
- **THEN** the emitted `RUN_START`, with `run_id`, `t0`, `seed`, `build` and `props_digest` masked, SHALL equal
  `src/test/resources/cells/<cell>.run_start.json`

### Requirement: Declared Consequences of the Cell Composition

Two consequences of the composition SHALL be declared rather than treated as leaks.

First, the `mop` cell carries no `MODEL_MENU`, because the menu action is an addon. `MENU_GATEWAY` requires
`MODEL_MENU`, so it SHALL be absent from the `mop` cell. Resolving the `mop` cell SHALL succeed: the MOP block
does not state `ape.mopWeightOpenMenu` (design D-B14.1), because a preset key counts as explicit and would abort the
`mop` cell with `MISSING_DEPENDENCY`. Its jar default, 250, activates `MENU_GATEWAY` where `MODEL_MENU` is present
and is left out silently where it is not. In `as_mop` and `as_mop_llm` the gateway is present.

Second, the `base` cell SHALL NOT be claimed equal to the upstream `ape.jar` (`8f51b99c`) byte for byte. It differs
by the keyless bug fixes recorded in plan §2.2:

- `Graph.rebuildHistory` no longer double-increments;
- binary searches test `< 0`;
- the `maxGUITreesPerState` guard takes effect;
- `clearChildren` removes every child;
- an action outside the screen is discarded;
- `waitForActivity` gives up after about 10 s;
- the fuzzing pinch and zoom events are emitted;
- an empty `StringCache` is handled;
- the RNG is seeded instead of `ThreadLocalRandom`.

It also differs by the structural changes of this change that act in every cell: restart is a guard before
selection (`decision-pipeline`), the fuzz toss is drawn from its own seeded stream, and form completion's
deterministic fill and submit exclusion (addon F) are governed by `ape.formCompletionEnabled` and are therefore off
in `base`. The SATA extension points X1, X3 and X4 SHALL take the APE default in `base`, including their RNG
consumption (`action-selection`). `RUN_START.build` identifies the jar, and the equivalence with upstream is
checked by an A/A on a subset of APKs (plan §6.4).

#### Scenario: the mop cell has no menu gateway and does not abort

- **WHEN** a properties file contains `ape.preset=mop` and `ape.staticDataPath=/data/local/tmp/mop-artifact.json`
- **THEN** resolution SHALL succeed
- **AND** `features` SHALL contain neither `MODEL_MENU` nor `MENU_GATEWAY`, and no `StepRecord` of the run SHALL
  carry a non-zero `menu` field

#### Scenario: the gateway returns with the addons

- **WHEN** the same file names `ape.preset=as_mop` instead
- **THEN** `features` SHALL contain `MODEL_MENU` and `MENU_GATEWAY`

#### Scenario: the base cell runs the bug fixes and none of the addons

- **WHEN** the `base` cell runs
- **THEN** no ADDONS member SHALL be constructed (no `MODEL_MENU` action in `State.getActions()`, no BACK/MENU cap,
  no form fill)
- **AND** `RUN_START.features` SHALL be `[FUZZING]` and `RUN_START.build` SHALL name the APE-RV build

### Requirement: Addon Read Sites Read the Plan

Every site that decides whether an ADDONS, STATIC or MOP mechanism acts SHALL read the plan through
`RunContext.current().spec()` (INV-GRP-06). These sites read the plan, and `Config` has no field for any of
their switches:

| Mechanism | Site | Plan read |
|---|---|---|
| `MODEL_MENU` | the `State` constructor (`model/State`) | `spec.has(Feature.MODEL_MENU)` |
| `TREE_ENHANCEMENTS` | `GUITreeBuilder.treeEnhancementsEnabled()`, read by `GUITreeBuilder.checkAndRemoveWebView`, `GUITreeNode.isEditText` and `GUITreeNode.getScrollType` | `spec.has(Feature.TREE_ENHANCEMENTS)` |
| `HEURISTIC_INPUT` | `ApeAgent.generateInputText` | `spec.has(Feature.HEURISTIC_INPUT)` |
| `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD` | `MonkeySourceApe.generateEvents` | `spec.has(...)` of each |
| `MOP_ACTIVITY_SOURCE` | `StaticArtifactLoader.load` | `spec.has(Feature.MOP_ACTIVITY_SOURCE)` |
| `FORM_COMPLETION` | `StatefulAgent.inFormCompletionContext` (read by `ApeAgent.checkInput`), `SataAgent.mountExtensionPoints` (the submit exclusion), `ScoringParams.fromSpec` (`FormCompletionPass`) and the `MopCoin` stage's assembly in `DecisionPipeline.fromSpec` | `spec.has(Feature.FORM_COMPLETION)` |
| `FUZZING` (BASE) | `MonkeySourceApe.generateEvents` | `spec.has(Feature.FUZZING)` and its `ape.fuzzingRate` parameter |
| restart wrapping (BASE) | `ApeAgent.checkRestart` and the `Restart` candidate of `DecisionPipeline` | the `ExplorationParams` value of `ape.checkRestart` |
| `BACK_MENU_CAP` | the BACK/MENU cap in `SataAgent` | its parameter, `exploration().backMenuPickCap()` (`0` when the feature is absent) |
| `ACTIVITY_STABLE_RESTART` | the activity-stability restart check (`StatefulAgent`) | its parameter, `exploration().activityStableRestartThreshold()`; absent ⇒ threshold `Integer.MAX_VALUE` |

The two BASE rows carry no group switch; they read the plan so that `Config` is not a second authority over a
planned key (design D-B6, decision A10), and they change no behaviour. When a member is absent, its site SHALL
behave as upstream APE does for that mechanism. The absent
`ACTIVITY_STABLE_RESTART` threshold is the upstream `Integer.MAX_VALUE` (`8f51b99c` `utils/Config.java:48`).

#### Scenario: base runs without the model menu

- **WHEN** the `base` cell runs with the jar default `ape.modelMenuEnabled`
- **THEN** `State.getActions()` SHALL contain no `MODEL_MENU` action

#### Scenario: activity-stable restart off restores upstream

- **WHEN** the `mop` cell runs and an activity stays stable for 500 consecutive steps
- **THEN** no restart SHALL be due on the activity-stability criterion

#### Scenario: guards follow the plan, not Config

- **WHEN** `GroupReadSiteTest` inspects `Config` and the plan
- **THEN** `Config` SHALL declare no field named `modelMenuEnabled`, `treeEnhancementsEnabled`, `heuristicInput`,
  `foreignActivityGuard`, `treePackageGuard`, `mopActivitySourceComponents`, `doFuzzing`, `fuzzingRate`,
  `checkRestart`, `minEpsilon`, `maxEpsilon`, `mopDataPath`, `mopTargetPickCap`, `leastVisitedPriorityTiebreak` or
  `frontierBoostWeight`
- **AND** under the `base` cell every ADDONS member the table names SHALL be absent from the plan, the BACK/MENU
  cap SHALL read `0` and the activity-stable threshold `Integer.MAX_VALUE`, while `FUZZING` and `ape.checkRestart`
  keep their BASE defaults

### Requirement: Component Triggering as a STATIC Member

`COMPONENT_TRIGGER` SHALL require `STATIC`, not `MOP`, and SHALL be activated by `ape.componentPercentage > 0`
(jar default `0.0`; the STATIC block sets `0.03`). Its trigger pool SHALL be built from the receivers, services and
providers of the artifact's manifest that the existing admission rules accept (permission, same package), read from
`StaticGuiModel`, which also supplies the package the intents are addressed to: one tuple per (intent filter ×
action) of a receiver or a service, a name-only tuple for every service with no filter action, whether or not it
lists `targetMethods`, and none for a receiver with no filter action, and the provider operations for every
provider with `authorities` (design D-B14.15; `component-triggering` INV-CT-03). `triggerMopComponent()` is renamed
`triggerComponent()`. The pool SHALL NOT be filtered by `reachesTarget` or by any other MOP datum. The component coin
SHALL draw from its own stream derived from the run seed, every step while the feature is mounted
(`decision-pipeline`), so mounting the trigger shifts no SATA draw. The broadcast catalog `system-broadcast.json`
SHALL be loaded iff `COMPONENT_TRIGGER` is mounted.

When a dispatched intent comes from a filter that carries `data`, the intent SHALL carry it: the URI is
`scheme://host + path` from the first scheme, the first host and the first path (falling back to the first path
prefix), with `host` and `path` empty when absent, and the MIME type is the first one of the filter that supplied
the action (design D-B14.11, D-B14.15): `setDataAndType(Uri, mimeType)`; with only a MIME type, `setType`; with only
a scheme, `setData`. A filter without
`data` produces an intent without data, as before. Receivers keep the catalog's extras; no extras are inferred from
the static analysis.

#### Scenario: the pool does not depend on MOP

- **WHEN** the `as` cell runs on an artifact whose manifest declares a same-package receiver with
  `reachesMop` false
- **THEN** that receiver SHALL be in the trigger pool
- **AND** `getMopData()` SHALL be null and no MOP consumer SHALL hold a `MopData` reference (design D-B14.21)

#### Scenario: a filter with a scheme and a MIME type

- **WHEN** the coin dispatches a service whose filter carries `data` with `schemes: ["content"]`,
  `hosts: ["com.example"]` and `mimeTypes: ["text/plain"]`
- **THEN** the intent SHALL be built with `setDataAndType(content://com.example, "text/plain")`

#### Scenario: component triggering is absent from base and mop

- **WHEN** the `base` or the `mop` cell resolves
- **THEN** `COMPONENT_TRIGGER` SHALL be absent from `features` and `system-broadcast.json` SHALL NOT be read

### Requirement: A Cell Without MOP Carries No MOP Signal

A plan without `MOP` SHALL produce no MOP decision and no MOP field (INV-GRP-07). The MOP variants of X3 and X4,
the MOP coin, the launcher, the MOP scorers and the MOP prompt contribution SHALL NOT be constructed. Telemetry
fields that only MOP writes SHALL be absent, not written as zero. The LLM in such a cell SHALL read no MOP signal:
the prompt is built from the contributions of the mounted groups only (`llm-prompt`).

#### Scenario: clean control in the as cell

- **WHEN** the `as` cell runs and the trace is parsed
- **THEN** no `StepRecord` SHALL carry `dec.src` equal to `mop` or `launcher`, or a `mop`, `mopf`, `wtg`, `menu`,
  `mopd`, `mops`, `hnd`, `ret`, `mopx` or `uM` member, no `ACT` entry SHALL carry `mop`, and the UICOV dump SHALL carry no
  `mopReach`
- **AND** `navf` MAY be present, since `NAV_FRONTIER` is STATIC

#### Scenario: the LLM without MOP sees no MOP instruction

- **WHEN** the `as_llm` cell sends a prompt
- **THEN** the prompt SHALL contain no `[DM]` or `[M]` marker, no `N/M MOP.` count, no `MOP NAVIGATION:` section and
  no MOP sentence in the system prompt
