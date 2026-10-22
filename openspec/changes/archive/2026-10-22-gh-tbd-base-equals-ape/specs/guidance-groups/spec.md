# guidance-groups — delta: gh-tbd-base-equals-ape

## MODIFIED Requirements

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
| ADDONS | `MODEL_MENU`, `FORM_COMPLETION`, `TREE_ENHANCEMENTS`, `ACTIVITY_BUDGET`, `DYNAMIC_EPSILON`, `HEURISTIC_INPUT`, `FOREIGN_ACTIVITY_GUARD`, `TREE_PACKAGE_GUARD`, `COVERAGE_BOOST`, `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART`, `APE_FIXES` |
| STATIC | `NAV_FRONTIER`, `TYPED_FUZZ`, `COMPONENT_TRIGGER` |
| MOP | `WTG`, `MENU_GATEWAY` (also requires `MODEL_MENU`), `MOP_FRONTIER`, `MOP_LAUNCHER`, `MOP_ACTIVITY_SOURCE`, `MOP_COIN` |
| LLM | `LLM_NEW_STATE`, `LLM_STAGNATION`, `LLM_COIN` |

`APE_FIXES` (activation `ape.apeFixes=true`, jar default `true`) SHALL hold the fork's corrections of APE's own
behaviour at the points listed in "Declared Consequences of the Cell Composition", as distinct from the other ADDONS
members, which add mechanisms. Each of those points SHALL have exactly one decision point, which reads
`spec.has(Feature.APE_FIXES)`.

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
  `ape.frontierBoostWeight=200`), `MOP_LAUNCHER` (formerly `ACTIVITY_TRIGGER`; jar default
  `ape.activityTriggerEnabled=true`) and `APE_FIXES` (jar default `ape.apeFixes=true`) SHALL be
  absent, with no abort
- **AND** the artifact SHALL NOT be loaded, no `MOP_DATA` record SHALL be written, and `ape.staticDataPath` SHALL be
  listed in `RUN_START.inert` (design D-B14.20)

#### Scenario: an explicit member key without its group aborts

- **WHEN** a properties file sets `ape.modelMenuEnabled=true` and does not set `ape.addons`
- **THEN** resolution SHALL abort with `[APE-RUNSPEC-ABORT] reason=missing_dependency key=ape.modelMenuEnabled`
  and a detail naming `ADDONS`
- **AND WHEN** a properties file sets `ape.apeFixes=true` and does not set `ape.addons`
- **THEN** resolution SHALL abort with `reason=missing_dependency key=ape.apeFixes` and a detail naming `ADDONS`
- **AND WHEN** it sets `ape.apeFixes=false` and does not set `ape.addons`
- **THEN** resolution SHALL succeed with `APE_FIXES` absent and `ape.apeFixes` listed in `RUN_START.inert`

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

### Requirement: Cell Presets as Unions of Group Blocks

`Presets.resolve` SHALL know exactly six names, the cells, and each cell's vector SHALL be the union of the blocks of
its groups (INV-GRP-04). Explicit keys override the vector, as for any preset.

| Block | Keys |
|---|---|
| ADDONS | `ape.addons=true`, `ape.backMenuPickCap=3`, `ape.foreignActivityGuard=true`, `ape.treePackageGuard=true`, `ape.dynamicEpsilon=true`, `ape.heuristicInput=true`, `ape.formCompletionEnabled=true`, `ape.modelMenuEnabled=true`, `ape.treeEnhancementsEnabled=true`, `ape.activityBudgetEnabled=true`, `ape.activityStableRestartThreshold=200`, `ape.coverageBoostWeight=100`, `ape.apeFixes=true` |
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

The ADDONS block is the former `aperv` preset plus `ape.apeFixes=true`, which it states as it states the other addon
keys; the STATIC block is the frontier at 200, typed input and component triggering at 0.03 (D-B13); the MOP
block is E6 arm 3's MOP configuration (`mopd_on_llm_off`) plus `p_M = 1.0`; the LLM block is E6 arm 4's sampling
configuration (`mopd_on_llm_90`) with `p_L = 0.9`. The doses `ape.llmPercentage`, `ape.mopCoinPercentage` and
`ape.componentPercentage` MAY be overridden per variant by explicit keys.

The resolved `features` per cell SHALL be:

| Cell | `features` |
|---|---|
| `base` | `FUZZING` |
| `mop` | `FUZZING`, `STATIC_DATA`, `MOP`, `WTG`, `MOP_FRONTIER`, `MOP_LAUNCHER`, `MOP_ACTIVITY_SOURCE`, `MOP_COIN` |
| `as` | `FUZZING`, `ADDONS`, the nine former addon features, `BACK_MENU_CAP`, `ACTIVITY_STABLE_RESTART`, `APE_FIXES`, `STATIC_DATA`, `STATIC`, `NAV_FRONTIER`, `TYPED_FUZZ`, `COMPONENT_TRIGGER` |
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
- **THEN** `features` SHALL contain `ADDONS`, `APE_FIXES`, `STATIC`, `MOP`, `LLM`, `MENU_GATEWAY`, `MOP_COIN` and
  `LLM_COIN`
- **AND** the effective `ape.llmPercentage` SHALL be `0.9` and the effective `ape.mopCoinPercentage` `1.0`

#### Scenario: a dose overridden per variant

- **WHEN** a properties file contains `ape.preset=as_mop`, the artifact path and `ape.mopCoinPercentage=0.5`
- **THEN** the effective `p_M` SHALL be `0.5` and every other key SHALL keep the `as_mop` value

#### Scenario: the committed RUN_START fixture matches

- **WHEN** `CellPresetTest` resolves each of the six cells with the harness keys of its row
- **THEN** the emitted `RUN_START`, with `run_id`, `t0`, `seed`, `build` and `props_digest` masked, SHALL equal
  `src/test/resources/cells/<cell>.run_start.json`
- **AND** the fixtures of `as`, `as_mop`, `as_llm` and `as_mop_llm` SHALL name `APE_FIXES` in `features` and carry
  `ape.apeFixes:true` in `params`, and the fixtures of `base` and `mop` SHALL name neither

### Requirement: Declared Consequences of the Cell Composition

Two consequences of the composition SHALL be declared rather than treated as leaks.

First, the `mop` cell carries no `MODEL_MENU`, because the menu action is an addon. `MENU_GATEWAY` requires
`MODEL_MENU`, so it SHALL be absent from the `mop` cell. Resolving the `mop` cell SHALL succeed: the MOP block
does not state `ape.mopWeightOpenMenu` (design D-B14.1), because a preset key counts as explicit and would abort the
`mop` cell with `MISSING_DEPENDENCY`. Its jar default, 250, activates `MENU_GATEWAY` where `MODEL_MENU` is present
and is left out silently where it is not. In `as_mop` and `as_mop_llm` the gateway is present.

Second, the `base` cell SHALL run the APE algorithm of the upstream `ape.jar` (`8f51b99c`) at every point the e03igg4
audit (rv-android `docs/20261021_e03igg4_investigacao.md` §7.4–7.5, item numbers below are its numbers) lists,
except the points declared here. It SHALL NOT be claimed equal to `8f51b99c` byte for byte. In every cell, BASE
carries, with no key:

- the `produce`/`consume` logs and the end-of-run writes are not produced (item 2);
- `Graph.rebuildHistory` does not double-increment (item 5);
- `GUITree.contains` tests `< 0` (item 7);
- `GUITreeNode.clearChildren` removes every child with the first-child loop, with no exception path (item 8,
  `ui-tree`);
- an empty `StringCache` is handled;
- the RNG is seeded instead of `ThreadLocalRandom`, and `RandomHelper` is seeded with `seed ^ RANDOM_HELPER`,
  separate from `Monkey.mRandom`, which is seeded with the run seed (item 13, `run-spec` "Run Identity and Seed");
- the per-step telemetry of `event-sink` (item 14);
- the action-history memory bound (item 15);
- per-step PNG and XML artifacts are off (item 1, the one declared exception to APE's defaults), and new-state
  capture is on (`ape.takeScreenshotForNewState` jar default `true`, `exploration`).

At the points below, `base` and `mop`, whose plans do not contain `APE_FIXES`, SHALL run APE's code, and every cell
that mounts ADDONS, in which `APE_FIXES` is present at its default `true`, SHALL run the fork's correction:

| Item | Without `APE_FIXES` (APE) | With `APE_FIXES` |
|---|---|---|
| 3 | the `maxGUITreesPerState` guard of both `NamingFactory` sites tests `an.getStates().size()` | it tests `state.getGUITrees().size()` (`naming`) |
| 4 | an activity visit is counted again during a model rebuild | it is not counted (`Graph.markVisited`, `model`) |
| 6 | `Naming.containsNamelet` tests `binarySearch(...) != -1` | it tests `binarySearch(...) >= 0` (`naming`) |
| 9 | the pipeline does not mount `Restart`; `ApeAgent` applies `checkRestart` after selection, SATA marks the replaced action visited and the restart's new episode weakens the planned edge | the `Restart` stage selects the restart before any other stage (`decision-pipeline`) |
| 10 | a target without visible bounds is clicked at the screen centre, the display bounds substituting for them | the action is dropped (`exploration` INV-EXPL-19) |
| 11 | the foreground wait is unbounded | it is bounded at 100 cycles and followed by a relaunch (`exploration` INV-EXPL-15) |
| 12 | the pinch/zoom event is built but not appended; the fuzz toss is `RandomHelper.toss(fuzzingRate)`, drawn only when `FUZZING` is in the plan | the pinch is appended; the fuzz toss is drawn from `StepRng`'s own stream every step (`decision-pipeline` INV-DP-13) |

Form completion's deterministic fill and submit exclusion (addon F) are governed by `ape.formCompletionEnabled` and
are therefore off in `base`. The SATA extension points X1, X3 and X4 SHALL take the APE default in `base`, including
their RNG consumption (`action-selection`). `RUN_START.build` identifies the jar, and the equivalence with upstream is
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
- **AND** `RUN_START.features` SHALL be `[FUZZING]`, without `APE_FIXES`, and `RUN_START.build` SHALL name the
  APE-RV build
- **AND** `PIPELINE.stages` SHALL NOT contain `Restart`, and a due restart SHALL be applied by `ApeAgent.checkRestart`
  after selection
- **AND** a fuzz toss SHALL draw from `RandomHelper` and from no `StepRng` stream

#### Scenario: the ADDONS cells run the fork's corrections

- **WHEN** the `as`, `as_mop`, `as_llm` or `as_mop_llm` cell resolves with `ape.checkRestart` at its default
- **THEN** `features` SHALL contain `APE_FIXES`
- **AND** `PIPELINE.stages` SHALL contain `Restart`, and the fuzz toss SHALL draw from `StepRng`'s fuzz stream

#### Scenario: an ADDONS cell without the corrections

- **WHEN** a properties file contains `ape.preset=as`, the artifact path and `ape.apeFixes=false`
- **THEN** resolution SHALL succeed with every other ADDONS member present and `APE_FIXES` absent
- **AND** items 3, 4, 6, 9, 10, 11 and 12 SHALL run APE's code, as in `base`

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
| `APE_FIXES` | both `maxGUITreesPerState` guards of `NamingFactory` (item 3), `Graph.markVisited(State, int)` (item 4), `Naming.containsNamelet` (item 6), the `Restart` candidate of `DecisionPipeline` (item 9), `MonkeySourceApe.generateClickEventAt` (item 10), `MonkeySourceApe.checkAppActivity` (item 11), the fuzz toss in `MonkeySourceApe` and `ApeFuzzer.generatePinchOrZoomEvent` (item 12) | `spec.has(Feature.APE_FIXES)` |
| `FUZZING` (BASE) | `MonkeySourceApe.generateEvents` | `spec.has(Feature.FUZZING)` and its `ape.fuzzingRate` parameter |
| restart wrapping (BASE) | `ApeAgent.checkRestart` and the `Restart` candidate of `DecisionPipeline` | the `ExplorationParams` value of `ape.checkRestart`; the candidate is assembled iff `ape.checkRestart` and `spec.has(Feature.APE_FIXES)` |
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
  `checkRestart`, `minEpsilon`, `maxEpsilon`, `mopDataPath`, `mopTargetPickCap`, `leastVisitedPriorityTiebreak`,
  `frontierBoostWeight` or `apeFixes`
- **AND** under the `base` cell every ADDONS member the table names, `APE_FIXES` included, SHALL be absent from the
  plan, the BACK/MENU cap SHALL read `0` and the activity-stable threshold `Integer.MAX_VALUE`, while `FUZZING` and
  `ape.checkRestart` keep their BASE defaults
